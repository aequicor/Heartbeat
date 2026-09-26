# Контракт фичи: state-machine в `api`

Каждая фича — пара модулей `features/<name>/api` и `features/<name>/impl`.
**`api` описывает поведение фичи как конечный автомат** (KStateMachine): все состояния, события и переходы.
`impl` лишь *исполняет* эффекты и рисует UI. Другие фичи взаимодействуют с машиной только через **object-key** и публичные события.

## Инфраструктура `core:state-machine`

```kotlin
package io.aequicor.heartbeat.core.statemachine

/** Stable address of a feature machine. Declared as `object` in the feature's api module. */
interface MachineKey<E : Event> {
    val name: String
}

/** Handle to a running machine that other features may use. Exposes only public events and state. */
interface MachineRef<E : Event> {
    val key: MachineKey<E>
    /** Active leaf states of the machine, updated on every transition. */
    val activeStates: StateFlow<Set<IState>>
    /** Sends a public event. Returns false if the machine ignored it (logged as WARN). */
    suspend fun send(event: E): Boolean
}

/** App-scoped registry of all feature machines (populated via Metro map multibinding). */
interface MachineRegistry {
    operator fun <E : Event> get(key: MachineKey<E>): MachineRef<E>
}

/** Contributed by each feature impl; the registry creates and starts machines lazily. */
interface MachineFactory {
    val key: MachineKey<*>
    suspend fun create(scope: CoroutineScope): StateMachine
}
```

Реестр при создании машины:
- ставит `StateMachine.Logger` → `core:logging` (тег `SM/<key.name>`);
- добавляет listener, логирующий `onTransitionTriggered` / `onStateEntry` (`from --Event--> to`);
- ставит `ignoredEventHandler`, логирующий отклонённые события (WARN);
- публикует `activeStatesFlow()` как `MachineRef.activeStates`.

Машины живут в `AppScope` (однажды созданная машина переживает экраны). Машина, привязанная к экрану, — исключение, фиксируется в ADR.

## Структура `api`

```
features/chat/api/src/commonMain/kotlin/io/aequicor/heartbeat/feature/chat/api/
  ChatMachineKey.kt     object ChatMachineKey : MachineKey<ChatEvent.Public>
  ChatEvent.kt          sealed interface ChatEvent : Event { Public, Internal }
  ChatState.kt          состояния (object / DataState)
  ChatEffects.kt        interface ChatEffects — suspend-операции для onEntry
  ChatMachineSpec.kt    fun BuildingStateMachine.chatMachine(effects: ChatEffects) — граф
  ChatRoute.kt          @Serializable @SerialName("chat") data class ChatRoute(chatId) : Route (+ ResultContract, если есть)
src/commonTest/…        ChatMachineSpecTest — переходы с FakeChatEffects
```

## Пример

```kotlin
// ChatMachineKey.kt
/** Address of the chat feature machine. */
object ChatMachineKey : MachineKey<ChatEvent.Public> {
    override val name = "chat"
}

// ChatEvent.kt
sealed interface ChatEvent : Event {
    /** Events any feature may send via [ChatMachineKey]. */
    sealed interface Public : ChatEvent {
        data class Open(val chatId: String) : Public
        data class SendPrompt(val text: String) : Public
        data object Cancel : Public
    }

    /** Results of [ChatEffects]; sent only by the chat feature itself. */
    sealed interface Internal : ChatEvent {
        data class Loaded(val chatId: String) : Internal
        data class Completed(val messageId: String) : Internal
        data class Failed(val reason: String) : Internal
    }
}

// ChatState.kt
sealed class ChatState : DefaultState() {
    data object Idle : ChatState()
    data object Loading : ChatState()
    data object Ready : ChatState()
    data object Generating : ChatState()
    data object Error : ChatState()
}

// ChatEffects.kt
interface ChatEffects {
    suspend fun load(chatId: String): ChatEvent.Internal
    suspend fun generate(prompt: String): ChatEvent.Internal
    suspend fun cancelGeneration()
}

// ChatMachineSpec.kt
/**
 * Chat flow.
 *
 * | From       | Event              | To         |
 * |------------|--------------------|------------|
 * | Idle       | Public.Open        | Loading    |
 * | Loading    | Internal.Loaded    | Ready      |
 * | Loading    | Internal.Failed    | Error      |
 * | Ready      | Public.SendPrompt  | Generating |
 * | Generating | Internal.Completed | Ready      |
 * | Generating | Public.Cancel      | Ready      |
 * | Generating | Internal.Failed    | Error      |
 * | Error      | Public.Open        | Loading    |
 */
suspend fun BuildingStateMachine.chatMachine(effects: ChatEffects, scope: CoroutineScope) {
    addInitialState(ChatState.Idle) {
        transition<ChatEvent.Public.Open> { targetState = ChatState.Loading }
    }
    addState(ChatState.Loading) {
        onEntry {
            val open = it.event as ChatEvent.Public.Open
            // effect runs outside the event-processing loop; its result comes back as an Internal event
            scope.launch { machine.processEvent(effects.load(open.chatId)) }
        }
        transition<ChatEvent.Internal.Loaded> { targetState = ChatState.Ready }
        transition<ChatEvent.Internal.Failed> { targetState = ChatState.Error }
    }
    // …
}
```

> Эффекты в `onEntry` запускай через `scope.launch` (scope машины из реестра), а не суспендом внутри колбэка,
> иначе обработка событий блокируется (например, `Cancel` во время `Generating`). Ответ эффекта — всегда событие `Internal`.
> Точный API KStateMachine — https://kstatemachine.github.io/kstatemachine/ (DataState/DataEvent, `transitionOn`, `guard`).

## Взаимодействие фич

```kotlin
// features/projects/impl — открыть чат из другой фичи
@Inject
class OpenChatUseCase(private val machines: MachineRegistry) {
    suspend operator fun invoke(chatId: String) =
        machines[ChatMachineKey].send(ChatEvent.Public.Open(chatId))
}
```

- Только `Event.Public`. `Internal` — приватный протокол фичи (в `api` из-за sealed-иерархии, но KDoc помечает, что слать его извне запрещено; ревью это проверяет).
- Реакция на состояние чужой машины — подписка на `machines[Key].activeStates`, без синхронных ожиданий «A ждёт B, B ждёт A».
- Навигация в чужую фичу — `navigator.navigate(OtherRoute(…))` по маршруту из её `api` (скилл `navigation`), а не через компоненты/конфиги её `impl`.

## Что в `impl`

```kotlin
@ContributesIntoMap(AppScope::class)
@StringKey("chat")           // == ChatMachineKey.name
@Inject
internal class ChatMachineFactory(private val effects: ChatEffects) : MachineFactory {
    override val key = ChatMachineKey
    override suspend fun create(scope: CoroutineScope) =
        createStateMachine(scope, name = key.name, start = false) { chatMachine(effects, scope) }
}

@ContributesBinding(AppScope::class)
@Inject
internal class ChatEffectsImpl(
    private val repository: ChatRepository,
    private val agent: ChatAgent,
) : ChatEffects { /* … */ }
```

## Изменение контракта

1. Правка состояний/событий/переходов → обнови таблицу в KDoc спеки и тест `…MachineSpecTest`.
2. Изменение `Event.Public` → найди потребителей: `Grep "<Name>MachineKey"` по `features/`.
3. Удаление публичного события — breaking change для других фич: сначала мигрируй потребителей.
