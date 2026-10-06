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
	"lanyard/internal/notify"
	"lanyard/internal/peerapi"
	"lanyard/internal/shares"
	"lanyard/internal/transfer"
	"lanyard/internal/trust"
	"lanyard/internal/uiserver"
	"lanyard/internal/update"
)

const version = "1.0.0"

type runInfo struct {
	PID     int    `json:"pid"`
	UIURL   string `json:"ui_url"`
	Base    string `json:"base"`
	UI      string `json:"ui,omitempty"`
	Version string `json:"version,omitempty"`
}

func main() {
	attachParentConsole()
	// Subcommands talk to the running instance; anything else starts the server.
	// A leading --data-dir is allowed before the command (README), so hoist it.
	args := hoistDataDir(os.Args[1:])
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		if runCLI(args[0], args[1:]) {
			return
		}
		fmt.Fprintf(os.Stderr, "unknown command %q\nusage: lanyard [server flags] | peers | share add <path> | get <peer> <share> [dest] | settings get|set | mount add|list|remove | shares cancel-all | update status|check|download\n", args[0])
		os.Exit(2)
	}
	var (
		dataDir   = flag.String("data-dir", "", "data directory (default: per-user config dir)")
		noBrowser = flag.Bool("no-browser", false, "run headless (no window, no browser)")
		webUI     = flag.Bool("web", false, "use the browser UI instead of the native window (Windows, Linux)")
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

	// Apply a previously downloaded, verified update. On Windows a running
	// binary may be renamed but not overwritten, so this happens before
	// anything else touches the executable.
	if exe, err := os.Executable(); err == nil {
		if applied, err := update.ApplyStaged(exe, update.EmbeddedPublicKey); err != nil {
			log.Warn("could not apply a staged update", "err", err)
		} else if applied {
			log.Info("applied a staged update; the new build is used from the next start")
		}
	}
	cfg, err := config.Open(dataDir)
	if err != nil {
		return err
	}
	runPath := filepath.Join(dataDir, "run.json")

	// Which interface this launch will use; recorded in run.json so a second
	// launch can find/focus an existing window instead of opening a browser.
	nativeMode := nativeSupported() && !noBrowser && !webUI
	uiMode := "headless"
	if nativeMode {
		uiMode = "native"
	} else if !noBrowser {
		uiMode = "web"
	}

	// Single instance per data dir: if one is already serving, surface it
	// instead of starting a second copy.
	if ri := readRunInfo(runPath); ri != nil && alreadyRunning(ri) {
		if ri.Version != "" && ri.Version != version {
			log.Warn("a different LANyard version is already running; quit it to use this build",
				"running", ri.Version, "this", version)
		}
		if ri.UI == "native" && focusNativeWindow(ri.PID) {
			log.Info("LANyard is already running; focused its window", "pid", ri.PID)
			return nil
		}
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
	// Desktop notifications are on unless the person turned them off. The
	// setting is read at send time so a change takes effect without a restart.
	notifier := notify.New(func() bool { return cfg.Get().NotificationsEnabled() })
	id, err := identity.LoadOrCreate(dataDir, st.DeviceName)
	if err != nil {
		return fmt.Errorf("identity: %w", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// If auto-update is on and a channel is configured, check shortly after
	// start and stage a newer, verified build for the next launch. Inert until
	// a manifest URL is set (the release channel is not live yet).
	if st.AutoUpdate {
		go func() {
			select {
			case <-ctx.Done():
				return
			case <-time.After(20 * time.Second):
			}
			u := st.UpdateURL
			if u == "" {
				u = update.DefaultManifestURL
			}
			if u == "" {
				return
			}
			exe, err := os.Executable()
			if err != nil {
				return
			}
			cctx, cancel := context.WithTimeout(context.Background(), 16*time.Minute)
			defer cancel()
			res, err := update.Check(cctx, nil, u, version)
			if err != nil {
				log.Warn("update check failed", "err", err)
				return
			}
			if !res.Available {
				return
			}
			log.Info("update available", "current", version, "latest", res.Latest)
			if _, err := update.StageDownload(cctx, nil, exe, res, update.EmbeddedPublicKey); err != nil {
				log.Warn("could not stage the update", "err", err)
				return
			}
			log.Info("update downloaded and verified; it will apply on the next start", "version", res.Latest)
		}()
	}

	client := peerapi.NewClient(id)

	// Local UI event hub is referenced by the managers so any change pushes an
	// SSE update; ui is assigned below.
	var ui *uiserver.Server
	// resolvePeer turns a certificate fingerprint into a friendly device name;
	// assigned once discovery exists, and read from the notification callbacks.
	var resolvePeer func(fp string) string
	onChange := func() {
		if ui != nil {
			ui.Notify()
		}
	}

	// Shares and their expiry scheduler.
	shMgr := shares.New(cfg, id.DeviceID, onChange)
	if err := shMgr.Load(); err != nil {
		return err
	}
	shStop := make(chan struct{})
	shMgr.Start(shStop)
	defer close(shStop)

	// Transfer jobs.
	trMgr := transfer.New(cfg, client, log, 3, onChange)
	if err := trMgr.Load(); err != nil {
		return err
	}

	// Trust store and Connect/Pair sessions.
	trustStore := trust.New(cfg, id.DeviceID, onChange)
	if err := trustStore.Load(); err != nil {
		return err
	}
	trustStop := make(chan struct{})
	trustStore.Start(trustStop)
	defer close(trustStop)

	// Peer service (mutual TLS). Authorization always comes from the trust
	// store; there is no bypass in a shipped build.
	var auth peerapi.Authorizer = trustStore
	inboxMgr := inbox.New(inboxDir(dataDir, st.InboxFolder), onChange)
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
	approvals := approval.New(onChange)
	peerSrv.SetApprovals(approvals)

	// A Connect session is for a single transfer: when one finishes, end the
	// session on both sides unless "Keep connected" was chosen. The same event
	// tells the person their download or send finished (or failed).
	trMgr.SetOnDone(func(ji transfer.JobInfo) {
		peer := ji.PeerName
		if peer == "" && resolvePeer != nil {
			peer = resolvePeer(ji.PeerID)
		}
		notifier.Notify(notify.Notice{Title: "LANyard", Body: transferDoneText(ji.Direction, peer, ji.Files)})
		if ui == nil {
			return
		}
		kind := "download"
		if ji.Direction == "push" {
			kind = "send"
		}
		ui.NotifyUser(uiserver.Notice{Kind: kind, Peer: peer, Files: ji.Files, Total: ji.Total, Label: ji.Label})
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		client.EndConnectAfterTransfer(ctx, trustStore, ji.PeerID, ji.Host, ji.Port)
	})
	trMgr.SetOnFail(func(ji transfer.JobInfo) {
		peer := ji.PeerName
		if peer == "" && resolvePeer != nil {
			peer = resolvePeer(ji.PeerID)
		}
		notifier.Notify(notify.Notice{Title: "LANyard", Body: transferFailText(ji.Direction, peer)})
		if ui == nil {
			return
		}
		kind := "download-failed"
		if ji.Direction == "push" {
			kind = "send-failed"
		}
		ui.NotifyUser(uiserver.Notice{Kind: kind, Peer: peer, Files: ji.Files, Total: ji.Total, Label: ji.Label, Error: ji.Error})
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

	// Notify the person on the receiving side when files start arriving and
	// when they have all landed in the Inbox. Names come from the trust store
	// first, then discovery.
	resolvePeer = func(fp string) string {
		if trustStore != nil {
			if e, ok := trustStore.Entry(fp); ok && e.Name != "" {
				return e.Name
			}
		}
		for _, p := range disc.Peers() {
			if p.DeviceID == fp && p.Name != "" {
				return p.Name
			}
		}
		if fp == "" {
			return "a device"
		}
		return identity.ShortID(fp)
	}
	inboxMgr.SetOnOffer(func(peerFP string, files int, total int64) {
		if ui != nil {
			ui.NotifyUser(uiserver.Notice{Kind: "receive-start", Peer: resolvePeer(peerFP), Files: files, Total: total})
		}
	})
	inboxMgr.SetOnDone(func(peerFP string, files int, total int64) {
		if ui != nil {
			ui.NotifyUser(uiserver.Notice{Kind: "receive", Peer: resolvePeer(peerFP), Files: files, Total: total})
		}
	})
	// A received text snippet is announced too; notify truncates and sanitizes
	// the body before it reaches the desktop.
	inboxMgr.SetOnSnippet(func(peerFP, text string) {
		notifier.Notify(notify.Notice{Title: "LANyard text message", Body: resolvePeer(peerFP) + ": " + text})
	})

	// A device asking to Connect or Pair is shown even when the window is not
	// focused; the request itself is still answered in the UI.
	trustStore.SetOnIncoming(func(sess *trust.Session) {
		who := sess.PeerName
		if who == "" && resolvePeer != nil {
			who = resolvePeer(sess.PeerFP)
		}
		if who == "" {
			who = "A device"
		}
		action := "wants to connect"
		if sess.Mode == trust.ModePair {
			action = "wants to pair"
		}
		notifier.Notify(notify.Notice{Title: "LANyard", Body: who + " " + action})
	})

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
				PeerPortRequested: peerSrv.RequestedPort(), PeerPortFallback: peerSrv.PortFellBack(),
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
		Inbox:     inboxMgr,
		Mounts:    mountMgr,
		SelfFP:    id.DeviceID,
		Cfg:       cfg,
		ApplySettings: func(s config.Settings) {
			inboxMgr.SetDir(inboxDir(dataDir, s.InboxFolder))
			trMgr.SetBandwidthLimit(s.BandwidthLimitMBps)
			disc.SetIdentity(s.DeviceName, s.DeviceIDLabel) // re-announce without a restart
			minimizeToTray.Store(s.MinimizeToTray)
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
	native := nativeMode

	// System tray: click to open (or restore) the window, right-click to quit.
	// With the "minimize to system tray" setting the native window hides here
	// instead of using the taskbar.
	minimizeToTray.Store(st.MinimizeToTray)
	stopTray := func() {}
	if !noTray {
		stopTray = startTray(trayOptions{
			IconPath: filepath.Join(dataDir, "icon.ico"),
			OnOpen: func() {
				if native && showNativeWindow() {
					return
				}
				openBrowser(ui.URL())
			},
			OnQuit: stop,
			Log:    log,
		})
	}
	defer stopTray()

	ri := runInfo{PID: os.Getpid(), UIURL: ui.URL(), Base: fmt.Sprintf("http://127.0.0.1:%d", ui.Port()), UI: uiMode, Version: version}
	if b, err := json.Marshal(ri); err == nil {
		_ = config.WriteFileAtomic(runPath, b, 0o600)
	}
	// Only remove run.json if it is still ours: a restart can start the new
	// process before this one finishes exiting, and its file must survive.
	defer func() {
		if cur := readRunInfo(runPath); cur != nil && cur.PID == os.Getpid() {
			_ = os.Remove(runPath)
		}
	}()

	fmt.Printf("\nLANyard File Transfer %s\n  Device : %s\n  ID     : %s\n  Peers  : port %d\n  UI     : %s\n\n",
		version, st.DeviceName, identity.Pretty(id.DeviceID), peerSrv.Port(), ui.URL())

	switch {
	case native:
		err := runNativeUI(nativeUIOptions{
			URL:      ui.URL(),
			Title:    "LANyard File Transfer",
			DataPath: filepath.Join(dataDir, nativeProfileDir),
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
	// The window is closed (or Quit was chosen): the program must end. Stop the
	// background work, take the tray icon away, and exit even if a graceful
	// stop hangs on an open connection or a discovery probe.
	stop()
	stopTray()
	go func() {
		time.Sleep(4 * time.Second)
		log.Warn("graceful shutdown timed out; exiting")
		_ = os.Remove(runPath)
		os.Exit(0)
	}()
	sctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
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

// transferDoneText is the body of a finished-transfer desktop notification.
func transferDoneText(direction, peer string, files int) string {
	act, prep := "Received", "from"
	if direction == "push" {
		act, prep = "Sent", "to"
	}
	return fmt.Sprintf("%s %d %s %s %s", act, files, fileWord(files), prep, peerOrDevice(peer))
}

// transferFailText is the body of a failed-transfer desktop notification.
func transferFailText(direction, peer string) string {
	if direction == "push" {
		return "Sending to " + peerOrDevice(peer) + " failed"
	}
	return "Download from " + peerOrDevice(peer) + " failed"
}

func peerOrDevice(peer string) string {
	if peer == "" {
		return "a device"
	}
	return peer
}

func fileWord(n int) string {
	if n == 1 {
		return "file"
	}
	return "files"
}

// hoistDataDir moves a leading `--data-dir X` / `--data-dir=X` after the
// command word, so `lanyard --data-dir X peers` behaves like
// `lanyard peers --data-dir X`. Server invocations (no command) are unchanged.
func hoistDataDir(args []string) []string {
	var lead []string
	i := 0
	for i < len(args) {
		switch {
		case args[i] == "--data-dir" && i+1 < len(args):
			lead = append(lead, args[i], args[i+1])
			i += 2
		case strings.HasPrefix(args[i], "--data-dir="):
			lead = append(lead, args[i])
			i++
		default:
			goto done
		}
	}
done:
	if len(lead) == 0 || i >= len(args) || strings.HasPrefix(args[i], "-") {
		return args
	}
	out := append([]string{args[i]}, args[i+1:]...)
	return append(out, lead...)
}
