---
paths:
  - "features/**/api/**"
---

# Модуль `features/<name>/api` — контракт фичи

Это **спецификация поведения** фичи. Процедура — скилл `state-machine`.

Содержит только (всё на `core:state-machine:api`):
- `<Name>State : MachineState` — sealed-иерархия **всех** состояний (`data object` / `data class` с данными; `@Serializable`, если машина `persist`).
- `<Name>Intent : MachineIntent` — `Public` (шлют другие фичи и стор) и `Internal` (результаты эффектов, внутренний ввод).
- `<Name>Effect : MachineEffect` — команды на IO (исполняет `EffectHandler` в `impl`); `<Name>Output : MachineOutput` — одноразовые события наружу.
- `object <Name>MachineKey : MachineKey<State, Intent, Intent.Public, Effect, Output>` — единственный способ адресовать машину извне.
- `<Name>MachineSpec` — `machineSpec(<Name>MachineKey, initial) { … }`: состояния, переходы, эффекты, outputs. Лямбды чистые, без IO.
- Публичные маршруты `@Serializable @SerialName("<name>") <Name>Route : Route` и `ResultContract`-ы (если фича открывается извне / возвращает результат). Скилл `navigation`.
- Определения тоглов фичи, если их читают другие модули.

Запрещено:
- Compose / UI / `design-system`, ресурсы экранов.
- Зависимости на `core:network`, `core:ai`, `core:datastore` и любые `impl`.
- KStateMachine и FlowMVI (движок — внутренность `core:state-machine:impl`, стор — в `impl` фичи).
- Логика с IO, репозитории, реализации.

Каждое изменение состояний/переходов:
1. Обнови KDoc спеки и таблицу переходов в KDoc `<Name>MachineSpec`.
2. Обнови/добавь тесты переходов в `api/src/commonTest`: `assertTransition` / `assertIgnored` — без рантайма и фейков.
3. Проверь потребителей `<Name>MachineKey` в других фичах (`Grep`), если меняется `Intent.Public` или состояния.
