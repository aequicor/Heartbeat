---
name: state-machine
description: "Создание и изменение state-machine фичи Heartbeat — типизированный автомат machineSpec { } в api-модуле (core:state-machine:api): состояния, интенты Public/Internal, переходы goto/stay, guards, эффекты (EffectHandler в impl) и outputs, запуск машины в скоупе фичи через MachineLauncher, адресация по MachineKey через MachineRegistry, отражение в сторе (flowmvi-ext), тесты assertTransition и диаграмма toMermaid. Используй при любой работе с поведением/флоу фичи или межфичевым взаимодействием."
---

# State-machine фичи

Контракт и модель state / intent / effect / output — KDoc `core:state-machine:api`. Прочитай перед началом.

Модули: `core:state-machine:api` (контракт + DSL + чистая семантика), `core:state-machine:impl` (рантайм на
KStateMachine — фичи его **не видят**), `core:state-machine:flowmvi-ext` (стор ↔ машина). Пакет
`io.aequicor.heartbeat.core.statemachine`.

## Процедура: новая машина / изменение

1. **Спроектируй на бумаге**: таблица `From | Intent | Guard | To | Effect / Output`. Для каждого состояния ответь:
   как из него выйти при ошибке и при отмене? Нет выхода — баг.
2. **Состояния** (`<Name>State.kt`): `sealed interface <Name>State : MachineState`; `data object` / `data class`
   с данными, нужными пока состояние активно. Объявляй листовые классы — сопоставление по точному классу.
   Персистентная машина — `@Serializable` на иерархии.
3. **Интенты** (`<Name>Intent.kt`): `Public` — извне (другие фичи, стор); `Internal` — результаты эффектов и
   внутренний ввод. Данные — в полях интентов.
4. **Эффекты** (`<Name>Effect.kt`) — команды на IO; **outputs** (`<Name>Output.kt`) — одноразовые события наружу.
5. **Ключ** (`<Name>MachineKey.kt`): `object <Name>MachineKey : MachineKey<State, Intent, Intent.Public, Effect, Output>`,
   `name` уникален (тег лога `SM/<name>`).
6. **Спека** (`<Name>MachineSpec.kt`): `val <Name>MachineSpec = machineSpec(<Name>MachineKey, initial) { … }`,
   KDoc с таблицей переходов. `persist(<Name>State.serializer()) { saved -> restore(…) }`, если флоу должен пережить
   смерть процесса: состояния, ждущие эффекта, перезапускают его или откатываются (эффекты смерть не переживают).
7. **Тесты** в `api/src/commonTest` (ниже).
8. **impl**: `EffectHandler<Effect, Intent>` с `@ContributesBinding(<Feature>Scope::class)` + `@Provides @SingleIn(<Feature>Scope::class)`
   машины через `MachineLauncher.launch(spec, scope, effects)`.
   Машина создаётся лениво — инжектируй её в корневой компонент фичи, чтобы она стартовала с открытием фичи.
9. **Диаграмма**: `<Name>MachineSpec.toMermaid()` → в KDoc/PR.

## DSL

```kotlin
machineSpec(ChatMachineKey, initial = ChatState.Idle) {
    state<ChatState.Ready> {                                   // все состояния объявлены, даже без переходов
        on<ChatIntent.Public.SendPrompt>(guard = { intent.text.isNotBlank() }) {   // guard — чистая функция
            goto<ChatState.Generating> { ChatState.Generating(state.chatId) }      // выход из состояния
            effect { ChatEffect.Generate(intent.text) }         // 0..n; null — ничего
            output { ChatOutput.Started }                       // 0..n; null — ничего
        }
        on<ChatIntent.Internal.DraftChanged> { stay { state.copy(draft = intent.text) } }  // данные без выхода
        on<ChatIntent.Public.Ping>()                            // принять и ничего не делать
    }
    state<ChatState.Error>()
    any { on<ChatIntent.Public.Reset> { goto<ChatState.Idle> { ChatState.Idle } } }   // из любого состояния
    onEffectFailure { effect, error -> ChatIntent.Internal.Failed(error.message.orEmpty()) }
    persist(ChatState.serializer()) { saved ->
        when (saved) {
            is ChatState.Generating -> restore(ChatState.Ready(saved.chatId))   // эффект не пережил смерть процесса
            else -> restore(saved)                                              // Loading: restore(saved, Load(id))
        }
    }
}
```

- `state` в лямбдах сужен до `state<T>`, `intent` — до `on<J>` (можно `on<ChatIntent.Public>` — любой публичный).
- Переход состояния приоритетнее `any`. Два подходящих перехода — `IllegalStateException`: guards взаимоисключающие.
- `goto` в тот же класс — повторный вход (эффекты состояния отменяются), `stay` — нет.

## Эффекты

- `EffectHandler.handle(effect, machine)` — `suspend`, main-safe (IO через `withContext(dispatchers.io)`).
  Результат — `machine.send(Internal…)`, можно несколько раз (стриминг, прогресс).
- Эффект живёт в скоупе состояния, из которого запущен: выход из него отменяет эффект, поздние `send` отбрасываются.
  Отмена генерации = переход `Cancel` из `Generating` — отдельный `cancel`-эффект не нужен.
- Интент, уводящий из состояния (`Completed`, `Loaded`), отправляй **последним**: код после него будет отменён.
- Исключение логируется рантаймом с throwable и мапится `onEffectFailure`; `CancellationException` изнутри живого
  эффекта (`withTimeout`) — тоже ошибка. Отмена самого эффекта (выход из состояния) — не ошибка.
  В хендлере логируй только детали IO (запрос, размер ответа) — жизненный цикл эффекта уже в логе.

## Взаимодействие

- Другие фичи: `machines.send(OtherMachineKey, Other.Public.X)` → `Accepted | Ignored | NotRunning`;
  `machines.observe(Key)` — машина появляется/исчезает вместе с фичей. `Internal` снаружи не отправить (тип ключа).
- Машина живёт в скоупе фичи: закрыта фича — `NotRunning`. Открыть фичу — навигация (скилл `navigation`).
- Стор экрана: `reflect(machine) { when (it) { … } }` и `sendTo(machine, intent) { rejected -> … }` — скилл `mvi-store`.

## Правила

- Машина — **единственный** источник бизнес-флоу фичи. Стор не решает «можно ли», он шлёт интент и реагирует на
  `Ignored`.
- В `api` нет IO, KStateMachine и FlowMVI (хук `check-conventions.sh`). Лямбды спеки чистые.
- Логирование (интенты, переходы, `stay`, отклонения, эффекты, outputs, старт/стоп) делает рантайм — вручную не
  дублируй. В сообщениях только имена классов.
- Все `when` по состояниям/интентам/эффектам — исчерпывающие, без `else`.

## Тест переходов (без рантайма)

```kotlin
class ChatMachineSpecTest {
    @Test
    fun `open from Idle loads the chat`() {
        ChatMachineSpec.assertTransition(
            from = ChatState.Idle,
            intent = ChatIntent.Public.Open("c1"),
            to = ChatState.Loading("c1"),
            effects = listOf(ChatEffect.Load("c1")),
        )
    }

    @Test
    fun `blank prompt is ignored`() {
        ChatMachineSpec.assertIgnored(ChatState.Ready("c1"), ChatIntent.Public.SendPrompt(" "))
    }

    @Test
    fun `completion returns to Ready and notifies`() {
        ChatMachineSpec.assertTransition(
            from = ChatState.Generating("c1"),
            intent = ChatIntent.Internal.Completed,
            to = ChatState.Ready("c1"),
            outputs = listOf(ChatOutput.Generated),
        )
    }
}
```

Покрывай: каждый разрешённый переход, ключевые запрещённые (`assertIgnored`), ошибки эффектов (`onEffectFailure`),
отмену. `EffectHandler` тестируй отдельно в `impl/commonTest` с фейковым `EffectScope`, собирающим интенты.
Запуск: `./gradlew :features:<name>:api:jvmTest`.
