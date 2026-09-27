---
name: mvi-store
description: "Создание FlowMVI-стора экрана в impl-модуле фичи Heartbeat — State/Intent/Action, heartbeatStore с логированием, отражение state-machine фичи через reflect (core:state-machine:flowmvi-ext), отправка интентов в машину через sendTo, retainedStore в Decompose-компоненте, подписка из Compose и тесты. Используй при создании/изменении экрана или его состояния."
---

# FlowMVI-стор экрана

Роли: **машина** (`api`) — бизнес-флоу; **стор** (`impl/store`) — состояние экрана: **отражает** состояние машины
(+ локальный ввод) в UI-state, превращает intent'ы экрана в интенты машины, отдаёт одноразовые Action'ы (тосты,
навигация), в том числе из outputs машины. Связка — `core:state-machine:flowmvi-ext` ([ADR-0004](../../../docs/adr/0004-state-machine.md)).

## Контракт (`store/<Screen>Contract.kt`)

```kotlin
// Данные экрана (сообщения, ввод) — поля верхнего уровня: их не теряет смена фазы машины (Loading ↔ Content).
@Immutable
internal data class ChatScreenState(
    val phase: Phase = Phase.Loading,
    val messages: ImmutableList<MessageUi> = persistentListOf(),
    val input: String = "",
) : MVIState {
    sealed interface Phase {
        data object Loading : Phase
        data class Content(val isGenerating: Boolean) : Phase
        data class Error(val message: StringResource) : Phase
    }
}

internal sealed interface ChatScreenIntent : MVIIntent {
    data class InputChanged(val value: String) : ChatScreenIntent
    data object SendClicked : ChatScreenIntent
    data object CancelClicked : ChatScreenIntent
}

internal sealed interface ChatScreenAction : MVIAction {
    data class ShowSnackbar(val message: StringResource) : ChatScreenAction
    data object ScrollToBottom : ChatScreenAction
}
```

## Стор (`store/<Screen>Container.kt`)

`heartbeatStore` — обёртка из `core:mvi`: `store(initial) { configure { name; debuggable; logger = NapierStoreLogger; coroutineContext = dispatchers.main } ; enableLogging(); recover { log + Error-state } ; …block }`. Логирование intent/action/state подключено — вручную не дублируй.

```kotlin
@Inject
internal class ChatScreenContainer(
    private val chat: Machine<ChatState, ChatIntent, ChatOutput>,   // своя машина — из графа фичи (ChatScope)
    private val messages: ChatRepository,
    storeFactory: HeartbeatStoreFactory,
) : Container<ChatScreenState, ChatScreenIntent, ChatScreenAction> {

    override val store = storeFactory.create<ChatScreenState, ChatScreenIntent, ChatScreenAction>(
        name = "ChatScreen",
        initial = ChatScreenState().reduceChat(chat.state.value),
    ) {
        // состояние и outputs машины → стор, пока экран подписан; логируется как MVI/ChatScreen
        reflect(chat, onOutput = { output ->
            when (output) {
                ChatOutput.Generated -> action(ChatScreenAction.ScrollToBottom)
            }
        }) { machineState -> reduceChat(machineState) }
        whileSubscribed {                                               // прочие источники экрана
            messages.observe().collect { list -> updateState { copy(messages = list) } }
        }
        reduce { intent ->
            when (intent) {
                is ChatScreenIntent.InputChanged -> updateState { copy(input = intent.value) }
                ChatScreenIntent.SendClicked -> withState {
                    sendTo(chat, ChatIntent.Public.SendPrompt(input)) {
                        action(ChatScreenAction.ShowSnackbar(Res.string.chat_cannot_send))
                    }
                }
                ChatScreenIntent.CancelClicked -> sendTo(chat, ChatIntent.Public.Cancel)
            }
        }
    }
}

/** Every machine state maps to a screen phase: no `else`, so a new machine state breaks the build here. */
private fun ChatScreenState.reduceChat(machine: ChatState): ChatScreenState = when (machine) {
    ChatState.Idle, is ChatState.Loading -> copy(phase = ChatScreenState.Phase.Loading)
    is ChatState.Ready -> copy(phase = ChatScreenState.Phase.Content(isGenerating = false))
    is ChatState.Generating -> copy(phase = ChatScreenState.Phase.Content(isGenerating = true), input = "")
    is ChatState.Error -> copy(phase = ChatScreenState.Phase.Error(Res.string.chat_error))
}
```

- Стор **не хранит копию** состояния машины: только фазу, которую `reduceChat` выводит из неё, и данные экрана
  (ввод, сообщения из репозитория), которые смена фазы не затирает.
- Чужая машина — через `MachineRegistry`: `machines.observe(OtherMachineKey)` в `whileSubscribed`, отправка —
  `machines.send(OtherMachineKey, intent)`.

Правила:
- UI-state иммутабелен (`ImmutableList`), без доменных сущностей с изменяемыми полями — маппинг в `*Ui`-модели.
- Стор не решает, «можно ли отправить» — это guard машины; стор лишь реагирует на отказ (`sendTo(…) { rejected -> }`
  получает `Ignored`/`NotRunning`, отказ логируется WARN).
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
    val listState = rememberLazyListState()
    val state by component.subscribe { action ->
        when (action) {
            is ChatScreenAction.ShowSnackbar -> snackbar.showSnackbar(getString(action.message))
            ChatScreenAction.ScrollToBottom -> listState.animateScrollToItem(0)
        }
    }
    ChatScreenContent(state = state, onIntent = component::intent, modifier = modifier)
}

@Composable
private fun ChatScreenContent(state: ChatScreenState, onIntent: (ChatScreenIntent) -> Unit, modifier: Modifier = Modifier) { /* Hb*-компоненты */ }
```

Артефакты: `pro.respawn.flowmvi:core`, `:compose`, `:essenty`, `:essenty-compose`, тесты — `:test` (версия в [tech-stack.md](../../../docs/ai/tech-stack.md)).

## Тест

Машину в тесте стора заменяет фейк `Machine`: состояние задаёт тест, отправленные интенты записываются.

```kotlin
private class FakeChatMachine(initial: ChatState) : Machine<ChatState, ChatIntent, ChatOutput> {
    override val name = "chat"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<ChatOutput>(extraBufferCapacity = 8)
    val sent = mutableListOf<ChatIntent>()
    var result = SendResult.Accepted
    override suspend fun send(intent: ChatIntent) = result.also { sent += intent }
}

@Test
fun `send clicked forwards prompt to chat machine`() = runTest {
    val chat = FakeChatMachine(ChatState.Ready("c1"))
    val container = ChatScreenContainer(chat, FakeChatRepository(), testStoreFactory())
    container.store.subscribeAndTest {
        intent(ChatScreenIntent.InputChanged("hi"))
        intent(ChatScreenIntent.SendClicked)
        advanceUntilIdle()
        assertEquals(listOf<ChatIntent>(ChatIntent.Public.SendPrompt("hi")), chat.sent)
    }
}

@Test
fun `machine state is reflected`() = runTest {
    val chat = FakeChatMachine(ChatState.Ready("c1"))
    ChatScreenContainer(chat, FakeChatRepository(), testStoreFactory()).store.subscribeAndTest {
        chat.state.value = ChatState.Generating("c1")
        advanceUntilIdle()
        assertEquals(ChatScreenState.Phase.Content(isGenerating = true), states.value.phase)
    }
}
```

Переходы самой машины здесь не тестируй — они покрыты `assertTransition` в `api`.
