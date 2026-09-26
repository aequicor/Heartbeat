---
paths:
  - "design-system/**"
  - "features/*/impl/**/ui/**"
  - "**/*Screen.kt"
  - "**/*Content.kt"
---

# Дизайн-система Mission и Compose-UI

Источник истины по токенам — `docs/ai/design-system.md` (палитра Mission: brand `#3A5AFF`/`#5E7CFF`, primary `#0040FF`/`#4D6AFF`, secondary `#FF5C39`/`#FF7A5C` …).

- Hex-литералы допустимы **только** в `design-system/tokens`. Везде ещё — `HbTheme.colors.*`.
- Типографика, отступы, формы, elevation — `HbTheme.typography/spacing/shapes/elevation`.
- Фичи используют только `Hb*`-компоненты из `design-system:components`. Прямые импорты `io.github.composefluent.*`, macOS-кита (`dev.nucleusframework.*`) или `androidx.compose.material3.*` в фичах запрещены — только в `design-system:adaptive`.
- Компонент ДС: stateless, `modifier: Modifier = Modifier` первый опциональный параметр, события — лямбды `onX`, параметры стабильны (`@Immutable`/`@Stable` модели).
- Каждый новый компонент: реализации для material / fluent / macos (или явный fallback на material + TODO с issue), превью light/dark, запись в каталог.
- compose-rules: без `remember` в невалидных местах, без побочных эффектов в composition, `LaunchedEffect` с корректными ключами, `CompositionLocal` — только в ДС.
- Доступность: `contentDescription` для иконок-действий, минимальная зона касания 48dp (моб.), контраст текста ≥ 4.5:1.
- Производительность — скилл `compose-optimization` при подозрении на лишние рекомпозиции.
