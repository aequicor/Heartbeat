---
name: di-metro
description: "Внедрение зависимостей в Heartbeat на Metro — скоупы app → profile → feature → screen (core:di, core:profile-facade), граф в platform-main:di-bundle (per-platform @DependencyGraph), @GraphExtension фич, @ForScope, retainedGraph/retainedScope/retainedShared (core:di:ext), восстановление после смерти процесса через ScopeSavedState, @ContributesBinding/IntoMap/IntoSet, @BindingContainer, assisted-фабрики компонентов. Используй при добавлении класса в граф, графа фичи, связывании api с impl, ошибках компиляции Metro."
---

# DI (Metro)

Документация: https://zacsweers.github.io/metro/ . Пакет аннотаций — `dev.zacsweers.metro`. Подключение — плагин `dev.zacsweers.metro` (runtime добавляется автоматически), через convention-плагин `heartbeat.metro`.

## Скоупы

Решение и мотивация — [ADR-0002](../../../docs/adr/0002-di-scopes.md).

```
AppScope (Metro)     HeartbeatGraph   :platform-main:di-bundle
└ ProfileScope       ProfileGraph     core:profile-facade:api (маркер ProfileScope — core:di:api)
  ├ <Feature>Scope   <Feature>Graph   features/<x>/impl
  │ └ screen         retainedScope()  без графа
  └ shared:<key>     SharedScopes     ref-counted объект по SharedKey
```

- У каждого уровня — `@ForScope(<Scope>::class) ScopeHandle` (корутины, `savedState`, `onClose`) и, для App/Profile,
  `@ForScope(...) CoroutineScope`. Без квалификатора дочерний граф конфликтовал бы с родительским биндингом.
- Так же квалифицированы хранилища `core:datastore`: `@ForScope(AppScope::class) DataStores` — данные приложения,
  `@ForScope(ProfileScope::class) DataStores` — данные активного профиля, закрываются с ним (скилл `data-storage`).
- Закрытие профиля (логаут/смена) каскадно закрывает фичи и shared-объекты.
- Состояние, которое должно пережить смерть процесса, — в `scope.savedState` (`consume` + `register`), не в полях графа.
- Модули: `core:di:api` (контракты) ← фичи; `core:di:ext` (`retainedGraph`/`retainedScope`/`retainedShared`) ← impl фич;
  `core:di:impl`, `core:profile-facade:impl`, `core:network:impl`, `core:datastore:impl` ← только `di-bundle`.

## Типовые приёмы

```kotlin
// 1. Реализация интерфейса из api/core — в impl, internal
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class ChatRepositoryImpl(
    private val dao: ChatDao,
    private val dispatchers: DispatcherProvider,
) : ChatRepository

// 2. Мультибиндинг в map: shared-объекты профиля (ключ == SharedKey.name)
@ContributesIntoMap(ProfileScope::class)
@StringKey("upload-session")
@Inject
internal class UploadSessionFactory(private val api: UploadApi) : SharedFactory<UploadSession>

// 3. Мультибиндинг в set: тоглы, логирующие плагины, инициализаторы
@ContributesIntoSet(AppScope::class)
@Inject
internal class ChatStartup(/* … */) : AppInitializer

// 4. Провайдеры сторонних типов — public @BindingContainer с @ContributesTo (так сделан core:network:impl).
//    Параметр со значением по умолчанию — опциональная зависимость: берётся из графа, если там есть биндинг.
@ContributesTo(AppScope::class)
@BindingContainer
public object NetworkBindings {
    @Provides @SingleIn(AppScope::class)
    public fun httpClient(
        engine: HttpClientEngine,
        @ForScope(AppScope::class) appScope: ScopeHandle,
        config: NetworkConfig = NetworkConfig(),
    ): HttpClient = createHttpClient(engine, config).also { client -> appScope.onClose(client::close) } // ресурс — закрыть со скоупом
}

// 5. Потребление мультибиндинга
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class SharedScopesImpl(
    private val factories: Map<String, SharedFactory<*>>,
    @ForScope(ProfileScope::class) private val parent: ScopeHandle,
) : SharedScopes

// 6. Одна реализация — два контракта (repeatable @ContributesBinding): так устроен рантайм машин
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<MachineLauncher>())
@ContributesBinding(AppScope::class, binding = binding<MachineRegistry>())
@Inject
internal class MachineRuntime : MachineLauncher, MachineRegistry
```

Машина фичи — в графе фичи, не мультибиндингом (скилл `state-machine`):

```kotlin
@ContributesTo(ChatScope::class)
@BindingContainer
public object ChatMachineBindings {
    @Provides @SingleIn(ChatScope::class)
    public fun machine(
        launcher: MachineLauncher,
        @ForScope(ChatScope::class) scope: ScopeHandle,
        effects: EffectHandler<ChatEffect, ChatIntent>,
    ): Machine<ChatState, ChatIntent, ChatOutput> = launcher.launch(ChatMachineSpec, scope, effects)
}
```

## Граф приложения (`:platform-main:di-bundle`)

Metro собирает контрибуции там, где компилируется `@DependencyGraph`, а при контрибуциях из платформенных source set'ов
граф обязан быть объявлен в платформенном source set'е:

```kotlin
// commonMain — без аннотации: то, что нужно точкам входа
interface HeartbeatGraph {
    val profileSessions: ProfileSessions
}

// androidMain / jvmMain / iosMain — по одному на платформу
@DependencyGraph(AppScope::class)
internal interface JvmHeartbeatGraph : HeartbeatGraph

fun createHeartbeatGraph(): HeartbeatGraph = createGraph<JvmHeartbeatGraph>()

// androidMain — граф получает Context (пути хранилищ, Room): только applicationContext
@DependencyGraph(AppScope::class)
internal interface AndroidHeartbeatGraph : HeartbeatGraph {
    @DependencyGraph.Factory
    fun interface Factory { fun create(@Provides context: Context): AndroidHeartbeatGraph }
}

fun createHeartbeatGraph(context: Context): HeartbeatGraph =
    createGraphFactory<AndroidHeartbeatGraph.Factory>().create(context.applicationContext)
```

`di-bundle` — единственный модуль, который зависит от `…:impl`; фичи графов не создают. Проверка всего графа без сборки
приложения: `./gradlew :platform-main:di-bundle:compileKotlinJvm` (+ `jvmTest` — интеграционные тесты скоупов).

## Граф фичи (graph extension)

```kotlin
// features/chat/impl
abstract class ChatScope private constructor()

@GraphExtension(ChatScope::class)
interface ChatGraph {
    val rootFactory: ChatRootComponent.Factory

    @ContributesTo(ProfileScope::class)          // генерируется вместе с ProfileGraph в di-bundle
    @GraphExtension.Factory
    fun interface Factory {
        fun create(@Provides route: ChatRoute, @Provides @ForScope(ChatScope::class) scope: ScopeHandle): ChatGraph
    }
}

@SingleIn(ChatScope::class)
@ContributesBinding(ChatScope::class)
@Inject
internal class ChatRepositoryImpl(
    private val api: ChatApi,                                         // из ProfileScope
    @ForScope(ChatScope::class) private val scope: ScopeHandle,
) : ChatRepository

// Маршрут фичи (скилл navigation): граф удерживается компонентом (поворот — тот же, смерть процесса — пересоздан, destroy — закрыт)
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ChatRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: ChatGraph.Factory,
) : RouteEntry<ChatRoute>(ChatRoute::class, ChatRoute.serializer()) {
    override fun create(route: ChatRoute, context: ComponentContext, navigator: Navigator) =
        context.retainedGraph(scopes, profile, name = "chat") { scope -> graphs.create(route, scope) }
            .rootFactory.create(context, navigator)
}
```

- Один маркер скоупа на фичу; общий `FeatureScope` смешал бы контрибуции разных фич.
- Экран без своего графа: `ctx.retainedScope(scopes, featureScope, name = "screen")` → передай в assisted-фабрику стора.
- Общий объект нескольких фич: `SharedKey` (object в api владельца) + `@ContributesIntoMap(ProfileScope::class) @StringKey(...)
  SharedFactory` в impl; в компоненте — `retainedShared(sharedScopes, Key)`.
- Точка входа платформы достаёт профильные entry point'ы через аксессор `@ContributesTo(ProfileScope::class) interface X`
  и `session.graph as X`.

## Ассистед-фабрики для компонентов

Компонентам нужен `ComponentContext` в рантайме — assisted injection:

```kotlin
@AssistedInject
internal class ChatScreenComponent(
    @Assisted context: ComponentContext,
    @Assisted private val onBack: () -> Unit,
    container: () -> ChatScreenContainer,          // Metro умеет инжектить функции-провайдеры
) : ComponentContext by context { /* … */
    @AssistedFactory
    fun interface Factory {
        fun create(context: ComponentContext, onBack: () -> Unit): ChatScreenComponent
    }
}
```

Фабрику (`ChatScreenComponent.Factory`) инжектируй в родительский компонент. Документация: https://zacsweers.github.io/metro/latest/injection-types/

## Правила

- Реализации — `internal`; наружу видны только интерфейсы (`heartbeat.metro` включает `generateContributionProviders`).
  Не инжектируй реализацию напрямую — только её интерфейс (Metro предупредит).
- `@BindingContainer` / `@ContributesTo`-контейнеры из другого модуля — `public` (на них `generateContributionProviders`
  не распространяется; `internal` контейнер молча не попадёт в граф → MissingBinding).
- Пустой мультибиндинг — ошибка компиляции; объявляй `@Multibinds(allowEmpty = true)`.
- Дефолтная реализация, которую можно перекрыть (`ActiveProfileStorage`): `@ContributesBinding(..., priority = N)` —
  побеждает больший приоритет.
- Никаких ручных `SomeImpl(...)` в продовом коде, если класс может быть в графе.
- Замена реализации в тестах/флейворах — `replaces = [...]` или `excludes` у графа, либо `priority`.
- Ошибки Metro (missing binding, cycle) показываются при компиляции `platform-main` — читай путь зависимостей в сообщении. Обычно причина — забытый `@Inject`, отсутствие `@ContributesBinding` или модуль `impl` не подключён в `platform-main`.
