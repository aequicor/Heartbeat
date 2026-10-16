package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCondition
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFormat
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigSection
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigUnit
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntriesShape
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntryField
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntryMatch
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntryVariant
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListSyntax
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.TextRole
import kotlinx.serialization.json.JsonPrimitive

/** Documents of Pi's configuration; [Host] holds Heartbeat's own launch choices and is never written to Pi files. */
internal object PiDocuments {
    val Settings = ConfigDocumentId("settings")
    val Mcp = ConfigDocumentId("mcp")
    val Agents = ConfigDocumentId("agents")
    val System = ConfigDocumentId("system")
    val AppendSystem = ConfigDocumentId("append_system")
    val Host = ConfigDocumentId("heartbeat")
}

/** Keys of the [PiDocuments.Host] document the adapter interprets at launch. */
internal object PiHostKeys {
    val Mcp = host("builtins", "mcp")
    val Codemode = host("builtins", "codemode")
    val ToolSearch = host("builtins", "toolSearch")
    val LlamaCpp = host("builtins", "llamaCpp")
    val ProjectContextFiles = host("projectContextFiles")
    val Offline = host("environment", "offline")
    val LongCacheRetention = host("environment", "longCacheRetention")
    val ExtensionTools = host("extensionTools")
}

/**
 * What the engine control panel configures for Pi (pi.dev settings, MCP servers, instructions and resources) as Pi
 * runs inside Heartbeat in RPC mode. Terminal-only settings (themes, keybindings, fullscreen, editor) have no effect
 * there and stay reachable only through the raw JSON editor. Model, thinking level, sessions, tools and project trust
 * are Heartbeat's own and listed in [EngineConfigSchema.ownedKeys].
 */
internal val PiConfigSchema: EngineConfigSchema by lazy {
    EngineConfigSchema(
        documents = piDocuments(),
        pages = listOf(
            page(
                "model",
                t("Model and cache", "Модель и кэш"),
                chosenSection(),
                budgetsSection(),
                cacheSection(),
                images(),
            ),
            page("compaction", t("Context compaction", "Сжатие контекста"), compactionSection()),
            page("network", t("Network and retries", "Сеть и повторы"), networkSection(), retrySection()),
            page("shell", t("Shell and system", "Оболочка и система"), shellSection(), codemodeSection(), privacy()),
            page("instructions", t("Instructions", "Инструкции"), documentsSection(), contextSection()),
            page("resources", t("Extensions and skills", "Расширения и навыки"), builtins(), paths(), packages()),
            page("mcp", t("MCP", "MCP"), serversSection()),
        ),
        ownedKeys = ownedKeys(),
        capabilities = setOf(ConfigCapability.ImportNative, ConfigCapability.Secrets, ConfigCapability.ProjectTrust),
    )
}

private fun piDocuments() = listOf(
    document(PiDocuments.Settings, t("Settings", "Настройки"), ConfigFormat.Json, "settings.json"),
    document(PiDocuments.Mcp, t("MCP servers", "MCP-серверы"), ConfigFormat.Json, "mcp.json"),
    document(PiDocuments.Agents, t("Instructions", "Инструкции"), ConfigFormat.Text, "AGENTS.md"),
    document(PiDocuments.System, t("System prompt", "Системный промпт"), ConfigFormat.Text, "SYSTEM.md"),
    document(
        PiDocuments.AppendSystem,
        t("System prompt addition", "Дополнение системного промпта"),
        ConfigFormat.Text,
        "APPEND_SYSTEM.md",
    ),
    ConfigDocumentSpec(
        PiDocuments.Host,
        t("Heartbeat launch", "Запуск в Heartbeat"),
        ConfigFormat.Json,
        setOf(ConfigScope.Profile),
        "heartbeat",
        isHostOnly = true,
    ),
)

private fun chosenSection() = section(
    "chosen",
    t("Model and thinking", "Модель и размышления"),
    note(
        "model",
        settings("defaultModel"),
        t(
            "The model is chosen in the chat and in Models and engines.",
            "Модель выбирается в чате и в «Модели и движки».",
        ),
    ),
    note(
        "thinking",
        settings("defaultThinkingLevel"),
        t("The thinking level is chosen in the chat.", "Уровень размышлений выбирается в чате."),
    ),
)

private fun budgetsSection() = ConfigSection(
    "budgets",
    t("Thinking budgets", "Бюджеты размышлений"),
    listOf("minimal", "low", "medium", "high").map { level ->
        ConfigField(
            "budget_$level",
            t("Level $level", "Уровень $level"),
            settings("thinkingBudgets", level),
            tokens(),
            description = t("Where the provider takes a token budget.", "Если поставщик принимает бюджет токенов."),
        )
    },
)

private fun cacheSection() = section(
    "cache",
    t("Prompt cache", "Кэш промптов"),
    ConfigField(
        "cache_warming",
        t("Cache warming", "Прогрев кэша"),
        settings("cacheWarming"),
        choice(
            "streaming",
            "off" to t("Off", "Выключен"),
            "streaming" to t("During runs", "Во время ходов"),
            "idle" to t("Between runs", "Между ходами"),
        ),
        scopes = ProfileOnly,
    ),
    ConfigField(
        "cache_long",
        t("Extended cache retention", "Долгое хранение кэша"),
        PiHostKeys.LongCacheRetention,
        ConfigFieldKind.Flag(isOnByDefault = false),
        description = t("Where providers support it.", "Если поставщик это поддерживает."),
        scopes = ProfileOnly,
    ),
)

private fun images() = section(
    "images",
    t("Images", "Изображения"),
    flag("images_resize", t("Resize to 2000×2000", "Уменьшать до 2000×2000"), settings("images", "autoResize"), true),
    flag("images_block", t("Never send images", "Не отправлять изображения"), settings("images", "blockImages"), false),
)

private fun compactionSection() = section(
    "compaction",
    t("Automatic compaction", "Автоматическое сжатие"),
    flag("compaction_enabled", t("Compact automatically", "Сжимать автоматически"), compaction("enabled"), true),
    ConfigField(
        "compaction_reserve",
        t("Reserved for the answer", "Резерв под ответ"),
        compaction("reserveTokens"),
        tokens(default = 16_384),
    ),
    ConfigField(
        "compaction_keep",
        t("Recent context kept", "Сохраняемый свежий контекст"),
        compaction("keepRecentTokens"),
        tokens(default = 20_000),
    ),
    ConfigField(
        "compaction_models",
        t("Per model", "Для моделей"),
        compaction("modelOverrides"),
        ConfigFieldKind.Entries(
            EntriesShape.NamedMap,
            t("Model provider/id", "Модель provider/id"),
            listOf(
                EntryVariant(
                    "override",
                    t("Override", "Переопределение"),
                    EntryMatch.AnyObject,
                    listOf(
                        EntryField(
                            "reserve",
                            t("Reserved for the answer", "Резерв под ответ"),
                            listOf("reserveTokens"),
                            tokens(),
                        ),
                        EntryField(
                            "keep",
                            t("Recent context kept", "Свежий контекст"),
                            listOf("keepRecentTokens"),
                            tokens(),
                        ),
                    ),
                ),
            ),
            keyPattern = "[^/\\s]+/\\S+",
        ),
        isAdvanced = true,
    ),
    note = t(
        "Heartbeat can compact the context itself (Settings → Context); keep one of them on.",
        "Heartbeat умеет сжимать контекст сам («Настройки → Контекст»); оставьте включённым что-то одно.",
    ),
)

private fun networkSection() = section(
    "network",
    t("Network", "Сеть"),
    ConfigField(
        "transport",
        t("Transport", "Транспорт"),
        settings("transport"),
        choice(
            "auto",
            "auto" to t("Automatic", "Автоматически"),
            "sse" to t("SSE", "SSE"),
            "websocket" to t("WebSocket", "WebSocket"),
            "websocket-cached" to t("WebSocket, cached", "WebSocket с кэшем"),
        ),
    ),
    ConfigField(
        "proxy",
        t("HTTP proxy", "HTTP-прокси"),
        settings("httpProxy"),
        ConfigFieldKind.Text(TextRole.Url),
        description = t("Keep credentials out of the address.", "Не указывайте логин и пароль в адресе."),
        scopes = ProfileOnly,
    ),
    ConfigField(
        "idle_timeout",
        t("Idle timeout", "Таймаут простоя"),
        settings("httpIdleTimeoutMs"),
        millis(default = 300_000),
    ),
    ConfigField(
        "ws_timeout",
        t("WebSocket connect timeout", "Таймаут подключения WebSocket"),
        settings("websocketConnectTimeoutMs"),
        millis(default = 15_000),
    ),
    ConfigField(
        "offline",
        t("Offline", "Без сети"),
        PiHostKeys.Offline,
        ConfigFieldKind.Flag(isOnByDefault = false),
        description = t(
            "No background requests such as catalog refreshes.",
            "Без фоновых запросов, например каталога.",
        ),
        scopes = ProfileOnly,
    ),
)

private fun retrySection() = section(
    "retry",
    t("Retries", "Повторы"),
    flag("retry_enabled", t("Retry transient failures", "Повторять временные сбои"), retry("enabled"), true),
    ConfigField("retry_max", t("Attempts", "Попыток"), retry("maxRetries"), count(default = 3)),
    ConfigField("retry_base", t("First delay", "Первая задержка"), retry("baseDelayMs"), millis(default = 2_000)),
    ConfigField(
        "retry_cap",
        t("Longest delay", "Наибольшая задержка"),
        retry("maxAgentDelayMs"),
        millis(default = 60_000),
    ),
    ConfigField(
        "provider_timeout",
        t("Provider request timeout", "Таймаут запроса к поставщику"),
        retry("provider", "timeoutMs"),
        millis(),
        isAdvanced = true,
    ),
    ConfigField(
        "provider_retries",
        t("Provider retries", "Повторы поставщика"),
        retry("provider", "maxRetries"),
        count(default = 0),
        isAdvanced = true,
    ),
    ConfigField(
        "provider_delay",
        t("Longest server delay", "Наибольшая задержка сервера"),
        retry("provider", "maxRetryDelayMs"),
        millis(default = 60_000),
        isAdvanced = true,
    ),
)

private fun shellSection() = section(
    "shell",
    t("Shell", "Оболочка"),
    ConfigField("shell_path", t("Shell", "Оболочка"), settings("shellPath"), ConfigFieldKind.Text(TextRole.Path)),
    ConfigField(
        "shell_prefix",
        t("Command prefix", "Префикс команд"),
        settings("shellCommandPrefix"),
        ConfigFieldKind.Text(TextRole.Command),
        description = t(
            "Prepended to every shell command; safety reviews see the command without it.",
            "Добавляется к каждой команде; проверка безопасности видит команду без него.",
        ),
    ),
    ConfigField(
        "npm",
        t("npm command", "Команда npm"),
        settings("npmCommand"),
        ConfigFieldKind.TextList(TextRole.Command),
    ),
)

private fun codemodeSection(): ConfigSection {
    val whenOn = ConfigCondition(PiHostKeys.Codemode, JsonPrimitive(true))
    return section(
        "codemode",
        t("Code mode", "Режим кода"),
        ConfigField(
            "codemode_mode",
            t("Tools in scripts", "Инструменты в скриптах"),
            settings("codemode", "mode"),
            choice(
                "on",
                "on" to t("Also from scripts", "Также из скриптов"),
                "only" to t("Only scripts", "Только скрипты"),
            ),
            visibleWhen = whenOn,
        ),
        ConfigField(
            "codemode_budget",
            t("Declarations budget", "Бюджет описаний"),
            settings("codemode", "inlineBudget"),
            tokens(default = 3_000),
            visibleWhen = whenOn,
        ),
    )
}

private fun privacy() = section(
    "privacy",
    t("Telemetry and warnings", "Телеметрия и предупреждения"),
    flag(
        "telemetry",
        t("Anonymous install telemetry", "Анонимная телеметрия установки"),
        settings("enableInstallTelemetry"),
        true,
    ),
    flag(
        "extra_usage",
        t("Warn about paid extra usage", "Предупреждать о платном перерасходе"),
        settings("warnings", "anthropicExtraUsage"),
        true,
    ),
)

private fun documentsSection() = section(
    "documents",
    t("Instructions", "Инструкции"),
    document(
        "agents",
        PiDocuments.Agents,
        t(
            "Followed in every session; in a project, for that project.",
            "Выполняются в каждой сессии; в проекте — для него.",
        ),
    ),
    document(
        "system",
        PiDocuments.System,
        t("Replaces Pi's own system prompt.", "Заменяет системный промпт Pi целиком."),
    ),
    document(
        "append_system",
        PiDocuments.AppendSystem,
        t("Added to the end of the system prompt.", "Добавляется в конец системного промпта."),
    ),
)

private fun contextSection() = section(
    "context",
    t("Project context", "Контекст проекта"),
    ConfigField(
        "context_files",
        t("Read AGENTS.md and CLAUDE.md of projects", "Читать AGENTS.md и CLAUDE.md проектов"),
        PiHostKeys.ProjectContextFiles,
        ConfigFieldKind.Flag(isOnByDefault = true),
        scopes = ProfileOnly,
    ),
)

private fun builtins() = section(
    "builtins",
    t("Built-in extensions", "Встроенные расширения"),
    builtin("mcp", t("MCP servers", "MCP-серверы"), PiHostKeys.Mcp),
    builtin("codemode", t("Code mode", "Режим кода"), PiHostKeys.Codemode),
    builtin("tool_search", t("Tool search", "Поиск инструментов"), PiHostKeys.ToolSearch),
    builtin("llama_cpp", t("llama.cpp router", "Маршрутизатор llama.cpp"), PiHostKeys.LlamaCpp),
    note = t(
        "Every call of their tools passes Heartbeat's safety review.",
        "Каждый вызов их инструментов проходит проверку безопасности Heartbeat.",
    ),
)

private fun paths() = section(
    "paths",
    t("Resources", "Ресурсы"),
    resources("extensions", t("Extensions", "Расширения")),
    resources("skills", t("Skills", "Навыки")),
    resources("prompts", t("Prompt templates", "Шаблоны промптов")),
    flag(
        "skill_commands",
        t("Skills as /skill commands", "Навыки как команды /skill"),
        settings("enableSkillCommands"),
        true,
    ),
    ConfigField(
        "extension_tools",
        t("Allowed extension tools", "Разрешённые инструменты расширений"),
        PiHostKeys.ExtensionTools,
        ConfigFieldKind.TextList(),
        description = t(
            "Tool names or * patterns Pi may declare.",
            "Имена или шаблоны * инструментов, которые Pi объявит.",
        ),
        scopes = ProfileOnly,
    ),
)

private fun packages() = section(
    "packages",
    t("Pi packages", "Пакеты Pi"),
    ConfigField(
        "packages",
        t("Packages", "Пакеты"),
        settings("packages"),
        ConfigFieldKind.Entries(
            EntriesShape.List,
            t("Package", "Пакет"),
            listOf(
                EntryVariant(
                    "source",
                    t("Whole package", "Пакет целиком"),
                    EntryMatch.Primitive,
                    listOf(EntryField("source", t("Source", "Источник"), emptyList(), ConfigFieldKind.Text())),
                ),
                EntryVariant(
                    "filtered",
                    t("Selected resources", "Выбранные ресурсы"),
                    EntryMatch.HasKey("source"),
                    listOf(
                        EntryField("source", t("Source", "Источник"), listOf("source"), ConfigFieldKind.Text()),
                        filter("extensions", t("Extensions", "Расширения")),
                        filter("skills", t("Skills", "Навыки")),
                        filter("prompts", t("Prompt templates", "Шаблоны промптов")),
                    ),
                ),
            ),
        ),
        description = t("npm:, git: or a local path.", "npm:, git: или локальный путь."),
    ),
)

private fun serversSection() = section(
    "servers",
    t("Servers", "Серверы"),
    ConfigField(
        "servers",
        t("MCP servers", "MCP-серверы"),
        ConfigBinding.JsonPath(PiDocuments.Mcp, listOf("mcpServers")),
        ConfigFieldKind.Entries(
            EntriesShape.NamedMap,
            t("Server", "Сервер"),
            listOf(
                EntryVariant(
                    "stdio",
                    t("Command", "Команда"),
                    EntryMatch.HasKey("command"),
                    stdioFields() + serverFields(),
                ),
                EntryVariant("http", t("HTTP", "HTTP"), EntryMatch.HasKey("url"), httpFields() + serverFields()),
            ),
            keyPattern = "[A-Za-z0-9_-]+",
        ),
    ),
    flag(
        "auto_codemode",
        t("Turn code mode on for MCP", "Включать режим кода для MCP"),
        ConfigBinding.JsonPath(PiDocuments.Mcp, listOf("autoEnableCodemode")),
        true,
    ),
    note = t(
        "Servers start only while the MCP built-in extension is on.",
        "Серверы запускаются, только когда включено встроенное расширение MCP.",
    ),
)

private fun stdioFields() = listOf(
    EntryField("command", t("Command", "Команда"), listOf("command"), ConfigFieldKind.Text(TextRole.Command)),
    EntryField("args", t("Arguments", "Аргументы"), listOf("args"), ConfigFieldKind.TextList()),
    EntryField("env", t("Environment", "Окружение"), listOf("env"), ConfigFieldKind.KeyValues(isSecretAllowed = true)),
    EntryField(
        "cwd",
        t("Working directory", "Рабочая папка"),
        listOf("cwd"),
        ConfigFieldKind.Text(TextRole.Path),
        isAdvanced = true,
    ),
)

private fun httpFields() = listOf(
    EntryField("url", t("URL", "URL"), listOf("url"), ConfigFieldKind.Text(TextRole.Url)),
    EntryField(
        "headers",
        t("Headers", "Заголовки"),
        listOf("headers"),
        ConfigFieldKind.KeyValues(isSecretAllowed = true),
    ),
    EntryField(
        "oauth_client",
        t("OAuth client id", "OAuth client id"),
        oauth("clientId"),
        ConfigFieldKind.Text(),
        isAdvanced = true,
    ),
    EntryField(
        "oauth_scope",
        t("OAuth scopes", "Области OAuth"),
        oauth("scope"),
        ConfigFieldKind.Text(),
        isAdvanced = true,
    ),
    EntryField(
        "oauth_port",
        t("OAuth callback port", "Порт возврата OAuth"),
        oauth("callbackPort"),
        ConfigFieldKind.Number(min = 1, max = 65_535),
        isAdvanced = true,
    ),
)

private fun serverFields() = listOf(
    EntryField("enabled", t("Enabled", "Включён"), listOf("enabled"), ConfigFieldKind.Flag(isOnByDefault = true)),
    EntryField("description", t("Description", "Описание"), listOf("description"), ConfigFieldKind.Text()),
    EntryField(
        "timeout",
        t("Request timeout", "Таймаут запроса"),
        listOf("timeout"),
        ConfigFieldKind.Number(min = 1, max = 3_600, default = 60, unit = ConfigUnit.Seconds),
    ),
    EntryField(
        "exposure",
        t("Tools reach the model", "Доступ модели к инструментам"),
        listOf("exposure"),
        ConfigFieldKind.Choice(exposureOptions(), JsonPrimitive("codemode")),
    ),
    EntryField(
        "tool_exposure",
        t("Per tool", "Для отдельных инструментов"),
        listOf("toolExposure"),
        ConfigFieldKind.KeyValues(valueChoices = exposureOptions()),
        isAdvanced = true,
    ),
)

private fun exposureOptions() = listOf(
    ConfigOption(JsonPrimitive("codemode"), t("From code mode scripts", "Из скриптов режима кода")),
    ConfigOption(JsonPrimitive("deferred"), t("Found by tool search", "Через поиск инструментов")),
    ConfigOption(JsonPrimitive("direct"), t("Declared directly", "Объявлены напрямую")),
    ConfigOption(JsonPrimitive("hidden"), t("Hidden", "Скрыты")),
)

private fun ownedKeys(): List<OwnedKey> {
    val model = t("Chosen in the chat and in Models and engines.", "Выбирается в чате и в «Модели и движки».")
    val thinking = t("Chosen in the chat for every turn.", "Выбирается в чате для каждого хода.")
    val sessions = t("Heartbeat stores Pi sessions itself.", "Heartbeat сам хранит сессии Pi.")
    val tools = t("Set by Heartbeat's tool policy (Harness).", "Задаётся политикой инструментов Heartbeat («Харнесс»).")
    val trust = t("Decided per project in Heartbeat.", "Решается для каждого проекта в Heartbeat.")
    return listOf(
        "defaultProvider" to model,
        "defaultModel" to model,
        "enabledModels" to model,
        "defaultThinkingLevel" to thinking,
        "modelThinkingLevels" to thinking,
        "sessionDir" to sessions,
        "defaultTools" to tools,
        "defaultProjectTrust" to trust,
    ).map { (key, reason) -> OwnedKey(PiDocuments.Settings, listOf(key), reason) }
}

private val ProfileOnly = setOf(ConfigScope.Profile)

private fun page(id: String, title: LocalizedText, vararg sections: ConfigSection) =
    ConfigPage(id, title, sections.toList())

private fun section(id: String, title: LocalizedText, vararg fields: ConfigField, note: LocalizedText? = null) =
    ConfigSection(id, title, fields.toList(), note)

private fun document(id: ConfigDocumentId, title: LocalizedText, format: ConfigFormat, fileName: String) =
    ConfigDocumentSpec(id, title, format, ConfigScope.entries.toSet(), fileName)

private fun document(id: String, document: ConfigDocumentId, description: LocalizedText): ConfigField {
    val name = PiConfigFileNames.getValue(document)
    return ConfigField(
        id,
        t(name, name),
        ConfigBinding.WholeDocument(document),
        ConfigFieldKind.Document("markdown"),
        description,
    )
}

private val PiConfigFileNames = mapOf(
    PiDocuments.Agents to "AGENTS.md",
    PiDocuments.System to "SYSTEM.md",
    PiDocuments.AppendSystem to "APPEND_SYSTEM.md",
)

private fun note(id: String, binding: ConfigBinding, reason: LocalizedText) =
    ConfigField(id, reason, binding, ConfigFieldKind.ManagedNote(reason))

private fun flag(id: String, title: LocalizedText, binding: ConfigBinding, isOn: Boolean) =
    ConfigField(id, title, binding, ConfigFieldKind.Flag(isOn))

private fun builtin(id: String, title: LocalizedText, binding: ConfigBinding) =
    ConfigField("builtin_$id", title, binding, ConfigFieldKind.Flag(isOnByDefault = false), scopes = ProfileOnly)

private fun resources(key: String, title: LocalizedText) = ConfigField(
    key,
    title,
    settings(key),
    ConfigFieldKind.TextList(TextRole.Path, ListSyntax.ResourcePatterns),
    description = t(
        "Files or folders; !glob excludes, +path and -path force one path.",
        "Файлы или папки; !маска исключает, +путь и -путь задают отдельный путь.",
    ),
)

private fun filter(key: String, title: LocalizedText) =
    EntryField(key, title, listOf(key), ConfigFieldKind.TextList(TextRole.Path, ListSyntax.ResourcePatterns))

private fun choice(default: String, vararg options: Pair<String, LocalizedText>) = ConfigFieldKind.Choice(
    options.map { (value, title) -> ConfigOption(JsonPrimitive(value), title) },
    JsonPrimitive(default),
)

private fun tokens(default: Long? = null) = ConfigFieldKind.Number(min = 0, default = default, unit = ConfigUnit.Tokens)

private fun millis(default: Long? = null) =
    ConfigFieldKind.Number(min = 0, default = default, unit = ConfigUnit.Milliseconds)

private fun count(default: Long) = ConfigFieldKind.Number(min = 0, max = MAX_RETRIES, default = default)

private fun settings(vararg path: String) = ConfigBinding.JsonPath(PiDocuments.Settings, path.toList())

private fun compaction(key: String) = settings("compaction", key)

private fun retry(vararg path: String) = settings("retry", *path)

private fun oauth(key: String) = listOf("oauth", key)

private fun host(vararg path: String) = ConfigBinding.JsonPath(PiDocuments.Host, path.toList())

private fun t(en: String, ru: String) = LocalizedText(en, mapOf("ru" to ru))

private const val MAX_RETRIES = 20L
