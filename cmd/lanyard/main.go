// lanyard: LANyard File Transfer, single-binary encrypted LAN file sharing. See the project spec.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"

	"lanyard/internal/approval"
	"lanyard/internal/autostart"
	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/inbox"
	"lanyard/internal/mount"
	"lanyard/internal/peerapi"
	"lanyard/internal/shares"
	"lanyard/internal/transfer"
	"lanyard/internal/trust"
	"lanyard/internal/uiserver"
)

const version = "0.5.0-rc1"

type runInfo struct {
	PID   int    `json:"pid"`
	UIURL string `json:"ui_url"`
	Base  string `json:"base"`
}

func main() {
	attachParentConsole()
	// Subcommands talk to the running instance; anything else starts the server.
	if len(os.Args) > 1 && !strings.HasPrefix(os.Args[1], "-") {
		if runCLI(os.Args[1], os.Args[2:]) {
			return
		}
		fmt.Fprintf(os.Stderr, "unknown command %q\nusage: lanyard [server flags] | peers | share add <path> | get <peer> <share> [dest] | settings get|set | mount add|list|remove | shares cancel-all\n", os.Args[1])
		os.Exit(2)
	}
	var (
		dataDir   = flag.String("data-dir", "", "data directory (default: per-user config dir)")
		noBrowser = flag.Bool("no-browser", false, "run headless (no window, no browser)")
		webUI     = flag.Bool("web", false, "use the browser UI instead of the native window (Windows)")
		noTray    = flag.Bool("no-tray", false, "do not show a system tray icon (Windows)")
		name      = flag.String("name", "", "override device name (saved)")
		uiPort    = flag.Int("ui-port", 0, "preferred UI port (default 47810)")
		peerPort  = flag.Int("port", 0, "preferred peer port (default 47800)")
	)
	flag.Parse()
	logDir := *dataDir
	if logDir == "" {
		logDir, _ = config.DefaultDir()
	}
	log := newLogger(logDir)

	if err := run(log, *dataDir, *noBrowser, *webUI, *noTray, *name, *uiPort, *peerPort); err != nil {
		log.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger, dataDir string, noBrowser, webUI, noTray bool, name string, uiPortFlag, peerPortFlag int) error {
	if dataDir == "" {
		d, err := config.DefaultDir()
		if err != nil {
			return err
		}
		dataDir = d
	}
	cfg, err := config.Open(dataDir)
	if err != nil {
		return err
	}
	runPath := filepath.Join(dataDir, "run.json")

	// Single instance per data dir: if one is already serving, just show its UI.
	if ri := readRunInfo(runPath); ri != nil && alreadyRunning(ri) {
		log.Info("LANyard is already running; opening its window")
		if !noBrowser {
			openBrowser(ri.UIURL)
		}
		return nil
	}

	if name != "" {
		_ = cfg.Update(func(s *config.Settings) { s.DeviceName = name })
	}
	st := cfg.Get()
	id, err := identity.LoadOrCreate(dataDir, st.DeviceName)
	if err != nil {
		return fmt.Errorf("identity: %w", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	client := peerapi.NewClient(id)

	// Local UI event hub is referenced by the managers so any change pushes an
	// SSE update; ui is assigned below.
	var ui *uiserver.Server
	notify := func() {
		if ui != nil {
			ui.Notify()
		}
	}

	// Shares and their expiry scheduler.
	shMgr := shares.New(cfg, id.DeviceID, notify)
	if err := shMgr.Load(); err != nil {
		return err
	}
	shStop := make(chan struct{})
	shMgr.Start(shStop)
	defer close(shStop)

	// Transfer jobs.
	trMgr := transfer.New(cfg, client, log, 3, notify)
	if err := trMgr.Load(); err != nil {
		return err
	}

	// Trust store and Connect/Pair sessions.
	trustStore := trust.New(cfg, id.DeviceID, notify)
	if err := trustStore.Load(); err != nil {
		return err
	}
	trustStop := make(chan struct{})
	trustStore.Start(trustStop)
	defer close(trustStop)

	// Peer service (mutual TLS). Authorization always comes from the trust
	// store; there is no bypass in a shipped build.
	var auth peerapi.Authorizer = trustStore
	inboxMgr := inbox.New(inboxDir(dataDir, st.InboxFolder), notify)
	trMgr.SetBandwidthLimit(st.BandwidthLimitMBps)
	var peerSrv *peerapi.Server
	hello := func() discovery.Hello {
		cur := cfg.Get()
		label := cur.DeviceIDLabel
		if label == "" {
			label = identity.ShortID(id.DeviceID)
		}
		return discovery.Hello{
			DeviceID: label, Fingerprint: id.DeviceID, Name: cur.DeviceName, OS: runtime.GOOS,
			Version: version, Port: peerSrv.Port(),
		}
	}
	peerSrv = peerapi.NewServer(id, hello, shMgr, trustStore, auth, log)
	peerSrv.SetInbox(inboxMgr)
	approvals := approval.New(notify)
	peerSrv.SetApprovals(approvals)

	// A Connect session is for a single transfer: when one finishes, end the
	// session on both sides unless "Keep connected" was chosen.
	trMgr.SetOnDone(func(ji transfer.JobInfo) {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		client.EndConnectAfterTransfer(ctx, trustStore, ji.PeerID, ji.Host, ji.Port)
	})
	want := st.PeerPort
	if peerPortFlag != 0 {
		want = peerPortFlag
	}
	peerLn, err := peerSrv.Listen(want)
	if err != nil {
		return fmt.Errorf("peer listener: %w", err)
	}
	go func() {
		if err := peerSrv.Serve(peerLn); err != nil {
			log.Error("peer service stopped", "err", err)
		}
	}()
	log.Info("peer service listening", "port", peerSrv.Port(), "device_id", identity.ShortID(id.DeviceID))

	// Discovery.
	disc := discovery.New(discovery.Announcement{
		Name: st.DeviceName, OS: runtime.GOOS, Port: peerSrv.Port(), DeviceLabel: st.DeviceIDLabel,
	}, id.DeviceID, client.Probe, log)
	disc.Start(ctx)

	// When a peer (re)appears, refresh its jobs' address and retry at once.
	go func() {
		changes, unsub := disc.Subscribe()
		defer unsub()
		known := map[string]string{} // device ID -> "host:port" last announced
		for {
			select {
			case <-ctx.Done():
				return
			case <-changes:
			}
			seen := map[string]bool{}
			for _, p := range disc.Peers() {
				if !p.Verified || len(p.Addrs) == 0 {
					continue
				}
				seen[p.DeviceID] = true
				addr := fmt.Sprintf("%s:%d", p.Addrs[0], p.Port)
				if known[p.DeviceID] != addr {
					known[p.DeviceID] = addr
					trMgr.PeerAvailable(p.DeviceID, p.Addrs[0], p.Port)
				}
			}
			for id := range known {
				if !seen[id] {
					delete(known, id) // gone: announce again when it returns
				}
			}
		}
	}()

	// Keep the sign-in entry pointing at this executable (it may have moved).
	if st.StartOnLogin {
		if err := setStartOnLogin(dataDir, true); err != nil {
			log.Warn("could not refresh the start-on-login entry", "err", err)
		}
	}

	// Mount a paired device as a drive (spec §11.2): a loopback WebDAV server
	// answered from the peer's authenticated API.
	mountMgr := mount.New(client,
		func(fp string) (string, int, bool) {
			for _, p := range disc.Peers() {
				if p.DeviceID == fp && p.Verified && len(p.Addrs) > 0 {
					return p.Addrs[0], p.Port, true
				}
			}
			return "", 0, false
		},
		func(fp string) bool { _, ok := trustStore.Entry(fp); return ok },
		log)
	defer func() {
		for _, in := range mountMgr.List() {
			if in.Drive != "" {
				_ = mount.UnmountOS(in.Drive) // the endpoint disappears with us
			}
		}
		mountMgr.Close()
	}()
	go func() { // unpairing revokes: drop mounts of devices that are no longer paired
		t := time.NewTicker(5 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				for _, in := range mountMgr.Sweep() {
					if in.Drive != "" {
						_ = mount.UnmountOS(in.Drive)
					}
				}
			}
		}
	}()
	for _, pref := range st.Mounts { // bring back last session's mounts
		pref := pref
		info, err := mountMgr.Add(pref.DeviceID, pref.Name)
		if err != nil {
			continue // no longer paired
		}
		if pref.Drive != "" {
			go func() {
				_ = mount.UnmountOS(pref.Drive) // clear a stale mapping from the previous run
				if err := mount.MountOS(pref.Drive, info.URL); err != nil {
					mountMgr.SetOSState(info.ID, "", false, err.Error())
					return
				}
				mountMgr.SetOSState(info.ID, pref.Drive, true, "")
			}()
		}
	}

	// Local UI.
	ui = uiserver.New(uiserver.Deps{
		Self: func() uiserver.SelfInfo {
			cur := cfg.Get()
			gen := identity.ShortID(id.DeviceID)
			label := cur.DeviceIDLabel
			if label == "" {
				label = gen
			}
			return uiserver.SelfInfo{
				Name: cur.DeviceName, DeviceID: id.DeviceID, Pretty: identity.Pretty(id.DeviceID),
				GeneratedLabel: gen, DeviceLabel: label,
				OS: runtime.GOOS, PeerPort: peerSrv.Port(), Version: version,
			}
		},
		Peers:     disc.Peers,
		Subscribe: disc.Subscribe,
		AddPeer:   disc.AddManual,
		Shares:    shMgr,
		Transfers: trMgr,
		Client:    client,
		Trust:     trustStore,
		Approvals: approvals,
		Mounts:    mountMgr,
		SelfFP:    id.DeviceID,
		Cfg:       cfg,
		ApplySettings: func(s config.Settings) {
			inboxMgr.SetDir(inboxDir(dataDir, s.InboxFolder))
			trMgr.SetBandwidthLimit(s.BandwidthLimitMBps)
			disc.SetIdentity(s.DeviceName, s.DeviceIDLabel) // re-announce without a restart
		},
		SetStartOnLogin:     func(enable bool) error { return setStartOnLogin(dataDir, enable) },
		StartOnLoginEnabled: autostart.Enabled,
		Log:                 log,
	})
	uiWant := st.UIPort
	if uiPortFlag != 0 {
		uiWant = uiPortFlag
	}
	uiLn, err := ui.Listen(uiWant)
	if err != nil {
		return fmt.Errorf("ui listener: %w", err)
	}
	go func() {
		if err := ui.Serve(uiLn); err != nil {
			log.Error("ui server stopped", "err", err)
		}
	}()

	// Interface: a native window on Windows by default, the browser UI with
	// --web, or nothing at all with --no-browser (headless).
	native := runtime.GOOS == "windows" && !noBrowser && !webUI

	// System tray: click to open the window, right-click to quit. Skipped when
	// the native window is the interface (it has its own taskbar entry).
	stopTray := func() {}
	if !noTray && !native {
		stopTray = startTray(trayOptions{
			IconPath: filepath.Join(dataDir, "icon.ico"),
			OnOpen:   func() { openBrowser(ui.URL()) },
			OnQuit:   stop,
			Log:      log,
		})
	}
	defer stopTray()

	ri := runInfo{PID: os.Getpid(), UIURL: ui.URL(), Base: fmt.Sprintf("http://127.0.0.1:%d", ui.Port())}
	if b, err := json.Marshal(ri); err == nil {
		_ = config.WriteFileAtomic(runPath, b, 0o600)
	}
	defer os.Remove(runPath)

	fmt.Printf("\nLANyard File Transfer %s\n  Device : %s\n  ID     : %s\n  Peers  : port %d\n  UI     : %s\n\n",
		version, st.DeviceName, identity.Pretty(id.DeviceID), peerSrv.Port(), ui.URL())

	switch {
	case native:
		err := runNativeUI(nativeUIOptions{
			URL:      ui.URL(),
			Title:    "LANyard File Transfer",
			DataPath: filepath.Join(dataDir, "webview2"),
			Done:     ctx.Done(),
			Log:      log,
		})
		if err != nil {
			log.Error("native window unavailable; using the browser UI", "err", err)
			openBrowser(ui.URL())
			<-ctx.Done()
		}
	case !noBrowser:
		openBrowser(ui.URL())
		<-ctx.Done()
	default:
		<-ctx.Done()
	}

	log.Info("shutting down")
	sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = ui.Shutdown(sctx)
	_ = peerSrv.Shutdown(sctx)
	disc.Stop()
	return nil
}

func readRunInfo(path string) *runInfo {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var ri runInfo
	if json.Unmarshal(b, &ri) != nil || ri.Base == "" {
		return nil
	}
	return &ri
}

func alreadyRunning(ri *runInfo) bool {
	c := &http.Client{Timeout: time.Second}
	resp, err := c.Get(ri.Base + "/api/ping")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	var v map[string]string
	return json.NewDecoder(resp.Body).Decode(&v) == nil && v["app"] == "lanyard"
}

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}

// inboxDir resolves the configured Inbox folder, defaulting under the data dir.
func inboxDir(dataDir, custom string) string {
	if strings.TrimSpace(custom) != "" {
		return custom
	}
	return filepath.Join(dataDir, "Inbox")
}

// setStartOnLogin adds or removes the OS sign-in entry. The entry starts the
// server without opening a browser window, and keeps a custom --data-dir.
func setStartOnLogin(dataDir string, enable bool) error {
	if !enable {
		return autostart.Disable()
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	args := []string{"--no-browser"}
	if def, err := config.DefaultDir(); err != nil || filepath.Clean(def) != filepath.Clean(dataDir) {
		args = append(args, "--data-dir", dataDir)
	}
	return autostart.Enable(exe, args...)
}
