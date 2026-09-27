# core:profile-facade:api

Фасад профиля пользователя: сессии, граф `ProfileScope`, хранение активного профиля. От модуля может зависеть кто угодно.
Маркер `ProfileScope` и скоупы — в [core:di:api](../../di/api/README.md).

| Тип | Назначение |
|---|---|
| `ProfileSessions` | `active: StateFlow<ProfileSession?>`, `restore()`, `open(id)`, `close()`; одновременно активен только один профиль |
| `ProfileSession` | `id` + `graph` открытого профиля |
| `ProfileId` | `@Serializable` value class |
| `ProfileGraph` | `@GraphExtension(ProfileScope::class)`: `scope`, `sharedScopes`; генерируется в `di-bundle` вместе с графом приложения |
| `ActiveProfileStorage` | где хранится id активного профиля (по умолчанию в памяти, см. ниже) |

## Использование

```kotlin
// фича входа — после успешного логина
sessions.open(ProfileId(account.id))

// кнопка «Выйти» в сторе любой фичи внутри профиля — можно вызывать из корутины самого профиля
sessions.close()

// точка входа платформы — холодный старт и после смерти процесса
graph.profileSessions.restore()
graph.profileSessions.active.collect { session -> /* LoggedIn(session) / LoggedOut */ }
```

- `open` / `close` сначала пишут в хранилище, потом синхронно переключают сессии. Поэтому вызов из корутины закрываемого
  профиля не отменяет сам себя, а подписчики `active` при смене профиля не видят промежуточный `null`.
- Вызывать с main-потока: close-действия закрываемых скоупов выполняются в потоке вызывающего.
- Закрытие профиля закрывает все скоупы фич и shared-объекты под ним.

## Доступ к профильным entry point'ам

`ProfileGraph` в `core` ничего не знает о фичах. Точка входа платформы объявляет интерфейс-аксессор, а Metro делает так,
что сгенерированный граф профиля его реализует:

```kotlin
@ContributesTo(ProfileScope::class)
interface HomeAccessors { val home: HomeEntryPoint }

val home = (session.graph as HomeAccessors).home
```

`ProfileGraph.Factory` технически виден в `AppScope`, но создаёт графы только `ProfileSessions` (правило ревью).

## Хранилище активного профиля

Дефолт в `core:profile-facade:impl` хранит id в памяти (после смерти процесса профиль не восстановился бы, при старте пишется
`log.w`). В приложении его перекрывает постоянная реализация из [core:datastore:impl](../../datastore/impl/README.md)
(app-хранилище `core_profile`, ключ `active_profile_id`):

```kotlin
@ContributesBinding(AppScope::class, priority = 0)   // любое значение выше Int.MIN_VALUE
@Inject
internal class DataStoreActiveProfileStorage(/* … */) : ActiveProfileStorage
```

Реализация — [core:profile-facade:impl](../impl/README.md).
