---
name: new-feature
description: "Пошаговое создание новой фичи Heartbeat — модули features/<name>/api и impl, регистрация в Gradle, state-machine в api, эффекты, DI-контрибуции, стор, компоненты, экраны, тоглы, тесты и проверка. Используй, когда пользователь просит добавить новую фичу/раздел/экран-флоу."
argument-hint: "<feature-name> [краткое описание]"
---

# Новая фича: `$ARGUMENTS`

Имя фичи — kebab-case для Gradle (`chat-history`), camelCase в accessors (`chatHistory`), lower без дефисов в пакете (`chathistory`).

## 0. Дизайн (обязательно для нетривиальной фичи)

Запусти субагента `feature-architect` с описанием фичи. Согласуй с пользователем таблицу переходов и публичные события, если есть неясности.

## 1. Модули

```
features/<name>/
  api/build.gradle.kts    plugins { id("heartbeat.feature.api") }
  api/src/commonMain/kotlin/io/aequicor/heartbeat/feature/<pkg>/api/
  api/src/commonTest/kotlin/…
  impl/build.gradle.kts   plugins { id("heartbeat.feature.impl") }  dependencies { implementation(projects.features.<name>.api) }
  impl/src/commonMain/kotlin/io/aequicor/heartbeat/feature/<pkg>/impl/{di,machine,data,component,store,ui}/
  impl/src/commonMain/composeResources/values/strings.xml
  impl/src/commonTest/kotlin/…
```

- `settings.gradle.kts`: `include(":features:<name>:api", ":features:<name>:impl")`.
- `platform-main:*`: добавь `implementation(projects.features.<name>.impl)` — иначе Metro не увидит контрибуции.
- Если `build-logic` ещё нет — сначала скилл `module-setup`.

## 2. `api` — контракт (скилл `state-machine`)

1. `<Name>MachineKey`, `<Name>State`, `<Name>Intent` (`Public`/`Internal`), `<Name>Effect`, `<Name>Output`, `<Name>MachineSpec = machineSpec { }` с KDoc-таблицей.
2. `<Name>Route` (`@Serializable @SerialName`) и `ResultContract`, если фичу открывают извне / она возвращает результат (скилл `navigation`).
3. Тоглы, которые читают другие фичи (скилл `feature-toggle`).
4. Тесты переходов (`assertTransition` / `assertIgnored`) → `./gradlew :features:<name>:api:jvmTest`.

## 3. `impl` — исполнение

1. `machine/`: `<Name>EffectHandler` (`@ContributesBinding(<Name>Scope::class)`); `di/<Name>MachineBindings` — `@Provides @SingleIn(<Name>Scope::class)` машины через `MachineLauncher`. Скиллы `state-machine`, `di-metro`.
2. `data/`: репозитории, DAO/DataStore/сеть (скиллы `data-storage`, `network`), ИИ — скилл `ai-koog`.
3. `store/`: FlowMVI-сторы экранов, отражающие машину через `reflect` (скилл `mvi-store`).
4. `component/`: Decompose-компоненты (`ComposableComponent`), `<Name>RootComponent` со своим `StackHost`/`PanelsHost` (скилл `navigation`).
5. `ui/`: экраны только из `Hb*` + токены (скилл `design-system`), `@Preview` light/dark.
6. `di/`: `RouteEntry` и `DeepLinkEntry` (`@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())`), тоглы (`@ContributesIntoSet`).
7. Новая функциональность под тоглом `<name>.enabled` с `default = false`, пока фича не готова.

## 4. Интеграция

- Открытие из другой фичи — `navigator.navigate(<Name>Route(…))`; бизнес-взаимодействие — `machineRegistry.send(<Name>MachineKey, Public intent)` (пока фича открыта).
- Проверь, что фичи-потребители зависят только от `api`.

## 5. Проверка

1. Скилл `verify` (компиляция, jvmTest, detekt).
2. Субагенты `architecture-reviewer` и `ui-reviewer` на изменённые файлы; исправь блокеры.
3. Запусти desktop (`./gradlew :platform-main:desktop:run`) и пройди основной флоу, проверь логи машины (`SM/<name>`: интенты, переходы, эффекты).

## Чек-лист готовности

- [ ] Машина покрыта тестами, диаграмма в KDoc
- [ ] Нет `impl → impl`, `api` без UI
- [ ] Все действия и IO залогированы (централизованно или вручную)
- [ ] Строки в ресурсах, цвета — токены
- [ ] Тогл заведён и виден в панели тоглов
- [ ] detekt зелёный
