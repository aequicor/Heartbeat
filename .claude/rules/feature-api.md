---
paths:
  - "features/*/api/**"
---

# Модуль `features/<name>/api` — контракт фичи

Это **спецификация поведения** фичи. Подробно: `docs/ai/feature-contract.md`, процедура — скилл `state-machine`.

Содержит только:
- `<Name>Event` — sealed-иерархия событий; публичные (для других фич) — `<Name>Event.Public`, внутренние (от эффектов) — `<Name>Event.Internal`.
- `<Name>State` — sealed-иерархия состояний машины (KStateMachine), с данными через `DataState`.
- `object <Name>MachineKey : MachineKey<<Name>Event.Public>` — единственный способ адресовать машину извне.
- `<Name>MachineSpec` — функция/класс, строящий граф состояний и переходов. Без IO: побочные эффекты — через интерфейс `<Name>Effects`.
- `<Name>Effects` — интерфейс эффектов (реализует `impl`).
- Публичные маршруты `@Serializable @SerialName("<name>") <Name>Route : Route` и `ResultContract`-ы (если фича открывается извне / возвращает результат). Скилл `navigation`.
- Определения тоглов фичи, если их читают другие модули.

Запрещено:
- Compose / UI / `design-system`, ресурсы экранов.
- Зависимости на `core:network`, `core:ai`, `core:datastore` и любые `impl`.
- Логика с IO, репозитории, реализации.

Каждое изменение состояний/переходов:
1. Обнови KDoc спеки и таблицу переходов в KDoc `<Name>MachineSpec`.
2. Обнови/добавь тест графа переходов в `api/src/commonTest` (переходы тестируются без impl — с фейковыми эффектами).
3. Проверь потребителей `<Name>MachineKey` в других фичах (`Grep`), если меняется `Event.Public`.
