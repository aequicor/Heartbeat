# core:di:api

Контракты DI-скоупов Heartbeat. Только интерфейсы и маркеры, без Essenty, Decompose и FlowMVI. От модуля может зависеть кто угодно.

```
AppScope (Metro)     HeartbeatGraph   platform-main:di-bundle
└ ProfileScope       ProfileGraph     core:profile-facade:api
  ├ <Feature>Scope   <Feature>Graph   features/<x>/impl
  │ └ screen         без графа        retainedScope() из core:di:ext
  └ shared:<key>     без графа        SharedScopes
```

| Тип | Назначение |
|---|---|
| `ProfileScope` | маркер скоупа профиля (`AppScope` — встроенный в Metro) |
| `@ForScope(X::class)` | квалификатор того, что есть на каждом уровне: `ScopeHandle`, `CoroutineScope` |
| `ScopeHandle` | имя-путь (`app/profile/chat`), `coroutineScope`, `savedState`, `onClose`, `isClosed` |
| `OwnedScope` | `ScopeHandle` + `close()`: его получает только создатель скоупа |
| `ScopeSavedState`, `SavedBundle` | состояние скоупа, переживающее смерть процесса |
| `ScopeFactory` | создание дочернего скоупа. Напрямую почти не нужен, вместо него экстеншены из `core:di:ext` |
| `SharedKey`, `SharedFactory`, `SharedScopes`, `Lease` | объект, общий для нескольких фич, с подсчётом ссылок |

## Скоуп в классе

```kotlin
@SingleIn(ChatScope::class)
@ContributesBinding(ChatScope::class)
@Inject
internal class ChatDraftRepositoryImpl(
    @ForScope(ChatScope::class) private val scope: ScopeHandle,
) : ChatDraftRepository {
    private val log = Log.tag("ChatDraftRepository")

    // переживает смерть процесса, если скоуп принадлежит компоненту (retainedGraph / retainedScope)
    private var draft: String? = scope.savedState.consume(KEY, String.serializer())

    init {
        scope.savedState.register(KEY, String.serializer()) { draft }
        scope.onClose { log.d { "draft repository released" } }
    }

    fun sync() = scope.coroutineScope.launch { /* main-диспетчер; IO — через withContext */ }
}
```

## Семантика

- **Корутины** скоупа выполняются на main-диспетчере, как `viewModelScope`. Упавшая корутина логируется и не отменяет соседние.
- **Закрытие** синхронное: сначала отмена корутин (без ожидания их завершения), затем `onClose`-действия в обратном порядке.
  Дочерний скоуп — одно из close-действий родителя, зарегистрированное при создании. Закрытие профиля каскадно
  закрывает фичи и shared-объекты; соседние скоупы закрываются от младшего к старшему.
- **Состояние** (`savedState`) сохраняется только у скоупов, которые принадлежат компоненту (`retainedGraph` / `retainedScope`).
  То, что регистрируется в app-, profile- и shared-скоупах, при смерти процесса теряется.
- **Потоки**: скоупы создаются и закрываются на main-потоке; `ScopeSavedState` и `SharedScopes` тоже main-only.
  Потокобезопасен только `onClose`.

## Shared-объект

```kotlin
// api фичи-владельца
object UploadSessionKey : SharedKey<UploadSession> { override val name = "upload-session" }

// impl фичи-владельца
@ContributesIntoMap(ProfileScope::class)
@StringKey("upload-session")          // == UploadSessionKey.name
@Inject
internal class UploadSessionFactory(private val api: UploadApi) : SharedFactory<UploadSession> {
    override fun create(scope: ScopeHandle): UploadSession = UploadSessionImpl(api, scope)
}
```

В компоненте — `retainedShared(sharedScopes, UploadSessionKey)` из [core:di:ext](../ext/README.md).
Бизнес-состояние, которое должно пережить смерть процесса, сюда не кладут: оно живёт в state-machine.

Реализация — [core:di:impl](../impl/README.md).
