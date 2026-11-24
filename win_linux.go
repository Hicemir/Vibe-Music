//go:build linux

package main

/*
#cgo pkg-config: gtk+-3.0 webkit2gtk-4.1 libsoup-3.0
#include <gtk/gtk.h>
#include <gdk/gdk.h>
#include <gdk/gdkwayland.h>
#include <webkit2/webkit2.h>
#include <libsoup/soup.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

static GtkWindow *vm_win = NULL;
static GtkWidget *vm_login_win = NULL;
static WebKitWebView *vm_login_wv = NULL;
static WebKitWebsiteDataManager *vm_ytm_dm = NULL;
static WebKitWebContext *vm_ytm_ctx = NULL;
static int vm_login_handled = 0;
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
static guint32 vm_last_event_time = 0;

extern void goOnWindowGeometryChanged(int w, int h, int max);
extern void goOnMouseNav(int dir);
extern int goOnYtMusicCookies(char *raw, int requireMusicHost);
extern void goOnYtMusicLoginClosed(void);

static GtkWindow* vm_find_window(void) {
	if (vm_win != NULL && GTK_IS_WINDOW(vm_win)) {
		return vm_win;
	}
	GList *list = gtk_window_list_toplevels();
	for (GList *l = list; l != NULL; l = l->next) {
		if (GTK_IS_WINDOW(l->data) && l->data != (gpointer)vm_login_win && gtk_window_get_window_type(GTK_WINDOW(l->data)) == GTK_WINDOW_TOPLEVEL) {
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
	// Reserve the top-right 140x36 window control area so the corner close button always receives clicks
	if (y < 36 && x >= (w - 140)) return 0;
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
	if (event->time) vm_last_event_time = event->time;

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
		int w = gtk_widget_get_allocated_width(GTK_WIDGET(win));
		vm_drag_start_x = event->x_root;
		vm_drag_start_y = event->y_root;
		// Top 10px window margin (except top-right 140px window buttons) is draggable; inside .topbar JS calls ArmWindowDrag()
		vm_drag_armed = (event->y >= 0 && event->y <= 10 && event->x < (w - 140)) ? 1 : 0;
	}

	return FALSE;
}

static gboolean vm_on_button_release(GtkWidget *widget, GdkEventButton *event, gpointer data) {
	if (event && event->time) vm_last_event_time = event->time;
	vm_drag_armed = 0;
	return FALSE;
}

static gboolean vm_on_motion(GtkWidget *widget, GdkEventMotion *event, gpointer data) {
	if (!event) return FALSE;
	if (!vm_drag_armed && (vm_saved_max || vm_mini_mode) && !vm_edge_cursor_active) return FALSE;
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

typedef struct {
	char *url;
	int background;
	guint32 timestamp;
} VmOpenUrlReq;

static gboolean vm_open_url_restore_idle(gpointer data) {
	GtkWindow *win = vm_find_window();
	if (win) {
		gtk_window_present_with_time(win, GDK_CURRENT_TIME);
	}
	return G_SOURCE_REMOVE;
}

static gboolean vm_open_url_idle(gpointer data) {
	VmOpenUrlReq *req = (VmOpenUrlReq *)data;
	if (!req) return G_SOURCE_REMOVE;
	GtkWindow *win = vm_find_window();
	if (req->background) {
		// Launch without a startup notification token and re-present Vibe Music so it stays in front
		g_app_info_launch_default_for_uri(req->url, NULL, NULL);
		g_timeout_add(180, vm_open_url_restore_idle, NULL);
		g_timeout_add(450, vm_open_url_restore_idle, NULL);
	} else if (win) {
		GdkWindow *gw = gtk_widget_get_window(GTK_WIDGET(win));
		GdkDisplay *disp = gw ? gdk_window_get_display(gw) : gdk_display_get_default();
		if (disp && gw) {
			GdkAppLaunchContext *ctx = gdk_display_get_app_launch_context(disp);
			gdk_app_launch_context_set_screen(ctx, gtk_window_get_screen(win));
			gdk_app_launch_context_set_timestamp(ctx, req->timestamp ? req->timestamp : GDK_CURRENT_TIME);
			g_app_info_launch_default_for_uri(req->url, G_APP_LAUNCH_CONTEXT(ctx), NULL);
			g_object_unref(ctx);
		} else {
			gtk_show_uri_on_window(win, req->url, req->timestamp ? req->timestamp : GDK_CURRENT_TIME, NULL);
		}
	} else {
		g_app_info_launch_default_for_uri(req->url, NULL, NULL);
	}
	free(req->url);
	free(req);
	return G_SOURCE_REMOVE;
}

static void vm_open_url(const char *url, int background) {
	if (!url) return;
	VmOpenUrlReq *req = (VmOpenUrlReq *)malloc(sizeof(VmOpenUrlReq));
	if (!req) return;
	req->url = strdup(url);
	req->background = background;
	req->timestamp = vm_last_event_time ? vm_last_event_time : GDK_CURRENT_TIME;
	g_idle_add(vm_open_url_idle, req);
}

typedef struct {
	char *data_dir;
	char *cookie_file;
} VmYtmLoginReq;

static void vm_ensure_ytm_context(const char *data_dir, const char *cookie_file) {
	if (!vm_ytm_dm) {
		vm_ytm_dm = webkit_website_data_manager_new(
			"base-data-directory", data_dir,
			"base-cache-directory", data_dir,
			NULL);
	}
	if (cookie_file && cookie_file[0]) {
		WebKitCookieManager *cm = webkit_website_data_manager_get_cookie_manager(vm_ytm_dm);
		if (cm) {
			webkit_cookie_manager_set_persistent_storage(cm, cookie_file, WEBKIT_COOKIE_PERSISTENT_STORAGE_TEXT);
			webkit_cookie_manager_set_accept_policy(cm, WEBKIT_COOKIE_POLICY_ACCEPT_ALWAYS);
		}
	}
	if (!vm_ytm_ctx) {
		vm_ytm_ctx = webkit_web_context_new_with_website_data_manager(vm_ytm_dm);
	}
}

static void vm_on_cookies_ready(GObject *source, GAsyncResult *res, gpointer user_data) {
	WebKitCookieManager *cm = WEBKIT_COOKIE_MANAGER(source);
	int require_music_host = GPOINTER_TO_INT(user_data);
	GError *err = NULL;
	GList *cookies = webkit_cookie_manager_get_all_cookies_finish(cm, res, &err);
	if (err) {
		g_error_free(err);
	}
	if (!vm_login_win || !GTK_IS_WIDGET(vm_login_win) || vm_login_handled) {
		if (cookies) g_list_free_full(cookies, (GDestroyNotify)soup_cookie_free);
		return;
	}
	GString *buf = g_string_new("");
	for (GList *l = cookies; l != NULL; l = l->next) {
		SoupCookie *c = (SoupCookie *)l->data;
		if (!c) continue;
		const char *dom = soup_cookie_get_domain(c);
		const char *path = soup_cookie_get_path(c);
		const char *name = soup_cookie_get_name(c);
		const char *val = soup_cookie_get_value(c);
		gboolean sec = soup_cookie_get_secure(c);
		if (!dom || !name || !val) continue;
		if (strchr(dom, '\t') || strchr(dom, '\n') || strchr(name, '\t') || strchr(name, '\n') || strchr(val, '\t') || strchr(val, '\n')) {
			continue;
		}
		g_string_append_printf(buf, "%s\t%s\t%d\t%s\t%s\n", dom, path ? path : "/", sec ? 1 : 0, name, val);
	}
	if (cookies) {
		g_list_free_full(cookies, (GDestroyNotify)soup_cookie_free);
	}
	int ok = goOnYtMusicCookies(buf->str, require_music_host);
	g_string_free(buf, TRUE);
	if (ok && vm_login_win && GTK_IS_WIDGET(vm_login_win)) {
		vm_login_handled = 1;
		gtk_widget_destroy(vm_login_win);
	}
}

static void vm_fetch_ytm_cookies(int require_music_host) {
	if (!vm_login_win || !vm_login_wv || vm_login_handled || !vm_ytm_dm) return;
	if (require_music_host) {
		const gchar *uri = webkit_web_view_get_uri(vm_login_wv);
		if (!uri || !strstr(uri, "music.youtube.com")) return;
	}
	WebKitCookieManager *cm = webkit_website_data_manager_get_cookie_manager(vm_ytm_dm);
	if (cm) {
		webkit_cookie_manager_get_all_cookies(cm, NULL, vm_on_cookies_ready, GINT_TO_POINTER(require_music_host));
	}
}

static void vm_on_login_load_changed(WebKitWebView *wv, WebKitLoadEvent event, gpointer data) {
	if (event == WEBKIT_LOAD_COMMITTED || event == WEBKIT_LOAD_FINISHED) {
		vm_fetch_ytm_cookies(1);
	}
}

static void vm_on_login_uri_changed(GObject *obj, GParamSpec *pspec, gpointer data) {
	vm_fetch_ytm_cookies(1);
}

static GtkWidget* vm_on_login_create(WebKitWebView *wv, WebKitNavigationAction *action, gpointer data) {
	WebKitURIRequest *req = webkit_navigation_action_get_request(action);
	if (req) {
		const gchar *uri = webkit_uri_request_get_uri(req);
		if (uri) {
			webkit_web_view_load_uri(wv, uri);
		}
	}
	return NULL;
}

static void vm_on_login_done_clicked(GtkButton *btn, gpointer data) {
	vm_fetch_ytm_cookies(0);
}

static void vm_on_login_cancel_clicked(GtkButton *btn, gpointer data) {
	if (vm_login_win && GTK_IS_WIDGET(vm_login_win)) {
		gtk_widget_destroy(vm_login_win);
	}
}

static void vm_on_login_destroy(GtkWidget *w, gpointer data) {
	vm_login_win = NULL;
	vm_login_wv = NULL;
	int handled = vm_login_handled;
	vm_login_handled = 0;
	if (!handled) {
		goOnYtMusicLoginClosed();
	}
}

static void vm_apply_login_css(GtkWidget *widget) {
	static GtkCssProvider *prov = NULL;
	if (!prov) {
		prov = gtk_css_provider_new();
		gtk_css_provider_load_from_data(prov,
			".vm-login-hdr { background-color: #181822; padding: 10px 14px; border-bottom: 1px solid #2b2b38; }"
			".vm-login-title { color: #f5f5f7; font-weight: 700; font-size: 14px; }"
			".vm-login-done { background: #1ed760; color: #000000; border: none; border-radius: 999px; padding: 6px 16px; font-weight: 700; }"
			".vm-login-done:hover { background: #3be477; }"
			".vm-login-cancel { background: #2a2a36; color: #f5f5f7; border: 1px solid #3c3c4c; border-radius: 999px; padding: 6px 14px; font-weight: 600; }"
			".vm-login-cancel:hover { background: #363646; }",
			-1, NULL);
	}
	GdkScreen *scr = gtk_widget_get_screen(widget);
	if (scr) {
		gtk_style_context_add_provider_for_screen(scr, GTK_STYLE_PROVIDER(prov), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
	}
}

static gboolean vm_open_ytm_login_idle(gpointer data) {
	VmYtmLoginReq *req = (VmYtmLoginReq *)data;
	if (!req) return G_SOURCE_REMOVE;

	vm_ensure_ytm_context(req->data_dir, req->cookie_file);
	free(req->data_dir);
	free(req->cookie_file);
	free(req);

	if (vm_login_win && GTK_IS_WIDGET(vm_login_win)) {
		gtk_window_present(GTK_WINDOW(vm_login_win));
		return G_SOURCE_REMOVE;
	}

	vm_login_handled = 0;
	GtkWidget *win = gtk_window_new(GTK_WINDOW_TOPLEVEL);
	vm_login_win = win;
	gtk_window_set_title(GTK_WINDOW(win), "Sign in to YouTube Music");
	gtk_window_set_default_size(GTK_WINDOW(win), 820, 680);
	gtk_window_set_position(GTK_WINDOW(win), GTK_WIN_POS_CENTER_ON_PARENT);
	GtkWindow *parent = vm_find_window();
	if (parent) {
		gtk_window_set_transient_for(GTK_WINDOW(win), parent);
	}
	vm_apply_login_css(win);

	GtkWidget *vbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
	GtkWidget *hdr = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
	gtk_style_context_add_class(gtk_widget_get_style_context(hdr), "vm-login-hdr");

	GtkWidget *title = gtk_label_new("Sign in to YouTube Music");
	gtk_widget_set_halign(title, GTK_ALIGN_START);
	gtk_style_context_add_class(gtk_widget_get_style_context(title), "vm-login-title");

	GtkWidget *done_btn = gtk_button_new_with_label("Done");
	gtk_style_context_add_class(gtk_widget_get_style_context(done_btn), "vm-login-done");

	GtkWidget *cancel_btn = gtk_button_new_with_label("Cancel");
	gtk_style_context_add_class(gtk_widget_get_style_context(cancel_btn), "vm-login-cancel");

	gtk_box_pack_start(GTK_BOX(hdr), title, TRUE, TRUE, 0);
	gtk_box_pack_end(GTK_BOX(hdr), cancel_btn, FALSE, FALSE, 0);
	gtk_box_pack_end(GTK_BOX(hdr), done_btn, FALSE, FALSE, 0);

	GtkWidget *wv_widget = webkit_web_view_new_with_context(vm_ytm_ctx);
	vm_login_wv = WEBKIT_WEB_VIEW(wv_widget);
	WebKitSettings *ws = webkit_settings_new();
	webkit_settings_set_enable_javascript(ws, TRUE);
	webkit_settings_set_enable_html5_local_storage(ws, TRUE);
	webkit_settings_set_user_agent(ws, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15");
	webkit_web_view_set_settings(vm_login_wv, ws);
	g_object_unref(ws);

	gtk_box_pack_start(GTK_BOX(vbox), hdr, FALSE, FALSE, 0);
	gtk_box_pack_start(GTK_BOX(vbox), wv_widget, TRUE, TRUE, 0);
	gtk_container_add(GTK_CONTAINER(win), vbox);

	g_signal_connect(G_OBJECT(vm_login_wv), "load-changed", G_CALLBACK(vm_on_login_load_changed), NULL);
	g_signal_connect(G_OBJECT(vm_login_wv), "notify::uri", G_CALLBACK(vm_on_login_uri_changed), NULL);
	g_signal_connect(G_OBJECT(vm_login_wv), "create", G_CALLBACK(vm_on_login_create), NULL);
	g_signal_connect(G_OBJECT(done_btn), "clicked", G_CALLBACK(vm_on_login_done_clicked), NULL);
	g_signal_connect(G_OBJECT(cancel_btn), "clicked", G_CALLBACK(vm_on_login_cancel_clicked), NULL);
	g_signal_connect(G_OBJECT(win), "destroy", G_CALLBACK(vm_on_login_destroy), NULL);

	webkit_web_view_load_uri(vm_login_wv, "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F");
	gtk_widget_show_all(win);
	gtk_window_present(GTK_WINDOW(win));
	return G_SOURCE_REMOVE;
}

static void vm_open_ytm_login(const char *data_dir, const char *cookie_file) {
	VmYtmLoginReq *req = (VmYtmLoginReq *)malloc(sizeof(VmYtmLoginReq));
	if (!req) return;
	req->data_dir = strdup(data_dir ? data_dir : "");
	req->cookie_file = strdup(cookie_file ? cookie_file : "");
	g_idle_add(vm_open_ytm_login_idle, req);
}

static gboolean vm_clear_ytm_cookies_idle(gpointer data) {
	if (vm_ytm_dm) {
		webkit_website_data_manager_clear(vm_ytm_dm, WEBKIT_WEBSITE_DATA_ALL, 0, NULL, NULL, NULL);
	}
	return G_SOURCE_REMOVE;
}

static void vm_clear_ytm_cookies(void) {
	g_idle_add(vm_clear_ytm_cookies_idle, NULL);
}
*/
import "C"

import (
	_ "embed"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"
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

//export goOnYtMusicCookies
func goOnYtMusicCookies(raw *C.char, requireMusicHost C.int) C.int {
	if winApp == nil || raw == nil {
		return 0
	}
	if winApp.HandleWebViewCookies(C.GoString(raw), requireMusicHost != 0) {
		return 1
	}
	return 0
}

//export goOnYtMusicLoginClosed
func goOnYtMusicLoginClosed() {
	if winApp == nil {
		return
	}
	winApp.emit("ytm:login-cancelled")
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

func nativeOpenBrowser(url string, background bool) {
	cs := C.CString(url)
	defer C.free(unsafe.Pointer(cs))
	bg := 0
	if background {
		bg = 1
	}
	C.vm_open_url(cs, C.int(bg))
}

func nativeOpenYtMusicLogin(cookieFilePath string) {
	dd := filepath.Dir(cookieFilePath)
	_ = os.MkdirAll(dd, 0o755)
	cdd := C.CString(dd)
	ccf := C.CString(cookieFilePath)
	defer C.free(unsafe.Pointer(cdd))
	defer C.free(unsafe.Pointer(ccf))
	C.vm_open_ytm_login(cdd, ccf)
}

func nativeClearYtMusicCookies() {
	C.vm_clear_ytm_cookies()
}

func setCmdHidden(_ *exec.Cmd) {}

func configureManagedCmd(cmd *exec.Cmd) {
	if cmd == nil {
		return
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error {
		if cmd.Process != nil {
			return syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		}
		return nil
	}
	cmd.WaitDelay = 2 * time.Second
}

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
		exe = "/home/hicemir/Projects/vibeMusic/build/bin/VibeMusic"
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
