---
name: design-system
description: "Работа с дизайн-системой Heartbeat (плотный плоский стиль AI Studio) — токены и их пресеты по хосту, HbTheme, новые Hb*-компоненты с реализациями Material (Android/iOS), Fluent (Windows) и macOS 26 UI (macOS), выбор кита в рантайме. Используй при создании/изменении UI-компонентов, экранов, тем и цветов."
---

# Дизайн-система Heartbeat

> Единственный визуальный стиль — **плотный плоский стиль AI Studio** (`HbVisualStyle.Flat`). Эталон — экран студии и режим
> «Исследование» (`features/ai-studio/impl/**/ui`, `features/research-chat/impl/**/ui`). Glass, blur, неоморфные тени и
> декоративные градиенты удалены. Принципы эталона (окно edge-to-edge, плотность desktop, типографика 13/12/14sp, тихие
> состояния, гаснущий текст, режимы вместо экранов) — в `.claude/rules/design-system.md`; соблюдай их в каждом экране.
> Примеры кода ниже показывают устройство темы и китов; актуальные значения — только в KDoc `design-system/tokens`.

## Порядок работы над экраном
1. Каркас: хост рисует сайдбар/шапку/titlebar; фича — только содержимое. Контент — колонка ≤ `HbTheme.dimensions.messageMaxWidth`
   (настройки — `settingsMaxWidth`), фон — `HbTheme.surfaces.backdrop`, без карточек вокруг экрана.
2. Списки и настройки — `HbSettingsSection` + `HbSettingsRow` (или `HbNavigationItem` в сайдбаре), разделители `HbDivider`.
3. Действия — `HbButton` (`Primary` один на регион, `Secondary`/`Ghost`, `Danger` для деструктивного) размеров `Regular`/`Small`.
4. Состояния — `HbLoadingState`, `HbEmptyState`, `HbBanner` (ошибка + «Повторить»); модальное — `HbDialog`.
5. Однострочный текст — `HbText(maxLines = 1)` (гаснет к краю, тултип); `TextOverflow.Ellipsis` не использовать.
6. Проверка: превью light/dark, скриншот-тест desktop 1280 и компактной ширины ~420dp в обеих темах, фокус по Tab.

## Пресеты токенов

`HbTheme` выбирает по хосту один пресет каждого набора: `HbDimensions.Mobile/Desktop/DesktopMacOs`,
`HbColors.forHost(isDark, isDesktop)`, `HbSurfaceColors.forHost(...)`, `HbTypography.Mobile/Desktop` (со шрифтом хоста),
`HbShapes.Mobile/Desktop`. Для превью мобильной плотности на desktop: `HbTheme(dimensions = HbDimensions.Mobile) { … }`.

## Токены (`design-system:tokens`) — единственное место с hex

```kotlin
package io.aequicor.heartbeat.ds.tokens

@Immutable
data class HbColors(
    val brand: Color,
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val error: Color,
    val success: Color,
    val warning: Color,
    val textPrimary: Color,
    val dataViolet: Color,
    val dataCyan: Color,
    val isDark: Boolean,
) {
    companion object {
        val Light = HbColors(
            brand = Color(0xFF3A5AFF), primary = Color(0xFF0040FF), secondary = Color(0xFFFF5C39),
            background = Color(0xFFFFFFFF), surface = Color(0xFFF2F2F7),
            error = Color(0xFFFF3B30), success = Color(0xFF34C759), warning = Color(0xFFFFCC00),
            textPrimary = Color(0xFF000000), dataViolet = Color(0xFFBF40FF), dataCyan = Color(0xFF00A0FF),
            isDark = false,
        )
        val Dark = HbColors(
            brand = Color(0xFF5E7CFF), primary = Color(0xFF4D6AFF), secondary = Color(0xFFFF7A5C),
            background = Color(0xFF000000), surface = Color(0xFF1C1C1E),
            error = Color(0xFFFF453A), success = Color(0xFF30D158), warning = Color(0xFFFFD60A),
            textPrimary = Color(0xFFFFFFFF), dataViolet = Color(0xFFD56CFF), dataCyan = Color(0xFF00C8FF),
            isDark = true,
        )
    }
}

@Immutable
data class HbGradients(val primaryAccent: Brush, val cosmicDeep: Brush) {
    companion object {
        val Light = HbGradients(
            primaryAccent = Brush.verticalGradient(listOf(Color(0xFF4D6AFF), Color(0xFF0040FF))),
            cosmicDeep = Brush.verticalGradient(listOf(Color(0xFF3A5AFF), Color(0xFF001A78))),
        )
        val Dark = HbGradients(
            primaryAccent = Brush.verticalGradient(listOf(Color(0xFF5E7CFF), Color(0xFF3A5AFF))),
            cosmicDeep = Brush.verticalGradient(listOf(Color(0xFF7B9CFF), Color(0xFF3A5AFF))),
        )
    }
}
```

Новый цвет → сначала токен здесь (обе темы) с KDoc о назначении. Производные (`onPrimary`, `textSecondary`, `outline`) вычисляются из базовых и проверяются на контраст ≥ 4.5:1.

`HbSpacing` (компактная шкала: `xxs=2, xs=4, s=6, m=8, l=12, xl=16, xxl=20 dp`), `HbTypography`, `HbShapes` — рядом, по тому же принципу.

## Тема (`design-system:theme`)

```kotlin
object HbTheme {
    val colors: HbColors @Composable @ReadOnlyComposable get() = LocalHbColors.current
    val gradients: HbGradients @Composable @ReadOnlyComposable get() = LocalHbGradients.current
    val typography: HbTypography @Composable @ReadOnlyComposable get() = LocalHbTypography.current
    val spacing: HbSpacing @Composable @ReadOnlyComposable get() = LocalHbSpacing.current
}

@Composable
fun HbTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    platformUi: PlatformUi = LocalPlatformUi.current,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) HbColors.Dark else HbColors.Light
    CompositionLocalProvider(
        LocalHbColors provides colors,
        LocalHbGradients provides if (darkTheme) HbGradients.Dark else HbGradients.Light,
        LocalPlatformUi provides platformUi,
    ) {
        platformUi.Theme(colors) { content() }   // MaterialTheme / FluentTheme / MacosTheme, accent = colors.brand
    }
}
```

## Адаптивные компоненты (`design-system:adaptive` + `components`)

```kotlin
// design-system:adaptive (commonMain)
enum class PlatformUi { Material, Fluent, MacOs }
val LocalPlatformUi = staticCompositionLocalOf { PlatformUi.Material }

// jvmMain — выбор в рантайме, вызывается из platform-main:desktop
fun detectDesktopPlatformUi(): PlatformUi {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.startsWith("windows") -> PlatformUi.Fluent
        os.startsWith("mac") -> PlatformUi.MacOs
        else -> PlatformUi.Material
    }
}

// design-system:components — публичный API для фич
@Composable
fun HbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: HbButtonStyle = HbButtonStyle.Primary,
    enabled: Boolean = true,
) {
    when (LocalPlatformUi.current) {
        PlatformUi.Material -> MaterialHbButton(text, onClick, modifier, style, enabled)
        PlatformUi.Fluent -> FluentHbButton(text, onClick, modifier, style, enabled)   // jvmMain
        PlatformUi.MacOs -> MacOsHbButton(text, onClick, modifier, style, enabled)     // jvmMain
    }
}
```

Fluent/macOS-реализации лежат в `jvmMain` (киты — desktop-only). В `commonMain` объяви `internal expect`-диспетчер или передавай реализации через `PlatformUi`-интерфейс с композиционными слотами, чтобы Android/iOS не тянули desktop-киты.

## Чек-лист нового компонента

1. API в `components`: stateless, `modifier` первым опциональным, события `onX`, стабильные параметры.
2. Реализации: material (+ iOS-нюансы), fluent, macos — или явный fallback на material с `// TODO(issue)`.
3. Только токены `HbTheme`; стиль — плотный плоский (без glass/blur/теней, кроме всплывающих поверхностей).
4. `@Preview` light/dark; запись в `design-system:catalog` со всеми состояниями (hover, pressed, selected, disabled, фокус, ошибка, длинный русский текст).
5. Доступность: семантика, `contentDescription`, фокус/клавиатура на desktop.
6. Проверка субагентом `ui-reviewer`.
7. На каждой горизонтальной/вертикальной scrollable-поверхности — общий auto-hide scrollbar из `layouts`, включая поля ввода. Для lazy — нормализованный thumb без оценки средней высоты видимых строк; для sticky host — одна внешняя полоса вне fade-слоя. Проверяй hover, wheel, drag/release, RTL/reverse и края диапазона.

Версии/API китов (compose-fluent-ui, compose-macos-26-ui) — `gradle/libs.versions.toml` и документация китов.
