# core:di:impl

Реализация контрактов [core:di:api](../api/README.md). **Подключается только в `:platform-main:di-bundle`.**
Любой другой модуль, который зависит от `…:impl`, роняет конфигурацию Gradle (`build-logic/.../ModuleBoundaries.kt`).

| Класс | Роль |
|---|---|
| `ScopeHandleImpl` | `SupervisorJob` родителя + `CoroutineExceptionHandler` (лог); close-действия на атомиках: закрытие идемпотентно, `onClose` после закрытия выполняется сразу |
| `ScopeSavedStateImpl` | значения хранятся как JSON-строки; невостребованные восстановленные значения переживают следующий снапшот; нечитаемые данные отбрасываются с `log.w` |
| `ScopeFactoryImpl` | `child()`: имя `parent/name`, связь с родителем (закрытие каскадом, отвязка при явном закрытии), лог `created` / `restored` |
| `SharedScopesImpl` | `ProfileScope`: подсчёт ссылок по `SharedKey.name`, скоуп `shared:<key>` на объект |
| `AppScopeBindings` | корневой скоуп `app` (`@ForScope(AppScope::class)` `ScopeHandle` и `CoroutineScope`); никогда не закрывается |
| `ProfileScopeBindings` | `@ForScope(ProfileScope::class) CoroutineScope`, пустой мультибиндинг `SharedFactory` |

## Почему binding container'ы `public`

`generateContributionProviders` (включён в `heartbeat.metro`) позволяет держать `@ContributesBinding`-реализации `internal`,
но на `@BindingContainer` не распространяется. `internal`-контейнер молча не попадёт в граф, который генерируется в другом
модуле, и получится `MissingBinding`. Модуль видит только `di-bundle`, так что API для фич от этого не расширяется.

## Логи

Тег `DI`: создание, восстановление и закрытие скоупа (`I`), восстановленные и отброшенные значения состояния (`D` / `W`),
ошибки close-действий и корутин скоупа (`E`). Тег `SharedScopes`: захват и освобождение shared-объектов (`D`).

## Тесты

- `./gradlew :core:di:impl:jvmTest`: порядок закрытия, идемпотентность, отказоустойчивость close-действий,
  supervisor-поведение, сохранение состояния.
- Интеграция с реальным графом — в [platform-main/di-bundle](../../../platform-main/di-bundle/README.md).
