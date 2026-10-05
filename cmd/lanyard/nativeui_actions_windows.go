//go:build windows

package main

import (
	"fmt"
	"strings"
	"time"

	"github.com/lxn/walk"
	"github.com/lxn/walk/declarative"
)

func sasText(s string) string {
	if len(s) == 6 {
		return s[:3] + " " + s[3:]
	}
	return s
}

func (a *nativeApp) selectedShare() (apiShare, bool) {
	i := a.sharesTV.CurrentIndex()
	items, _ := a.client.Shares()
	if i < 0 || i >= len(items) {
		return apiShare{}, false
	}
	return items[i], true
}

func (a *nativeApp) selectedTransfer() (apiTransfer, bool) {
	i := a.transfersTV.CurrentIndex()
	items, _ := a.client.Transfers()
	if i < 0 || i >= len(items) {
		return apiTransfer{}, false
	}
	return items[i], true
}

func lifetimeFromIndex(i int) (string, int) {
	switch i {
	case 0:
		return "until_stopped", 0
	case 1:
		return "persistent", 0
	case 2:
		return "one_time", 0
	case 3:
		return "timed", 5 * 60
	case 4:
		return "timed", 15 * 60
	case 5:
		return "timed", 30 * 60
	case 6:
		return "timed", 3600
	case 7:
		return "timed", 2 * 3600
	case 8:
		return "timed", 3 * 3600
	case 9:
		return "timed", 6 * 3600
	case 10:
		return "timed", 12 * 3600
	case 11:
		return "timed", 24 * 3600
	}
	return "until_stopped", 0
}

func (a *nativeApp) addShare(lifeCombo *walk.ComboBox, folder bool) {
	fd := walk.FileDialog{Title: "Choose what to share"}
	var ok bool
	var err error
	if folder {
		ok, err = fd.ShowBrowseFolder(a.mw)
	} else {
		ok, err = fd.ShowOpen(a.mw)
	}
	if err != nil || !ok {
		return
	}
	life, secs := lifetimeFromIndex(lifeCombo.CurrentIndex())
	if _, err := a.client.AddShare(fd.FilePath, "", life, secs); err != nil {
		walk.MsgBox(a.mw, "LANyard", err.Error(), walk.MsgBoxIconError)
		return
	}
	a.refresh()
}

// sharePathWith offers/creates a share for a specific connected device.
func (a *nativeApp) sharePathWith(path string) {
	dev, ok := a.pickConnectedDevice()
	if !ok {
		return
	}
	if _, err := a.client.AddShareTo(path, dev.DeviceID); err != nil {
		walk.MsgBox(a.mw, "LANyard", err.Error(), walk.MsgBoxIconError)
		return
	}
	a.refresh()
}

func (a *nativeApp) stopShareSelected() {
	s, ok := a.selectedShare()
	if !ok {
		walk.MsgBox(a.mw, "LANyard", "Select a share first.", walk.MsgBoxIconInformation)
		return
	}
	_ = a.client.StopShare(s.ShareID)
	a.refresh()
}

func (a *nativeApp) cancelAll() {
	if walk.MsgBox(a.mw, "LANyard", "Stop every share and cancel every transfer now?", walk.MsgBoxYesNo|walk.MsgBoxIconQuestion) != walk.DlgCmdYes {
		return
	}
	if _, err := a.client.CancelAll(); err != nil {
		walk.MsgBox(a.mw, "LANyard", err.Error(), walk.MsgBoxIconError)
	}
	a.refresh()
}

func (a *nativeApp) transferAction(action string) {
	t, ok := a.selectedTransfer()
	if !ok {
		return
	}
	_ = a.client.TransferAction(t.ID, action)
	a.refresh()
}

func (a *nativeApp) cancelTransferSelected() {
	t, ok := a.selectedTransfer()
	if !ok {
		return
	}
	del := false
	if t.State != "Done" {
		if walk.MsgBox(a.mw, "LANyard", "Cancel this transfer?", walk.MsgBoxYesNo|walk.MsgBoxIconQuestion) != walk.DlgCmdYes {
			return
		}
		del = walk.MsgBox(a.mw, "LANyard", "Also delete the partial files?", walk.MsgBoxYesNo|walk.MsgBoxIconQuestion) == walk.DlgCmdYes
	}
	_ = a.client.CancelTransfer(t.ID, del)
	a.refresh()
}

func (a *nativeApp) unpairSelected() {
	p, ok := a.selectedPeer()
	if !ok {
		return
	}
	a.unpairFP(p.DeviceID, displayName(p))
}

func (a *nativeApp) unpairTrustSelected() {
	i := a.trustTV.CurrentIndex()
	items, _ := a.client.Trust()
	if i < 0 || i >= len(items) {
		return
	}
	a.unpairFP(items[i].Fingerprint, items[i].Name)
}

func (a *nativeApp) unpairFP(fp, name string) {
	if walk.MsgBox(a.mw, "LANyard", "Unpair "+name+"?", walk.MsgBoxYesNo|walk.MsgBoxIconQuestion) != walk.DlgCmdYes {
		return
	}
	_ = a.client.Unpair(fp)
	a.refresh()
}

func (a *nativeApp) pushToSelected() {
	p, ok := a.selectedPeer()
	if !ok {
		walk.MsgBox(a.mw, "LANyard", "Select a paired device first.", walk.MsgBoxIconInformation)
		return
	}
	fd := walk.FileDialog{Title: "Choose a file or folder to push to the Inbox"}
	ok, err := fd.ShowBrowseFolder(a.mw)
	if err != nil || !ok {
		return
	}
	if err := a.client.Push(p.DeviceID, []string{fd.FilePath}); err != nil {
		walk.MsgBox(a.mw, "LANyard", err.Error(), walk.MsgBoxIconError)
	}
	a.refresh()
}

func (a *nativeApp) saveSettings() {
	body := map[string]any{
		"device_name":             a.nameEdit.Text(),
		"device_id_label":         a.labelEdit.Text(),
		"default_download_folder": a.dlEdit.Text(),
		"inbox_folder":            a.inboxEdit.Text(),
	}
	if n, err := parseIntOr(a.bwEdit.Text(), 0); err == nil {
		body["bandwidth_limit_mbps"] = n
	}
	if n, err := parseIntOr(a.portEdit.Text(), 47800); err == nil {
		body["peer_port"] = n
	}
	if err := a.client.SaveSettings(body); err != nil {
		walk.MsgBox(a.mw, "LANyard", err.Error(), walk.MsgBoxIconError)
		return
	}
	walk.MsgBox(a.mw, "LANyard", "Settings saved.", walk.MsgBoxIconInformation)
	a.refresh()
}

func (a *nativeApp) openDataFolder() {
	s, err := a.client.Settings()
	if err != nil {
		return
	}
	_ = s
	// The settings payload does not expose the data dir; open the Inbox default
	// is not useful, so just tell the user where it is via the header later.
	walk.MsgBox(a.mw, "LANyard", "Your settings, key and log live in the LANyard data folder\n(%APPDATA%\\LANyard on Windows).", walk.MsgBoxIconInformation)
}

// pickConnectedDevice shows a small chooser of paired/connected devices.
func (a *nativeApp) pickConnectedDevice() (apiPeer, bool) {
	peers, _ := a.client.Peers()
	trust, _ := a.client.Trust()
	paired := map[string]bool{}
	for _, t := range trust {
		paired[t.Fingerprint] = true
	}
	var options []apiPeer
	for _, p := range peers {
		if p.Verified && paired[p.DeviceID] {
			options = append(options, p)
		}
	}
	if len(options) == 0 {
		walk.MsgBox(a.mw, "LANyard", "Pair with a device first, then share to it.", walk.MsgBoxIconInformation)
		return apiPeer{}, false
	}
	var chosen *apiPeer
	var dlg *walk.Dialog
	var combo *walk.ComboBox
	names := make([]string, len(options))
	for i, p := range options {
		names[i] = displayName(p)
	}
	declarative.Dialog{
		AssignTo: &dlg,
		Title:    "Share with…",
		MinSize:  declarative.Size{Width: 360, Height: 150},
		Layout:   declarative.VBox{},
		Children: []declarative.Widget{
			declarative.Label{Text: "Share this folder with a connected device:"},
			declarative.ComboBox{AssignTo: &combo, Model: names, CurrentIndex: 0},
			declarative.Composite{Layout: declarative.HBox{}, Children: []declarative.Widget{
				declarative.PushButton{Text: "Share", OnClicked: func() {
					i := combo.CurrentIndex()
					if i >= 0 && i < len(options) {
						p := options[i]
						chosen = &p
					}
					dlg.Accept()
				}},
				declarative.PushButton{Text: "Cancel", OnClicked: func() { dlg.Cancel() }},
			}},
		},
	}.Create(a.mw)
	dlg.Run()
	if chosen == nil {
		return apiPeer{}, false
	}
	return *chosen, true
}

// --- pairing / connect dialog ---

func (a *nativeApp) openSessionDialog(s apiSession) {
	var dlg *walk.Dialog
	var sasLbl, statusLbl *walk.Label
	var browseChk, pushChk *walk.CheckBox
	perms := s.Requested
	if perms.Browse == false && perms.Push == false {
		perms = s.Granted
	}

	declarative.Dialog{
		AssignTo: &dlg,
		Title:    "Pair / Connect",
		MinSize:  declarative.Size{Width: 460, Height: 320},
		Layout:   declarative.VBox{},
		Children: []declarative.Widget{
			declarative.Label{Text: "Device: " + firstNonEmpty(s.PeerName, shortID(s.PeerFP))},
			declarative.Label{Text: "Confirm that both screens show the same code:"},
			declarative.Label{AssignTo: &sasLbl, Text: "Code:  " + sasText(s.SAS)},
			declarative.Label{AssignTo: &statusLbl, Text: "Status: " + s.Status},
			declarative.CheckBox{AssignTo: &browseChk, Text: "Let them browse and pull my shares", Checked: perms.Browse},
			declarative.CheckBox{AssignTo: &pushChk, Text: "Let them push files to my Inbox", Checked: perms.Push},
			declarative.Composite{Layout: declarative.HBox{}, Children: []declarative.Widget{
				declarative.PushButton{Text: "Accept", OnClicked: func() {
					p := aPerms(browseChk.Checked(), pushChk.Checked())
					if _, err := a.client.SessionAccept(s.ID, p, false); err != nil {
						walk.MsgBox(dlg, "LANyard", err.Error(), walk.MsgBoxIconError)
						return
					}
					_ = statusLbl.SetText("Status: accepted")
				}},
				declarative.PushButton{Text: "The codes match — confirm", OnClicked: func() {
					if _, err := a.client.SessionConfirm(s.ID); err != nil {
						walk.MsgBox(dlg, "LANyard", err.Error(), walk.MsgBoxIconError)
						return
					}
					_ = statusLbl.SetText("Status: connected")
				}},
				declarative.PushButton{Text: "Close", OnClicked: func() { dlg.Accept() }},
			}},
		},
	}.Create(a.mw)

	stop := make(chan struct{})
	go func() {
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				v, err := a.client.SessionRefresh(s.ID)
				if err != nil {
					continue
				}
				dlg.Synchronize(func() {
					_ = statusLbl.SetText("Status: " + v.Status)
					if v.SAS != "" {
						_ = sasLbl.SetText("Code:  " + sasText(v.SAS))
					}
				})
			}
		}
	}()
	dlg.Run()
	close(stop)
	_ = a.client.SessionClose(s.ID)
	a.refresh()
}

func aPerms(browse, push bool) apiPerms { return apiPerms{Browse: browse, Push: push} }

func firstNonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

// --- remote browse + download ---

type remoteModel struct {
	walk.TableModelBase
	entries []apiRemoteEntry
	path    string
}

func (m *remoteModel) set(path string, entries []apiRemoteEntry) {
	m.path, m.entries = path, entries
}
func (m *remoteModel) RowCount() int { return len(m.entries) }
func (m *remoteModel) Value(row, col int) interface{} {
	e := m.entries[row]
	switch col {
	case 0:
		if e.IsDir {
			return "📁 " + e.Name
		}
		return "📄 " + e.Name
	case 1:
		if e.IsDir {
			return "Folder"
		}
		return "File"
	case 2:
		if e.IsDir {
			return ""
		}
		return fmtBytes(e.Size)
	}
	return ""
}

func (a *nativeApp) browseSelected() {
	p, ok := a.selectedPeer()
	if !ok {
		walk.MsgBox(a.mw, "LANyard", "Select a device first.", walk.MsgBoxIconInformation)
		return
	}
	shares, err := a.client.RemoteShares(p.DeviceID)
	if err != nil {
		walk.MsgBox(a.mw, "LANyard", "Could not list shares:\n\n"+err.Error(), walk.MsgBoxIconError)
		return
	}
	if len(shares) == 0 {
		walk.MsgBox(a.mw, "LANyard", "That device is not sharing anything you can see.", walk.MsgBoxIconInformation)
		return
	}
	labels := make([]string, len(shares))
	for i, s := range shares {
		labels[i] = s.Label
	}

	var dlg *walk.Dialog
	var combo *walk.ComboBox
	var addr *walk.LineEdit
	var tv *walk.TableView
	model := &remoteModel{}
	cur := "" // current path within the selected share

	load := func() {
		i := combo.CurrentIndex()
		if i < 0 {
			return
		}
		entries, err := a.client.RemoteTree(p.DeviceID, shares[i].ShareID, cur)
		if err != nil {
			walk.MsgBox(dlg, "LANyard", err.Error(), walk.MsgBoxIconError)
			return
		}
		model.set(cur, entries)
		tv.Model().(*remoteModel).PublishRowsReset()
		_ = addr.SetText("/" + cur)
	}

	declarative.Dialog{
		AssignTo: &dlg,
		Title:    "Browse " + displayName(p),
		Size:     declarative.Size{Width: 720, Height: 520},
		Layout:   declarative.VBox{},
		Children: []declarative.Widget{
			declarative.Composite{Layout: declarative.HBox{}, Children: []declarative.Widget{
				declarative.Label{Text: "Share:"},
				declarative.ComboBox{AssignTo: &combo, Model: labels, CurrentIndex: 0},
				declarative.Label{Text: "Path:"},
				declarative.LineEdit{AssignTo: &addr, Text: "/"},
				declarative.PushButton{Text: "Go", OnClicked: func() {
					cur = strings.TrimPrefix(addr.Text(), "/")
					load()
				}},
				declarative.PushButton{Text: "Up", OnClicked: func() {
					cur = parentPath(cur)
					load()
				}},
			}},
			declarative.TableView{
				AssignTo: &tv,
				Model:    model,
				Columns: []declarative.TableViewColumn{
					{Title: "Name", Width: 420},
					{Title: "Type", Width: 100},
					{Title: "Size", Width: 120},
				},
				OnItemActivated: func() {
					i := tv.CurrentIndex()
					if i >= 0 && model.entries[i].IsDir {
						cur = model.entries[i].Path
						load()
					}
				},
				ContextMenuItems: []declarative.MenuItem{
					declarative.Action{Text: "Download to…", OnTriggered: func() { a.downloadEntry(dlg, p, shares, combo.CurrentIndex(), model, tv.CurrentIndex()) }},
				},
			},
			declarative.Composite{Layout: declarative.HBox{}, Children: []declarative.Widget{
				declarative.PushButton{Text: "Download selected…", OnClicked: func() {
					a.downloadEntry(dlg, p, shares, combo.CurrentIndex(), model, tv.CurrentIndex())
				}},
				declarative.PushButton{Text: "Download whole share…", OnClicked: func() {
					a.downloadEntry(dlg, p, shares, combo.CurrentIndex(), model, -1)
				}},
				declarative.PushButton{Text: "Close", OnClicked: func() { dlg.Accept() }},
			}},
		},
	}.Create(a.mw)
	load()
	dlg.Run()
}

func parentPath(p string) string {
	if p == "" {
		return ""
	}
	if i := strings.LastIndex(p, "/"); i >= 0 {
		return p[:i]
	}
	return ""
}

func (a *nativeApp) downloadEntry(owner walk.Form, p apiPeer, shares []apiRemoteShare, shareIdx int, model *remoteModel, row int) {
	if shareIdx < 0 || shareIdx >= len(shares) {
		return
	}
	dest := ""
	if fd := (walk.FileDialog{Title: "Download into folder"}); true {
		if ok, err := fd.ShowBrowseFolder(owner); err == nil && ok {
			dest = fd.FilePath
		}
	}
	if dest == "" {
		if st, err := a.client.Settings(); err == nil && st.DefaultDownloadFolder != "" {
			dest = st.DefaultDownloadFolder
		}
	}
	if dest == "" {
		walk.MsgBox(owner, "LANyard", "Choose a destination folder.", walk.MsgBoxIconInformation)
		return
	}
	paths := []string{""} // whole share
	if row >= 0 && row < len(model.entries) {
		paths = []string{model.entries[row].Path}
	}
	if err := a.client.CreateTransfer(p.DeviceID, shares[shareIdx].ShareID, shares[shareIdx].Label, displayName(p), dest, paths); err != nil {
		walk.MsgBox(owner, "LANyard", err.Error(), walk.MsgBoxIconError)
		return
	}
	a.refresh()
}

func parseIntOr(s string, def int) (int, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return def, nil
	}
	n := 0
	_, err := fmt.Sscanf(s, "%d", &n)
	if err != nil {
		return def, err
	}
	return n, nil
}
