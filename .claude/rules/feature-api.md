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


## Контракты ai-engine

`features/ai-engine/facade/api` содержит UI-независимые сервисные интерфейсы каталога, сессий,
истории, capabilities и SPI адаптеров, а также чистую машину активной сессии. Это явное исключение
из ограничения состава обычного feature:api. `authenticator/api` содержит общие типы источников
авторизации, проверок и ошибок, сервисы `AuthSources` / `AuthChecks` и SPI `Authenticator` / `AuthCredentials`.
Зависимость направлена `facade:api → authenticator:api`;
обратная зависимость запрещена. API конкретных движков могут зависеть от этих общих контрактов.
Пакеты `facade.api.spi` и `authenticator.api.spi` используют только модули `ai-engine` и `platform-main:di-bundle`.
Доступ к IO, UI, сторонним SDK и любым impl в этих API по-прежнему запрещён.
Машина активной сессии принадлежит объекту доступа в профиле; native runtime и принятый ход
живут в скоупе профиля и не отменяются при закрытии экрана или смене состояния машины.

`features/ai-engine/acp-interface/api` — сервисный контракт ACP v1: клиент, транспорт,
DTO и callbacks без реализации IO. Допускает kotlinx.serialization JSON для расширяемых полей
протокола. Собственной бизнес-машины и регистрации движка нет; конкретные ACP-адаптеры используют
этот контракт и фасад. Соединение принадлежит переданному скоупу профиля, отмена ожидания
потребителем не останавливает принятый ход. Доступность движков по-прежнему задают их descriptor/toggle.
Контракт даёт запуск процессов (`AcpStdioTransportFactory`), поэтому `acp-interface:api` используют только модули `ai-engine`
и `platform-main:di-bundle`.
