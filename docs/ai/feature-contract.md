# Контракт фичи: state-machine в `api`

Каждая фича — пара модулей `features/<name>/api` и `features/<name>/impl`.
**`api` описывает поведение фичи как типизированный автомат** (`machineSpec { }`): все состояния, интенты, переходы,
эффекты и outputs. `impl` лишь *исполняет* эффекты и рисует UI. Другие фичи взаимодействуют с машиной только через
**object-key** и публичные интенты. Решение и мотивация — [ADR-0004](../adr/0004-state-machine.md).

## Модель: state / intent / effect / output

| Понятие | Что это | Кто создаёт | Кто получает |
|---|---|---|---|
| **State** (`MachineState`) | `data object` / `data class` одной sealed-иерархии; данные живут в состоянии | переход (`goto`/`stay`) | стор (`reflect`), другие фичи (`state`) |
| **Intent** (`MachineIntent`) | `Public` — извне, `Internal` — от самой фичи и её эффектов | стор, другие фичи, эффекты | машина |
| **Effect** (`MachineEffect`) | команда на IO, объявленная переходом | переход (`effect { }`) | `EffectHandler` в `impl`; ответ — Internal-интент |
| **Output** (`MachineOutput`) | одноразовое событие наружу (горячий поток без replay) | переход (`output { }`) | стор (`onOutput` → action), другие фичи |

## Инфраструктура `core:state-machine`

| Модуль | Содержимое | Кто зависит |
|---|---|---|
| `core:state-machine:api` | `MachineKey`, `MachineRef`, `Machine`, `SendResult`, `machineSpec { }`, `MachineSpec.resolve`, `assertTransition`, `toMermaid()`, `MachineLauncher`, `MachineRegistry`, `EffectHandler` | api и impl фич |
| `core:state-machine:impl` | рантайм на KStateMachine, реестр живых машин, логи `SM/<name>` | только `platform-main:di-bundle` |
| `core:state-machine:flowmvi-ext` | `StoreBuilder.reflect(machine) { }`, `PipelineContext.sendTo(machine, intent)` | impl фич |

```kotlin
package io.aequicor.heartbeat.core.statemachine

/** Stable address of a feature machine: `object` in the feature api. P = public intents only. */
interface MachineKey<S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> {
    val name: String
}

/** What other features see: typed state, outputs, public intents. */
interface MachineRef<out S : MachineState, in P : MachineIntent, out O : MachineOutput> {
    val name: String
    val state: StateFlow<S>
    val outputs: Flow<O>
    suspend fun send(intent: P): SendResult          // Accepted | Ignored | NotRunning
}

/** Running machine inside its feature: accepts internal intents too. */
interface Machine<S, I, O> : MachineRef<S, I, O>

interface MachineLauncher {                           // impl: MachineRuntime (AppScope)
    fun <S, I, E, O> launch(spec: MachineSpec<S, I, E, O>, scope: ScopeHandle, effects: EffectHandler<E, I>): Machine<S, I, O>
}

interface MachineRegistry {                           // живые машины по ключу
    fun find(key): MachineRef<S, P, O>?
    fun observe(key): StateFlow<MachineRef<S, P, O>?>
    suspend fun send(key, intent: P): SendResult
}

fun interface EffectHandler<in E, out I> {
    suspend fun handle(effect: E, machine: EffectScope<I>)   // machine.send(Internal…) — сколько угодно раз
}
```

**Жизненный цикл.** Машина — часть фичи: её создаёт граф фичи (`@Provides @SingleIn(<Feature>Scope::class)`), она
живёт, пока открыт скоуп фичи (ADR-0002), и останавливается при его закрытии (в т.ч. каскадом при закрытии профиля).
Создание ленивое — при первой инъекции, поэтому корневой компонент фичи инжектит `Machine` сразу (иначе, пока экран
её не запросил, реестр отвечает `NotRunning`). Пока машина запущена, она зарегистрирована в `MachineRegistry`
(`AppScope`) под `key.name`; если открыто несколько экземпляров фичи, адресуется последний. Реестр отдаёт другим
фичам обёртку `MachineRef` (только `Public`, каждая отправка — I в логе). Отправка в незапущенную машину —
`SendResult.NotRunning`. Реестр можно вызывать из любого потока.

**Семантика** целиком в `api`: `MachineSpec.resolve(state, intent)` — чистая функция. Рантайм в `impl` строит по спеке
граф KStateMachine, обрабатывает интенты по одному, держит типизированный `StateFlow<S>`, запускает эффекты и эмитит outputs.

**Эффекты привязаны к состоянию.** Эффект, запущенный переходом в состояние X, отменяется при выходе из X (`goto`,
в т.ч. в тот же класс); его поздние результаты отбрасываются. `stay` (обновление данных без перехода) эффекты не
отменяет. Поэтому интент, уводящий машину из состояния, эффект отправляет **последним**: код после него будет отменён.
Исключение эффекта логируется (E) и через `onEffectFailure` превращается в Internal-интент; `CancellationException`
изнутри живого эффекта (`withTimeout`) — тоже ошибка, а не отмена.

**Смерть процесса.** `persist(serializer) { saved -> restore(…) }` сохраняет состояние в `ScopeSavedState` скоупа фичи.
Эффекты не переживают смерть процесса, поэтому состояния, ждущие результата эффекта (`Loading`, `Generating`),
маппятся при восстановлении: перезапуск эффекта (`restore(saved, Effect)`) или откат в стабильное состояние.
Сохранённое состояние, которого больше нет в спеке, — старт с `initial` (в логе I «fell back to initial»).

**Логирование** — централизованно в рантайме (`SM/<name>`), фичи его не дублируют: приход интента и источник (D),
переход (I), `stay` (D), отклонённый интент (W), эффект: старт/завершение/отмена (D), ошибка (E), output (D),
старт/restore/стоп (I). Только имена классов — данные состояний и интентов в логи не попадают.

## Структура `api`

```
features/chat/api/src/commonMain/kotlin/io/aequicor/heartbeat/feature/chat/api/
  ChatMachineKey.kt     object ChatMachineKey : MachineKey<ChatState, ChatIntent, ChatIntent.Public, ChatEffect, ChatOutput>
  ChatState.kt          sealed interface ChatState : MachineState — все состояния (@Serializable, если persist)
  ChatIntent.kt         sealed interface ChatIntent : MachineIntent { Public, Internal }
  ChatEffect.kt         sealed interface ChatEffect : MachineEffect — команды на IO
  ChatOutput.kt         sealed interface ChatOutput : MachineOutput — одноразовые события наружу
  ChatMachineSpec.kt    val ChatMachineSpec = machineSpec(ChatMachineKey, initial) { … } + KDoc-таблица переходов
  ChatRoute.kt          @Serializable @SerialName("chat") data class ChatRoute(chatId) : Route (+ ResultContract)
src/commonTest/…        ChatMachineSpecTest — assertTransition / assertIgnored, без рантайма и фейков
```

## Пример

```kotlin
// ChatMachineKey.kt
/** Address of the chat feature machine. */
public object ChatMachineKey : MachineKey<ChatState, ChatIntent, ChatIntent.Public, ChatEffect, ChatOutput> {
    override val name: String = "chat"
}

// ChatState.kt
@Serializable
public sealed interface ChatState : MachineState {
    @Serializable public data object Idle : ChatState
    @Serializable public data class Loading(val chatId: String) : ChatState
    @Serializable public data class Ready(val chatId: String, val draft: String = "") : ChatState
    @Serializable public data class Generating(val chatId: String) : ChatState
    @Serializable public data class Error(val reason: String) : ChatState
}

// ChatIntent.kt
public sealed interface ChatIntent : MachineIntent {
    /** Intents any feature may send via [ChatMachineKey]. */
    public sealed interface Public : ChatIntent {
        public data class Open(val chatId: String) : Public
        public data class SendPrompt(val text: String) : Public
        public data object Cancel : Public
    }

    /** Results of [ChatEffect]s and chat's own input; sent only by the chat feature itself. */
    public sealed interface Internal : ChatIntent {
        public data class Loaded(val chatId: String) : Internal
        public data class DraftChanged(val text: String) : Internal
        public data object Completed : Internal
        public data class Failed(val reason: String) : Internal
    }
}

// ChatEffect.kt / ChatOutput.kt
public sealed interface ChatEffect : MachineEffect {
    public data class Load(val chatId: String) : ChatEffect
    public data class Generate(val prompt: String) : ChatEffect
}

public sealed interface ChatOutput : MachineOutput {
    public data object Generated : ChatOutput
}

// ChatMachineSpec.kt
/**
 * Chat flow.
 *
 * | From       | Intent                  | To         | Effect / Output |
 * |------------|-------------------------|------------|-----------------|
 * | Idle       | Public.Open             | Loading    | Load            |
 * | Loading    | Internal.Loaded         | Ready      |                 |
 * | Loading    | Internal.Failed         | Error      |                 |
 * | Ready      | Public.SendPrompt [text] | Generating | Generate        |
 * | Ready      | Internal.DraftChanged   | (stay)     |                 |
 * | Generating | Internal.Completed      | Ready      | → Generated     |
 * | Generating | Public.Cancel           | Ready      | (Generate cancelled) |
 * | Generating | Internal.Failed         | Error      |                 |
 * | Error      | Public.Open             | Loading    | Load            |
 */
public val ChatMachineSpec: MachineSpec<ChatState, ChatIntent, ChatEffect, ChatOutput> =
    machineSpec(ChatMachineKey, initial = ChatState.Idle) {
        state<ChatState.Idle> {
            on<ChatIntent.Public.Open> {
                goto<ChatState.Loading> { ChatState.Loading(intent.chatId) }
                effect { ChatEffect.Load(intent.chatId) }
            }
        }
        state<ChatState.Loading> {
            on<ChatIntent.Internal.Loaded> { goto<ChatState.Ready> { ChatState.Ready(intent.chatId) } }
            on<ChatIntent.Internal.Failed> { goto<ChatState.Error> { ChatState.Error(intent.reason) } }
        }
        state<ChatState.Ready> {
            on<ChatIntent.Public.SendPrompt>(guard = { intent.text.isNotBlank() }) {
                goto<ChatState.Generating> { ChatState.Generating(state.chatId) }
                effect { ChatEffect.Generate(intent.text) }
            }
            on<ChatIntent.Internal.DraftChanged> { stay { state.copy(draft = intent.text) } }
        }
        state<ChatState.Generating> {
            on<ChatIntent.Internal.Completed> {
                goto<ChatState.Ready> { ChatState.Ready(state.chatId) }
                output { ChatOutput.Generated }
            }
            on<ChatIntent.Public.Cancel> { goto<ChatState.Ready> { ChatState.Ready(state.chatId) } }
            on<ChatIntent.Internal.Failed> { goto<ChatState.Error> { ChatState.Error(intent.reason) } }
        }
        state<ChatState.Error> {
            on<ChatIntent.Public.Open> {
                goto<ChatState.Loading> { ChatState.Loading(intent.chatId) }
                effect { ChatEffect.Load(intent.chatId) }
            }
        }
        onEffectFailure { _, error -> ChatIntent.Internal.Failed(error.message.orEmpty()) }
        persist(ChatState.serializer()) { saved ->
            when (saved) {
                is ChatState.Loading -> restore(saved, ChatEffect.Load(saved.chatId))   // перезапустить загрузку
                is ChatState.Generating -> restore(ChatState.Ready(saved.chatId))       // генерацию не продолжить
                ChatState.Idle, is ChatState.Ready, is ChatState.Error -> restore(saved)
            }
        }
    }
```

- Внутри `on { }` лямбды `goto`/`stay`/`effect`/`output`/`guard` получают `state` (сужен до `state<T>`) и `intent`
  (сужен до `on<J>`). Они чистые: без IO, без времени, без случайности.
- Без `goto`/`stay` интент принимается, состояние не меняется. `effect { null }` / `output { null }` — ничего.
- Проверки при сборке спеки: объявлены `initial` и все цели `goto`; состояние не объявлено дважды.
- Диаграмма: `ChatMachineSpec.toMermaid()` → вставь в PR/доку.

## Взаимодействие фич

```kotlin
// features/projects/impl — отправить интент машине чата
@Inject
class ShareToChatUseCase(private val machines: MachineRegistry) {
    suspend operator fun invoke(text: String): SendResult =
        machines.send(ChatMachineKey, ChatIntent.Public.SendPrompt(text))    // NotRunning, если чат не открыт
}

// реакция на чужую машину
machines.observe(ChatMachineKey)
    .flatMapLatest { chat -> chat?.state ?: flowOf(null) }
    .collect { state -> /* … */ }
```

- Только `Public`: тип ключа не даёт отправить `Internal` через реестр.
- Машина доступна, пока открыта её фича. Чтобы открыть фичу — `navigator.navigate(ChatRoute(…))` (скилл `navigation`),
  а не интент. Никаких синхронных ожиданий «A ждёт B, B ждёт A».

## Что в `impl`

```kotlin
// machine/ChatEffectHandler.kt — исполняет эффекты, ответы — Internal-интенты
@ContributesBinding(ChatScope::class)
@Inject
internal class ChatEffectHandler(
    private val repository: ChatRepository,
    private val agent: ChatAgent,
) : EffectHandler<ChatEffect, ChatIntent> {
    override suspend fun handle(effect: ChatEffect, machine: EffectScope<ChatIntent>) {
        when (effect) {
            is ChatEffect.Load -> machine.send(ChatIntent.Internal.Loaded(repository.load(effect.chatId).id))
            is ChatEffect.Generate -> {
                agent.generate(effect.prompt).collect { /* repository.append(…) */ }
                machine.send(ChatIntent.Internal.Completed)    // последним: уводит из Generating и отменяет эффект
            }
        }
    }
}

// di/ChatMachineBindings.kt — машина живёт в скоупе фичи
@ContributesTo(ChatScope::class)
@BindingContainer
public object ChatMachineBindings {                  // binding container — public (ADR-0002)
    @Provides
    @SingleIn(ChatScope::class)
    public fun machine(
        launcher: MachineLauncher,
        @ForScope(ChatScope::class) scope: ScopeHandle,
        effects: EffectHandler<ChatEffect, ChatIntent>,
    ): Machine<ChatState, ChatIntent, ChatOutput> = launcher.launch(ChatMachineSpec, scope, effects)
}
```

Стор экрана отражает машину — скилл `mvi-store` (`reflect`, `sendTo`).

## Изменение контракта

1. Правка состояний/интентов/переходов → обнови таблицу в KDoc спеки и `…MachineSpecTest`; `reflect` сторов не
   скомпилируется, пока `when` не покроет новые состояния — так и задумано.
2. Изменение `Public` → найди потребителей: `Grep "<Name>MachineKey"` по `features/`.
3. Удаление публичного интента или состояния — breaking change для других фич: сначала мигрируй потребителей.
4. Изменение `@Serializable`-состояний персистентной машины ломает восстановление старых данных: несовместимые
   данные отбрасываются (`consume` вернёт `null` → `initial`), проверь, что это приемлемо.
