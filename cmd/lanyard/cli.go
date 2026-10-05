package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"lanyard/internal/config"
)

// runCLI handles `lanyard <command> ...` by talking to the running instance's
// loopback API (spec §2.1). It returns false if the first argument is not a
// known command, so the caller can start the server instead.
func runCLI(cmd string, args []string) bool {
	switch cmd {
	case "peers", "share", "shares", "get", "settings", "pair", "cancel-all", "mount":
	default:
		return false
	}
	if err := cliMain(cmd, args); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
	return true
}

type cliClient struct {
	base  string
	token string
	http  *http.Client
}

func cliMain(cmd string, args []string) error {
	dir, rest := takeDataDir(args)
	if dir == "" {
		d, err := config.DefaultDir()
		if err != nil {
			return err
		}
		dir = d
	}
	ri := readRunInfo(filepath.Join(dir, "run.json"))
	if ri == nil || !alreadyRunning(ri) {
		return fmt.Errorf("LANyard is not running (no instance for %s)", dir)
	}
	u, err := url.Parse(ri.UIURL)
	if err != nil {
		return err
	}
	c := &cliClient{base: ri.Base, token: u.Query().Get("t"), http: &http.Client{Timeout: 30 * time.Second}}

	switch cmd {
	case "peers":
		return c.peers()
	case "share":
		return c.share(rest)
	case "shares":
		if len(rest) > 0 && rest[0] == "cancel-all" {
			return c.cancelAll()
		}
		return fmt.Errorf("usage: lanyard shares cancel-all")
	case "cancel-all":
		return c.cancelAll()
	case "get":
		return c.get(rest)
	case "settings":
		return c.settings(rest)
	case "mount":
		return c.mount(rest)
	case "pair":
		return fmt.Errorf("CLI pairing is not supported yet; use the web UI to pair")
	}
	return nil
}

func takeDataDir(args []string) (string, []string) {
	out := make([]string, 0, len(args))
	dir := ""
	for i := 0; i < len(args); i++ {
		if args[i] == "--data-dir" && i+1 < len(args) {
			dir = args[i+1]
			i++
			continue
		}
		if strings.HasPrefix(args[i], "--data-dir=") {
			dir = strings.TrimPrefix(args[i], "--data-dir=")
			continue
		}
		out = append(out, args[i])
	}
	return dir, out
}

func (c *cliClient) do(method, path string, body any, out any) error {
	var rdr *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rdr = bytes.NewReader(b)
	} else {
		rdr = bytes.NewReader(nil)
	}
	req, err := http.NewRequest(method, c.base+path, rdr)
	if err != nil {
		return err
	}
	req.Header.Set("Cookie", "lany="+c.token)
	if method != http.MethodGet {
		req.Header.Set("Origin", c.base)
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		var buf bytes.Buffer
		_, _ = buf.ReadFrom(resp.Body)
		return fmt.Errorf("%s: %s", resp.Status, strings.TrimSpace(buf.String()))
	}
	if out == nil {
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

type cliPeer struct {
	ShortID     string   `json:"short_id"`
	DeviceID    string   `json:"device_id"`
	DeviceLabel string   `json:"device_label"`
	Name        string   `json:"name"`
	OS          string   `json:"os"`
	Addrs       []string `json:"addrs"`
	Port        int      `json:"port"`
	Verified    bool     `json:"verified"`
}

func (c *cliClient) peers() error {
	var list []cliPeer
	if err := c.do(http.MethodGet, "/api/peers", nil, &list); err != nil {
		return err
	}
	if len(list) == 0 {
		fmt.Println("No devices discovered. Try `lanyard` on the other machine, or add by address in the web UI.")
		return nil
	}
	for _, p := range list {
		addr := ""
		if len(p.Addrs) > 0 {
			addr = fmt.Sprintf("%s:%d", p.Addrs[0], p.Port)
		}
		label := p.DeviceLabel
		if label == "" {
			label = p.DeviceID[:min(16, len(p.DeviceID))]
		}
		fmt.Printf("%-16s %-20s %-16s %s\n", label, p.Name, p.OS, addr)
	}
	return nil
}

type cliShare struct {
	ShareID   string `json:"share_id"`
	Label     string `json:"label"`
	Kind      string `json:"kind"`
	Size      int64  `json:"size"`
	Lifetime  string `json:"lifetime"`
	ExpiresAt string `json:"expires_at"`
}

func (c *cliClient) share(args []string) error {
	if len(args) == 0 || args[0] != "add" {
		return fmt.Errorf("usage: lanyard share add <path> [--for 1h|30m|10s|always|once] [--label name]")
	}
	path, label, forSpec := "", "", ""
	for i := 1; i < len(args); i++ {
		switch args[i] {
		case "--label":
			if i+1 < len(args) {
				label = args[i+1]
				i++
			}
		case "--for":
			if i+1 < len(args) {
				forSpec = args[i+1]
				i++
			}
		default:
			if path == "" {
				path = args[i]
			}
		}
	}
	if path == "" {
		return fmt.Errorf("a path is required")
	}
	body := map[string]any{"path": path, "label": label, "lifetime": "until_stopped"}
	switch strings.ToLower(forSpec) {
	case "", "stop", "until-stopped":
		// default
	case "always", "persistent":
		body["lifetime"] = "persistent"
	case "once", "one-time":
		body["lifetime"] = "one_time"
	default:
		secs, err := parseDuration(forSpec)
		if err != nil {
			return err
		}
		body["lifetime"] = "timed"
		body["seconds"] = secs
	}
	var out struct {
		ShareID  string `json:"share_id"`
		Lifetime struct {
			Type string `json:"type"`
		} `json:"lifetime"`
	}
	if err := c.do(http.MethodPost, "/api/shares", body, &out); err != nil {
		return err
	}
	fmt.Printf("shared %s as %s (%s)\n", path, out.ShareID, out.Lifetime.Type)
	return nil
}

// parseDuration accepts Go-style durations plus a bare number of seconds.
func parseDuration(s string) (int, error) {
	if n, err := strconv.Atoi(s); err == nil {
		return n, nil
	}
	d, err := time.ParseDuration(s)
	if err != nil {
		return 0, fmt.Errorf("bad duration %q (try 30m, 1h, 45s)", s)
	}
	return int(d.Seconds()), nil
}

func (c *cliClient) cancelAll() error {
	var out map[string]int
	if err := c.do(http.MethodPost, "/api/cancel-all", map[string]any{}, &out); err != nil {
		return err
	}
	fmt.Printf("stopped %d share(s), cancelled %d transfer(s)\n", out["shares_stopped"], out["transfers_cancelled"])
	return nil
}

func (c *cliClient) get(args []string) error {
	if len(args) < 2 {
		return fmt.Errorf("usage: lanyard get <peer> <share> [destination]")
	}
	peerArg, shareArg := args[0], args[1]
	dest := ""
	if len(args) > 2 {
		dest = args[2]
	}
	var peers []cliPeer
	if err := c.do(http.MethodGet, "/api/peers", nil, &peers); err != nil {
		return err
	}
	p := matchPeer(peers, peerArg)
	if p == nil {
		return fmt.Errorf("no device matches %q", peerArg)
	}
	var list []cliShare
	if err := c.do(http.MethodGet, "/api/remote/shares?device="+url.QueryEscape(p.DeviceID), nil, &list); err != nil {
		return err
	}
	var sh *cliShare
	for i := range list {
		if list[i].ShareID == shareArg || strings.EqualFold(list[i].Label, shareArg) {
			sh = &list[i]
			break
		}
	}
	if sh == nil {
		return fmt.Errorf("%s is not sharing anything called %q (or you are not paired)", p.Name, shareArg)
	}
	if dest == "" {
		var st map[string]any
		_ = c.do(http.MethodGet, "/api/settings", nil, &st)
		if d, _ := st["default_download_folder"].(string); d != "" {
			dest = d
		} else if wd, err := os.Getwd(); err == nil {
			dest = wd
		}
	}
	var job struct {
		ID string `json:"id"`
	}
	if err := c.do(http.MethodPost, "/api/transfers", map[string]any{
		"device": p.DeviceID, "share_id": sh.ShareID, "share_label": sh.Label,
		"peer_name": p.Name, "paths": []string{""}, "dest": dest,
	}, &job); err != nil {
		return err
	}
	fmt.Printf("downloading %q from %s into %s (job %s)\n", sh.Label, p.Name, dest, job.ID)
	return c.watch(job.ID)
}

func matchPeer(peers []cliPeer, arg string) *cliPeer {
	low := strings.ToLower(arg)
	for i := range peers {
		p := &peers[i]
		if p.DeviceID == arg || p.ShortID == arg {
			return p
		}
		if strings.EqualFold(p.Name, arg) || strings.EqualFold(p.DeviceLabel, arg) {
			return p
		}
		if strings.HasPrefix(strings.ToLower(p.DeviceID), low) || strings.HasPrefix(strings.ToLower(p.ShortID), low) {
			return p
		}
	}
	return nil
}

func (c *cliClient) watch(id string) error {
	type view struct {
		ID    string  `json:"id"`
		State string  `json:"state"`
		Done  int64   `json:"done"`
		Total int64   `json:"total"`
		Speed float64 `json:"speed_mbps"`
		Error string  `json:"error"`
	}
	last := -1
	for {
		var list []view
		if err := c.do(http.MethodGet, "/api/transfers", nil, &list); err != nil {
			return err
		}
		var v *view
		for i := range list {
			if list[i].ID == id {
				v = &list[i]
				break
			}
		}
		if v == nil {
			return fmt.Errorf("job %s disappeared", id)
		}
		pct := 0
		if v.Total > 0 {
			pct = int(v.Done * 100 / v.Total)
		}
		if pct != last {
			fmt.Printf("\r  %3d%%  %s", pct, v.State)
			last = pct
		}
		switch v.State {
		case "Done":
			fmt.Println("\n  done")
			return nil
		case "Failed":
			fmt.Println()
			return fmt.Errorf("transfer failed: %s", v.Error)
		case "Paused", "Waiting for peer":
			// keep watching; it may resume
		}
		time.Sleep(500 * time.Millisecond)
	}
}

func (c *cliClient) settings(args []string) error {
	switch {
	case len(args) == 0 || args[0] == "get":
		var st map[string]any
		if err := c.do(http.MethodGet, "/api/settings", nil, &st); err != nil {
			return err
		}
		for _, k := range []string{"device_name", "device_id_label", "theme", "speed_unit", "sound_on_complete", "default_download_folder", "inbox_folder", "peer_port", "bandwidth_limit_mbps", "start_on_login"} {
			fmt.Printf("%-24s %v\n", k, st[k])
		}
		return nil
	case args[0] == "set":
		if len(args) < 3 {
			return fmt.Errorf("usage: lanyard settings set <key> <value>")
		}
		key, val := args[1], strings.Join(args[2:], " ")
		body := map[string]any{}
		switch key {
		case "device_name", "device_id_label", "theme", "speed_unit", "default_download_folder", "inbox_folder":
			body[key] = val
		case "sound_on_complete", "start_on_login":
			body[key] = val == "true" || val == "on" || val == "1"
		case "peer_port", "bandwidth_limit_mbps":
			n, err := strconv.Atoi(val)
			if err != nil {
				return fmt.Errorf("%s must be a number", key)
			}
			body[key] = n
		default:
			return fmt.Errorf("unknown setting %q", key)
		}
		var out map[string]any
		if err := c.do(http.MethodPut, "/api/settings", body, &out); err != nil {
			return err
		}
		fmt.Printf("%s updated\n", key)
		return nil
	default:
		return fmt.Errorf("usage: lanyard settings get | lanyard settings set <key> <value>")
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

type cliMount struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	URL     string `json:"url"`
	Drive   string `json:"drive"`
	Mounted bool   `json:"mounted"`
	Hint    string `json:"hint"`
	OSError string `json:"os_error"`
}

// mount manages "mount as share drive": add <peer> [Z:], list, remove <id>.
func (c *cliClient) mount(args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("usage: lanyard mount add <peer> [Z:] | list | remove <id>")
	}
	switch args[0] {
	case "list":
		var list []cliMount
		if err := c.do(http.MethodGet, "/api/mounts", nil, &list); err != nil {
			return err
		}
		if len(list) == 0 {
			fmt.Println("No mounted drives.")
		}
		for _, m := range list {
			fmt.Printf("%-12s %-20s %-4s %s\n", m.ID, m.Name, m.Drive, m.URL)
		}
		return nil
	case "add":
		if len(args) < 2 {
			return fmt.Errorf("usage: lanyard mount add <peer> [Z:]")
		}
		var peers []cliPeer
		if err := c.do(http.MethodGet, "/api/peers", nil, &peers); err != nil {
			return err
		}
		p := matchPeer(peers, args[1])
		if p == nil {
			return fmt.Errorf("no device matches %q (see `lanyard peers`)", args[1])
		}
		drive := ""
		if len(args) > 2 {
			drive = args[2]
		}
		var m cliMount
		if err := c.do(http.MethodPost, "/api/mounts", map[string]string{"device": p.DeviceID, "drive": drive}, &m); err != nil {
			return err
		}
		fmt.Printf("Serving %s read-only at %s\n", m.Name, m.URL)
		switch {
		case m.Mounted:
			fmt.Printf("Mounted as %s\n", m.Drive)
		case m.OSError != "":
			fmt.Printf("Could not mount it automatically: %s\nMount it yourself with:\n  %s\n", m.OSError, m.Hint)
		default:
			fmt.Printf("Mount it with:\n  %s\n", m.Hint)
		}
		return nil
	case "remove":
		if len(args) < 2 {
			return fmt.Errorf("usage: lanyard mount remove <id>")
		}
		return c.do(http.MethodPost, "/api/mounts/"+url.PathEscape(args[1])+"/remove", nil, nil)
	}
	return fmt.Errorf("usage: lanyard mount add <peer> [Z:] | list | remove <id>")
}
