# ADR-0003: Навигация — маршруты, дерево хостов, результаты, deep links

- Статус: принято
- Дата: 2026-09-26
- Затрагивает: `core:navigation:{api,impl,compose}`, `core:di:api` (`@ForScope` + `TYPE`), `platform-main:di-bundle`,
  `build-logic` (`heartbeat.kmp.compose`), все фичи

## Контекст

Нужна навигация на Decompose, в которой:

- фичи открывают друг друга, не зная `impl` и не проходя через корень;
- фича может содержать вложенные фичи и собственный стек; на планшетах и десктопе — список и детали рядом;
- данные передаются вперёд (аргументы) и назад (результат), в том числе после смерти процесса;
- переходы анимируются, включая «раскрытие экрана из превью» (container transform) и predictive back;
- интерфейс навигатора лежит в `api`, реализация — в `impl`, который видит только `di-bundle` (ADR-0002);
- deep links работают с первой версии, в том числе до входа в профиль.

## Решение

### Модули

| Модуль | Содержимое | Кто зависит |
|---|---|---|
| `core:navigation:api` | `Route`, `RouteEntry`, `Navigator`, `NavOptions` (`LaunchMode`, `NavTarget`, `NavTransition`), `ResultContract`, хосты `StackHost` / `PanelsHost` / `RootHost`, `NavHostFactory`, `RootNavHostFactory`, `DeepLinkEntry`, алиасы `*RouteBinding` / `*DeepLinkBinding`. Без Compose | `features:*:api`, `features:*:impl` |
| `core:navigation:impl` | хосты на `childStack` / `childPanels`, реестры маршрутов, сериализация стека, `ResultStore`, `DeepLinkRouter`, логи `NAV` | только `di-bundle` |
| `core:navigation:compose` | `ComposableComponent`, `NavStack`, `NavPanels`, `NavAnimations`, `NavSharedTransitionLayout`, `NavSharedBounds` | `features:*:impl`, `platform-main` |

### Маршруты и реестр

- `Route` — `@Serializable` с `@SerialName` (стабильный id типа). Публичные маршруты лежат в `api` фичи, внутренние — в `impl`.
- `RouteEntry<R>` создаёт компонент (`NavComponent`) по маршруту. Фичи регистрируют его в одном из **двух реестров**:
  гостевом `@ContributesIntoSet(AppScope::class, binding = binding<AppRouteBinding>())` и профильном
  `@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())`.
  Гостевое дерево видит только app-маршруты; профильное — app и profile.
- Реестры квалифицированы `@ForScope`, поэтому корректность не зависит от того, сливает ли Metro set-мультибиндинги
  родителя и `GraphExtension`. Metro игнорирует квалификатор на самом классе при `@ContributesIntoSet`, поэтому
  `@ForScope` получил цель `TYPE`, а в api есть typealias'ы. Контрибуция без квалификатора роняет создание корневой
  фабрики с понятной ошибкой.

### Дерево хостов

```
RootHost (StackHost)                         глобальные маршруты своего реестра
 └─ entry → Component
      ├─ StackHost "name"                    локальные маршруты (+ глобальные по GlobalRoutes)
      └─ PanelsHost "name"                   main + details (childPanels), режим SINGLE/DUAL задаёт UI
```

- Каждая запись получает свой `Navigator`. Запрос разрешается от хоста вызывающего вверх: `Nearest` — первый хост,
  который принимает маршрут (локальные — всегда; глобальные — корень и хосты с `GlobalRoutes.All/Only`), `Root` —
  корень, `Details` — ближайший `PanelsHost`.
- По умолчанию вложенный стек **не** принимает глобальные маршруты: чужая фича открывается над текущей.
- `close()` последней записи вложенного хоста закрывает запись-владельца. «Назад» — стандартные back-handler'ы
  Decompose: сначала срабатывает самый глубокий стек.
- Запись стека сохраняется как `{id, type, route JSON, transition, request}`: сериализатор маршрута ищется в реестре
  по `serialName`, полиморфная регистрация не нужна.

### Данные между фичами

- Вперёд — поля `Route` (идентификаторы, не объекты; без секретов и персональных данных).
- Назад — `ResultContract<R>` в `api` вызываемой фичи: `navigateForResult` → `finishWithResult` → `results(contract)`.
  Ожидающие результаты адресуются путём записи (`root/<id>/<host>/<id>`), хранятся в `ResultStore` корня
  (InstanceKeeper + StateKeeper) и переживают смерть процесса. Результат выдаётся один раз и удаляется, когда запись
  уничтожена окончательно.
- Бизнес-события — по-прежнему `MachineRegistry[Key].send(Event.Public)`. Общий живой объект — `SharedScopes` (ADR-0002).

### Анимации

- `NavTransition` (`Default`, `Fade`, `Modal`, `None`, `Expand(sharedKey)`) хранится в записи, поэтому pop проигрывает
  обратную анимацию того же перехода.
- `NavStack` / `NavPanels` построены на `extensions-compose-experimental` Decompose: контент получает
  `StackAnimationScope : AnimatedVisibilityScope`, так что shared elements Compose и predictive back работают вместе.
- «Раскрытие из превью»: источник оборачивает карточку в `NavSharedBounds(key)`, переход открывается с
  `NavTransition.Expand(key)`. Хост сам оборачивает экран назначения в `sharedBounds`, и фича назначения ничего
  не знает об анимации. Для превью внутри вложенного хоста берётся тот скоуп записи, чей переход сейчас идёт.

### Deep links

- `DeepLinkEntry(pattern)` в `impl` фичи, в тех же двух реестрах (`AppDeepLinkBinding` / `ProfileDeepLinkBinding`).
  Шаблон — сегменты и `{param}`; побеждает шаблон с большим числом литералов; одинаковая форма шаблонов — ошибка.
- Ссылка превращается в список `NavCommand`. Каждая команда отправляется навигатору самой глубокой активной записи
  после предыдущей: Decompose создаёт детей синхронно, поэтому команды попадают во вложенные хосты, созданные предыдущими.
- `RootHost.handleDeepLink` → `Handled` / `NoMatch` / `Rejected`. `NoMatch` в гостевом дереве — сигнал корню платформы
  отложить ссылку до входа в профиль.
- Разрешённые схемы и web-хосты задаёт `DeepLinkConfig` (по умолчанию только `heartbeat://`). Ссылка — недоверенный
  ввод: только навигация, параметры валидируются, в лог попадает шаблон, а не ссылка.

### Рендер

Компонент из `impl` реализует `ComposableComponent` (`@Composable Content(modifier)`, делегирует экрану из `ui/`).
Реестр рендереров не нужен.

## Альтернативы

| Вариант | Плюсы | Минусы | Почему нет |
|---|---|---|---|
| `XEntryPoint` с колбэками в api каждой фичи | явный граф зависимостей | корень пробрасывает все переходы; нет единой модели результатов и deep links | масштабируется хуже реестра |
| Реестр рендереров `@ContributesIntoMap @ClassKey` | UI полностью отделён от компонента | реестр в `CompositionLocal`, ошибки только в рантайме | `ComposableComponent` проще, компонент всё равно в `impl` |
| Колбэки вместо `ResultContract` | типобезопасно в пределах фичи | не переживают смерть процесса, не проходят через реестр | нужна межфичевая передача |
| Один реестр с проверкой логина в хосте | меньше биндингов | маршруты профиля без профильного графа | фичи живут в `ProfileScope` (ADR-0002) |
| Свой `AnimatedContent` вместо experimental API Decompose | нет experimental API | теряется predictive back Decompose, дублирование логики стека | принят риск experimental (`childPanels`, `ChildStack` из `extensions-compose-experimental`) |

## Последствия

- Фича открывается одной строкой `navigator.navigate(FeatureRoute(id))` из любого места; корень не меняется при добавлении фич.
- API `childPanels` и experimental Compose-расширений Decompose может измениться: `@OptIn` сосредоточены в
  `core:navigation:impl` и `core:navigation:compose`, при обновлении Decompose проверить эти модули.
- Корень платформы: `HeartbeatRoot` (`platform-main:di-bundle`) — `childSlot` по `ProfileSessions.active` → гостевой или
  профильный `RootHost`, «loading» до восстановления профиля, отложенные deep links (переживают смерть процесса);
  `RootContent` (`platform-main:root`) — Compose с `NavSharedTransitionLayout`. Компонент живёт в `di-bundle`, потому что
  только там его можно проверить на настоящем Metro-графе.
- Обновлены: скиллы `navigation`, `new-feature`, `di-metro`; правила `feature-api`, `feature-impl`; `architecture.md`,
  `feature-contract.md`, `tech-stack.md`, `CLAUDE.md`.
