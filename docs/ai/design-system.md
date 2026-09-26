# Дизайн-система Mission

Источник: https://github.com/aequicor/aequicor.github.io/blob/main/article/palette/index.html
Центральный цвет — лазурит Mission. Принципы: **функциональность** (контраст, доступность), **эмоциональность** (тёплое, не давящее пространство), **технологичность** (современные, не кричащие оттенки). Все цвета имеют пару light/dark.

## Цветовые токены

| Токен (`HbColors`) | Назначение | Light | Dark |
|---|---|---|---|
| `brand` | Лазурит, бренд | `#3A5AFF` | `#5E7CFF` |
| `primary` | Основные действия | `#0040FF` | `#4D6AFF` |
| `secondary` | Коралл, вторичный акцент | `#FF5C39` | `#FF7A5C` |
| `background` | Фон | `#FFFFFF` | `#000000` |
| `surface` | Поверхности, карточки | `#F2F2F7` | `#1C1C1E` |
| `error` | Ошибка, опасность | `#FF3B30` | `#FF453A` |
| `success` | Успех | `#34C759` | `#30D158` |
| `warning` | Предупреждение | `#FFCC00` | `#FFD60A` |
| `textPrimary` | Основной текст | `#000000` | `#FFFFFF` |
| `dataViolet` | Графики, теги | `#BF40FF` | `#D56CFF` |
| `dataCyan` | Графики, информация | `#00A0FF` | `#00C8FF` |

### Градиенты (`HbGradients`, вертикальные 180°)

| Токен | Light | Dark |
|---|---|---|
| `primaryAccent` | `#4D6AFF → #0040FF` | `#5E7CFF → #3A5AFF` |
| `cosmicDeep` | `#3A5AFF → #001A78` | `#7B9CFF → #3A5AFF` |

### Производные токены

Палитра не задаёт `onPrimary`, `textSecondary`, `outline`, `surfaceVariant` и т.п. Их **выводят** в `design-system:tokens` (альфа от `textPrimary`/`brand`), фиксируют в ADR и проверяют контраст (WCAG AA ≥ 4.5:1 для текста). Не придумывай новые hex в фичах — добавляй токен в `design-system:tokens`.

## Модули

```
design-system/
  tokens/      HbColors (light/dark), HbGradients, HbTypography, HbSpacing, HbShapes, HbElevation — чистые данные
  theme/       HbTheme { } — CompositionLocal-провайдеры токенов, dark/light, маппинг в MaterialTheme
  components/  публичный API компонентов: HbButton, HbTextField, HbCard, HbScaffold, HbChatBubble…
  adaptive/    LocalPlatformUi + реализации: material (android/ios), fluent (windows), macos (macOS)
  catalog/     (опц.) экран-каталог компонентов для визуальной проверки и превью
```

## Платформенные киты

| Платформа | Реализация `Hb*` | Библиотека |
|---|---|---|
| Android, iOS | Material 3, перекрашенный токенами | `org.jetbrains.compose.material3` |
| Windows | Fluent Design | https://github.com/compose-fluent/compose-fluent-ui |
| macOS | macOS 26 (Liquid Glass) | https://github.com/NucleusFramework/compose-macos-26-ui |

- Desktop — один JVM-таргет, поэтому кит выбирается **в рантайме** (`os.name`) и прокидывается через `LocalPlatformUi`.
- Токены Mission мапятся на цветовые схемы Fluent/macOS (accent = `brand`), а не наоборот.
- Фичи не импортируют классы китов напрямую — только `Hb*`.

## Правила для фич

- Только `HbTheme.colors.*`, `HbTheme.typography.*`, `HbTheme.spacing.*`.
- Никаких `Color(0x…)`, `Color.Red`, литералов `16.dp`/`14.sp` для стилей (размеры layout'а через `HbTheme.spacing`).
- Любой `@Composable` экран принимает `modifier: Modifier = Modifier` первым опциональным параметром (compose-rules).
- Превью — в `impl` (`@Preview` с `HbTheme` в light и dark).
- Строки — только из Compose Resources (`core:resources` или ресурсы модуля фичи), не хардкод.
