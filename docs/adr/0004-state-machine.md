# ADR-0004: Типизированные state-machine фич: `core:state-machine:{api,impl,flowmvi-ext}`

- Статус: принято
- Дата: 2026-09-26
- Затрагивает: `core:state-machine:{api,impl,flowmvi-ext}`, `platform-main:di-bundle`, все фичи, `docs/ai/feature-contract.md`
  (заменяет его прежний контракт `MachineKey<E>` / `activeStates: Set<IState>` / AppScope-реестр фабрик),
  последствие ADR-0002 про машины в `ProfileScope`

## Контекст

ADR-0001 требует, чтобы поведение фичи было автоматом в её `api`, а фичи общались через object-key. Первоначальный
контракт отдавал наружу KStateMachine как есть: нетипизированное `activeStates: Set<IState>`, события без данных у
состояний, эффекты — колбэки `onEntry` с ручным `scope.launch`. Стор FlowMVI должен был разбирать `Set<IState>` через
приведения типов, а спека машины требовала рантайма даже в тестах.

Требования:
- машина MVI-подобна: **state / intent / effect**; описывается в `api` фичи и перечисляет все свои состояния;
- эффекты двух видов: команды для `impl` (IO) и одноразовые события наружу;
- машина — часть фичи: живёт в скоупе фичи (ADR-0002), а не в `AppScope`;
- взаимодействие машин — через `object`-ключ;
- стор FlowMVI отражает машину; вся жизнь машины (интенты, переходы, эффекты, outputs) логируется.

## Решение

### Модули

| Модуль | Содержимое | Кто зависит |
|---|---|---|
| `core:state-machine:api` | маркеры `MachineState/Intent/Effect/Output`, `MachineKey<S, I, P, E, O>`, `MachineRef`, `Machine`, `SendResult`, DSL `machineSpec { }` и **чистая семантика** `MachineSpec.resolve`, `assertTransition`/`assertIgnored`, `toMermaid()`, контракты `MachineLauncher`, `MachineRegistry`, `EffectHandler` | api и impl фич |
| `core:state-machine:impl` | рантайм на KStateMachine 0.38.1: запуск в скоупе, реестр живых машин, логи `SM/<name>` | только di-bundle |
| `core:state-machine:flowmvi-ext` | FlowMVI 3.2.1: `StoreBuilder.reflect(machine) { }`, `PipelineContext.sendTo(machine, intent)` | impl фич |

`api` не зависит ни от KStateMachine, ни от FlowMVI.

### Модель

- **State** — `sealed interface <Name>State : MachineState` из `data object`/`data class`; каждое состояние объявляется
  `state<T> { }` (листовые классы, сопоставление по точному классу).
- **Intent** — `Public` (шлют другие фичи через ключ) и `Internal` (сама фича и её эффекты). Ключ типизирован `P = Public`.
- **Effect** — команда, объявленная переходом (`effect { }`), исполняется `EffectHandler` из `impl`; результат
  возвращается Internal-интентом (`machine.send`, можно многократно — стриминг). Эффект живёт в скоупе состояния:
  выход из состояния (`goto`, в т.ч. в тот же класс) отменяет его и отбрасывает поздние результаты; `stay` не отменяет.
  Исключение → лог + `onEffectFailure { effect, error -> Internal.Failed }`.
- **Output** — одноразовое событие наружу (`output { }`), горячий поток без replay.
- Переход: `on<Intent>(guard) { goto<T> { … } | stay { … }; effect { … }; output { … } }`; `any { }` — из любого
  состояния, переход состояния приоритетнее. Два подходящих перехода — ошибка (guards взаимоисключающие).
- `persist(serializer) { saved -> restore(state, effects…) }` — состояние сохраняется в `ScopeSavedState` скоупа фичи
  и переживает смерть процесса. Эффекты не переживают, поэтому состояния, ждущие эффекта, маппятся при восстановлении
  (перезапуск эффекта или откат); незнакомое спеке сохранённое состояние → `initial`.

### Рантайм

- Семантика одна — `MachineSpec.resolve` в `api`: её же проверяют тесты фич без рантайма.
- `impl` строит граф KStateMachine (узел на класс состояния, переход на объявление, guard = «выбран спекой»),
  создаёт движок на std-lib-абстракции (синхронная обработка, без смены корутины), обрабатывает интенты по одному под
  мьютексом, хранит типизированное состояние в `StateFlow`, запускает эффекты, эмитит outputs.
- `MachineLauncher.launch(spec, scope, effects)` вызывается графом фичи (`@Provides @SingleIn(<Feature>Scope)`),
  регистрирует машину в `MachineRegistry` (`@SingleIn(AppScope)`); закрытие скоупа фичи (в т.ч. каскадом от профиля)
  останавливает машину и снимает её с реестра. Несколько экземпляров фичи — адресуется последний. Создание ленивое
  (первая инъекция), корневой компонент фичи инжектит машину сразу. Реестр потокобезопасен (атомарная таблица слотов)
  и отдаёт другим фичам обёртку `MachineRef`, а не саму машину.
- `CancellationException` из ещё активного эффекта (`withTimeout`) — ошибка эффекта (`onEffectFailure`), а не отмена:
  иначе машина ждала бы результата вечно.
- `MachineRegistry.send(key, intent)` к незапущенной машине → `SendResult.NotRunning` (WARN), а не ожидание:
  открыть фичу — навигация (ADR-0003), не машина.

### Логирование

Всё централизованно, фичи не дублируют: приход интента с источником (D), переход (I), `stay` (D), отклонённый
интент (W), эффект: старт/завершение/отмена (D) и ошибка (E + throwable), output (D), старт/restore/стоп (I),
регистрация (D), отправка через реестр (I) / `NotRunning` (W), трассировка движка (V). В сообщениях — только имена
классов (данные состояний и интентов могут содержать пользовательский ввод и секреты). flowmvi-ext логирует каждое
отражённое обновление стора и output под `MVI/<store>`, отклонённые интенты — W.

## Альтернативы

| Вариант | Плюсы | Минусы | Почему нет |
|---|---|---|---|
| KStateMachine напрямую в `api` фич (прежний контракт) | меньше кода в core | нетипизированный `Set<IState>`, данные вне состояний, эффекты-колбэки, тест только с рантаймом | не отражается в стор без приведений, не MVI |
| Свой движок без KStateMachine | проще, меньше зависимостей | нет графа движка/экспорта, отказ от ADR-0001 | выбран KStateMachine как движок в `impl`; `api` от него не зависит, заменить можно без правок фич |
| Машины в `AppScope`/`ProfileScope` с реестром фабрик | машина доступна до открытия фичи | живёт дольше фичи, данные профиля переживают экран | машина — часть фичи; доступ к закрытой фиче — через навигацию |
| Одно `effect` для команд и событий наружу | меньше понятий | стор и чужие фичи видят IO-команды | разделено на Effect и Output |

## Последствия

- Фича: `api` — ключ, состояния, интенты, эффекты, outputs, `<Name>MachineSpec`, тесты `assertTransition`;
  `impl` — `EffectHandler` (`@ContributesBinding(<Feature>Scope)`) и `@Provides` машины в графе фичи; стор — `reflect`.
- Машина недоступна, пока фича закрыта; фоновые флоу вне экранов — отдельная фича со своим скоупом или ADR.
- KStateMachine и FlowMVI в `api` фич запрещены (хук `check-conventions.sh`; зависимость `…:api` → `flowmvi-ext`
  роняет конфигурацию Gradle, `ModuleBoundaries.kt`); `core:state-machine:impl` видит только di-bundle.
- Обновлены: `docs/ai/feature-contract.md`, `architecture.md`, `logging-policy.md`, скиллы `state-machine`, `mvi-store`,
  `logging`, `di-metro`, `new-feature`, правила `feature-api`/`feature-impl`/`tests`, агенты.
- `core:mvi` (`heartbeatStore`) по-прежнему нужен: flowmvi-ext не создаёт сторы, а ставит плагины в их билдер.
