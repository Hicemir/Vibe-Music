//go:build linux

package main

/*
#cgo pkg-config: gtk+-3.0
#include <gtk/gtk.h>
#include <gdk/gdk.h>
#include <gdk/gdkwayland.h>
#include <stdlib.h>
#include <math.h>

static GtkWindow *vm_win = NULL;
static int vm_saved_w = 1440;
static int vm_saved_h = 880;
static int vm_pending_w = 1440;
static int vm_pending_h = 880;
static int vm_saved_max = 1;
static int vm_mini_mode = 0;
static int vm_min_w = 900;
static int vm_min_h = 600;
static guint vm_save_timer = 0;

static int vm_drag_armed = 0;
static gdouble vm_drag_start_x = 0;
static gdouble vm_drag_start_y = 0;
static int vm_edge_cursor_active = 0;
static int vm_signals_connected = 0;

extern void goOnWindowGeometryChanged(int w, int h, int max);
extern void goOnMouseNav(int dir);

static GtkWindow* vm_find_window(void) {
	if (vm_win != NULL && GTK_IS_WINDOW(vm_win)) {
		return vm_win;
	}
	GList *list = gtk_window_list_toplevels();
	for (GList *l = list; l != NULL; l = l->next) {
		if (GTK_IS_WINDOW(l->data) && gtk_window_get_window_type(GTK_WINDOW(l->data)) == GTK_WINDOW_TOPLEVEL) {
			vm_win = GTK_WINDOW(l->data);
			break;
		}
	}
	g_list_free(list);
	return vm_win;
}

// Tell KWin / Wayland compositor to use Client-Side Decorations (MODE_CLIENT)
// so KWin never draws a second Server-Side titlebar on top of our frameless window.
static void vm_enforce_csd(GtkWindow *win) {
	if (!win) return;
	gtk_window_set_decorated(win, FALSE);
	GdkWindow *gw = gtk_widget_get_window(GTK_WIDGET(win));
	if (gw && GDK_IS_WAYLAND_WINDOW(gw)) {
		gdk_wayland_window_announce_csd(gw);
	}
}

static gboolean vm_on_map_event(GtkWidget *widget, GdkEventAny *event, gpointer data) {
	vm_enforce_csd(GTK_WINDOW(widget));
	return FALSE;
}

static void vm_on_realize(GtkWidget *widget, gpointer data) {
	vm_enforce_csd(GTK_WINDOW(widget));
}

static int vm_hit_resize_edge(GtkWindow *win, gdouble x, gdouble y, GdkWindowEdge *out_edge) {
	if (!win || vm_saved_max || vm_mini_mode) return 0;
	int w = gtk_widget_get_allocated_width(GTK_WIDGET(win));
	int h = gtk_widget_get_allocated_height(GTK_WIDGET(win));
	const int b = 5;
	int left = x < b;
	int right = x >= (w - b);
	int top = y < b;
	int bottom = y >= (h - b);
	if (top && left) { *out_edge = GDK_WINDOW_EDGE_NORTH_WEST; return 1; }
	if (top && right) { *out_edge = GDK_WINDOW_EDGE_NORTH_EAST; return 1; }
	if (bottom && left) { *out_edge = GDK_WINDOW_EDGE_SOUTH_WEST; return 1; }
	if (bottom && right) { *out_edge = GDK_WINDOW_EDGE_SOUTH_EAST; return 1; }
	if (top) { *out_edge = GDK_WINDOW_EDGE_NORTH; return 1; }
	if (bottom) { *out_edge = GDK_WINDOW_EDGE_SOUTH; return 1; }
	if (left) { *out_edge = GDK_WINDOW_EDGE_WEST; return 1; }
	if (right) { *out_edge = GDK_WINDOW_EDGE_EAST; return 1; }
	return 0;
}

static gboolean vm_on_button_press(GtkWidget *widget, GdkEventButton *event, gpointer data) {
	if (!event) return FALSE;

	if (event->type == GDK_BUTTON_PRESS) {
		if (event->button == 8) {
			goOnMouseNav(-1);
			return TRUE;
		}
		if (event->button == 9) {
			goOnMouseNav(1);
			return TRUE;
		}
	}

	GtkWindow *win = vm_find_window();
	if (!win) return FALSE;

	// Edge resize when floating
	GdkWindowEdge edge;
	if (event->button == 1 && event->type == GDK_BUTTON_PRESS && vm_hit_resize_edge(win, event->x, event->y, &edge)) {
		vm_drag_armed = 0;
		gtk_window_begin_resize_drag(win, edge, 1, (gint)event->x_root, (gint)event->y_root, event->time);
		return TRUE;
	}

	if (event->button == 1 && event->type == GDK_BUTTON_PRESS) {
		vm_drag_start_x = event->x_root;
		vm_drag_start_y = event->y_root;
		// Top 10px window margin is always draggable; inside .topbar JS calls ArmWindowDrag()
		vm_drag_armed = (event->y >= 0 && event->y <= 10) ? 1 : 0;
	}

	return FALSE;
}

static gboolean vm_on_button_release(GtkWidget *widget, GdkEventButton *event, gpointer data) {
	vm_drag_armed = 0;
	return FALSE;
}

static gboolean vm_on_motion(GtkWidget *widget, GdkEventMotion *event, gpointer data) {
	if (!event) return FALSE;
	GtkWindow *win = vm_find_window();
	if (!win) return FALSE;

	if (vm_drag_armed) {
		if (!(event->state & GDK_BUTTON1_MASK)) {
			vm_drag_armed = 0;
		} else if (fabs(event->x_root - vm_drag_start_x) >= 4.0 || fabs(event->y_root - vm_drag_start_y) >= 4.0) {
			vm_drag_armed = 0;
			gtk_window_begin_move_drag(win, 1, (gint)event->x_root, (gint)event->y_root, event->time);
			return TRUE;
		}
	}

	// Update resize cursor near window edges when floating
	GdkWindow *gw = gtk_widget_get_window(widget);
	if (gw && !(event->state & GDK_BUTTON1_MASK)) {
		GdkWindowEdge edge;
		if (vm_hit_resize_edge(win, event->x, event->y, &edge)) {
			const char *cname = "default";
			switch (edge) {
			case GDK_WINDOW_EDGE_NORTH_WEST: cname = "nw-resize"; break;
			case GDK_WINDOW_EDGE_NORTH:      cname = "n-resize"; break;
			case GDK_WINDOW_EDGE_NORTH_EAST: cname = "ne-resize"; break;
			case GDK_WINDOW_EDGE_WEST:       cname = "w-resize"; break;
			case GDK_WINDOW_EDGE_EAST:       cname = "e-resize"; break;
			case GDK_WINDOW_EDGE_SOUTH_WEST: cname = "sw-resize"; break;
			case GDK_WINDOW_EDGE_SOUTH:      cname = "s-resize"; break;
			case GDK_WINDOW_EDGE_SOUTH_EAST: cname = "se-resize"; break;
			}
			GdkDisplay *disp = gdk_window_get_display(gw);
			GdkCursor *cur = gdk_cursor_new_from_name(disp, cname);
			gdk_window_set_cursor(gw, cur);
			if (cur) g_object_unref(cur);
			vm_edge_cursor_active = 1;
		} else if (vm_edge_cursor_active) {
			gdk_window_set_cursor(gw, NULL);
			vm_edge_cursor_active = 0;
		}
	}
	return FALSE;
}

static void vm_connect_buttons_recursive(GtkWidget *w) {
	if (!w) return;
	gtk_widget_add_events(w, GDK_BUTTON_PRESS_MASK | GDK_BUTTON_RELEASE_MASK | GDK_POINTER_MOTION_MASK);
	g_signal_connect(G_OBJECT(w), "button-press-event", G_CALLBACK(vm_on_button_press), NULL);
	g_signal_connect(G_OBJECT(w), "button-release-event", G_CALLBACK(vm_on_button_release), NULL);
	g_signal_connect(G_OBJECT(w), "motion-notify-event", G_CALLBACK(vm_on_motion), NULL);
	if (GTK_IS_CONTAINER(w)) {
		GList *children = gtk_container_get_children(GTK_CONTAINER(w));
		for (GList *l = children; l != NULL; l = l->next) {
			vm_connect_buttons_recursive(GTK_WIDGET(l->data));
		}
		g_list_free(children);
	}
}

static void vm_apply_min_size_only(GtkWindow *win, int minW, int minH) {
	if (!win) return;
	GdkGeometry geom;
	memset(&geom, 0, sizeof(geom));
	geom.min_width = minW;
	geom.min_height = minH;
	// Pass ONLY GDK_HINT_MIN_SIZE so Wails's buggy GDK_HINT_MAX_SIZE on Wayland is cleared.
	gtk_window_set_geometry_hints(win, NULL, &geom, GDK_HINT_MIN_SIZE);
}

static int vm_is_normal_state(GtkWindow *win) {
	if (!win || vm_mini_mode) return 0;
	GdkWindow *gdk_win = gtk_widget_get_window(GTK_WIDGET(win));
	if (!gdk_win) return 0;
	GdkWindowState st = gdk_window_get_state(gdk_win);
	int non_normal = GDK_WINDOW_STATE_MAXIMIZED |
		GDK_WINDOW_STATE_FULLSCREEN |
		GDK_WINDOW_STATE_ICONIFIED |
		GDK_WINDOW_STATE_TILED |
		GDK_WINDOW_STATE_TOP_TILED |
		GDK_WINDOW_STATE_BOTTOM_TILED |
		GDK_WINDOW_STATE_LEFT_TILED |
		GDK_WINDOW_STATE_RIGHT_TILED;
	return (st & non_normal) == 0;
}

static gboolean vm_save_debounced(gpointer data) {
	vm_save_timer = 0;
	GtkWindow *win = vm_find_window();
	if (!win || !vm_is_normal_state(win)) {
		return G_SOURCE_REMOVE;
	}
	int w = vm_pending_w;
	int h = vm_pending_h;
	if (w >= vm_min_w && h >= vm_min_h) {
		GdkDisplay *disp = gtk_widget_get_display(GTK_WIDGET(win));
		GdkWindow *gw = gtk_widget_get_window(GTK_WIDGET(win));
		if (disp && gw) {
			GdkMonitor *mon = gdk_display_get_monitor_at_window(disp, gw);
			if (mon) {
				GdkRectangle rect;
				gdk_monitor_get_geometry(mon, &rect);
				if (rect.width > 0 && rect.height > 0 && w >= rect.width - 24 && h >= rect.height - 64) {
					return G_SOURCE_REMOVE;
				}
			}
		}
		vm_saved_w = w;
		vm_saved_h = h;
		gtk_window_set_default_size(win, vm_saved_w, vm_saved_h);
		goOnWindowGeometryChanged(vm_saved_w, vm_saved_h, 0);
	}
	return G_SOURCE_REMOVE;
}

static gboolean vm_restore_after_unmax(gpointer data) {
	GtkWindow *win = vm_find_window();
	if (!win || !vm_is_normal_state(win)) {
		return G_SOURCE_REMOVE;
	}
	vm_enforce_csd(win);
	vm_apply_min_size_only(win, vm_min_w, vm_min_h);
	gtk_window_set_default_size(win, vm_saved_w, vm_saved_h);
	gtk_window_resize(win, vm_saved_w, vm_saved_h);
	return G_SOURCE_REMOVE;
}

static gboolean vm_on_window_state(GtkWidget *widget, GdkEventWindowState *event, gpointer data) {
	if (vm_mini_mode) return FALSE;
	int max_now = (event->new_window_state & GDK_WINDOW_STATE_MAXIMIZED) != 0;
	int full_now = (event->new_window_state & GDK_WINDOW_STATE_FULLSCREEN) != 0;
	int min_now = (event->new_window_state & GDK_WINDOW_STATE_ICONIFIED) != 0;

	if (event->changed_mask & GDK_WINDOW_STATE_MAXIMIZED) {
		if (vm_save_timer != 0) {
			g_source_remove(vm_save_timer);
			vm_save_timer = 0;
		}
		vm_saved_max = max_now;
		goOnWindowGeometryChanged(vm_saved_w, vm_saved_h, vm_saved_max);
		if (!max_now && !full_now && !min_now) {
			g_idle_add(vm_restore_after_unmax, NULL);
		}
	}
	return FALSE;
}

static gboolean vm_on_configure(GtkWidget *widget, GdkEventConfigure *event, gpointer data) {
	if (vm_mini_mode) return FALSE;
	GtkWindow *win = GTK_WINDOW(widget);
	if (!vm_is_normal_state(win)) {
		return FALSE;
	}
	if (event->width >= vm_min_w && event->height >= vm_min_h) {
		vm_pending_w = event->width;
		vm_pending_h = event->height;
		if (vm_save_timer != 0) {
			g_source_remove(vm_save_timer);
		}
		vm_save_timer = g_timeout_add(250, vm_save_debounced, NULL);
	}
	return FALSE;
}

static gboolean vm_init_idle(gpointer data) {
	GtkWindow *win = vm_find_window();
	if (!win) return G_SOURCE_REMOVE;

	gtk_window_set_default_icon_name("vibe-music");
	gtk_window_set_icon_name(win, "vibe-music");
	gtk_window_set_resizable(win, TRUE);
	vm_enforce_csd(win);
	vm_apply_min_size_only(win, vm_min_w, vm_min_h);
	gtk_window_set_default_size(win, vm_saved_w, vm_saved_h);

	if (!vm_signals_connected) {
		vm_signals_connected = 1;
		g_signal_connect(G_OBJECT(win), "realize", G_CALLBACK(vm_on_realize), NULL);
		g_signal_connect(G_OBJECT(win), "map-event", G_CALLBACK(vm_on_map_event), NULL);
		g_signal_connect(G_OBJECT(win), "window-state-event", G_CALLBACK(vm_on_window_state), NULL);
		g_signal_connect(G_OBJECT(win), "configure-event", G_CALLBACK(vm_on_configure), NULL);
		vm_connect_buttons_recursive(GTK_WIDGET(win));
	}

	if (vm_saved_max) {
		gtk_window_maximize(win);
	} else {
		gtk_window_unmaximize(win);
		gtk_window_resize(win, vm_saved_w, vm_saved_h);
	}
	return G_SOURCE_REMOVE;
}

static void vm_init_window(int w, int h, int max) {
	g_set_prgname("vibe-music");
	g_set_application_name("Vibe Music");
	if (w >= 900) vm_saved_w = w;
	if (h >= 600) vm_saved_h = h;
	vm_saved_max = max;
	g_idle_add(vm_init_idle, NULL);
	g_timeout_add(50, vm_init_idle, NULL);
}

static gboolean vm_show_idle(gpointer data) {
	GtkWindow *win = vm_find_window();
	if (!win) return G_SOURCE_REMOVE;
	vm_enforce_csd(win);
	vm_apply_min_size_only(win, vm_mini_mode ? 340 : vm_min_w, vm_mini_mode ? 150 : vm_min_h);
	if (!vm_mini_mode) {
		gtk_window_set_default_size(win, vm_saved_w, vm_saved_h);
	}
	gtk_widget_show(GTK_WIDGET(win));
	vm_enforce_csd(win);
	gtk_window_present(win);
	if (!vm_mini_mode) {
		if (vm_saved_max) {
			gtk_window_maximize(win);
		} else {
			gtk_window_unmaximize(win);
			gtk_window_resize(win, vm_saved_w, vm_saved_h);
		}
	}
	return G_SOURCE_REMOVE;
}

static void vm_show_window(void) {
	g_idle_add(vm_show_idle, NULL);
}

static gboolean vm_minimise_idle(gpointer data) {
	GtkWindow *win = vm_find_window();
	if (win) {
		gtk_window_iconify(win);
	}
	return G_SOURCE_REMOVE;
}

static void vm_minimise_window(void) {
	g_idle_add(vm_minimise_idle, NULL);
}

static gboolean vm_toggle_max_idle(gpointer data) {
	GtkWindow *win = vm_find_window();
	if (win && !vm_mini_mode) {
		if (vm_saved_max) {
			gtk_window_unmaximize(win);
		} else {
			gtk_window_maximize(win);
		}
	}
	return G_SOURCE_REMOVE;
}

static void vm_toggle_max_window(void) {
	g_idle_add(vm_toggle_max_idle, NULL);
}

static void vm_arm_window_drag(void) {
	vm_drag_armed = 1;
}

static gboolean vm_mini_idle(gpointer data) {
	int on = GPOINTER_TO_INT(data);
	GtkWindow *win = vm_find_window();
	if (!win) return G_SOURCE_REMOVE;
	vm_mini_mode = on;
	vm_enforce_csd(win);
	if (on) {
		gtk_window_unmaximize(win);
		vm_apply_min_size_only(win, 340, 150);
		gtk_window_set_keep_above(win, TRUE);
		gtk_window_resize(win, 400, 190);
	} else {
		gtk_window_set_keep_above(win, FALSE);
		vm_apply_min_size_only(win, vm_min_w, vm_min_h);
		gtk_window_set_default_size(win, vm_saved_w, vm_saved_h);
		if (vm_saved_max) {
			gtk_window_maximize(win);
		} else {
			gtk_window_resize(win, vm_saved_w, vm_saved_h);
		}
	}
	return G_SOURCE_REMOVE;
}

static void vm_set_mini(int on) {
	g_idle_add(vm_mini_idle, GINT_TO_POINTER(on));
}
*/
import "C"

import (
	_ "embed"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

//go:embed build/appicon.png
var appIcon []byte

var winApp *App

//export goOnWindowGeometryChanged
func goOnWindowGeometryChanged(w, h, max C.int) {
	if winApp == nil {
		return
	}
	ww, hh := int(w), int(h)
	m := max != 0
	winApp.updateSettings(func(s *Settings) {
		if ww >= 900 && hh >= 600 {
			s.WindowWidth = ww
			s.WindowHeight = hh
		}
		s.WindowMaximised = m
	})
	winApp.emit("window:maximised", m)
}

//export goOnMouseNav
func goOnMouseNav(dir C.int) {
	if winApp == nil {
		return
	}
	if dir < 0 {
		winApp.emit("nav:back")
	} else if dir > 0 {
		winApp.emit("nav:forward")
	}
}

func (a *App) initNativeWindow() {
	winApp = a
	ensureDesktopIntegration()
	s := a.Settings()
	max := 0
	if s.WindowMaximised {
		max = 1
	}
	C.vm_init_window(C.int(s.WindowWidth), C.int(s.WindowHeight), C.int(max))
}

func nativeShowWindow() {
	C.vm_show_window()
}

func nativeMinimiseWindow() {
	C.vm_minimise_window()
}

func nativeToggleMaximiseWindow() {
	C.vm_toggle_max_window()
}

func nativeArmWindowDrag() {
	C.vm_arm_window_drag()
}

func nativeSetMini(on bool) {
	v := 0
	if on {
		v = 1
	}
	C.vm_set_mini(C.int(v))
}

func setCmdHidden(_ *exec.Cmd) {}

// ensureDesktopIntegration installs the icon and .desktop entry into ~/.local/share
// so Wayland compositors (KWin/Mutter) show our icon in the titlebar & taskbar and KRunner indexes "Vibe Music".
func ensureDesktopIntegration() {
	home, err := os.UserHomeDir()
	if err != nil {
		return
	}
	dataHome := os.Getenv("XDG_DATA_HOME")
	if dataHome == "" {
		dataHome = filepath.Join(home, ".local", "share")
	}

	// Write icons
	icon512 := filepath.Join(dataHome, "icons", "hicolor", "512x512", "apps", "vibe-music.png")
	_ = os.MkdirAll(filepath.Dir(icon512), 0o755)
	_ = os.WriteFile(icon512, appIcon, 0o644)

	icon64 := filepath.Join(dataHome, "icons", "hicolor", "64x64", "apps", "vibe-music.png")
	_ = os.MkdirAll(filepath.Dir(icon64), 0o755)
	_ = os.WriteFile(icon64, trayIcon, 0o644)

	pixmap := filepath.Join(dataHome, "pixmaps", "vibe-music.png")
	_ = os.MkdirAll(filepath.Dir(pixmap), 0o755)
	_ = os.WriteFile(pixmap, appIcon, 0o644)

	exe, err := os.Executable()
	if err != nil || exe == "" {
		exe = "/home/hicemir/Projects/vibeMusic/build/bin/vibeMusic"
	} else if real, err := filepath.EvalSymlinks(exe); err == nil {
		exe = real
	}

	desktopContent := fmt.Sprintf(`[Desktop Entry]
Type=Application
Version=1.0
Name=Vibe Music
GenericName=Music Player
Comment=Local music player with yt-dlp downloader
Exec=%q
Icon=%s
Terminal=false
Categories=AudioVideo;Audio;Player;Music;
Keywords=music;player;audio;mpv;yt-dlp;vibe;vibemusic;
StartupWMClass=vibe-music
StartupNotify=true
`, exe, icon512)

	appDir := filepath.Join(dataHome, "applications")
	_ = os.MkdirAll(appDir, 0o755)
	_ = os.WriteFile(filepath.Join(appDir, "vibe-music.desktop"), []byte(desktopContent), 0o644)
	_ = os.Remove(filepath.Join(appDir, "vibe-player.desktop"))
	_ = os.Remove(filepath.Join(appDir, "vibemusic.desktop"))

	go func() {
		_ = exec.Command("update-desktop-database", appDir).Run()
		_ = exec.Command("kbuildsycoca6", "--noincremental").Run()
	}()
}
