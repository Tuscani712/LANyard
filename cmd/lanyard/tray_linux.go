//go:build linux

package main

import (
	"bytes"
	_ "embed"
	"encoding/binary"
	"fmt"
	"image"
	"image/png"
	"os"
	"sync/atomic"

	"github.com/godbus/dbus/v5"
	"github.com/godbus/dbus/v5/introspect"
	"github.com/godbus/dbus/v5/prop"
)

// trayICO is the same multi-size .ico the Windows tray uses; its frames are PNG.
//
//go:embed icon.ico
var trayICO []byte

// trayActive is true while the icon is registered with a tray host, so the
// window only hides to the tray when there is somewhere to restore it from.
var trayActive atomic.Bool

const (
	sniPath  = dbus.ObjectPath("/StatusNotifierItem")
	menuPath = dbus.ObjectPath("/MenuBar")
	sniIface = "org.kde.StatusNotifierItem"
	menuIfc  = "com.canonical.dbusmenu"
)

type sni struct{ opts trayOptions }

// Activate is a left click on the icon.
func (s *sni) Activate(x, y int32) *dbus.Error {
	if s.opts.OnOpen != nil {
		go s.opts.OnOpen()
	}
	return nil
}
func (s *sni) SecondaryActivate(x, y int32) *dbus.Error { return s.Activate(x, y) }
func (s *sni) ContextMenu(x, y int32) *dbus.Error       { return nil } // host draws the dbusmenu
func (s *sni) Scroll(delta int32, orientation string) *dbus.Error {
	return nil
}

// menuLayout is dbusmenu's (ia{sv}av): id, properties, child layouts.
type menuLayout struct {
	ID       int32
	Props    map[string]dbus.Variant
	Children []dbus.Variant
}

type menu struct{ opts trayOptions }

func (m *menu) item(id int32) menuLayout {
	label := map[int32]string{1: "Open LANyard", 2: "Quit"}[id]
	return menuLayout{ID: id, Props: map[string]dbus.Variant{
		"label": dbus.MakeVariant(label), "enabled": dbus.MakeVariant(true), "visible": dbus.MakeVariant(true),
	}}
}

func (m *menu) GetLayout(parent, depth int32, names []string) (uint32, menuLayout, *dbus.Error) {
	root := menuLayout{ID: 0, Props: map[string]dbus.Variant{"children-display": dbus.MakeVariant("submenu")}}
	if parent == 0 {
		root.Children = []dbus.Variant{dbus.MakeVariant(m.item(1)), dbus.MakeVariant(m.item(2))}
		return 1, root, nil
	}
	return 1, m.item(parent), nil
}

type groupProps struct {
	ID    int32
	Props map[string]dbus.Variant
}

func (m *menu) GetGroupProperties(ids []int32, names []string) ([]groupProps, *dbus.Error) {
	var out []groupProps
	for _, id := range ids {
		it := m.item(id)
		out = append(out, groupProps{id, it.Props})
	}
	return out, nil
}
func (m *menu) GetProperty(id int32, name string) (dbus.Variant, *dbus.Error) {
	return m.item(id).Props[name], nil
}
func (m *menu) Event(id int32, event string, data dbus.Variant, ts uint32) *dbus.Error {
	if event != "clicked" {
		return nil
	}
	switch id {
	case 1:
		if m.opts.OnOpen != nil {
			go m.opts.OnOpen()
		}
	case 2:
		if m.opts.OnQuit != nil {
			go m.opts.OnQuit()
		}
	}
	return nil
}
func (m *menu) AboutToShow(id int32) (bool, *dbus.Error) { return false, nil }

// iconPixmap decodes the ~48px frame of the embedded .ico into the ARGB32
// (network byte order) pixmap StatusNotifierItem expects.
func iconPixmap() []struct {
	W, H int32
	Pix  []byte
} {
	type px = struct {
		W, H int32
		Pix  []byte
	}
	if len(trayICO) < 6 {
		return nil
	}
	n := int(binary.LittleEndian.Uint16(trayICO[4:6]))
	for i := 0; i < n && 6+16*i+16 <= len(trayICO); i++ {
		e := trayICO[6+16*i:]
		if e[0] != 48 {
			continue
		}
		size, off := int(binary.LittleEndian.Uint32(e[8:12])), int(binary.LittleEndian.Uint32(e[12:16]))
		if off+size > len(trayICO) {
			break
		}
		img, err := png.Decode(bytes.NewReader(trayICO[off : off+size]))
		if err != nil {
			break
		}
		b := img.Bounds()
		pix := make([]byte, 0, b.Dx()*b.Dy()*4)
		for y := b.Min.Y; y < b.Max.Y; y++ {
			for x := b.Min.X; x < b.Max.X; x++ {
				r, g, bl, a := img.At(x, y).RGBA()
				if _, ok := img.(*image.NRGBA); ok || true {
					// RGBA() is alpha-premultiplied; SNI wants straight alpha.
					if a != 0 {
						r, g, bl = r*0xffff/a, g*0xffff/a, bl*0xffff/a
					}
				}
				pix = append(pix, byte(a>>8), byte(r>>8), byte(g>>8), byte(bl>>8))
			}
		}
		return []px{{int32(b.Dx()), int32(b.Dy()), pix}}
	}
	return nil
}

// startTray registers a StatusNotifierItem (KDE, GNOME with the AppIndicator
// extension, XFCE, ...) over the session bus. With no bus or no tray host the
// app simply runs without an icon, and the window never hides to the tray.
func startTray(opts trayOptions) func() {
	noop := func() {}
	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		if opts.Log != nil {
			opts.Log.Info("no session bus; running without a tray icon", "err", err)
		}
		return noop
	}
	name := fmt.Sprintf("org.kde.StatusNotifierItem-%d-1", os.Getpid())
	if r, err := conn.RequestName(name, dbus.NameFlagDoNotQueue); err != nil || r != dbus.RequestNameReplyPrimaryOwner {
		conn.Close()
		return noop
	}
	item := &sni{opts}
	_ = conn.Export(item, sniPath, sniIface)
	_ = conn.Export(&menu{opts}, menuPath, menuIfc)
	_, err = prop.Export(conn, sniPath, prop.Map{sniIface: {
		"Category":   {Value: "ApplicationStatus"},
		"Id":         {Value: "lanyard"},
		"Title":      {Value: "LANyard File Transfer"},
		"Status":     {Value: "Active"},
		"WindowId":   {Value: uint32(0)},
		"IconName":   {Value: ""},
		"IconPixmap": {Value: iconPixmap()},
		"ToolTip": {Value: struct {
			Icon string
			Pix  []struct {
				W, H int32
				Pix  []byte
			}
			Title string
			Body  string
		}{"", nil, "LANyard File Transfer", ""}},
		"ItemIsMenu": {Value: false},
		"Menu":       {Value: menuPath},
	}})
	if err == nil {
		_, err = prop.Export(conn, menuPath, prop.Map{menuIfc: {
			"Version":       {Value: uint32(3)},
			"TextDirection": {Value: "ltr"},
			"Status":        {Value: "normal"},
			"IconThemePath": {Value: []string{}},
		}})
	}
	if err != nil {
		conn.Close()
		return noop
	}
	_ = conn.Export(introspect.NewIntrospectable(&introspect.Node{Name: string(sniPath)}), sniPath, "org.freedesktop.DBus.Introspectable")

	w := conn.Object("org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher")
	if call := w.Call("org.kde.StatusNotifierWatcher.RegisterStatusNotifierItem", 0, name); call.Err != nil {
		if opts.Log != nil {
			opts.Log.Info("no system tray host; running without a tray icon", "err", call.Err)
		}
		conn.Close()
		return noop
	}
	trayActive.Store(true)
	if opts.Log != nil {
		opts.Log.Info("tray icon added")
	}
	return func() {
		if trayActive.Swap(false) {
			conn.Close()
		}
	}
}
