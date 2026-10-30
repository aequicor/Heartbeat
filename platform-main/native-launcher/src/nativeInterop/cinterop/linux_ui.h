#ifndef HB_LINUX_UI_H
#define HB_LINUX_UI_H
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

typedef struct { int activate, minimized, x, y, width, height, maximized; } hb_gtk_presentation;
typedef struct { int x, y, width, height; } hb_gdk_rectangle;
typedef struct {
    void *library, *builder, *window, *panel, *stage, *bytes, *progress, *retry, *cancel, *title, *icon, *offline;
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
    void *(*style_context)(void *);
    void (*style_color)(void *, unsigned int, void *);
    void (*add_class)(void *, const char *), (*remove_class)(void *, const char *);
    void (*set_spacing)(void *, int), (*set_margin_bottom)(void *, int), (*set_size_request)(void *, int, int);
    void (*source_rgb)(void *, double, double, double);
    void (*move_to)(void *, double, double), (*line_to)(void *, double, double);
    void (*curve_to)(void *, double, double, double, double, double, double);
    void (*close_path)(void *), (*fill)(void *), (*fill_preserve)(void *), (*stroke)(void *);
    void (*line_width)(void *, double);
    void (*line_cap)(void *, int), (*line_join)(void *, int);
    void (*label_markup)(void *, const char *);
    char *(*markup_printf)(const char *, ...);
    void (*free_string)(void *);
    void *(*css_new)(void), *(*screen_default)(void);
    int (*css_load)(void *, const char *, long, void **);
    void (*css_add)(void *, void *, unsigned int);
} hb_gtk_ui;

/* Exact vectors of HbIllustration / Offline; Cairo and GTK resolve display scaling themselves. */
static void hb_offline_color(hb_gtk_ui *ui, void *cr, int dark, int index) {
    // HbColors/HbSurfaces DesktopLight and DesktopDark, matching the approved offline illustration.
    static const unsigned colors[2][7] = {
        {0xefedf3, 0xe4e4e5, 0xf7f7f7, 0xffffff, 0x616162, 0xe9e5ef, 0x000000},
        {0x36353b, 0x3d3d3f, 0x2b2b2d, 0x2b2b2d, 0xb6b6b8, 0xbfb3d4, 0x000000}
    };
    unsigned rgb = colors[dark][index];
    ui->source_rgb(cr, ((rgb >> 16) & 255)/255.0, ((rgb >> 8) & 255)/255.0, (rgb & 255)/255.0);
}
static int hb_offline_draw(void *widget, void *cr, void *context) {
    hb_gtk_ui *ui = (hb_gtk_ui *)context;
    double rgba[4] = {0};
    ui->style_color(ui->style_context(widget), 0, rgba);
    int dark = rgba[0] + rgba[1] + rgba[2] > 1.5;
    ui->line_width(cr, 1.6); ui->line_cap(cr, 1); ui->line_join(cr, 1);
    ui->move_to(cr , 40, 101);
    ui->curve_to(cr , 29, 65, 62, 26, 103, 33);
    ui->curve_to(cr , 142, 8, 207, 38, 201, 89);
    ui->curve_to(cr , 222, 132, 172, 157, 132, 147);
    ui->curve_to(cr , 87, 164, 32, 145, 40, 101);
    ui->close_path(cr);
    hb_offline_color(ui, cr, dark, 0); ui->fill(cr);
    ui->move_to(cr , 36, 150);
    ui->line_to(cr , 204, 150);
    ui->move_to(cr , 39, 57);
    ui->line_to(cr , 47, 57);
    ui->move_to(cr , 43, 53);
    ui->line_to(cr , 43, 61);
    ui->move_to(cr , 196, 117);
    ui->line_to(cr , 204, 117);
    ui->move_to(cr , 200, 113);
    ui->line_to(cr , 200, 121);
    hb_offline_color(ui, cr, dark, 1); ui->stroke(cr);
    ui->move_to(cr , 76, 135);
    ui->line_to(cr , 169, 135);
    ui->curve_to(cr , 177, 135, 181, 138.333, 181, 145);
    ui->line_to(cr , 64, 145);
    ui->curve_to(cr , 64, 138.333, 68, 135, 76, 135);
    ui->close_path(cr);
    hb_offline_color(ui, cr, dark, 2); ui->fill_preserve(cr);
    hb_offline_color(ui, cr, dark, 1); ui->stroke(cr);
    ui->move_to(cr , 70, 61);
    ui->line_to(cr , 169, 61);
    ui->curve_to(cr , 174.333, 61, 177, 63.667, 177, 69);
    ui->line_to(cr , 177, 127);
    ui->line_to(cr , 62, 127);
    ui->line_to(cr , 62, 69);
    ui->curve_to(cr , 62, 63.667, 64.667, 61, 70, 61);
    ui->close_path(cr);
    ui->move_to(cr , 55, 127);
    ui->line_to(cr , 184, 127);
    ui->line_to(cr , 178, 140);
    ui->line_to(cr , 61, 140);
    ui->close_path(cr);
    hb_offline_color(ui, cr, dark, 3); ui->fill_preserve(cr);
    hb_offline_color(ui, cr, dark, 4); ui->stroke(cr);
    ui->move_to(cr , 76, 73);
    ui->line_to(cr , 163, 73);
    ui->line_to(cr , 163, 116);
    ui->line_to(cr , 76, 116);
    ui->close_path(cr);
    hb_offline_color(ui, cr, dark, 5); ui->fill_preserve(cr);
    hb_offline_color(ui, cr, dark, 6); ui->stroke(cr);
    ui->move_to(cr , 108, 127);
    ui->line_to(cr , 108, 131);
    ui->line_to(cr , 131, 131);
    ui->line_to(cr , 131, 127);
    ui->move_to(cr , 100, 41);
    ui->curve_to(cr , 112.667, 31.667, 125.333, 31.667, 138, 41);
    ui->move_to(cr , 107, 50);
    ui->curve_to(cr , 115, 44, 123, 44, 131, 50);
    ui->move_to(cr , 92, 29);
    ui->line_to(cr , 146, 61);
    hb_offline_color(ui, cr, dark, 4); ui->stroke(cr);
    ui->move_to(cr , 107, 89);
    ui->line_to(cr , 132, 105);
    ui->move_to(cr , 132, 89);
    ui->line_to(cr , 107, 105);
    hb_offline_color(ui, cr, dark, 6); ui->stroke(cr);
    return 0;
}

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
    HB_LOAD(style_context, "gtk_widget_get_style_context"); HB_LOAD(style_color, "gtk_style_context_get_color");
    HB_LOAD(add_class, "gtk_style_context_add_class"); HB_LOAD(remove_class, "gtk_style_context_remove_class");
    HB_LOAD(set_spacing, "gtk_box_set_spacing"); HB_LOAD(set_margin_bottom, "gtk_widget_set_margin_bottom");
    HB_LOAD(set_size_request, "gtk_widget_set_size_request");
    HB_LOAD(source_rgb, "cairo_set_source_rgb"); HB_LOAD(move_to, "cairo_move_to"); HB_LOAD(line_to, "cairo_line_to");
    HB_LOAD(curve_to, "cairo_curve_to"); HB_LOAD(close_path, "cairo_close_path"); HB_LOAD(fill, "cairo_fill");
    HB_LOAD(fill_preserve, "cairo_fill_preserve"); HB_LOAD(stroke, "cairo_stroke"); HB_LOAD(line_width, "cairo_set_line_width");
    HB_LOAD(line_cap, "cairo_set_line_cap"); HB_LOAD(line_join, "cairo_set_line_join");
    HB_LOAD(label_markup, "gtk_label_set_markup");
    HB_LOAD(markup_printf, "g_markup_printf_escaped"); HB_LOAD(free_string, "g_free");
    HB_LOAD(css_new, "gtk_css_provider_new"); HB_LOAD(css_load, "gtk_css_provider_load_from_data");
    HB_LOAD(css_add, "gtk_style_context_add_provider_for_screen"); HB_LOAD(screen_default, "gdk_screen_get_default");
#undef HB_LOAD
    /* The supported Linux desktop is X11/XWayland, matching the JVM/AWT window backend. */
    ui->allowed_backends("x11");
    if (!ui->init_check(NULL, NULL)) goto failed;
    const char *xml = "<interface><requires lib=\"gtk+\" version=\"3.20\"/><object class=\"GtkWindow\" id=\"window\"><property name=\"title\">Heartbeat</property><property name=\"default-width\">1280</property><property name=\"default-height\">800</property><property name=\"window-position\">center</property><property name=\"icon-name\">heartbeat</property><child><object class=\"GtkBox\" id=\"panel\"><property name=\"orientation\">vertical</property><property name=\"spacing\">20</property><property name=\"halign\">center</property><property name=\"valign\">center</property><property name=\"width-request\">360</property><property name=\"margin\">32</property><child><object class=\"GtkDrawingArea\" id=\"offline\"><property name=\"width-request\">240</property><property name=\"height-request\">180</property><property name=\"halign\">center</property><property name=\"no-show-all\">True</property></object></child><child><object class=\"GtkImage\" id=\"icon\"/></child><child><object class=\"GtkLabel\" id=\"title\"><property name=\"label\">Heartbeat</property><property name=\"use-markup\">True</property></object></child><child><object class=\"GtkLabel\" id=\"stage\"><property name=\"label\">Подготовка приложения…</property><property name=\"wrap\">True</property><property name=\"max-width-chars\">48</property><property name=\"justify\">center</property></object></child><child><object class=\"GtkProgressBar\" id=\"progress\"/></child><child><object class=\"GtkLabel\" id=\"bytes\"><property name=\"label\"> </property></object></child><child><object class=\"GtkBox\"><property name=\"orientation\">horizontal</property><property name=\"spacing\">12</property><property name=\"halign\">center</property><child><object class=\"GtkButton\" id=\"retry\"><property name=\"label\">Повторить</property><property name=\"no-show-all\">True</property></object></child><child><object class=\"GtkButton\" id=\"cancel\"><property name=\"label\">Отмена</property></object></child></object></child></object></child></object></interface>";
    ui->builder = ui->builder_new(xml, -1);
    if (!ui->builder) goto failed;
    ui->window = ui->builder_get(ui->builder, "window");
    ui->panel = ui->builder_get(ui->builder, "panel");
    /* Bundled JBR21 XToolkit derives WM_CLASS from the selector main class, replacing dots with hyphens. */
    ui->wmclass(ui->window, "io-aequicor-heartbeat-selector-SelectorMain", "io-aequicor-heartbeat-selector-SelectorMain");
    ui->offline = ui->builder_get(ui->builder, "offline");
    ui->icon = ui->builder_get(ui->builder, "icon");
    ui->title = ui->builder_get(ui->builder, "title");
    ui->label_markup(ui->title, "<span size=\"32768\" weight=\"600\">Heartbeat</span>");
    ui->connect(ui->offline, "draw", (void (*)(void))hb_offline_draw, ui, NULL, 0);
    ui->stage = ui->builder_get(ui->builder, "stage");
    ui->bytes = ui->builder_get(ui->builder, "bytes");
    ui->progress = ui->builder_get(ui->builder, "progress");
    ui->retry = ui->builder_get(ui->builder, "retry");
    ui->cancel = ui->builder_get(ui->builder, "cancel");
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
    const char *rules =
        "progressbar progress { background: #e9e5ef; border: none; min-height: 6px; }"
        "button.hb-primary-light, button.hb-primary-dark { background-image: none; border: none; box-shadow: none; "
        "border-radius: 6px; min-width: 116px; min-height: 32px; padding: 0; color: #000; }"
        "button.hb-primary-light { background-color: #e9e5ef; }"
        "button.hb-primary-dark { background-color: #bfb3d4; }"
        "button.hb-ghost-light, button.hb-ghost-dark { background: transparent; border: none; box-shadow: none; "
        "border-radius: 6px; min-width: 80px; min-height: 32px; padding: 0; }"
        "button.hb-ghost-light { color: #242426; } button.hb-ghost-dark { color: #eeeef0; }"
        "label.hb-secondary-light { color: #616162; } label.hb-secondary-dark { color: #b6b6b8; }";
    if (ui->css_load(css, rules, -1, &css_error))
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
    const char *separator = failed ? strchr(stage, '\n') : NULL;
    char title[256];
    if (separator) {
        size_t length = (size_t)(separator - stage);
        if (length >= sizeof(title)) length = sizeof(title) - 1;
        memcpy(title, stage, length); title[length] = 0;
    }
    ui->label_text(ui->stage, separator ? separator + 1 : stage); ui->label_text(ui->bytes, bytes);
    double foreground[4] = {0};
    ui->style_color(ui->style_context(ui->title), 0, foreground);
    int dark = foreground[0] + foreground[1] + foreground[2] > 1.5;
    const char *primary = dark ? "hb-primary-dark" : "hb-primary-light";
    const char *ghost = dark ? "hb-ghost-dark" : "hb-ghost-light";
    const char *secondary = dark ? "hb-secondary-dark" : "hb-secondary-light";
    void *retryStyle = ui->style_context(ui->retry), *cancelStyle = ui->style_context(ui->cancel);
    void *stageStyle = ui->style_context(ui->stage);
    ui->remove_class(retryStyle, "hb-primary-light"); ui->remove_class(retryStyle, "hb-primary-dark");
    ui->remove_class(cancelStyle, "hb-ghost-light"); ui->remove_class(cancelStyle, "hb-ghost-dark");
    ui->remove_class(stageStyle, "hb-secondary-light"); ui->remove_class(stageStyle, "hb-secondary-dark");
    ui->set_spacing(ui->panel, failed ? 0 : 20);
    ui->set_margin_bottom(ui->offline, failed ? 40 : 0);
    ui->set_margin_bottom(ui->title, failed ? 24 : 0);
    ui->set_margin_bottom(ui->stage, failed ? 16 : 0);
    ui->set_size_request(ui->stage, failed ? 420 : -1, -1);
    if (failed) {
        ui->add_class(retryStyle, primary); ui->add_class(cancelStyle, ghost); ui->add_class(stageStyle, secondary);
        char *markup = ui->markup_printf("<span size=\"14336\" weight=\"600\">%s</span>",
            separator ? title : "Не удалось подготовить приложение");
        ui->label_markup(ui->title, markup);
        ui->free_string(markup);
        ui->show(ui->offline); ui->hide(ui->icon); ui->hide(ui->bytes);
    } else {
        ui->label_markup(ui->title, "<span size=\"32768\" weight=\"600\">Heartbeat</span>");
        ui->hide(ui->offline); ui->show(ui->icon); ui->show(ui->bytes);
    }
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
