---
name: navigation
description: "Навигация Heartbeat на Decompose — компоненты, childStack с @Serializable конфигами, StackNavigation, EntryPoint фичи в api для перехода между фичами, корневой компонент и платформенные точки входа (Android/Desktop/iOS), рендеринг Children в Compose, логирование навигации. Используй при добавлении экранов, переходов, диалогов, deep-link или правке root-компонента."
---

# Навигация (Decompose)

Документация: https://arkivanov.github.io/Decompose/navigation/overview/. Версия — [tech-stack.md](../../../docs/ai/tech-stack.md). Нужен плагин `kotlinx-serialization` для конфигов.

## Уровни

```
platform-main: RootComponent (childStack<RootConfig>)          ← единственный root
  └─ features/X/api: XEntryPoint — фабрика корневого компонента фичи + XEntryConfig
       └─ features/X/impl: DefaultXRootComponent (свой childStack<XConfig> для экранов фичи)
```

- Корень знает только `EntryPoint`-ы из `api` (инжектируются Metro).
- Внутренние конфиги экранов фичи — `private`/`internal` в `impl`.
- Переход в другую фичу — вызов колбэка/`EntryPoint` другой фичи, пробрасываемого через root, **или** событие в её машину (если переход — следствие бизнес-флоу).

## EntryPoint фичи (`api`) — без Compose

```kotlin
// core:navigation
/** Marker for a component that the platform root can host. */
interface FeatureComponent

/** Renders a [FeatureComponent]; implemented in feature impl, contributed with @ContributesIntoMap + @ClassKey. */
interface FeatureRenderer {
    @Composable fun Render(component: FeatureComponent, modifier: Modifier)
}

// features/chat/api
/** Entry into the chat feature. */
interface ChatEntryPoint {
    fun create(context: ComponentContext, args: ChatArgs, output: (ChatOutput) -> Unit): FeatureComponent
}

@Serializable data class ChatArgs(val chatId: String)
sealed interface ChatOutput { data object Closed : ChatOutput }
```

`core:navigation` — модуль с Compose (рендереры), `features/*/api` используют из него только `FeatureComponent` и Decompose-типы.
Root хранит `RootConfig.Chat(args: ChatArgs)` и через `Map<KClass<*>, FeatureRenderer>` рисует активного ребёнка.

## Стек внутри фичи (`impl/component`)

```kotlin
@AssistedInject
internal class DefaultChatRootComponent(
    @Assisted context: ComponentContext,
    @Assisted private val args: ChatArgs,
    @Assisted private val output: (ChatOutput) -> Unit,
    private val screens: ChatScreens,                     // фабрики экранных компонентов (Metro)
) : FeatureComponent, ComponentContext by context {

    private val navigation = StackNavigation<Config>()

    val stack: Value<ChildStack<Config, Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Conversation(args.chatId),
        handleBackButton = true,
        childFactory = ::child,
    )

    private fun child(config: Config, ctx: ComponentContext): Child = when (config) {
        is Config.Conversation -> Child.Conversation(screens.conversation(ctx, config.chatId, onSettings = { navigation.pushNew(Config.Settings) }))
        Config.Settings -> Child.Settings(screens.settings(ctx, onBack = navigation::pop))
    }

    @Serializable
    sealed interface Config {
        @Serializable data class Conversation(val chatId: String) : Config
        @Serializable data object Settings : Config
    }

    sealed interface Child {
        class Conversation(val component: ChatScreenComponent) : Child
        class Settings(val component: ChatSettingsComponent) : Child
    }
}

@Composable
internal fun ChatRootContent(component: DefaultChatRootComponent, modifier: Modifier = Modifier) {
    Children(stack = component.stack, modifier = modifier, animation = stackAnimation(fade() + slide())) {
        when (val child = it.instance) {
            is DefaultChatRootComponent.Child.Conversation -> ChatScreen(child.component)
            is DefaultChatRootComponent.Child.Settings -> ChatSettingsScreen(child.component)
        }
    }
}
```

Операции: `push`, `pushNew` (не дублирует верх), `pop`, `replaceCurrent`, `bringToFront`, `popTo(index)`. Для диалогов — `childSlot`, для мастер-деталь на desktop — `childPanels`.

## Логирование

Используй `navigation.logged(tag)` / `loggingChildStack(...)` из `core:navigation`: логирует операцию и конфиги (`NAV: push Settings; stack=[Conversation, Settings]`). Конфиги не должны содержать секретов/PII — они же сохраняются в state.

## Платформенные корни (`platform-main`)

```kotlin
// Android
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = graph.rootComponentFactory.create(defaultComponentContext())   // вызывать один раз
        setContent { HbTheme { RootContent(root) } }
    }
}

// Desktop (jvmMain)
fun main() {
    Log.init(isDebug = true)
    val lifecycle = LifecycleRegistry()
    val root = runOnUiThread { graph.rootComponentFactory.create(DefaultComponentContext(lifecycle)) }
    application {
        val windowState = rememberWindowState()
        LifecycleController(lifecycle, windowState)
        Window(onCloseRequest = ::exitApplication, state = windowState, title = "Heartbeat") {
            HbTheme(platformUi = detectDesktopPlatformUi()) { RootContent(root) }
        }
    }
}

// iOS (iosMain, platform-main:shared)
fun MainViewController(root: RootComponent): UIViewController = ComposeUIViewController { HbTheme { RootContent(root) } }
// root создаётся в Swift: DefaultComponentContext(lifecycle: ApplicationLifecycle())
```

## Тесты
Компоненты тестируются без UI: `DefaultComponentContext(LifecycleRegistry().apply { resume() })`, вызов методов компонента, проверка `stack.value.active.configuration`.
