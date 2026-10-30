#include "macos_ui.h"
#include <math.h>
#import <AppKit/AppKit.h>

// Native equivalents of the approved desktop primary/ghost controls and secondary text.
static NSColor *hb_color(unsigned rgb) {
    return [NSColor colorWithSRGBRed:((rgb >> 16) & 255)/255.0
        green:((rgb >> 8) & 255)/255.0 blue:(rgb & 255)/255.0 alpha:1];
}
static NSColor *hb_theme_color(unsigned light, unsigned dark) {
    return [NSColor colorWithName:nil dynamicProvider:^NSColor *(NSAppearance *appearance) {
        BOOL isDark = [[appearance bestMatchFromAppearancesWithNames:
            @[NSAppearanceNameAqua, NSAppearanceNameDarkAqua]] isEqualToString:NSAppearanceNameDarkAqua];
        return hb_color(isDark ? dark : light);
    }];
}
@interface HBLauncherButton : NSButton
@property(nonatomic, assign) BOOL errorPrimary;
@end
@implementation HBLauncherButton
- (void)drawRect:(NSRect)dirtyRect {
    if (self.errorPrimary) {
        [hb_theme_color(0xe9e5ef, 0xbfb3d4) setFill];
        [[NSBezierPath bezierPathWithRoundedRect:self.bounds xRadius:6 yRadius:6] fill];
    }
    [super drawRect:dirtyRect];
}
@end

// Exact vectors of HbIllustration / Offline in the approved launcher error state.
@interface HBLauncherOffline : NSView
@end
@implementation HBLauncherOffline
- (BOOL)isFlipped { return YES; }
- (void)drawRect:(NSRect)dirtyRect {
    BOOL dark = [[self.effectiveAppearance bestMatchFromAppearancesWithNames:
        @[NSAppearanceNameAqua, NSAppearanceNameDarkAqua]] isEqualToString:NSAppearanceNameDarkAqua];
    // HbColors/HbSurfaces DesktopLight and DesktopDark, matching the approved offline illustration.
    static const unsigned colors[2][7] = {
        {0xefedf3, 0xe4e4e5, 0xf7f7f7, 0xffffff, 0x616162, 0xe9e5ef, 0x000000},
        {0x36353b, 0x3d3d3f, 0x2b2b2d, 0x2b2b2d, 0xb6b6b8, 0xbfb3d4, 0x000000}
    };
    NSColor *(^color)(int) = ^NSColor *(int index) {
        unsigned rgb = colors[dark ? 1 : 0][index];
        return [NSColor colorWithSRGBRed:((rgb >> 16) & 255)/255.0
            green:((rgb >> 8) & 255)/255.0 blue:(rgb & 255)/255.0 alpha:1];
    };
    NSBezierPath *path;
    path = [NSBezierPath bezierPath]; path.lineWidth = 1.6;
    path.lineCapStyle = NSLineCapStyleRound; path.lineJoinStyle = NSLineJoinStyleRound;
    [path moveToPoint:NSMakePoint(40, 101)];
    [path curveToPoint:NSMakePoint(103, 33) controlPoint1:NSMakePoint(29, 65) controlPoint2:NSMakePoint(62, 26)];
    [path curveToPoint:NSMakePoint(201, 89) controlPoint1:NSMakePoint(142, 8) controlPoint2:NSMakePoint(207, 38)];
    [path curveToPoint:NSMakePoint(132, 147) controlPoint1:NSMakePoint(222, 132) controlPoint2:NSMakePoint(172, 157)];
    [path curveToPoint:NSMakePoint(40, 101) controlPoint1:NSMakePoint(87, 164) controlPoint2:NSMakePoint(32, 145)];
    [path closePath];
    [color(0) setFill]; [path fill];
    path = [NSBezierPath bezierPath]; path.lineWidth = 1.6;
    path.lineCapStyle = NSLineCapStyleRound; path.lineJoinStyle = NSLineJoinStyleRound;
    [path moveToPoint:NSMakePoint(36, 150)];
    [path lineToPoint:NSMakePoint(204, 150)];
    [path moveToPoint:NSMakePoint(39, 57)];
    [path lineToPoint:NSMakePoint(47, 57)];
    [path moveToPoint:NSMakePoint(43, 53)];
    [path lineToPoint:NSMakePoint(43, 61)];
    [path moveToPoint:NSMakePoint(196, 117)];
    [path lineToPoint:NSMakePoint(204, 117)];
    [path moveToPoint:NSMakePoint(200, 113)];
    [path lineToPoint:NSMakePoint(200, 121)];
    [color(1) setStroke]; [path stroke];
    path = [NSBezierPath bezierPath]; path.lineWidth = 1.6;
    path.lineCapStyle = NSLineCapStyleRound; path.lineJoinStyle = NSLineJoinStyleRound;
    [path moveToPoint:NSMakePoint(76, 135)];
    [path lineToPoint:NSMakePoint(169, 135)];
    [path curveToPoint:NSMakePoint(181, 145) controlPoint1:NSMakePoint(177, 135) controlPoint2:NSMakePoint(181, 138.333)];
    [path lineToPoint:NSMakePoint(64, 145)];
    [path curveToPoint:NSMakePoint(76, 135) controlPoint1:NSMakePoint(64, 138.333) controlPoint2:NSMakePoint(68, 135)];
    [path closePath];
    [color(2) setFill]; [path fill];
    [color(1) setStroke]; [path stroke];
    path = [NSBezierPath bezierPath]; path.lineWidth = 1.6;
    path.lineCapStyle = NSLineCapStyleRound; path.lineJoinStyle = NSLineJoinStyleRound;
    [path moveToPoint:NSMakePoint(70, 61)];
    [path lineToPoint:NSMakePoint(169, 61)];
    [path curveToPoint:NSMakePoint(177, 69) controlPoint1:NSMakePoint(174.333, 61) controlPoint2:NSMakePoint(177, 63.667)];
    [path lineToPoint:NSMakePoint(177, 127)];
    [path lineToPoint:NSMakePoint(62, 127)];
    [path lineToPoint:NSMakePoint(62, 69)];
    [path curveToPoint:NSMakePoint(70, 61) controlPoint1:NSMakePoint(62, 63.667) controlPoint2:NSMakePoint(64.667, 61)];
    [path closePath];
    [path moveToPoint:NSMakePoint(55, 127)];
    [path lineToPoint:NSMakePoint(184, 127)];
    [path lineToPoint:NSMakePoint(178, 140)];
    [path lineToPoint:NSMakePoint(61, 140)];
    [path closePath];
    [color(3) setFill]; [path fill];
    [color(4) setStroke]; [path stroke];
    path = [NSBezierPath bezierPath]; path.lineWidth = 1.6;
    path.lineCapStyle = NSLineCapStyleRound; path.lineJoinStyle = NSLineJoinStyleRound;
    [path moveToPoint:NSMakePoint(76, 73)];
    [path lineToPoint:NSMakePoint(163, 73)];
    [path lineToPoint:NSMakePoint(163, 116)];
    [path lineToPoint:NSMakePoint(76, 116)];
    [path closePath];
    [color(5) setFill]; [path fill];
    [color(6) setStroke]; [path stroke];
    path = [NSBezierPath bezierPath]; path.lineWidth = 1.6;
    path.lineCapStyle = NSLineCapStyleRound; path.lineJoinStyle = NSLineJoinStyleRound;
    [path moveToPoint:NSMakePoint(108, 127)];
    [path lineToPoint:NSMakePoint(108, 131)];
    [path lineToPoint:NSMakePoint(131, 131)];
    [path lineToPoint:NSMakePoint(131, 127)];
    [path moveToPoint:NSMakePoint(100, 41)];
    [path curveToPoint:NSMakePoint(138, 41) controlPoint1:NSMakePoint(112.667, 31.667) controlPoint2:NSMakePoint(125.333, 31.667)];
    [path moveToPoint:NSMakePoint(107, 50)];
    [path curveToPoint:NSMakePoint(131, 50) controlPoint1:NSMakePoint(115, 44) controlPoint2:NSMakePoint(123, 44)];
    [path moveToPoint:NSMakePoint(92, 29)];
    [path lineToPoint:NSMakePoint(146, 61)];
    [color(4) setStroke]; [path stroke];
    path = [NSBezierPath bezierPath]; path.lineWidth = 1.6;
    path.lineCapStyle = NSLineCapStyleRound; path.lineJoinStyle = NSLineJoinStyleRound;
    [path moveToPoint:NSMakePoint(107, 89)];
    [path lineToPoint:NSMakePoint(132, 105)];
    [path moveToPoint:NSMakePoint(132, 89)];
    [path lineToPoint:NSMakePoint(107, 105)];
    [color(6) setStroke]; [path stroke];
}
@end

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
@property(nonatomic, strong) NSLayoutConstraint *stageWidth;
@property(nonatomic, strong) NSTextField *title;
@property(nonatomic, strong) NSImageView *icon;
@property(nonatomic, strong) HBLauncherOffline *offline;
@property(nonatomic, strong) NSStackView *panel;
@property(nonatomic, strong) NSTextField *bytes;
@property(nonatomic, strong) HBLauncherProgress *progress;
@property(nonatomic, strong) NSButton *retry;
@property(nonatomic, strong) NSButton *cancelButton;
@property(nonatomic, strong) NSArray<NSLayoutConstraint *> *errorButtonConstraints;
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
    ui.panel = panel;
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
    ui.offline = [HBLauncherOffline new];
    ui.offline.hidden = YES;
    [ui.offline.widthAnchor constraintEqualToConstant:240].active = YES;
    [ui.offline.heightAnchor constraintEqualToConstant:180].active = YES;
    [panel addArrangedSubview:ui.offline];
    NSImageView *icon = [NSImageView new];
    ui.icon = icon;
    icon.image = app.applicationIconImage;
    [icon.widthAnchor constraintEqualToConstant:64].active = YES;
    [icon.heightAnchor constraintEqualToConstant:64].active = YES;
    [panel addArrangedSubview:icon];
    NSTextField *title = [NSTextField labelWithString:@"Heartbeat"];
    ui.title = title;
    title.font = [NSFont systemFontOfSize:32 weight:NSFontWeightSemibold];
    [panel addArrangedSubview:title];
    ui.stage = [NSTextField wrappingLabelWithString:@"Подготовка приложения…"];
    ui.stage.alignment = NSTextAlignmentCenter;
    ui.stage.maximumNumberOfLines = 3;
    ui.stage.font = [NSFont systemFontOfSize:14];
    ui.stageWidth = [ui.stage.widthAnchor constraintEqualToConstant:480];
    ui.stageWidth.active = YES;
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
    ui.retry = [HBLauncherButton buttonWithTitle:@"Повторить" target:ui action:@selector(retry:)];
    ui.retry.bezelStyle = NSBezelStyleRounded;
    ui.retry.hidden = YES;
    NSButton *cancel = [HBLauncherButton buttonWithTitle:@"Отмена" target:ui action:@selector(cancel:)];
    ui.cancelButton = cancel;
    cancel.bezelStyle = NSBezelStyleRounded;
    [buttons addArrangedSubview:ui.retry];
    [buttons addArrangedSubview:cancel];
    ui.errorButtonConstraints = @[
        [ui.retry.widthAnchor constraintEqualToConstant:116],
        [ui.retry.heightAnchor constraintEqualToConstant:32],
        [cancel.widthAnchor constraintEqualToConstant:80],
        [cancel.heightAnchor constraintEqualToConstant:32]
    ];
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
    NSString *message = [NSString stringWithUTF8String:stage];
    NSRange separator = [message rangeOfString:@"\n"];
    ui.title.stringValue = failed ? (separator.location == NSNotFound ? @"Не удалось подготовить приложение" :
        [message substringToIndex:separator.location]) : @"Heartbeat";
    ui.stage.stringValue = failed && separator.location != NSNotFound ?
        [message substringFromIndex:separator.location + 1] : message;
    ui.title.font = [NSFont systemFontOfSize:failed ? 14 : 32 weight:NSFontWeightSemibold];
    ui.stageWidth.constant = failed ? 420 : 480;
    ui.stage.textColor = failed ? hb_theme_color(0x616162, 0xb6b6b8) : [NSColor labelColor];
    ui.retry.bordered = !failed;
    ui.cancelButton.bordered = !failed;
    ((HBLauncherButton *)ui.retry).errorPrimary = failed;
    [NSLayoutConstraint deactivateConstraints:ui.errorButtonConstraints];
    if (failed) [NSLayoutConstraint activateConstraints:ui.errorButtonConstraints];
    NSFont *buttonFont = [NSFont systemFontOfSize:13 weight:NSFontWeightMedium];
    ui.retry.attributedTitle = [[NSAttributedString alloc] initWithString:@"Повторить" attributes:@{
        NSForegroundColorAttributeName: failed ? [NSColor blackColor] : [NSColor labelColor], NSFontAttributeName: buttonFont
    }];
    ui.cancelButton.attributedTitle = [[NSAttributedString alloc] initWithString:@"Отмена" attributes:@{
        NSForegroundColorAttributeName: failed ? hb_theme_color(0x242426, 0xeeeef0) : [NSColor labelColor],
        NSFontAttributeName: buttonFont
    }];
    ui.retry.needsDisplay = YES;
    ui.cancelButton.needsDisplay = YES;
    ui.offline.hidden = !failed;
    ui.icon.hidden = failed;
    ui.bytes.hidden = failed;
    [ui.panel setCustomSpacing:failed ? 40 : 20 afterView:ui.offline];
    [ui.panel setCustomSpacing:failed ? 24 : 20 afterView:ui.title];
    [ui.panel setCustomSpacing:failed ? 16 : 20 afterView:ui.stage];
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
