# core:mvi

`HeartbeatStoreFactory` создаёт FlowMVI-стор с `DispatcherProvider.main`, последовательными
интентами и обязательным `onError: State.(Exception) -> State`. `CancellationException`
пробрасывается. Логируются жизненный цикл и имена типов, без содержимого state/intent/action.
Диагностика FlowMVI с дампами состояния отключена; ошибки проходят через `core:logging`.

Фича вызывает `store.start(featureScope.coroutineScope)` один раз в retained-модели.
Compose подписывается через `store.collect { states.collect { … } }`; `reflect(machine)`
наблюдает машину, пока есть подписчик. Машина и её эффекты живут в скоупе фичи независимо
от пересоздания UI. Навигация с подтверждением остаётся у компонента и машины.

Примеры: `features/welcome/impl` и `features/toggles-panel/impl`.
Проверка: `./gradlew :core:mvi:jvmTest`.
