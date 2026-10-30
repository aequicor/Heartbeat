#ifndef HB_LINUX_UI_H
#define HB_LINUX_UI_H
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

typedef struct { int activate, minimized, x, y, width, height, maximized; } hb_gtk_presentation;
typedef struct { int x, y, width, height; } hb_gdk_rectangle;
typedef struct {
    void *library, *builder, *window, *stage, *bytes, *progress, *retry;
    int action;
    int (*init_check)(int *, char ***);
    void (*allowed_backends)(const char *);
    void *(*builder_new)(const char *, long);
    void *(*builder_get)(void *, const char *);
    unsigned long (*connect)(void *, const char *, void (*)(void), void *, void (*)(void *, void *), int);
    void (*show_all)(void *), (*show)(void *), (*hide)(void *), (*destroy)(void *), (*unref)(void *);
    void (*label_text)(void *, const char *);
    void (*wmclass)(void *, const char *, const char *);
    void (*fraction)(void *, double), (*pulse)(void *);
    int (*events_pending)(void), (*iteration)(int), (*is_active)(void *), (*window_state)(void *);
    void *(*get_window)(void *), *(*display_default)(void);
    void (*display_sync)(void *);
    void (*frame_extents)(void *, hb_gdk_rectangle *);
    void *(*pixbuf_load)(const char *, int, int, int, void **);
    void (*image_pixbuf)(void *, void *), (*window_icon)(void *, void *);
    void (*free_error)(void *);
    void *(*css_new)(void), *(*screen_default)(void);
    int (*css_load)(void *, const char *, long, void **);
    void (*css_add)(void *, void *, unsigned int);
} hb_gtk_ui;

static void hb_gtk_cancel(void *widget, void *context) { ((hb_gtk_ui *)context)->action = 1; }
static void hb_gtk_retry(void *widget, void *context) { ((hb_gtk_ui *)context)->action = 2; }
static int hb_gtk_close(void *widget, void *event, void *context) {
    ((hb_gtk_ui *)context)->action = 1;
    return 1; /* The session owns cancellation and process cleanup. */
}

static inline void *hb_gtk_create(const char *icon_path) {
    hb_gtk_ui *ui = (hb_gtk_ui *)calloc(1, sizeof(hb_gtk_ui));
    if (!ui) return NULL;
    ui->library = dlopen("libgtk-3.so.0", RTLD_NOW | RTLD_LOCAL);
    if (!ui->library) { free(ui); return NULL; }
#define HB_LOAD(field, symbol) do { *(void **)(&ui->field) = dlsym(ui->library, symbol); if (!ui->field) goto failed; } while (0)
    HB_LOAD(init_check, "gtk_init_check");
    HB_LOAD(allowed_backends, "gdk_set_allowed_backends");
    HB_LOAD(builder_new, "gtk_builder_new_from_string");
    HB_LOAD(builder_get, "gtk_builder_get_object");
    HB_LOAD(connect, "g_signal_connect_data");
    HB_LOAD(show_all, "gtk_widget_show_all"); HB_LOAD(show, "gtk_widget_show");
    HB_LOAD(hide, "gtk_widget_hide"); HB_LOAD(destroy, "gtk_widget_destroy");
    HB_LOAD(unref, "g_object_unref"); HB_LOAD(label_text, "gtk_label_set_text");
    HB_LOAD(wmclass, "gtk_window_set_wmclass");
    HB_LOAD(fraction, "gtk_progress_bar_set_fraction"); HB_LOAD(pulse, "gtk_progress_bar_pulse");
    HB_LOAD(events_pending, "gtk_events_pending"); HB_LOAD(iteration, "gtk_main_iteration_do");
    HB_LOAD(is_active, "gtk_window_is_active"); HB_LOAD(get_window, "gtk_widget_get_window");
    HB_LOAD(display_default, "gdk_display_get_default"); HB_LOAD(display_sync, "gdk_display_sync");
    HB_LOAD(window_state, "gdk_window_get_state"); HB_LOAD(frame_extents, "gdk_window_get_frame_extents");
    HB_LOAD(pixbuf_load, "gdk_pixbuf_new_from_file_at_scale"); HB_LOAD(image_pixbuf, "gtk_image_set_from_pixbuf");
    HB_LOAD(window_icon, "gtk_window_set_icon"); HB_LOAD(free_error, "g_error_free");
    HB_LOAD(css_new, "gtk_css_provider_new"); HB_LOAD(css_load, "gtk_css_provider_load_from_data");
    HB_LOAD(css_add, "gtk_style_context_add_provider_for_screen"); HB_LOAD(screen_default, "gdk_screen_get_default");
#undef HB_LOAD
    /* The supported Linux desktop is X11/XWayland, matching the JVM/AWT window backend. */
    ui->allowed_backends("x11");
    if (!ui->init_check(NULL, NULL)) goto failed;
    const char *xml = "<interface><requires lib=\"gtk+\" version=\"3.20\"/><object class=\"GtkWindow\" id=\"window\"><property name=\"title\">Heartbeat</property><property name=\"default-width\">1280</property><property name=\"default-height\">800</property><property name=\"window-position\">center</property><property name=\"icon-name\">heartbeat</property><child><object class=\"GtkBox\" id=\"panel\"><property name=\"orientation\">vertical</property><property name=\"spacing\">20</property><property name=\"halign\">center</property><property name=\"valign\">center</property><property name=\"width-request\">360</property><property name=\"margin\">32</property><child><object class=\"GtkImage\" id=\"icon\"/></child><child><object class=\"GtkLabel\"><property name=\"label\">Heartbeat</property><attributes><attribute name=\"size\" value=\"32768\"/><attribute name=\"weight\" value=\"600\"/></attributes></object></child><child><object class=\"GtkLabel\" id=\"stage\"><property name=\"label\">Подготовка приложения…</property><property name=\"wrap\">True</property><property name=\"max-width-chars\">48</property><property name=\"justify\">center</property></object></child><child><object class=\"GtkProgressBar\" id=\"progress\"/></child><child><object class=\"GtkLabel\" id=\"bytes\"><property name=\"label\"> </property></object></child><child><object class=\"GtkBox\"><property name=\"orientation\">horizontal</property><property name=\"spacing\">12</property><property name=\"halign\">center</property><child><object class=\"GtkButton\" id=\"retry\"><property name=\"label\">Повторить</property><property name=\"no-show-all\">True</property></object></child><child><object class=\"GtkButton\" id=\"cancel\"><property name=\"label\">Отмена</property></object></child></object></child></object></child></object></interface>";
    ui->builder = ui->builder_new(xml, -1);
    if (!ui->builder) goto failed;
    ui->window = ui->builder_get(ui->builder, "window");
    /* Bundled JBR21 XToolkit derives WM_CLASS from the selector main class, replacing dots with hyphens. */
    ui->wmclass(ui->window, "io-aequicor-heartbeat-selector-SelectorMain", "io-aequicor-heartbeat-selector-SelectorMain");
    ui->stage = ui->builder_get(ui->builder, "stage");
    ui->bytes = ui->builder_get(ui->builder, "bytes");
    ui->progress = ui->builder_get(ui->builder, "progress");
    ui->retry = ui->builder_get(ui->builder, "retry");
    ui->connect(ui->window, "delete-event", (void (*)(void))hb_gtk_close, ui, NULL, 0);
    ui->connect(ui->builder_get(ui->builder, "cancel"), "clicked", (void (*)(void))hb_gtk_cancel, ui, NULL, 0);
    ui->connect(ui->retry, "clicked", (void (*)(void))hb_gtk_retry, ui, NULL, 0);
    void *error = NULL;
    void *icon = ui->pixbuf_load(icon_path, 64, 64, 1, &error);
    if (icon) {
        ui->image_pixbuf(ui->builder_get(ui->builder, "icon"), icon);
        ui->window_icon(ui->window, icon);
        ui->unref(icon);
    }
    if (error) ui->free_error(error); /* Missing optional artwork does not prevent startup. */
    /* Snapshot of HbColors.DesktopLight.primaryContainer; native GTK handles system text/background/theme. */
    void *css = ui->css_new();
    void *css_error = NULL;
    if (ui->css_load(css, "progressbar progress { background: #e9e5ef; border: none; min-height: 6px; }", -1, &css_error))
        ui->css_add(ui->screen_default(), css, 600);
    if (css_error) ui->free_error(css_error);
    ui->unref(css);
    ui->show_all(ui->window);
    return ui;
failed:
    if (ui->builder) ui->unref(ui->builder);
    dlclose(ui->library);
    free(ui);
    return NULL;
}

static inline int hb_gtk_iterate(void *handle) {
    hb_gtk_ui *ui = (hb_gtk_ui *)handle;
    for (int count = 0; count < 128 && ui->events_pending(); count++) ui->iteration(0);
    int action = ui->action;
    ui->action = 0;
    return action;
}
static inline void hb_gtk_update(void *handle, const char *stage, const char *bytes, double fraction, int failed) {
    hb_gtk_ui *ui = (hb_gtk_ui *)handle;
    ui->label_text(ui->stage, stage); ui->label_text(ui->bytes, bytes);
    if (failed) { ui->hide(ui->progress); ui->show(ui->retry); }
    else {
        ui->show(ui->progress); ui->hide(ui->retry);
        if (fraction < 0) ui->pulse(ui->progress); else ui->fraction(ui->progress, fraction);
    }
}
static inline hb_gtk_presentation hb_gtk_get_presentation(void *handle) {
    hb_gtk_ui *ui = (hb_gtk_ui *)handle;
    hb_gtk_presentation result = {0};
    void *window = ui->get_window(ui->window);
    if (window) {
        hb_gdk_rectangle rect = {0};
        ui->frame_extents(window, &rect);
        result.x = rect.x; result.y = rect.y; result.width = rect.width; result.height = rect.height;
        int state = ui->window_state(window);
        result.minimized = (state & 2) != 0; result.maximized = (state & 4) != 0;
    }
    result.activate = ui->is_active(ui->window);
    return result;
}
static inline void hb_gtk_hide(void *handle) {
    hb_gtk_ui *ui = (hb_gtk_ui *)handle;
    ui->hide(ui->window);
    while (ui->events_pending()) ui->iteration(0);
    ui->display_sync(ui->display_default()); /* X server has unmapped native before JVM reads hidden. */
}
static inline void hb_gtk_dispose(void *handle) {
    hb_gtk_ui *ui = (hb_gtk_ui *)handle;
    ui->destroy(ui->window);
    ui->unref(ui->builder);
    /* GTK/GDK register global callbacks and types; keep their library loaded until process exit. */
    free(ui);
}
#endif
