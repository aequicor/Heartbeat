---
name: mvi-store
description: "Создание FlowMVI-стора экрана в impl-модуле фичи Heartbeat — State/Intent/Action, heartbeatStore с логированием, подписка на state-machine фичи, отправка событий в машину, retainedStore в Decompose-компоненте, подписка из Compose и тесты. Используй при создании/изменении экрана или его состояния."
---

# FlowMVI-стор экрана

Роли: **машина** (`api`) — бизнес-флоу; **стор** (`impl/store`) — состояние экрана: маппит состояние машины + локальный ввод в UI-state, превращает intent'ы в события машины, отдаёт одноразовые Action'ы (тосты, навигация).

## Контракт (`store/<Screen>Contract.kt`)

```kotlin
@Immutable
internal sealed interface ChatScreenState : MVIState {
    data object Loading : ChatScreenState
    data class Content(
        val messages: ImmutableList<MessageUi>,
        val input: String,
        val isGenerating: Boolean,
    ) : ChatScreenState
    data class Error(val message: StringResource) : ChatScreenState
}

internal sealed interface ChatScreenIntent : MVIIntent {
    data class InputChanged(val value: String) : ChatScreenIntent
    data object SendClicked : ChatScreenIntent
    data object CancelClicked : ChatScreenIntent
}

internal sealed interface ChatScreenAction : MVIAction {
    data class ShowSnackbar(val message: StringResource) : ChatScreenAction
}
```

## Стор (`store/<Screen>Container.kt`)

`heartbeatStore` — обёртка из `core:mvi`: `store(initial) { configure { name; debuggable; logger = NapierStoreLogger; coroutineContext = dispatchers.main } ; enableLogging(); recover { log + Error-state } ; …block }`. Логирование intent/action/state подключено — вручную не дублируй.

```kotlin
@Inject
internal class ChatScreenContainer(
    private val machines: MachineRegistry,
    private val messages: ChatRepository,
    storeFactory: HeartbeatStoreFactory,
) : Container<ChatScreenState, ChatScreenIntent, ChatScreenAction> {

    private val chat = machines[ChatMachineKey]

    override val store = storeFactory.create<ChatScreenState, ChatScreenIntent, ChatScreenAction>(
        name = "ChatScreen",
        initial = ChatScreenState.Loading,
    ) {
        whileSubscribed {                                  // подписка на машину, пока экран на экране
            combine(chat.activeStates, messages.observe()) { states, list -> states to list }
                .collect { (states, list) ->
                    updateState { reduceMachine(states, list, input = (this as? ChatScreenState.Content)?.input.orEmpty()) }
                }
        }
        reduce { intent ->
            when (intent) {
                is ChatScreenIntent.InputChanged -> updateState<ChatScreenState.Content, _> { copy(input = intent.value) }
                ChatScreenIntent.SendClicked -> withState {
                    val content = this as? ChatScreenState.Content ?: return@withState
                    if (!chat.send(ChatEvent.Public.SendPrompt(content.input))) {
                        action(ChatScreenAction.ShowSnackbar(Res.string.chat_cannot_send))
                    }
                }
                ChatScreenIntent.CancelClicked -> chat.send(ChatEvent.Public.Cancel)
            }
        }
    }
}
```

Правила:
- UI-state иммутабелен (`ImmutableList`), без доменных сущностей с изменяемыми полями — маппинг в `*Ui`-модели.
- Стор не решает, «можно ли отправить» — это guard машины; стор лишь реагирует на `send(...) == false`.
- Порядок плагинов важен: логирование ставится до `reduce` (это делает `heartbeatStore`).
- Ошибки: `recover { e -> … ; null }` уже в `heartbeatStore` (логирует и переводит в `Error`); специфичную обработку добавляй своим `recover` выше.
- `updateState` для изменений, `withState` для чтения без изменения; `action(...)` для одноразовых эффектов.

## Компонент и Compose

```kotlin
internal class ChatScreenComponent(
    context: ComponentContext,
    container: () -> ChatScreenContainer,            // фабрика из Metro (Provider)
    private val onBack: () -> Unit,
) : ComponentContext by context,
    Store<ChatScreenState, ChatScreenIntent, ChatScreenAction> by context.retainedStore(factory = container)

@Composable
internal fun ChatScreen(component: ChatScreenComponent, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    val state by component.subscribe { action ->
        when (action) {
            is ChatScreenAction.ShowSnackbar -> snackbar.showSnackbar(getString(action.message))
        }
    }
    ChatScreenContent(state = state, onIntent = component::intent, modifier = modifier)
}

@Composable
private fun ChatScreenContent(state: ChatScreenState, onIntent: (ChatScreenIntent) -> Unit, modifier: Modifier = Modifier) { /* Hb*-компоненты */ }
```

Артефакты: `pro.respawn.flowmvi:core`, `:compose`, `:essenty`, `:essenty-compose`, тесты — `:test` (версия в [tech-stack.md](../../../docs/ai/tech-stack.md)).

## Тест

```kotlin
@Test
fun `send clicked forwards prompt to chat machine`() = runTest {
    val machines = FakeMachineRegistry()
    val container = ChatScreenContainer(machines, FakeChatRepository(), testStoreFactory())
    container.store.subscribeAndTest {
        intent(ChatScreenIntent.InputChanged("hi"))
        intent(ChatScreenIntent.SendClicked)
        advanceUntilIdle()
        assertEquals(listOf(ChatEvent.Public.SendPrompt("hi")), machines.sent(ChatMachineKey))
    }
}
```
