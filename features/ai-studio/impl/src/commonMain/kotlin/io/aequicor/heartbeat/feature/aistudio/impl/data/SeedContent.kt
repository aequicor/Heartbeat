package io.aequicor.heartbeat.feature.aistudio.impl.data

/** Immutable demo copy of the seeded sessions. Markdown lines are joined with `\n`. */
internal object SeedContent {
    const val BUILD_PROMPT = "Сборка на Windows идёт почти четыре минуты. " +
        "Найди, что можно ускорить без смены инструментов."

    val BuildConsole = lines(
        "$ ./gradlew help --scan",
        "Configuration cache: enabled",
        "Build cache: enabled, 61% hits",
        "Daemon JVM: -Xmx2g",
    )

    val BuildReply = lines(
        "Нашёл три быстрых улучшения:",
        "",
        "1. Поднять `-Xmx` демона до **4 ГБ** — сейчас сборка упирается в GC при компиляции Compose.",
        "2. Включить `kotlin.incremental.native=true` только на macOS: на Windows iOS-таргеты не собираются.",
        "3. Разнести тяжёлые UI-тесты в отдельную задачу — быстрый контур `jvmTest` станет вдвое короче.",
        "",
        "Configuration cache и build cache уже включены, их трогать не нужно.",
    )

    const val FACADE_PROMPT = "Спроектируй фасад для AI-движков: один контракт для Koog, CLI-агентов и будущих " +
        "провайдеров. Что должно попасть в api, а что остаться в impl?"

    val FacadeConsole = lines(
        "$ ls core",
        "ai  common  datastore  di  feature-toggles  logging  mvi  navigation  network  secrets  state-machine",
        "$ rg \"interface .*Engine\" --type kotlin",
        "(совпадений нет)",
    )

    val FacadeReply = lines(
        "Предлагаю разделить фасад на **контракт** и **среду выполнения**.",
        "",
        "| Часть | Ответственность |",
        "|---|---|",
        "| `EngineCapabilities` | Что умеет движок: инструменты, стриминг, вложения |",
        "| `EngineSession` | Ход, продолжение, отмена и страницы истории |",
        "| `EngineRuntime` | Создание и продолжение выполнения, нативные события |",
        "| Контрактные тесты | Соответствие объявленных возможностей и поведения адаптера |",
        "",
        "Runtime создаётся лениво, его время жизни принадлежит профилю и активным операциям. " +
            "Смена профиля закрывает доступ и освобождает связанные ресурсы.",
        "",
        "В `facade:impl` остаются реестр регистраций, маршрутизация, проверки владения, кэши и объединение " +
            "событий. Контракт поведения активной сессии фиксируется в `api`; зависимости от Koog, " +
            "CLI-протоколов и БД туда не попадают.",
    )

    val HandoffReply = lines(
        "Перенос — отдельная операция с планом и отчётом:",
        "",
        "1. Фиксируем снимок завершённого хода.",
        "2. Проверяем возможности, авторизацию и окружение целевого движка.",
        "3. Готовим контекст под лимит целевой модели, оставляя место для ответа и инструментов.",
        "4. Создаём целевую сессию и передаём подготовленный контекст.",
        "5. Сохраняем связь с исходной сессией и отчёт о переносе.",
        "",
        "```kotlin",
        "data class SessionHandoff(",
        "    val id: TransferId,",
        "    val source: SessionRef,",
        "    val sourceRevision: HistoryRevision,",
        "    val target: SessionRef,",
        ")",
        "```",
        "",
        "Связь позволяет показать «Продолжено из…», открыть оригинал и защитить повторный перенос от дублей.",
    )

    const val TRANSFER_PROMPT = "Трансфер я бы заложил как отдельную фичу: " +
        "в сессии должна быть вся необходимая информация для переноса."

    val TransferReply = lines(
        "Согласен, трансфер лучше вынести в отдельную фичу `session-transfer`. Тогда сессии нужно хранить:",
        "",
        "- **ревизию истории** — чтобы план переноса можно было проверить перед исполнением;",
        "- **окружение** — рабочую директорию, ветку и доступные инструменты;",
        "- **ссылки на артефакты** — файлы, которые движок создал за время работы.",
        "",
        "Для первой версии достаточно снимка завершённого хода, резюме, последних сообщений и ссылок " +
            "на файлы. Структурированный импорт истории можно добавить позже, сохранив внешний контракт.",
    )

    val AdrConsole = lines(
        "$ rg -l \"SecretStore\" --type kotlin",
        "core/secrets/api/src/commonMain/kotlin/io/aequicor/heartbeat/core/secrets/SecretStore.kt",
        "core/secrets/impl/src/jvmMain/kotlin/io/aequicor/heartbeat/core/secrets/impl/DpapiSecretStore.kt",
    )

    val AdrReply = lines(
        "Аналитика в целом согласуется с кодом. Замечания:",
        "",
        "1. **Жизненный цикл ключа** — не описано, что происходит с секретами при удалении профиля. " +
            "Стоит сослаться на `ProfileStorageCleaner`.",
        "2. **Логирование** — явно зафиксируйте, что значения секретов не попадают в логи даже на `VERBOSE`.",
        "3. **Миграции** — нет плана на случай смены платформенного хранилища.",
        "",
        "Остальные решения выглядят обоснованными; блокеров нет.",
    )

    val CoreAiConsole = lines(
        "$ ./gradlew :core:ai:api:jvmTest",
        "BUILD SUCCESSFUL in 41s",
    )

    val CoreAiDiff = lines(
        "diff --git a/settings.gradle.kts b/settings.gradle.kts",
        "--- a/settings.gradle.kts",
        "+++ b/settings.gradle.kts",
        "@@ -42,3 +42,4 @@",
        " include(\":core:network:api\")",
        " include(\":core:network:impl\")",
        "+include(\":core:ai:api\", \":core:ai:impl\")",
        " include(\":core:navigation:api\")",
    )

    val CoreAiReply = lines(
        "Модуль добавлен в ветку `feature/core-ai`:",
        "",
        "- `core:ai:api` — `LlmProvider`, `PromptExecutor` и описание моделей без зависимостей от Koog;",
        "- `core:ai:impl` — адаптер Koog, логирование через `handleEvents` и ключи из `SecretStore`.",
        "",
        "Тесты используют мок `PromptExecutor`, сетевых вызовов нет.",
    )

    val BudgetConsole = lines(
        "$ python scripts/check_commit_size.py --base origin/master --head HEAD",
        "3f2a1c9  18 214 tokens  14 files  OK",
        "9b77d02  23 870 tokens  19 files  WARN  > 20 000 tokens",
        "All commits are within the 25 000 token budget.",
    )

    val BudgetReply = lines(
        "Проверка готова и подключена к CI. Бюджет — **25 000 токенов** полного diff на коммит, " +
            "предупреждение — после 20 000 токенов или 20 файлов.",
        "",
        "Подсчёт идёт по реальному diff, а не по числу строк, поэтому переименования не искажают результат.",
    )

    const val LANDING_REPLY = "Обновил лендинг в ветке `release/landing-0.3`: новые скриншоты студии в светлой " +
        "и тёмной темах, раздел о фича-тоглах и ссылку на каталог дизайн-системы. " +
        "Превью собрано, осталось утвердить тексты."

    val GreetingReply = lines(
        "Привет! Я агент студии Heartbeat. Могу:",
        "",
        "- спроектировать фичу по конвенциям проекта;",
        "- выполнить команды и показать их вывод;",
        "- внести изменения и вынести их в отдельную ветку;",
        "- провести ревью кода или аналитики.",
        "",
        "Выберите проект слева или просто опишите задачу.",
    )
}

/** Joins markdown or console lines. */
internal fun lines(vararg lines: String): String = lines.joinToString("\n")
