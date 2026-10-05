//go:build windows

package main

import (
	"fmt"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/lxn/walk"
	"github.com/lxn/walk/declarative"
)

// runNativeUI opens the native (Win32) window and blocks until it closes.
func runNativeUI(opts nativeUIOptions) error {
	app := &nativeApp{
		client: &cliClient{base: opts.Base, token: opts.Token, http: &http.Client{Timeout: 30 * time.Second}},
		opts:   opts,
		shown:  map[string]bool{},
	}
	return app.run()
}

type nativeApp struct {
	client *cliClient
	opts   nativeUIOptions

	mw *walk.MainWindow

	devicesModel   *devicesModel
	sharesModel    *sharesModel
	transfersModel *transfersModel
	trustModel     *trustModel

	devicesTV   *walk.TableView
	sharesTV    *walk.TableView
	transfersTV *walk.TableView
	trustTV     *walk.TableView

	statusLbl *walk.Label
	nameEdit  *walk.LineEdit
	labelEdit *walk.LineEdit
	dlEdit    *walk.LineEdit
	inboxEdit *walk.LineEdit
	bwEdit    *walk.LineEdit
	portEdit  *walk.LineEdit

	mu    sync.Mutex
	shown map[string]bool // session ids whose dialog we already opened
}

func (a *nativeApp) run() error {
	a.devicesModel = &devicesModel{}
	a.sharesModel = &sharesModel{}
	a.transfersModel = &transfersModel{}
	a.trustModel = &trustModel{}

	lifeItems := []string{
		"Until I stop", "Always (persistent)", "One-time",
		"5 minutes", "15 minutes", "30 minutes", "1 hour", "2 hours", "3 hours", "6 hours", "12 hours", "24 hours",
	}
	var lifeCombo *walk.ComboBox

	if err := (declarative.MainWindow{
		AssignTo: &a.mw,
		Title:    a.opts.Title,
		Size:     declarative.Size{Width: 1000, Height: 680},
		MinSize:  declarative.Size{Width: 820, Height: 520},
		Layout:   declarative.VBox{},
		Children: []declarative.Widget{
			declarative.TabWidget{
				Pages: []declarative.TabPage{
					{
						Title:  "Devices",
						Layout: declarative.VBox{},
						Children: []declarative.Widget{
							declarative.TableView{
								AssignTo: &a.devicesTV,
								Columns: []declarative.TableViewColumn{
									{Title: "Name", Width: 220},
									{Title: "State", Width: 110},
									{Title: "OS", Width: 110},
									{Title: "Address", Width: 200},
									{Title: "ID", Width: 220},
								},
								Model: a.devicesModel,
							},
							declarative.Composite{
								Layout: declarative.HBox{},
								Children: []declarative.Widget{
									declarative.PushButton{Text: "Pair", OnClicked: func() { a.startSession("pair") }},
									declarative.PushButton{Text: "Connect", OnClicked: func() { a.startSession("connect") }},
									declarative.PushButton{Text: "Browse / Download", OnClicked: a.browseSelected},
									declarative.PushButton{Text: "Push to Inbox", OnClicked: a.pushToSelected},
									declarative.PushButton{Text: "Unpair", OnClicked: a.unpairSelected},
									declarative.PushButton{Text: "Refresh", OnClicked: func() { a.refresh() }},
								},
							},
						},
					},
					{
						Title:  "My Shares",
						Layout: declarative.VBox{},
						Children: []declarative.Widget{
							declarative.TableView{
								AssignTo: &a.sharesTV,
								Columns: []declarative.TableViewColumn{
									{Title: "Label", Width: 240},
									{Title: "Kind", Width: 90},
									{Title: "Lifetime", Width: 160},
									{Title: "State", Width: 160},
									{Title: "Path", Width: 300},
								},
								Model: a.sharesModel,
							},
							declarative.Composite{
								Layout: declarative.HBox{},
								Children: []declarative.Widget{
									declarative.Label{Text: "When shared:"},
									declarative.ComboBox{AssignTo: &lifeCombo, Model: lifeItems, CurrentIndex: 0, MinSize: declarative.Size{Width: 160}},
									declarative.PushButton{Text: "Share a file…", OnClicked: func() { a.addShare(lifeCombo, false) }},
									declarative.PushButton{Text: "Share a folder…", OnClicked: func() { a.addShare(lifeCombo, true) }},
									declarative.PushButton{Text: "Stop", OnClicked: a.stopShareSelected},
									declarative.PushButton{Text: "Stop all + cancel transfers", OnClicked: a.cancelAll},
								},
							},
						},
					},
					{
						Title:  "Transfers",
						Layout: declarative.VBox{},
						Children: []declarative.Widget{
							declarative.TableView{
								AssignTo: &a.transfersTV,
								Columns: []declarative.TableViewColumn{
									{Title: "Dir", Width: 60},
									{Title: "Peer", Width: 150},
									{Title: "Item", Width: 260},
									{Title: "State", Width: 120},
									{Title: "Progress", Width: 90},
									{Title: "Speed", Width: 90},
									{Title: "ETA", Width: 80},
								},
								Model: a.transfersModel,
							},
							declarative.Composite{
								Layout: declarative.HBox{},
								Children: []declarative.Widget{
									declarative.PushButton{Text: "Pause", OnClicked: func() { a.transferAction("pause") }},
									declarative.PushButton{Text: "Resume", OnClicked: func() { a.transferAction("resume") }},
									declarative.PushButton{Text: "Cancel", OnClicked: a.cancelTransferSelected},
									declarative.PushButton{Text: "Clear finished", OnClicked: func() { _ = a.client.ClearFinished(); a.refresh() }},
								},
							},
						},
					},
					{
						Title:  "Settings",
						Layout: declarative.VBox{},
						Children: []declarative.Widget{
							declarative.Composite{
								Layout: declarative.Grid{Columns: 2},
								Children: []declarative.Widget{
									declarative.Label{Text: "Device name"},
									declarative.LineEdit{AssignTo: &a.nameEdit},
									declarative.Label{Text: "Device ID (label)"},
									declarative.LineEdit{AssignTo: &a.labelEdit},
									declarative.Label{Text: "Default download folder"},
									declarative.LineEdit{AssignTo: &a.dlEdit},
									declarative.Label{Text: "Inbox folder"},
									declarative.LineEdit{AssignTo: &a.inboxEdit},
									declarative.Label{Text: "Bandwidth limit (MB/s, 0 = unlimited)"},
									declarative.LineEdit{AssignTo: &a.bwEdit},
									declarative.Label{Text: "Peer port (restart to apply)"},
									declarative.LineEdit{AssignTo: &a.portEdit},
								},
							},
							declarative.Composite{
								Layout: declarative.HBox{},
								Children: []declarative.Widget{
									declarative.PushButton{Text: "Save settings", OnClicked: a.saveSettings},
									declarative.PushButton{Text: "Cancel all shares", OnClicked: a.cancelAll},
									declarative.PushButton{Text: "Open data folder", OnClicked: a.openDataFolder},
								},
							},
							declarative.Label{Text: "Paired devices (select a row, then Unpair)"},
							declarative.TableView{
								AssignTo: &a.trustTV,
								Columns: []declarative.TableViewColumn{
									{Title: "Name", Width: 220},
									{Title: "Permissions", Width: 300},
									{Title: "ID", Width: 300},
								},
								Model: a.trustModel,
							},
							declarative.PushButton{Text: "Unpair selected", OnClicked: a.unpairTrustSelected},
						},
					},
				},
			},
			declarative.Composite{
				Layout: declarative.HBox{},
				Children: []declarative.Widget{
					declarative.Label{AssignTo: &a.statusLbl, Text: "Starting…"},
				},
			},
		},
	}).Create(); err != nil {
		return err
	}

	// Poll the local API and push updates onto the UI thread.
	go a.pollLoop()

	if a.opts.OnQuit != nil {
		a.mw.Closing().Attach(func(canceled *bool, reason walk.CloseReason) {
			a.opts.OnQuit()
		})
	}
	if a.opts.Done != nil {
		go func() {
			<-a.opts.Done
			a.mw.Synchronize(func() { _ = a.mw.Close() })
		}()
	}
	a.mw.Run()
	return nil
}

func (a *nativeApp) pollLoop() {
	// Applies model updates and status on a slow tick.
	t := time.NewTicker(1500 * time.Millisecond)
	defer t.Stop()
	a.refresh()
	for range t.C {
		a.refresh()
	}
}

func (a *nativeApp) setStatus(s string) {
	if a.statusLbl != nil {
		_ = a.statusLbl.SetText(s)
	}
}

func (a *nativeApp) refresh() {
	peers, errP := a.client.Peers()
	shares, errS := a.client.Shares()
	transfers, errT := a.client.Transfers()
	trust, errX := a.client.Trust()
	sessions, _ := a.client.Sessions()

	a.mw.Synchronize(func() {
		a.devicesModel.set(peers)
		a.sharesModel.set(shares)
		a.transfersModel.set(transfers)
		a.trustModel.set(trust)
		a.devicesModel.PublishRowsReset()
		a.sharesModel.PublishRowsReset()
		a.transfersModel.PublishRowsReset()
		a.trustModel.PublishRowsReset()

		switch {
		case errP != nil || errS != nil || errT != nil || errX != nil:
			a.setStatus("Cannot reach LANyard's local service yet…")
		default:
			a.setStatus(fmt.Sprintf("%d device(s) · %d share(s) · %d transfer(s)", len(peers), len(shares), len(transfers)))
		}
		a.handleIncomingSessions(sessions)
	})
}

// handleIncomingSessions opens a dialog for a pending request we initiated or
// received, once. (Called on the UI thread.)
func (a *nativeApp) handleIncomingSessions(sessions []apiSession) {
	for _, s := range sessions {
		if !s.Incoming || s.Status != "pending" {
			continue
		}
		a.mu.Lock()
		seen := a.shown[s.ID]
		if !seen {
			a.shown[s.ID] = true
		}
		a.mu.Unlock()
		if !seen {
			a.openSessionDialog(s)
			return
		}
	}
}

func (a *nativeApp) selectedPeer() (apiPeer, bool) {
	i := a.devicesTV.CurrentIndex()
	peers, _ := a.client.Peers()
	if i < 0 || i >= len(peers) {
		return apiPeer{}, false
	}
	return peers[i], true
}

func (a *nativeApp) startSession(mode string) {
	p, ok := a.selectedPeer()
	if !ok {
		walk.MsgBox(a.mw, "LANyard", "Select a device first.", walk.MsgBoxIconInformation)
		return
	}
	if !p.Verified {
		walk.MsgBox(a.mw, "LANyard", "That device is still being verified. Wait a moment and refresh.", walk.MsgBoxIconWarning)
		return
	}
	perms := apiPerms{Browse: true, Push: false}
	view, err := a.client.StartSession(p.DeviceID, mode, perms, false)
	if err != nil {
		walk.MsgBox(a.mw, "LANyard", "Could not reach the device:\n\n"+err.Error(), walk.MsgBoxIconError)
		return
	}
	go a.openSessionDialog(view)
}

func displayName(p apiPeer) string {
	if p.Name != "" {
		return p.Name
	}
	if p.DeviceLabel != "" {
		return p.DeviceLabel
	}
	return p.DeviceID
}

func peerAddr(p apiPeer) string {
	if len(p.Addrs) == 0 {
		return ""
	}
	return fmt.Sprintf("%s:%d", p.Addrs[0], p.Port)
}

func peerState(p apiPeer) string {
	if !p.Verified {
		return "Verifying…"
	}
	return "Ready"
}

func fmtBytes(n int64) string {
	const u = 1024
	if n < u {
		return fmt.Sprintf("%d B", n)
	}
	units := []string{"KB", "MB", "GB", "TB"}
	v := float64(n)
	i := -1
	for v >= u && i < len(units)-1 {
		v /= u
		i++
	}
	return fmt.Sprintf("%.1f %s", v, units[i])
}

func fmtETA(sec int) string {
	if sec <= 0 {
		return "--"
	}
	m, s := sec/60, sec%60
	if m >= 60 {
		return fmt.Sprintf("%d:%02d:%02d", m/60, m%60, s)
	}
	return fmt.Sprintf("%02d:%02d", m, s)
}

func shortID(id string) string {
	if len(id) > 16 {
		return id[:16]
	}
	return id
}

// --- models ---

type devicesModel struct {
	walk.TableModelBase
	items []apiPeer
}

func (m *devicesModel) set(v []apiPeer) { m.items = v }
func (m *devicesModel) RowCount() int   { return len(m.items) }
func (m *devicesModel) Value(row, col int) interface{} {
	p := m.items[row]
	switch col {
	case 0:
		return displayName(p)
	case 1:
		return peerState(p)
	case 2:
		return p.OS
	case 3:
		return peerAddr(p)
	case 4:
		return shortID(p.DeviceID)
	}
	return ""
}

type sharesModel struct {
	walk.TableModelBase
	items []apiShare
}

func (m *sharesModel) set(v []apiShare) { m.items = v }
func (m *sharesModel) RowCount() int    { return len(m.items) }
func (m *sharesModel) Value(row, col int) interface{} {
	s := m.items[row]
	switch col {
	case 0:
		return s.Label
	case 1:
		return s.Kind
	case 2:
		return shareLifetime(s.Lifetime)
	case 3:
		if s.State == "finishing" {
			return "Finishing (" + s.EndReason + ")"
		}
		return "Active"
	case 4:
		return s.Path
	}
	return ""
}

func shareLifetime(l apiLifetime) string {
	switch l.Type {
	case "persistent":
		return "Always"
	case "one_time":
		return "One-time"
	case "timed":
		return "Timed"
	default:
		return "Until stopped"
	}
}

type transfersModel struct {
	walk.TableModelBase
	items []apiTransfer
}

func (m *transfersModel) set(v []apiTransfer) { m.items = v }
func (m *transfersModel) RowCount() int       { return len(m.items) }
func (m *transfersModel) Value(row, col int) interface{} {
	t := m.items[row]
	switch col {
	case 0:
		if t.Direction == "push" {
			return "↑"
		}
		return "↓"
	case 1:
		return t.PeerName
	case 2:
		if t.ShareLabel != "" {
			return t.ShareLabel
		}
		return t.ShareID
	case 3:
		if t.Note != "" && t.State != "Done" {
			return t.State + " (" + t.Note + ")"
		}
		return t.State
	case 4:
		if t.Total > 0 {
			return fmt.Sprintf("%d%%", t.Done*100/t.Total)
		}
		return ""
	case 5:
		if t.SpeedMBPS > 0 {
			return fmt.Sprintf("%.1f MB/s", t.SpeedMBPS)
		}
		return ""
	case 6:
		return fmtETA(t.ETASeconds)
	}
	return ""
}

type trustModel struct {
	walk.TableModelBase
	items []apiTrust
}

func (m *trustModel) set(v []apiTrust) { m.items = v }
func (m *trustModel) RowCount() int    { return len(m.items) }
func (m *trustModel) Value(row, col int) interface{} {
	e := m.items[row]
	switch col {
	case 0:
		if e.Name != "" {
			return e.Name
		}
		return e.DeviceID
	case 1:
		bits := []string{}
		if e.Permissions.Browse {
			bits = append(bits, "browse")
		}
		if e.Permissions.Push {
			bits = append(bits, "push")
		}
		if len(bits) == 0 {
			return "none"
		}
		return strings.Join(bits, ", ")
	case 2:
		return shortID(e.Fingerprint)
	}
	return ""
}

// sortedStrings is a tiny helper used by dialogs below.
func sortedStrings(in []string) []string {
	out := append([]string(nil), in...)
	sort.Strings(out)
	return out
}
