---
name: state-machine
description: "Создание и изменение state-machine фичи Heartbeat на KStateMachine в api-модуле — состояния, события Public/Internal, переходы, guards, эффекты onEntry, регистрация в MachineRegistry по MachineKey, отправка событий в машины других фич, тесты переходов и экспорт диаграммы. Используй при любой работе с поведением/флоу фичи или межфичевым взаимодействием."
---

# State-machine фичи

Контракт и инфраструктура (`MachineKey`, `MachineRef`, `MachineRegistry`, `MachineFactory`) — [docs/ai/feature-contract.md](../../../docs/ai/feature-contract.md). Прочитай перед началом.

## Процедура: новая машина / изменение

1. **Спроектируй на бумаге**: таблица `From | Event | Guard | To | Эффект`. Для каждого состояния ответь: как из него выйти при ошибке и при отмене? Нет выхода — баг.
2. **События** (`<Name>Event.kt`): `Public` — то, что можно слать извне; `Internal` — результаты эффектов. Данные — в полях событий.
3. **Состояния** (`<Name>State.kt`): `sealed class … : DefaultState()`; данные, которые нужны пока состояние активно — `DefaultDataState<T>` + `DataEvent<T>` + `dataTransition`. Финальное — `FinalState`.
4. **Эффекты** (`<Name>Effects.kt`): `suspend`-методы, возвращающие `<Name>Event.Internal`. Никаких IO в `api`.
5. **Спека** (`<Name>MachineSpec.kt`): `suspend fun BuildingStateMachine.<name>Machine(effects, scope)`, KDoc с таблицей переходов.
6. **Тесты** в `api/src/commonTest` (см. ниже).
7. **Регистрация** в `impl` — `MachineFactory` через `@ContributesIntoMap(AppScope::class) @StringKey("<key.name>")`.
8. **Диаграмма**: сгенерируй Mermaid (ниже) и вставь в KDoc/PR.

## API KStateMachine (0.38.x, пакет `ru.nsk.kstatemachine.*`)

```kotlin
val machine = createStateMachine(scope, name = "chat", start = false) {   // kstatemachine-coroutines
    logger = StateMachine.Logger { msg -> log.v { msg() } }               // ставит реестр
    ignoredEventHandler = StateMachine.IgnoredEventHandler { log.w { "ignored ${it.event}" } }

    addInitialState(ChatState.Idle) {
        transition<ChatEvent.Public.Open> { targetState = ChatState.Loading }
    }
    addState(ChatState.Ready) {
        transition<ChatEvent.Public.SendPrompt> {
            guard = { event.text.isNotBlank() }      // suspend-лямбда; false → переход не срабатывает
            targetState = ChatState.Generating
        }
    }
    addState(ChatState.Generating) {
        onEntry { scope.launch { machine.processEvent(effects.generate((it.event as ChatEvent.Public.SendPrompt).text)) } }
        onExit { /* отмена/очистка */ }
        transition<ChatEvent.Internal.Completed> { targetState = ChatState.Ready }
        transition<ChatEvent.Public.Cancel> {
            targetState = ChatState.Ready
            onTriggered { scope.launch { effects.cancelGeneration() } }
        }
    }
}

machine.start()
val result: ProcessingResult = machine.processEvent(ChatEvent.Public.Open("id"))   // PROCESSED | IGNORED | PENDING
machine.activeStatesFlow()          // StateFlow<Set<IState>> (kstatemachine-coroutines)
machine.exportToMermaid()           // диаграмма, также exportToPlantUml()
```

Прочие формы переходов: `transition<E>("name", targetState)`, `transitionOn<E> { targetState = { … } }` (ленивая цель),
`transitionConditionally<E> { direction = { targetState(X) / noTransition() } }`, `dataTransition<E, D> { targetState = dataState }`.
Параллельные регионы — `childMode = ChildMode.PARALLEL`. Документация: https://kstatemachine.github.io/kstatemachine/

## Правила

- Машина — **единственный** источник бизнес-флоу фичи. Стор не принимает решений, меняющих флоу, он шлёт события.
- Не вызывай `processEvent` синхронно-суспендом внутри `onEntry` с долгой работой — только `scope.launch`.
- `processEventBlocking` — никогда из колбэков машины (deadlock).
- Внешние фичи шлют только `Event.Public` через `MachineRegistry[Key].send(...)`.
- Логирование переходов/отклонений делает реестр — вручную не дублируй. Логируй в эффектах (IO, ошибки).
- Все `when` по состояниям/событиям — исчерпывающие, без `else` (detekt `ElseCaseInsteadOfExhaustiveWhen`).

## Тест переходов

```kotlin
class ChatMachineSpecTest {
    private val effects = FakeChatEffects()

    private suspend fun TestScope.machine(): StateMachine =
        createStateMachine(backgroundScope, start = false) { chatMachine(effects, backgroundScope) }

    @Test
    fun `open from Idle moves to Loading and then Ready`() = runTest {
        val machine = machine().apply { start() }
        effects.loadResult = ChatEvent.Internal.Loaded("c1")

        machine.processEvent(ChatEvent.Public.Open("c1"))
        advanceUntilIdle()

        assertTrue(ChatState.Ready in machine.activeStates())
        assertEquals(listOf("c1"), effects.loadCalls)
    }

    @Test
    fun `send prompt in Idle is ignored`() = runTest {
        val machine = machine().apply { start() }
        assertEquals(ProcessingResult.IGNORED, machine.processEvent(ChatEvent.Public.SendPrompt("hi")))
    }

    @Test
    fun `cancel during generation returns to Ready`() = runTest {
        val machine = machine()
        machine.startFrom(ChatState.Generating)       // Testing.startFrom — старт из произвольного состояния
        machine.processEvent(ChatEvent.Public.Cancel)
        advanceUntilIdle()
        assertTrue(ChatState.Ready in machine.activeStates())
        assertTrue(effects.cancelled)
    }
}
```

Покрывай: каждый разрешённый переход, ключевые запрещённые (IGNORED), ошибки эффектов → `Error`, отмену.
Запуск: `./gradlew :features:<name>:api:jvmTest`.
