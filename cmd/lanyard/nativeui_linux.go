//go:build linux && cgo

package main

/*
#cgo pkg-config: gtk+-3.0 webkit2gtk-4.1
#include <stdlib.h>
#include <gtk/gtk.h>
#include <webkit2/webkit2.h>

static GtkWidget *lan_win = NULL;
static GtkWidget *lan_view = NULL;
static int lan_hide_on_close = 0;

static gboolean lan_on_delete(GtkWidget *w, GdkEvent *e, gpointer d) {
	if (lan_hide_on_close) { gtk_widget_hide(w); return TRUE; }
	return FALSE;
}

static gboolean lan_on_new_window(WebKitWebView *v, WebKitNavigationAction *a, gpointer d) {
	// Links that would open a new window stay inside the app's one window.
	return TRUE;
}

// lan_open builds the window. Returns 0 on success, 1 when GTK has no display.
static int lan_open(const char *title, const char *url, const char *datadir,
                    int w, int h, const unsigned char *icon, size_t iconlen) {
	g_set_prgname("lanyard");
	g_set_application_name("LANyard File Transfer");
	if (!gtk_init_check(NULL, NULL)) return 1;
	WebKitWebsiteDataManager *dm = webkit_website_data_manager_new(
		"base-data-directory", datadir, "base-cache-directory", datadir, NULL);
	WebKitWebContext *ctx = webkit_web_context_new_with_website_data_manager(dm);
	lan_view = webkit_web_view_new_with_context(ctx);
	WebKitSettings *s = webkit_web_view_get_settings(WEBKIT_WEB_VIEW(lan_view));
	webkit_settings_set_enable_developer_extras(s, FALSE);
	webkit_settings_set_enable_write_console_messages_to_stdout(s, FALSE);
	g_signal_connect(lan_view, "create", G_CALLBACK(lan_on_new_window), NULL);

	lan_win = gtk_window_new(GTK_WINDOW_TOPLEVEL);
	gtk_window_set_title(GTK_WINDOW(lan_win), title);
	gtk_window_set_default_size(GTK_WINDOW(lan_win), w, h);
	gtk_window_set_position(GTK_WINDOW(lan_win), GTK_WIN_POS_CENTER);
	if (icon != NULL && iconlen > 0) {
		GdkPixbufLoader *l = gdk_pixbuf_loader_new();
		if (gdk_pixbuf_loader_write(l, icon, iconlen, NULL) && gdk_pixbuf_loader_close(l, NULL)) {
			GdkPixbuf *pb = gdk_pixbuf_loader_get_pixbuf(l);
			if (pb) gtk_window_set_icon(GTK_WINDOW(lan_win), pb);
		}
		g_object_unref(l);
	}
	gtk_container_add(GTK_CONTAINER(lan_win), lan_view);
	g_signal_connect(lan_win, "delete-event", G_CALLBACK(lan_on_delete), NULL);
	g_signal_connect(lan_win, "destroy", G_CALLBACK(gtk_main_quit), NULL);
	webkit_web_view_load_uri(WEBKIT_WEB_VIEW(lan_view), url);
	gtk_widget_show_all(lan_win);
	return 0;
}

static void lan_run(void) { gtk_main(); }

static gboolean lan_idle_show(gpointer d) {
	if (lan_win) { gtk_widget_show_all(lan_win); gtk_window_deiconify(GTK_WINDOW(lan_win)); gtk_window_present(GTK_WINDOW(lan_win)); }
	return G_SOURCE_REMOVE;
}
static gboolean lan_idle_quit(gpointer d) { gtk_main_quit(); return G_SOURCE_REMOVE; }
static void lan_show(void) { g_idle_add(lan_idle_show, NULL); }
static void lan_quit(void) { g_idle_add(lan_idle_quit, NULL); }
static void lan_set_hide_on_close(int v) { lan_hide_on_close = v; }
*/
import "C"

import (
	_ "embed"
	"errors"
	"os"
	"os/signal"
	"runtime"
	"syscall"
	"unsafe"
)

//go:embed icon.ico
var windowIcon []byte

// nativeProfileDir holds WebKit's cookies and storage inside the data dir.
const nativeProfileDir = "webkit"

// nativeSupported: a native window needs a display; with none (ssh, CI) the
// app falls back to headless/browser mode.
func nativeSupported() bool {
	return os.Getenv("DISPLAY") != "" || os.Getenv("WAYLAND_DISPLAY") != ""
}

var nativeOpen bool

// showNativeWindow raises (and un-hides) the window; false without one.
func showNativeWindow() bool {
	if !nativeOpen {
		return false
	}
	C.lan_show()
	return true
}

// focusNativeWindow asks an already-running instance to raise its window
// (SIGUSR2, handled in runNativeUI). Windows matches by HWND; X11/Wayland have
// no portable cross-process equivalent.
func focusNativeWindow(pid int) bool {
	if pid <= 0 {
		return false
	}
	return syscall.Kill(pid, syscall.SIGUSR2) == nil
}

// runNativeUI shows the UI in an embedded WebKitGTK window and blocks until it
// closes. GTK is single-threaded, so the whole thing stays on this OS thread.
func runNativeUI(opts nativeUIOptions) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if err := os.MkdirAll(opts.DataPath, 0o700); err != nil {
		return err
	}
	cTitle, cURL, cDir := C.CString(opts.Title), C.CString(opts.URL), C.CString(opts.DataPath)
	defer C.free(unsafe.Pointer(cTitle))
	defer C.free(unsafe.Pointer(cURL))
	defer C.free(unsafe.Pointer(cDir))
	if C.lan_open(cTitle, cURL, cDir, 1280, 820,
		(*C.uchar)(unsafe.Pointer(&windowIcon[0])), C.size_t(len(windowIcon))) != 0 {
		return errors.New("no display available for the native window")
	}
	nativeOpen = true
	C.lan_set_hide_on_close(boolToInt(minimizeToTray.Load() && currentTrayActive()))

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGUSR2)
	stop := make(chan struct{})
	defer close(stop)
	defer signal.Stop(sig)
	go func() {
		for {
			select {
			case <-sig:
				C.lan_show()
			case <-opts.Done:
				C.lan_quit()
				return
			case <-stop:
				return
			}
		}
	}()
	if opts.Log != nil {
		opts.Log.Info("native window open", "url", opts.URL)
	}
	C.lan_run()
	nativeOpen = false
	if opts.OnQuit != nil {
		opts.OnQuit()
	}
	return nil
}

func boolToInt(b bool) C.int {
	if b {
		return 1
	}
	return 0
}

// currentTrayActive is false until the Linux tray exists.
func currentTrayActive() bool { return false }
