# ADR-0002: Иерархия DI-скоупов, di-bundle и восстановление после смерти процесса

- Статус: принято
- Дата: 2026-09-26
- Затрагивает: `core:di:{api,ext,impl}`, `core:profile-facade:{api,impl}`, `platform-main:di-bundle`, `build-logic`, все фичи

## Контекст

Нужны связанные скоупы (app → profile → feature → screen), восстановление графа после смерти процесса,
простое описание биндингов в фича-модуле и хелперы для компонентов Decompose / сторов FlowMVI,
привязанных к скоупу. Ограничения Metro (проверено на 1.4.5 + Kotlin 2.4.20):

- контрибуции собираются там, где компилируется `@DependencyGraph`; расширения графа (`@GraphExtension`)
  генерируются там же, вместе с родителем;
- если контрибуции есть в платформенных source set'ах, `@DependencyGraph` должен быть объявлен в платформенном
  source set'е (иначе граф не видит `androidMain`/`jvmMain`/`iosMain`-контрибуции);
- `internal`-реализации между модулями работают только с `generateContributionProviders`;
  на binding container'ы (`@BindingContainer`) эта опция не распространяется — они `public`.

## Решение

### Скоупы

```
AppScope (Metro)          HeartbeatGraph         platform-main:di-bundle, per-platform @DependencyGraph
└─ ProfileScope           ProfileGraph           core:profile-facade:api, фабрика контрибутится в AppScope
   ├─ <Feature>Scope      <Feature>Graph         features/<x>/impl, фабрика контрибутится в ProfileScope
   │  └─ screen           без графа              retainedScope(): ScopeHandle в InstanceKeeper компонента
   └─ shared:<key>        без графа              SharedScopes: ref-counted объект по SharedKey
```

- Каждый уровень имеет `ScopeHandle` (`@ForScope(<Scope>::class)`): имя-путь (`app/profile/chat`),
  `CoroutineScope` на `SupervisorJob` родителя, `ScopeSavedState`, `onClose`.
- Корутины скоупа — на main-диспетчере (как `viewModelScope`): `ScopeSavedState` и `SharedScopes` main-only.
- Закрытие синхронное: отмена корутин (без ожидания) → close-действия в обратном порядке. Дочерний скоуп —
  close-действие родителя, зарегистрированное при создании, поэтому закрытие профиля каскадно закрывает фичи и
  shared-объекты (соседи — от младшего к старшему).
- `ProfileSessions.open/close` можно вызывать из корутины самого профиля: сначала запись в хранилище, затем
  синхронное переключение без точек приостановки.
- У каждой фичи свой маркер скоупа (общий `FeatureScope` смешал бы контрибуции разных фич).
- Сессии профиля — `core:profile-facade`: один активный профиль, id хранится через `ActiveProfileStorage`
  (по умолчанию in-memory с `priority = Int.MIN_VALUE`; постоянная реализация перекрывает её приоритетом).

### Восстановление после смерти процесса

Граф — чистая функция от родителя и аргументов; сериализуется не граф, а состояние:

| Уровень | Откуда аргументы после смерти процесса |
|---|---|
| Profile | `ProfileSessions.restore()` читает `ActiveProfileStorage` (граф новый; `savedState` app/profile/shared-скоупов не сохраняется) |
| Feature / screen | конфиг Decompose-стека + `SavedBundle` скоупа в `StateKeeper` компонента |

`retainedGraph`/`retainedScope` (`core:di:ext`) кладут скоуп в `InstanceKeeper` и регистрируют снапшот
`ScopeSavedState` в `StateKeeper` **на каждом экземпляре компонента** — иначе изменения после смены
конфигурации теряются при следующей смерти процесса (покрыто тестом в `di-bundle`).

### Модули и границы

| Модуль | Содержимое | Кто зависит |
|---|---|---|
| `core:di:api` (Metro, kotlinx-serialization) | `ProfileScope`, `ForScope`, `ScopeHandle`/`OwnedScope`, `ScopeSavedState`/`SavedBundle`, `ScopeFactory`, `SharedKey`/`SharedFactory`/`SharedScopes`/`Lease` | все |
| `core:di:ext` | `retainedGraph`, `retainedScope`, `retainedShared` (Essenty) | impl фич, platform-main |
| `core:di:impl` | реализация скоупов, корневой app-скоуп, shared-скоупы, binding container'ы | только di-bundle |
| `core:profile-facade:api` | `ProfileSessions`, `ProfileSession`, `ProfileId`, `ActiveProfileStorage`, `ProfileGraph` | все |
| `core:profile-facade:impl` | `ProfileSessionsImpl`, in-memory `ActiveProfileStorage` | только di-bundle |
| `platform-main:di-bundle` | `HeartbeatGraph` (common) + `Android/Jvm/IosHeartbeatGraph` | android, desktop, shared |

Проверка «от `…:impl` зависит только `:platform-main:di-bundle`» — `build-logic/.../ModuleBoundaries.kt`, подключается
плагином `heartbeat.detekt`, который есть у каждого модуля, включая приложения (падает конфигурация Gradle).

## Альтернативы

| Вариант | Почему нет |
|---|---|
| Граф в `platform-main:android/desktop/shared` | три копии графа; iOS-зонтик не для этого |
| Граф в `core:di:impl` | не видит impl фич: missing binding или молча пустые мультибиндинги |
| Metro-граф на каждый экран | бойлерплейт без выгоды; жизнь экрана и так задаёт `InstanceKeeper` |
| Экстеншены в `core:di:api` | api тянул бы Essenty (и FlowMVI) во все модули, которым нужны только скоупы |
| `ScopeHandle` в собственном `ComponentContext` | меньше параметров, но все `childStack` через свою фабрику контекстов; можно добавить позже |

## Последствия

- Фича: маркер скоупа + `@GraphExtension` с фабрикой `@ContributesTo(ProfileScope::class)`; биндинги —
  обычные `@Contributes*`; компонент получает граф через `retainedGraph`.
- ~~Машины фич с пользовательскими данными должны жить в `ProfileScope`~~ — решено в [ADR-0004](0004-state-machine.md):
  машина живёт в скоупе своей фичи (дочернем к профилю), реестр адресует только запущенные машины.
- Постоянный `ActiveProfileStorage` — в `core:datastore` с `priority` выше дефолтного.
- `scopedStore` для FlowMVI — в `core:di:ext` после появления `core:mvi`.
- iOS-тесты (`iosSimulatorArm64Test`) требуют установленного Xcode.
