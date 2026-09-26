---
name: di-metro
description: "Внедрение зависимостей в Heartbeat на Metro — AppScope, @DependencyGraph в platform-main, @Inject, @SingleIn, @ContributesTo/@ContributesBinding для api/impl, мультибиндинги @ContributesIntoMap/@ContributesIntoSet (машины, тоглы, рендереры), @Provides/@BindingContainer, graph extensions, платформенные зависимости через Factory. Используй при добавлении класса в граф, связывании api с impl, ошибках компиляции Metro."
---

# DI (Metro)

Документация: https://zacsweers.github.io/metro/ . Пакет аннотаций — `dev.zacsweers.metro`. Подключение — плагин `dev.zacsweers.metro` (runtime добавляется автоматически), через convention-плагин `heartbeat.metro`.

## Скоупы

- `AppScope` — из `dev.zacsweers.metro.AppScope` (встроен в Metro). Единственный граф — `HeartbeatGraph` в `platform-main`.
- Экранные/фичевые подграфы — только если реально нужны (graph extension, см. ниже). Жизненный цикл экрана обычно даёт Decompose (`retainedStore`/`instanceKeeper`), а не DI.

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

// 2. Мультибиндинг в map: машины фич (ключ == MachineKey.name)
@ContributesIntoMap(AppScope::class)
@StringKey("chat")
@Inject
internal class ChatMachineFactory(private val effects: ChatEffects) : MachineFactory

// 3. Мультибиндинг в set: тоглы, логирующие плагины, инициализаторы
@ContributesIntoSet(AppScope::class)
@Inject
internal class ChatStartup(/* … */) : AppInitializer

// 4. Провайдеры сторонних типов — интерфейс с @ContributesTo (или @BindingContainer)
@ContributesTo(AppScope::class)
interface NetworkProviders {
    @Provides @SingleIn(AppScope::class)
    fun httpClient(engine: HttpClientEngine, json: Json): HttpClient = createHttpClient(engine, json)
}

// 5. Потребление мультибиндинга
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DefaultMachineRegistry(
    private val factories: Map<String, MachineFactory>,
    @AppCoroutineScope private val scope: CoroutineScope,
) : MachineRegistry
```

## Граф приложения (`platform-main`)

```kotlin
@DependencyGraph(AppScope::class)
interface HeartbeatGraph {
    val rootComponentFactory: RootComponentFactory
    val initializers: Set<AppInitializer>

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides platform: PlatformContext): HeartbeatGraph   // Android Context / пути / флаги сборки
    }
}

val graph = createGraphFactory<HeartbeatGraph.Factory>().create(platformContext)
```

Граф собирается только в `platform-main` — там видны все `impl`, и Metro агрегирует их контрибуции. Модули фич графов не создают.

## Graph extension (при необходимости)

```kotlin
@GraphExtension(ChatScope::class)
interface ChatGraph {
    val root: ChatRootComponentFactory
    @GraphExtension.Factory interface Factory { fun create(@Provides args: ChatArgs): ChatGraph }
}

@ContributesTo(AppScope::class)
interface ChatGraphParent { val chatGraphFactory: ChatGraph.Factory }
```

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

- Реализации — `internal`; наружу видны только интерфейсы.
- Никаких ручных `SomeImpl(...)` в продовом коде, если класс может быть в графе.
- Замена реализации в тестах/флейворах — `replaces = [...]` или `excludes` у графа, либо `priority`.
- Ошибки Metro (missing binding, cycle) показываются при компиляции `platform-main` — читай путь зависимостей в сообщении. Обычно причина — забытый `@Inject`, отсутствие `@ContributesBinding` или модуль `impl` не подключён в `platform-main`.
