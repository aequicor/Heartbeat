#pragma once

typedef void (*hb_ui_action)(void *, int);
typedef void (*hb_ui_tick)(void *);
void *hb_macos_create(void *context, hb_ui_action action, hb_ui_tick tick);
void hb_macos_run(void);
void hb_macos_update(void *handle, const char *stage, const char *bytes, double fraction, int failed);
void hb_macos_prepare_launch(void);
void hb_macos_hide(void *handle);
void hb_macos_finish(void *handle);
void hb_macos_dispose(void *handle);

typedef struct {
    int activate, minimized, x, y, width, height, maximized;
} hb_window_presentation;
hb_window_presentation hb_macos_get_presentation(void *handle, int child_pid);
