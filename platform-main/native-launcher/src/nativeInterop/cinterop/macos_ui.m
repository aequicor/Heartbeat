#include "macos_ui.h"
#include <math.h>
#import <AppKit/AppKit.h>



// Native snapshot of HbColors.DesktopLight/Dark: the bootstrap cannot load Compose before the JVM.
@interface HBLauncherProgress : NSView
@property(nonatomic, assign) double fraction;
@end

@implementation HBLauncherProgress
- (void)drawRect:(NSRect)dirtyRect {
    BOOL dark = [[self.effectiveAppearance bestMatchFromAppearancesWithNames:
        @[NSAppearanceNameAqua, NSAppearanceNameDarkAqua]] isEqualToString:NSAppearanceNameDarkAqua];
    NSColor *track = dark ? [NSColor colorWithSRGBRed:63.0/255 green:63.0/255 blue:66.0/255 alpha:1]
                          : [NSColor colorWithSRGBRed:232.0/255 green:232.0/255 blue:233.0/255 alpha:1];
    NSColor *fill = dark ? [NSColor colorWithSRGBRed:191.0/255 green:179.0/255 blue:212.0/255 alpha:1]
                         : [NSColor colorWithSRGBRed:233.0/255 green:229.0/255 blue:239.0/255 alpha:1];
    NSRect bounds = self.bounds;
    [track setFill];
    [[NSBezierPath bezierPathWithRoundedRect:bounds xRadius:3 yRadius:3] fill];
    double fraction = self.fraction;
    if (fraction < 0) {
        double phase = fmod([NSProcessInfo processInfo].systemUptime, 1.5) / 1.5;
        bounds.origin.x = bounds.size.width * 0.75 * phase;
        bounds.size.width *= 0.25;
    } else {
        bounds.size.width *= fmax(0.0, fmin(1.0, fraction));
    }
    [fill setFill];
    [[NSBezierPath bezierPathWithRoundedRect:bounds xRadius:3 yRadius:3] fill];
}
@end

@interface HBLauncherWindow : NSObject <NSWindowDelegate, NSApplicationDelegate>
@property(nonatomic, strong) NSWindow *window;
@property(nonatomic, strong) NSTextField *stage;
@property(nonatomic, strong) NSTextField *bytes;
@property(nonatomic, strong) HBLauncherProgress *progress;
@property(nonatomic, strong) NSButton *retry;
@property(nonatomic, strong) NSTimer *timer;
@property(nonatomic, assign) void *context;
@property(nonatomic, assign) hb_ui_action action;
@property(nonatomic, assign) hb_ui_tick tick;
@end

@implementation HBLauncherWindow
- (NSApplicationTerminateReply)applicationShouldTerminate:(NSApplication *)sender {
    self.action(self.context, 0);
    return NSTerminateCancel;
}
- (BOOL)windowShouldClose:(NSWindow *)sender {
    self.action(self.context, 0);
    return NO;
}
- (void)cancel:(id)sender { self.action(self.context, 0); }
- (void)retry:(id)sender { self.action(self.context, 1); }
@end

void *hb_macos_create(void *context, hb_ui_action action, hb_ui_tick tick) {
    NSApplication *app = [NSApplication sharedApplication];
    [app setActivationPolicy:NSApplicationActivationPolicyRegular];
    HBLauncherWindow *ui = [HBLauncherWindow new];
    ui.context = context;
    ui.action = action;
    ui.tick = tick;
    ui.window = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, 1280, 800)
        styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskMiniaturizable |
                  NSWindowStyleMaskResizable | NSWindowStyleMaskFullSizeContentView
        backing:NSBackingStoreBuffered defer:NO];
    ui.window.title = @"Heartbeat";
    ui.window.titleVisibility = NSWindowTitleHidden;
    ui.window.titlebarAppearsTransparent = YES;
    ui.window.releasedWhenClosed = NO;
    ui.window.minSize = NSMakeSize(640, 480);
    ui.window.delegate = ui;
    app.delegate = ui;
    NSStackView *panel = [NSStackView new];
    panel.orientation = NSUserInterfaceLayoutOrientationVertical;
    panel.alignment = NSLayoutAttributeCenterX;
    panel.spacing = 20;
    panel.translatesAutoresizingMaskIntoConstraints = NO;
    NSView *content = ui.window.contentView;
    [content addSubview:panel];
    [NSLayoutConstraint activateConstraints:@[
        [panel.centerXAnchor constraintEqualToAnchor:content.centerXAnchor],
        [panel.centerYAnchor constraintEqualToAnchor:content.centerYAnchor],
        [panel.widthAnchor constraintEqualToConstant:480]
    ]];
    NSImageView *icon = [NSImageView new];
    icon.image = app.applicationIconImage;
    [icon.widthAnchor constraintEqualToConstant:64].active = YES;
    [icon.heightAnchor constraintEqualToConstant:64].active = YES;
    [panel addArrangedSubview:icon];
    NSTextField *title = [NSTextField labelWithString:@"Heartbeat"];
    title.font = [NSFont systemFontOfSize:32 weight:NSFontWeightSemibold];
    [panel addArrangedSubview:title];
    ui.stage = [NSTextField wrappingLabelWithString:@"Подготовка приложения…"];
    ui.stage.alignment = NSTextAlignmentCenter;
    ui.stage.maximumNumberOfLines = 3;
    ui.stage.font = [NSFont systemFontOfSize:14];
    [ui.stage.widthAnchor constraintEqualToConstant:480].active = YES;
    [panel addArrangedSubview:ui.stage];
    ui.progress = [HBLauncherProgress new];
    ui.progress.fraction = -1;
    ui.progress.accessibilityElement = YES;
    ui.progress.accessibilityRole = NSAccessibilityProgressIndicatorRole;
    ui.progress.accessibilityLabel = @"Загрузка компонентов";
    ui.progress.accessibilityMinValue = @0;
    ui.progress.accessibilityMaxValue = @100;
    [ui.progress.widthAnchor constraintEqualToConstant:480].active = YES;
    [ui.progress.heightAnchor constraintEqualToConstant:6].active = YES;
    [panel addArrangedSubview:ui.progress];
    ui.bytes = [NSTextField labelWithString:@" "];
    ui.bytes.textColor = [NSColor secondaryLabelColor];
    ui.bytes.font = [NSFont systemFontOfSize:12];
    [panel addArrangedSubview:ui.bytes];
    NSStackView *buttons = [NSStackView new];
    buttons.orientation = NSUserInterfaceLayoutOrientationHorizontal;
    buttons.spacing = 12;
    ui.retry = [NSButton buttonWithTitle:@"Повторить" target:ui action:@selector(retry:)];
    ui.retry.bezelStyle = NSBezelStyleRounded;
    ui.retry.hidden = YES;
    NSButton *cancel = [NSButton buttonWithTitle:@"Отмена" target:ui action:@selector(cancel:)];
    cancel.bezelStyle = NSBezelStyleRounded;
    [buttons addArrangedSubview:ui.retry];
    [buttons addArrangedSubview:cancel];
    [panel addArrangedSubview:buttons];
    [ui.window center];
    [ui.window makeKeyAndOrderFront:nil];
    [ui.window displayIfNeeded];
    [app activateIgnoringOtherApps:YES];
    ui.timer = [NSTimer timerWithTimeInterval:0.1 repeats:YES block:^(NSTimer *timer) {
        ui.tick(ui.context);
        if (ui.progress.fraction < 0) ui.progress.needsDisplay = YES;
    }];
    [[NSRunLoop mainRunLoop] addTimer:ui.timer forMode:NSRunLoopCommonModes];
    return (__bridge_retained void *)ui;
}

void hb_macos_run(void) { [[NSApplication sharedApplication] run]; }

void hb_macos_update(void *handle, const char *stage, const char *bytes, double fraction, int failed) {
    HBLauncherWindow *ui = (__bridge HBLauncherWindow *)handle;
    ui.stage.stringValue = [NSString stringWithUTF8String:stage];
    ui.bytes.stringValue = [NSString stringWithUTF8String:bytes];
    ui.retry.hidden = !failed;
    ui.progress.hidden = failed;
    ui.progress.fraction = fraction;
    ui.progress.accessibilityValue = fraction < 0 ? nil : @(fraction * 100);
    ui.progress.needsDisplay = YES;
    if (failed) [[NSApplication sharedApplication] setActivationPolicy:NSApplicationActivationPolicyRegular];
}

void hb_macos_prepare_launch(void) {
    [[NSApplication sharedApplication] setActivationPolicy:NSApplicationActivationPolicyAccessory];
}

void hb_macos_hide(void *handle) {
    HBLauncherWindow *ui = (__bridge HBLauncherWindow *)handle;
    [ui.window orderOut:nil];
}

void hb_macos_finish(void *handle) {
    HBLauncherWindow *ui = (__bridge HBLauncherWindow *)handle;
    [ui.timer invalidate];
    ui.timer = nil;
    [[NSApplication sharedApplication] stop:nil];
    NSEvent *wake = [NSEvent otherEventWithType:NSEventTypeApplicationDefined location:NSZeroPoint
        modifierFlags:0 timestamp:0 windowNumber:0 context:nil subtype:0 data1:0 data2:0];
    [[NSApplication sharedApplication] postEvent:wake atStart:NO];
}

void hb_macos_dispose(void *handle) {
    HBLauncherWindow *ui = (__bridge_transfer HBLauncherWindow *)handle;
    [ui.timer invalidate];
    ui.timer = nil;
    ui.window.delegate = nil;
    [NSApplication sharedApplication].delegate = nil;
    [ui.window close];
}

hb_window_presentation hb_macos_get_presentation(void *handle, int child_pid) {
    HBLauncherWindow *ui = (__bridge HBLauncherWindow *)handle;
    NSRect frame = ui.window.frame;
    NSScreen *primary = [NSScreen screens].firstObject;
    double primary_height = primary ? NSHeight(primary.frame) : NSMaxY(frame);
    BOOL active = [[NSApplication sharedApplication] isActive] ||
        [NSWorkspace sharedWorkspace].frontmostApplication.processIdentifier == child_pid;
    hb_window_presentation result = {
        active, ui.window.miniaturized,
        (int)lround(NSMinX(frame)), (int)lround(primary_height - NSMaxY(frame)),
        (int)lround(NSWidth(frame)), (int)lround(NSHeight(frame)), ui.window.zoomed
    };
    return result;
}
