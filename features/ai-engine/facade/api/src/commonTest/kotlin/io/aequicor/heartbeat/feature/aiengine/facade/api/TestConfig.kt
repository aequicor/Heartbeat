package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.JsonPrimitive

/** A small engine configuration schema shared by the configuration contract tests. */
internal object TestConfig {
    val settings = ConfigDocumentId("settings")
    val mcp = ConfigDocumentId("mcp")
    val agents = ConfigDocumentId("agents")
    val host = ConfigDocumentId("heartbeat")

    val retries = ConfigBinding.JsonPath(settings, listOf("retry", "maxRetries"))
    val retryEnabled = ConfigBinding.JsonPath(settings, listOf("retry", "enabled"))
    val transport = ConfigBinding.JsonPath(settings, listOf("transport"))
    val proxy = ConfigBinding.JsonPath(settings, listOf("httpProxy"))
    val skills = ConfigBinding.JsonPath(settings, listOf("skills"))
    val servers = ConfigBinding.JsonPath(mcp, listOf("mcpServers"))
    val instructions = ConfigBinding.WholeDocument(agents)
    val offline = ConfigBinding.JsonPath(host, listOf("offline"))

    private val both = ConfigScope.entries.toSet()

    val schema = EngineConfigSchema(
        documents = listOf(
            ConfigDocumentSpec(settings, LocalizedText("Settings"), ConfigFormat.Json, both, "settings.json"),
            ConfigDocumentSpec(mcp, LocalizedText("MCP"), ConfigFormat.Json, both, "mcp.json"),
            ConfigDocumentSpec(
                agents,
                LocalizedText("Instructions"),
                ConfigFormat.Text,
                both,
                "AGENTS.md",
                maxChars = 64,
            ),
            ConfigDocumentSpec(
                host,
                LocalizedText("Heartbeat"),
                ConfigFormat.Json,
                setOf(ConfigScope.Profile),
                "heartbeat",
                isHostOnly = true,
            ),
        ),
        pages = listOf(
            ConfigPage(
                "network",
                LocalizedText("Network"),
                listOf(
                    ConfigSection(
                        "retry",
                        LocalizedText("Retry"),
                        listOf(
                            ConfigField(
                                "retry_enabled",
                                LocalizedText("Retry"),
                                retryEnabled,
                                ConfigFieldKind.Flag(true),
                            ),
                            ConfigField(
                                "retry_max",
                                LocalizedText("Attempts"),
                                retries,
                                ConfigFieldKind.Number(min = 0, max = 10, default = 3),
                                visibleWhen = ConfigCondition(
                                    retryEnabled,
                                    JsonPrimitive(true),
                                    isMetWhenAbsent = true,
                                ),
                            ),
                            ConfigField(
                                "transport",
                                LocalizedText("Transport"),
                                transport,
                                ConfigFieldKind.Choice(
                                    listOf(
                                        ConfigOption(JsonPrimitive("auto"), LocalizedText("Auto")),
                                        ConfigOption(JsonPrimitive("sse"), LocalizedText("SSE")),
                                    ),
                                    default = JsonPrimitive("auto"),
                                ),
                            ),
                            ConfigField(
                                "proxy",
                                LocalizedText("Proxy"),
                                proxy,
                                ConfigFieldKind.Text(TextRole.Url, maxChars = 32),
                                scopes = setOf(ConfigScope.Profile),
                            ),
                            ConfigField(
                                "offline",
                                LocalizedText("Offline"),
                                offline,
                                ConfigFieldKind.Flag(false),
                                scopes = setOf(ConfigScope.Profile),
                            ),
                        ),
                    ),
                ),
            ),
            ConfigPage(
                "resources",
                LocalizedText("Resources"),
                listOf(
                    ConfigSection(
                        "resources",
                        LocalizedText("Resources"),
                        listOf(
                            ConfigField(
                                "skills",
                                LocalizedText("Skills"),
                                skills,
                                ConfigFieldKind.TextList(TextRole.Path, ListSyntax.ResourcePatterns, maxItems = 3),
                            ),
                            ConfigField(
                                "agents",
                                LocalizedText("Instructions"),
                                instructions,
                                ConfigFieldKind.Document("markdown"),
                            ),
                            ConfigField("servers", LocalizedText("Servers"), servers, serversKind),
                        ),
                    ),
                ),
            ),
        ),
        ownedKeys = listOf(OwnedKey(settings, listOf("defaultModel"), LocalizedText("Chosen in the chat"))),
        capabilities = setOf(ConfigCapability.Secrets),
    )

    private val serversKind: ConfigFieldKind.Entries
        get() = ConfigFieldKind.Entries(
            shape = EntriesShape.NamedMap,
            itemTitle = LocalizedText("Server"),
            keyPattern = "[A-Za-z0-9_-]+",
            variants = listOf(
                EntryVariant(
                    "stdio",
                    LocalizedText("Command"),
                    EntryMatch.HasKey("command"),
                    listOf(
                        EntryField(
                            "command",
                            LocalizedText("Command"),
                            listOf("command"),
                            ConfigFieldKind.Text(TextRole.Command),
                        ),
                        EntryField(
                            "env",
                            LocalizedText("Environment"),
                            listOf("env"),
                            ConfigFieldKind.KeyValues(isSecretAllowed = true),
                        ),
                        EntryField(
                            "timeout",
                            LocalizedText("Timeout"),
                            listOf("timeout"),
                            ConfigFieldKind.Number(min = 1),
                        ),
                    ),
                ),
                EntryVariant(
                    "http",
                    LocalizedText("HTTP"),
                    EntryMatch.HasKey("url"),
                    listOf(
                        EntryField("url", LocalizedText("URL"), listOf("url"), ConfigFieldKind.Text(TextRole.Url)),
                        EntryField(
                            "headers",
                            LocalizedText("Headers"),
                            listOf("headers"),
                            ConfigFieldKind.KeyValues(isSecretAllowed = true),
                        ),
                    ),
                ),
            ),
        )
}
