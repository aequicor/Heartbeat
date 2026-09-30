---
paths:
  - "design-system/**"
  - "features/**/impl/**/ui/**"
  - "**/*Screen.kt"
  - "**/*Content.kt"
---

# Дизайн-система Heartbeat: плотный стиль студии и Compose-UI

Источник истины по токенам — `design-system/tokens` и их KDoc. Единственный стиль — плотный плоский стиль AI Studio
(`HbVisualStyle.Flat`). Glass, blur-подложки, неоморфные тени и декоративные градиенты удалены и не возвращаются.
Эталон — экран студии и режим «Исследование» (`features/ai-studio/impl/**/ui`, `features/research-chat/impl/**/ui`).

## Токены и тема
- Один набор токенов с пресетами по хосту: `HbDimensions.Mobile/Desktop/DesktopMacOs`, `HbColors.Light/Dark/DesktopLight/DesktopDark`,
  `HbSurfaceColors` (сайдбар, шапки, поверхности беседы), `HbTypography.Mobile/Desktop`, `HbShapes.Mobile/Desktop`.
  `HbTheme` выбирает пресет по хосту (не по киту). Второй параллельный набор («старый/студийный») не заводить.
- Hex-литералы допустимы **только** в `design-system/tokens`. Везде ещё — `HbTheme.colors/surfaces/typography/spacing/shapes/dimensions`.
  `.dp`/`.sp`-литералы для стилей вне ДС запрещены: новый размер — сначала токен с KDoc.
- `HbVisualStyle.Platform` включает нативные адаптеры китов (Material на Android/iOS, Fluent на Windows, macOS) — только для превью.

## Принципы эталона (обязательны для каждого экрана)
- **Окно edge-to-edge.** Слева одна цельная поверхность-сайдбар (`HbTheme.surfaces.sidebar`), справа контент. Никаких плавающих
  карточек вокруг экрана, пустых рамок, стеклянных подложек и декоративных градиентов. На macOS прозрачный titlebar: светофор
  поверх сайдбара, окно тянется за шапку (`HbWindowDragArea`), отступ под светофор — `dimensions.titlebarLeadingInset`/`titlebarInset`.
- **Плотность desktop.** Шапка `headerHeight` (44dp), строки списков `navigationRowHeight` (28dp), контролы 28–32dp
  (`HbButtonSize.Small/Regular`), колонка контента ≤ `messageMaxWidth` (760dp) по центру, раздел настроек ≤ `settingsMaxWidth`,
  инспектор — `inspectorPanelWidth`. На мобильных — touch-размеры ≥ 44–48dp через пресет `Mobile` (не уменьшать хит-зоны).
- **Типографика.** Desktop: UI 13sp, вторичный 12sp `textSecondary`, чтение 14sp, заголовок шапки 13–14sp semibold, display 22–24sp.
  Веса только 400/500/600, текст не мельче 12sp. Иерархия — вес и цвет, а не размер.
- **Состояния.** Hover, pressed, selected — тихие заливки без рамок. Кольцо фокуса — только при клавиатурной навигации, нативное
  для кита (`hbFocusOutline`/`AdaptiveFocus`); поля ввода плоские, без обводки и теней, их активный фокус (и клавиатурный, и от
  указателя) — акцентный контур по скруглённой кромке поля вне macOS, выделение текста — `colors.selectionHighlight`.
- **Текст.** Однострочный текст не обрезается многоточием, а гаснет к краю (`HbText` с `maxLines = 1`, тултип с полным текстом
  при обрезке). `TextOverflow.Ellipsis` для однострочного текста запрещён. Действия строки (архив, «⋯») появляются при наведении.
- **Режимы, а не новые экраны.** Смена режима меняет вёрстку области контента, а не открывает экран поверх всего с «назад».
  Каркас окна (сайдбар, шапка, titlebar) принадлежит хосту; фича рисует только содержимое. Поверх всего — только `HbDialog`.
- **Esc.** Модальные поверхности (`HbDialog`) перехватывают Esc через `onPreviewKeyEvent`; хосты окна (настройки)
  слушают `onKeyEvent`, чтобы вложенный поток с фокусом (визард) обработал Esc первым как свой «назад».
- **Поверхности.** `hbSurface` — непрозрачная заливка и hairline. Единственное исключение с тенью — всплывающие поверхности
  (`hbPopupSurface`: меню, диалоги); blur не используется.

## Компоненты
- Фичи используют только `Hb*`-компоненты из `design-system:components`. Прямые импорты `io.github.composefluent.*`,
  macOS-кита (`dev.nucleusframework.*`) или `androidx.compose.material3.*` в фичах запрещены — только в `design-system:adaptive`.
- Готовые блоки: `HbButton` (Primary/Secondary/Ghost/Danger × Regular/Small), `HbIconButton`, `HbTextField`/`HbSearchField`,
  `HbSwitch`, `HbChip`, `HbMenu`, `HbNavigationItem`/`HbNavigationHeader`, `HbSettingsSection`/`HbSettingsRow` (строка настроек),
  `HbDialog` (диалог/шторка, Esc закрывает), `HbBanner` (ошибки), `HbEmptyState`, `HbLoadingState`, `HbBadge`, `HbTooltip`.
- Компонент ДС: stateless, `modifier: Modifier = Modifier` первый опциональный параметр, события — лямбды `onX`, параметры стабильны.
- Каждый новый компонент: реализации для material / fluent / macos (или общий Foundation-вариант с объяснением в KDoc), превью
  light/dark, запись в каталог со всеми состояниями (hover, pressed, selected, disabled, фокус по Tab, ошибка, длинный русский текст).
- compose-rules: без `remember` в невалидных местах, без побочных эффектов в composition, `LaunchedEffect` с корректными ключами,
  `CompositionLocal` — только в ДС.
- Доступность: `contentDescription` для иконок-действий, контраст текста ≥ 4.5:1 (тесты контраста в `tokens`).
- Производительность — скилл `compose-optimization` при подозрении на лишние рекомпозиции.
- Каждая горизонтальная и вертикальная прокручиваемая поверхность имеет auto-hide scrollbar: `hbHorizontalScroll` / `hbVerticalScroll`,
  `HbLazyColumn` / `HbLazyRow` или `hbScrollbars` на уже существующем scroll state. Он виден при прокрутке и наведении на область
  полосы, скрыт в покое. Не добавляй второй scrollable-контейнер ради индикатора. Lazy-полоса использует логическую нормализацию без
  средней высоты видимых строк. В sticky host полоса одна и находится вне fade-слоя.
