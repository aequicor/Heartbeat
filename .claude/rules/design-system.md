---
paths:
  - "design-system/**"
  - "features/*/impl/**/ui/**"
  - "**/*Screen.kt"
  - "**/*Content.kt"
---

# Дизайн-система Aequicor Glass UI и Compose-UI

Источник истины по токенам — `docs/ai/design-system.md` и `design-system/tokens`.
Актуальный стиль по запросу пользователя — Glass (ADR-0011), с пастельной палитрой ADR-0009.

- Hex-литералы допустимы **только** в `design-system/tokens`. Везде ещё — `HbTheme.colors.*`.
- Типографика, отступы, формы, elevation и тени — `HbTheme.typography/spacing/shapes/elevation/shadows`.
- Основной стиль — `HbVisualStyle.Glass` на Compose Foundation; `HbGlassPanel` для blur-перекрытий. `Platform` выбирает нативные адаптеры.
- Фичи используют только `Hb*`-компоненты из `design-system:components`. Прямые импорты `io.github.composefluent.*`, macOS-кита (`dev.nucleusframework.*`) или `androidx.compose.material3.*` в фичах запрещены — только в `design-system:adaptive`.
- Компонент ДС: stateless, `modifier: Modifier = Modifier` первый опциональный параметр, события — лямбды `onX`, параметры стабильны (`@Immutable`/`@Stable` модели).
- Каждый новый компонент: реализации для material / fluent / macos (или явный fallback на material + TODO с issue), превью light/dark, запись в каталог.
- compose-rules: без `remember` в невалидных местах, без побочных эффектов в composition, `LaunchedEffect` с корректными ключами, `CompositionLocal` — только в ДС.
- Доступность: `contentDescription` для иконок-действий, минимальная зона касания 48dp (моб.), контраст текста ≥ 4.5:1.
- Производительность — скилл `compose-optimization` при подозрении на лишние рекомпозиции.
- Каждая горизонтальная и вертикальная прокручиваемая поверхность имеет auto-hide scrollbar: `hbHorizontalScroll` / `hbVerticalScroll`, `HbLazyColumn` / `HbLazyRow` или `hbScrollbars` на уже существующем scroll state. Он виден при прокрутке и наведении на область полосы, скрыт в покое. Не добавляй второй scrollable-контейнер ради индикатора. Lazy-полоса использует логическую нормализацию без средней высоты видимых строк (ADR-0012). В sticky host полоса одна и находится вне fade-слоя.
