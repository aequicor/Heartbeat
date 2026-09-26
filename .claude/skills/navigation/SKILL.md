---
name: navigation
description: "Навигация Heartbeat на Decompose (core:navigation) — Route и RouteEntry в двух реестрах (AppScope/ProfileScope), Navigator записи, вложенные StackHost/PanelsHost (планшеты: список + детали), передача данных и результатов (ResultContract), анимации переходов и раскрытие экрана из превью (NavTransition.Expand + NavSharedBounds), predictive back, deep links (DeepLinkEntry), корень платформы, логирование NAV, тесты. Используй при добавлении экранов, переходов между фичами, вложенной навигации, deep link или правке root-компонента."
---

# Навигация (core:navigation)

Решение и мотивация — [ADR-0003](../../../docs/adr/0003-navigation.md). Decompose — https://arkivanov.github.io/Decompose/ .

| Модуль | Что брать | Кто подключает |
|---|---|---|
| `core:navigation:api` | `Route`, `RouteEntry`, `routeEntry {}`, `Navigator`, `NavOptions`, `ResultContract`, `NavHostFactory`, `DeepLinkEntry`, `*RouteBinding`/`*DeepLinkBinding` | `api` и `impl` фич |
| `core:navigation:compose` | `ComposableComponent`, `NavStack`, `NavPanels`, `NavSharedBounds`, `NavAnimations` | `impl` фич, `platform-main` |
| `core:navigation:impl` | реализация | только `di-bundle` |

## 1. Маршрут фичи (`api`)

```kotlin
@Serializable @SerialName("chat")                  // стабильный id: переживает переименование класса
public data class ChatRoute(val chatId: String) : Route   // только id — маршрут сохраняется и логируется

/** Результат, если фича что-то возвращает. */
public object PickContactResult : ResultContract<ContactRef>("contacts.pick", ContactRef.serializer())
```

## 2. Регистрация (`impl/di`) — в реестр своего скоупа

```kotlin
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())   // до логина: AppScope + AppRouteBinding
@Inject
internal class ChatRouteEntry(private val factory: ChatRootComponent.Factory) :
    RouteEntry<ChatRoute>(ChatRoute::class, ChatRoute.serializer()) {
    override fun create(route: ChatRoute, context: ComponentContext, navigator: Navigator) =
        factory.create(context, route, navigator)
}
```

`binding<…>()` обязателен: квалификатор на классе Metro игнорирует, а неквалифицированная запись роняет создание корня.

## 3. Компонент фичи (`impl/component`) со своим стеком

```kotlin
@AssistedInject
internal class ChatRootComponent(
    @Assisted context: ComponentContext,
    @Assisted route: ChatRoute,
    @Assisted private val navigator: Navigator,          // навигатор ЭТОЙ записи
    hosts: NavHostFactory,
    conversation: ConversationComponent.Factory,
) : ComposableComponent, ComponentContext by context {

    val stack: StackHost = hosts.stack(
        context, navigator, name = "chat",
        initial = listOf(ConversationRoute(route.chatId)),
        local = listOf(                                  // внутренние экраны: видны только в этом стеке
            routeEntry<ConversationRoute> { r, ctx, nav -> conversation.create(ctx, r.chatId, nav) },
            routeEntry<ChatSettingsRoute> { _, ctx, nav -> ChatSettingsComponent(ctx, nav) },
        ),
        // global = GlobalRoutes.All — если чужие фичи должны открываться внутри этого стека (например, в панели деталей)
    )

    @Composable override fun Content(modifier: Modifier) = ChatRootContent(this, modifier)

    @AssistedFactory
    fun interface Factory {
        fun create(context: ComponentContext, route: ChatRoute, navigator: Navigator): ChatRootComponent
    }
}

@Composable
internal fun ChatRootContent(component: ChatRootComponent, modifier: Modifier = Modifier) {
    NavStack(component.stack, modifier)                  // анимации, predictive back, shared elements
}
```

Внутренние маршруты (`ConversationRoute`, `ChatSettingsRoute`) — `internal` в `impl`, тоже `@Serializable @SerialName`.

## 4. Переходы

```kotlin
navigator.navigate(ChatSettingsRoute)                              // локальный → в стеке chat
navigator.navigate(UserProfileRoute(id))                           // чужой → всплывает до корня (над chat)
navigator.navigate(ChatRoute(id), NavOptions(target = NavTarget.Details))    // в панель деталей ближайшего PanelsHost
navigator.navigate(HomeRoute, NavOptions(launch = LaunchMode.ReplaceAll, target = NavTarget.Root))
navigator.close()                                                  // убрать свою запись (последняя в стеке закрывает владельца)
```

`LaunchMode`: `Push`, `SingleTop`, `BringToFront` (сохраняет компонент), `ReplaceCurrent`, `ReplaceAll`.
Бизнес-переходы (следствие флоу) — событием в машину; навигацию делает эффект/компонент, подписанный на состояние машины.

## 5. Результат

```kotlin
// вызывающий (в компоненте, собирать в его scope)
navigator.navigateForResult(PickContactRoute, PickContactResult)
scope.launch { navigator.results(PickContactResult).collect { contact -> store.intent(ContactPicked(contact)) } }

// вызываемый
navigator.finishWithResult(PickContactResult, contact)            // доставляет и закрывает запись
```

Результат переживает поворот и смерть процесса, выдаётся один раз. «Назад» — без результата.
Для `navigateForResult` режимы `SingleTop` и `BringToFront` заменяют совпавшую запись новой: её получатель результата
должен соответствовать текущему запросу. Обычный `navigate` по-прежнему сохраняет компонент при повторном открытии.

## 6. Планшеты: список + детали

```kotlin
val panels: PanelsHost = hosts.panels(context, navigator, name = "chats", main = ChatListRoute)
// из списка:
navigator.navigate(ChatRoute(id), NavOptions(target = NavTarget.Details))

@Composable override fun Content(modifier: Modifier) {
    val mode = if (/* window size class из design-system */ isExpanded) ChildPanelsMode.DUAL else ChildPanelsMode.SINGLE
    NavPanels(panels, mode, modifier)
}
```

Состояние одинаково в обоих режимах (поворот/ресайз ничего не теряет); в SINGLE детали поверх списка, «назад» их закрывает.

## 7. Анимации и «раскрытие из превью»

```kotlin
// источник: карточка превью в любой фиче
NavSharedBounds(key = "chat:$id") { ChatCard(chat, onClick = {
    navigator.navigate(ChatRoute(id), NavOptions(transition = NavTransition.Expand("chat:$id")))
}) }
```

Экран назначения разворачивается из карточки и сворачивается обратно при pop — фича назначения ничего не делает.
Остальные переходы: `NavTransition.Default | Fade | Modal | None`. Свой набор аниматоров — `LocalNavAnimations provides NavAnimations(…)`.
Требуется `NavSharedTransitionLayout` вокруг корня (иначе Expand деградирует до fade).

## 8. Deep links

```kotlin
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileDeepLinkBinding>())
@Inject
internal class ChatDeepLink : DeepLinkEntry("chat/{chatId}") {           // heartbeat://chat/42, https://<web host>/chat/42
    override fun commands(params: DeepLinkParams): List<NavCommand> {
        val id = params.path("chatId")
        require(id.matches(ID_REGEX)) { "invalid chat id" }             // IllegalArgumentException → Rejected
        return listOf(
            NavCommand(HomeRoute, NavOptions(launch = LaunchMode.ReplaceAll, target = NavTarget.Root)),
            NavCommand(ChatRoute(id), NavOptions(target = NavTarget.Details)),   // попадёт в панели, созданные Home
        )
    }
}
```

Ссылка — недоверенный ввод: только навигация, никаких действий без подтверждения на экране. Схемы/хосты — `DeepLinkConfig`
(переопределяется `@ContributesBinding(AppScope::class, priority = 0)`).

## 9. Корень платформы (`platform-main`)

Готов: `HeartbeatRoot` (`platform-main:di-bundle`, пакет `…platform.dibundle.root`) + `RootContent` (`platform-main:root`).
Точке входа остаётся создать его один раз и отдать ссылки:

```kotlin
val root = HeartbeatRoot(
    context = defaultComponentContext(),               // Android; desktop — DefaultComponentContext(LifecycleRegistry()) на UI-потоке
    graph = graph,                                     // createHeartbeatGraph()
    start = RootStart(guest = listOf(WelcomeRoute), profile = listOf(HomeRoute)),   // маршруты должны быть в реестрах
)
intent.data?.let { root.handleDeepLink(it.toString()) }  // + onNewIntent / iOS onOpenURL / desktop URI handler
setContent { HbTheme { RootContent(root, loading = { Splash() }) } }
```

- Слот `Guest` / `Profile(id)` по `ProfileSessions.active`: вход, выход и смена профиля переключают дерево сами
  (фича вызывает только `profileSessions.open/close`). Профильное дерево пересоздаётся при замене графа сессии,
  в том числе при быстром выходе и повторном входе в тот же профиль, когда `StateFlow` пропустил промежуточный `null`.
- Холодный старт: `slot.child == null` («loading») до `profileSessions.restore()`; после смерти процесса слот
  восстанавливается как `Profile(id)`, а дерево создаётся из сохранённого стека, как только сессия восстановлена.
- Deep link в гостевом дереве, который оно не показывает (`NoMatch`), и ссылка во время загрузки откладываются
  (переживают смерть процесса) и применяются, когда дерево готово; в профильном дереве `NoMatch` отбрасывается с `w`-логом.
- `NavSharedTransitionLayout` уже внутри `RootContent`. Тесты: `di-bundle/src/jvmTest/.../RootIntegrationTest.kt`.

## Логирование

Хосты логируют сами (тег `NAV`, I): операция, путь хоста, `serialName` маршрута, стек как список `serialName` — без полей
маршрутов. Жизненный цикл записей — V. Deep link — шаблон, не ссылка. Фичам вручную навигацию логировать не нужно.

## Тесты (без UI)

```kotlin
val context = DefaultComponentContext(LifecycleRegistry().apply { resume() }, StateKeeperDispatcher(saved), …)
```

Навигацию проверяют через `host.stack.value.items`, `panels.value.details`, `BackDispatcher.back()`; смерть процесса —
`StateKeeperDispatcher.save()` → сериализация и десериализация `SerializableContainer` в JSON → новый контекст.
Без JSON-преобразования контейнер может сохранить исходные объекты, и сериализаторы маршрутов не будут проверены.
При несовместимом сохранённом маршруте хост начинает с исходной конфигурации и удаляет ожидающие результаты его дерева.
Примеры: `core/navigation/impl/src/commonTest`,
интеграция с Metro — `platform-main/di-bundle/src/jvmTest/.../NavigationIntegrationTest.kt`.
