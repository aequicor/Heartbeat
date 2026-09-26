# core:di:ext

Привязывает DI-скоупы к жизни компонента (Essenty `InstanceKeeper` + `StateKeeper`, то есть любой Decompose `ComponentContext`).
Потребители — `impl` фич и `platform-main`. `core:mvi` и `core:navigation` от этого модуля не зависят.

| Экстеншен | Что удерживает | Поворот экрана | Смерть процесса | Уничтожение компонента |
|---|---|---|---|---|
| `retainedGraph(scopes, parent, name) { scope -> graph }` | граф дочернего скоупа | тот же экземпляр | новый граф, `savedState` восстановлен | скоуп закрыт |
| `retainedScope(scopes, parent, name)` | `ScopeHandle` без графа (скоуп экрана) | тот же | новый, `savedState` восстановлен | закрыт |
| `retainedShared(sharedScopes, Key)` | ссылку на shared-объект | та же | берётся заново | ссылка отпущена |

## Граф фичи

```kotlin
@ContributesBinding(ProfileScope::class)
@Inject
internal class ChatEntryPointImpl(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: ChatGraph.Factory,
) : ChatEntryPoint {
    override fun create(ctx: ComponentContext, args: ChatArgs): ChatComponent =
        ctx.retainedGraph(scopes, profile, name = "chat") { scope -> graphs.create(args, scope) }
            .rootFactory.create(ctx)
}
```

## Скоуп экрана

```kotlin
@AssistedInject
internal class ChatScreenComponent(
    @Assisted ctx: ComponentContext,
    scopes: ScopeFactory,
    @ForScope(ChatScope::class) chat: ScopeHandle,
    storeFactory: ChatScreenStore.Factory,
) : ComponentContext by ctx {
    private val screen = retainedScope(scopes, chat, name = "screen")
    // стор получает screen.coroutineScope / screen.savedState
}
```

## Подводные камни

- Лямбда `create` выполняется только при (пере)создании. Значения, захваченные при первом вызове, а также
  `parent` и `name`, сохраняются; повторные вызовы с другими значениями получат уже удерживаемый граф.
- В retained-объекты не передавай `ComponentContext` или `Activity`, только `ScopeHandle`. Иначе будет утечка при повороте.
- Если в одном компоненте удерживается несколько объектов, у каждого должен быть свой `key`.
  `retainedGraph` и `retainedScope` с одинаковым ключом конфликтуют.
- Если `parent` уже закрыт, будет `IllegalStateException`.
- Снапшот состояния регистрируется в `StateKeeper` на **каждом** экземпляре компонента. Если регистрироваться один раз,
  изменения, сделанные после поворота экрана, пропадут при следующей смерти процесса. Это покрыто тестом в
  [di-bundle](../../../platform-main/di-bundle/README.md).
