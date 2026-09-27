# ADR-0015: Подключение ИИ-агентов — движки, общие ключи и изолированные подписки

- Статус: предложено
- Дата: 2026-09-27
- Затрагивает: новые `core:secrets:{api,impl}`, `core:ai:{api,spi,impl,process}`,
  `core:ai:engine-{native,acp,codex,claude-code}`, `platform-main:di-bundle`, `features/ai-studio`,
  новая `features/agent-connections`, `gradle/libs.versions.toml` (ACP Kotlin SDK, JNA), `docs/ai/tech-stack.md`

## Контекст

Студия должна работать с четырьмя видами агентов:

1. **Встроенный чат.** Простой агент для запросов и разговора. Работает на всех платформах.
2. **ACP-агенты.** Внешние агенты по [Agent Client Protocol](https://agentclientprotocol.com).
3. **Codex.** Работает по подписке ChatGPT.
4. **Claude Code.** Работает по подписке Claude (Pro/Max).

Требования:

- **API-ключ хранится один раз.** Его используют все движки, которые понимают этого провайдера. Замена ключа
  применяется ко всем. Удалить ключ, пока он используется, нельзя.
- **Подписка работает только в своём движке.** Подписка ChatGPT действует только в Codex, подписка Claude — только в
  Claude Code. Встроенный чат и ACP-агенты не могут ею воспользоваться, даже случайно.
- **Никаких неожиданных оплат.** Если выбрана подписка, по API-ключу ничего не оплачивается, и наоборот. Если режим
  не совпал, сессия не запускается.

Внешние факты (проверены 2026-09-27):

- **ACP.** JSON-RPC 2.0 через stdio: клиент запускает агента как подпроцесс. HTTP-транспорт пока в черновике.
  - Авторизация: `initialize` возвращает `authMethods` двух типов. `agent` — агент входит сам. `terminal` — клиент
    запускает команду агента в терминале.
  - Тип `env_var` удалён из протокола, типизированной передачи API-ключа нет. На практике ключ передают переменной
    окружения при запуске процесса.
  - Есть официальный реестр агентов и Kotlin SDK от JetBrains: `com.agentclientprotocol:acp` 0.30.1. Он работает
    только на JVM и требует JDK 21.
- **Codex.** Команда `codex app-server` — это JSON-RPC (NDJSON) через stdio.
  - Методы аккаунта: `account/read` (тип `apiKey` или `chatgpt`), `account/login/start` (`apiKey`, `chatgpt`).
  - Разрешения запрашиваются вызовами `item/*/requestApproval`.
  - Авторизация хранится в `$CODEX_HOME` (`auth.json` или keyring). Переменная `CODEX_HOME` изолирует вход.
  - `account/login/start {type: apiKey}` **перезаписывает общий `auth.json`** и выбивает вход ChatGPT во всех
    клиентах Codex на машине (openai/codex#48465). Это же делает адаптер `codex-acp`.
- **Claude Code.** Headless-режим: `claude -p --input-format stream-json --output-format stream-json`.
  - Порядок выбора авторизации: `ANTHROPIC_AUTH_TOKEN` → `ANTHROPIC_API_KEY` (с `-p` используется всегда, если задан)
    → `apiKeyHelper` → `CLAUDE_CODE_OAUTH_TOKEN` → подписка (`/login`).
  - `CLAUDE_CONFIG_DIR` изолирует вход. `claude auth status` отдаёт JSON; exit code 1 — вход не выполнен.
  - **Политика Anthropic** (code.claude.com/docs/en/legal-and-compliance): стороннее приложение не может предлагать
    вход Claude.ai, хранить или проксировать его токены. Разрешено, чтобы пользователь сам входил в **неизменённый**
    бинарник Claude Code. Использование через `claude -p` расходует лимиты подписки.

## Решение

### Ключевая идея: ключ и подписка — разные сущности

|                    | **API-ключ** (`ApiKeyCredential`)                       | **Подписка** (`EngineAuth.Subscription`)                                    |
|--------------------|---------------------------------------------------------|-----------------------------------------------------------------------------|
| Кто владеет секретом | Heartbeat: `core:secrets`, хранилище профиля          | CLI вендора: `$CODEX_HOME`, `CLAUDE_CONFIG_DIR`. У Heartbeat секрета **нет** |
| Где объявлен       | `CredentialVault`: провайдер, метка, `baseUrl`           | режим авторизации конкретного движка. Отдельной записи нет                 |
| Переиспользование  | любым движком, который принимает этого провайдера        | только движком, у которого `descriptor.subscription == kind`               |
| Как передаётся     | `ResolvedAuth.ApiKey(Secret)` только при старте сессии   | движок запускает CLI вендора с его собственным хранилищем                  |

Подписку нельзя «одолжить» другому движку по двум причинам:

- **Типы.** В `core:ai` нет API, который выдаёт токен подписки. Подписка — это режим запуска чужого CLI, а не значение.
- **Среда процессов.** Каждый подпроцесс запускается с очищенным окружением и перенаправленными каталогами вендоров.
  Поэтому ACP-обёртка над Codex или Claude физически не видит вход пользователя.

### Модули

```
core/secrets/api            SecretStore, SecretKey, Secret (redacted toString)                    KMP
core/secrets/impl           Android Keystore · iOS Keychain · Windows DPAPI · macOS Keychain (JNA)
core/ai/api                 контракт для фич: CredentialVault, EngineCatalog, EngineConfigs,
                            AgentSessions / AgentSession, AgentEvent, EngineDescriptor, EngineAuth   KMP, без Koog
core/ai/spi                 контракт для движков: AgentEngine, ResolvedAuth, Secret.reveal { }      KMP
core/ai/impl                реестр движков (мультибиндинг), CredentialVault поверх core:secrets +
                            профильного KV, AuthResolver, менеджер сессий, логи AI                  KMP
core/ai/process             JVM: запуск процессов, EnvPolicy, NDJSON/JSON-RPC-транспорт, логи AI/PROC
core/ai/engine-native       встроенный чат на Koog                                                  KMP
core/ai/engine-acp          ACP-клиент (com.agentclientprotocol:acp)                                 JVM, JDK 21
core/ai/engine-codex        codex app-server                                                         JVM
core/ai/engine-claude-code  claude -p stream-json                                                    JVM
features/ai-studio          чат: машина сессии в api, стор/экран в impl → core:ai:api
features/agent-connections  ключи, движки, ACP-агенты, статус входа → core:ai:api
```

Правила зависимостей в духе ADR-0001:

- `features/*/impl` видит только `core:ai:api`.
- `engine-*` видят `core:ai:spi` и `core:ai:process`. От них зависит только `di-bundle`.
- `core:ai:spi` нельзя импортировать из фич. Это проверяет хук `check-conventions.sh`, как `FeatureToggleControl` в
  ADR-0007.
- JVM-движки подключаются только в `jvm`-граф `di-bundle`. На Android и iOS доступен только `engine-native`.

### Контракт `core:ai:api` (эскиз)

```kotlin
@JvmInline value class EngineId(val value: String)          // "native", "codex", "claude_code", "acp.<agentId>"
@JvmInline value class CredentialId(val value: String)

enum class LlmProvider { Anthropic, OpenAI, Google, OpenRouter, OpenAICompatible }
enum class SubscriptionKind { ChatGpt, ClaudeAi }

data class EngineDescriptor(
    val id: EngineId,
    val kind: EngineKind,                      // Native | Acp | Codex | ClaudeCode
    val title: String,
    val acceptedKeys: Map<LlmProvider, KeyUse>, // что понимает и как применяет (env-переменная / login / HTTP)
    val subscription: SubscriptionKind?,        // только «своя» подписка; null — подписок нет
    val agentAuthMethods: List<AgentAuthMethod>,// ACP: agent/terminal-методы самого агента (после фильтра)
    val capabilities: EngineCapabilities,       // workspace, fs, terminal, permissions, models, resume
)

sealed interface EngineAuth {
    data class SharedKey(val credential: CredentialId) : EngineAuth
    data class Subscription(val kind: SubscriptionKind) : EngineAuth
    data class AgentManaged(val methodId: String) : EngineAuth
    data object None : EngineAuth                // Ollama / локальный OpenAI-compatible
}

/** Pure check shared by the settings UI, EngineConfigs and AuthResolver. */
fun EngineDescriptor.accepts(auth: EngineAuth, provider: (CredentialId) -> LlmProvider?): Boolean

interface CredentialVault {                      // @ForScope(ProfileScope)
    fun observe(): Flow<List<ApiKeyCredential>>  // метаданные, без секрета
    fun observeUsages(id: CredentialId): Flow<Set<EngineId>>
    suspend fun add(provider: LlmProvider, label: String, secret: Secret, baseUrl: String? = null): CredentialId
    suspend fun replace(id: CredentialId, secret: Secret)
    suspend fun remove(id: CredentialId): RemoveResult // InUse(engines) | Removed
    suspend fun verify(id: CredentialId): VerifyResult // дешёвый запрос (list models) через core:network
}

interface EngineConfigs {                        // профиль: какая авторизация и модель у каждого движка
    fun observe(): Flow<Map<EngineId, EngineConfig>>
    suspend fun set(config: EngineConfig)        // require(descriptor.accepts(config.auth))
}

interface AgentSessions {                        // @ForScope(ProfileScope)
    fun observeEngines(): Flow<List<EngineStatus>>          // доступность + статус авторизации
    suspend fun open(request: SessionRequest): Result<AgentSession>  // engine, workspace?, resume?
}

interface AgentSession {
    val id: AgentSessionId
    val events: Flow<AgentEvent>
    suspend fun prompt(parts: List<PromptPart>)
    suspend fun cancel()
    suspend fun answer(permission: PermissionRequestId, option: PermissionOptionId)
    suspend fun setOption(option: ConfigOptionId, value: String)   // модель, режим
    suspend fun close()
}
```

`AgentEvent` повторяет модель `session/update` из ACP, потому что это самый полный общий знаменатель:

- `MessageChunk`, `ThoughtChunk`
- `ToolCall(id, kind, title, status, content)` и `ToolCallUpdate`. `content` — это Markdown, Diff или Terminal, то
  есть те же секции, что у `HbToolCallView` (ADR-0010).
- `Plan`, `PermissionRequested(options)`, `Usage`
- `TurnEnded(stopReason)`, `Failed(AgentFailure)`

Codex и Claude Code приводят свои события к этой модели в своём движке.

`AgentFailure` — закрытый набор:

- `NotInstalled`, `NotLoggedIn(kind)`, `AuthMismatch(expected, actual)`, `InvalidKey`
- `RateLimited`, `ProcessCrashed(exitCode)`, `Protocol`, `Network`

Текстов процесса в нём нет: stderr может содержать секреты.

### Разрешение авторизации

```
EngineConfig.auth ──▶ AuthResolver (core:ai:impl) ──▶ ResolvedAuth ──▶ AgentEngine.open(...)
                         │ descriptor.accepts(auth)  (вторая проверка)
                         │ SharedKey → SecretStore(profile).read(SecretKey(credId)) → Secret
                         │ Subscription → без секрета, только kind
                         ▼
                      логи AI: "auth engine=codex mode=subscription(ChatGpt)" — без значений
```

- **`Secret`** оборачивает `CharArray`, `toString() = "Secret(***)"`. Значение открывается явным `Secret.reveal { }`
  из `core:secrets:api` ([ADR-0016](0016-profile-secrets.md)); движки вызывают его только в транспортном адаптере, UI не раскрывает секрет.
  Движок держит секрет, пока запускает процесс или делает login-вызов, и не сохраняет его.
- **Замена ключа** действует на новые сессии. Окружение процесса фиксируется при запуске, поэтому открытые сессии
  этого движка получают `EngineStatus.RestartSuggested`.
- **`wipeProfile`** удаляет и защищённый снимок профиля в `SecretStore`: `core:secrets:impl` участвует в очистке
  профиля (ADR-0006).

### EnvPolicy — изоляция подпроцессов (`core:ai:process`)

Каждый процесс движка запускается через `AgentProcessLauncher`:

1. **Наследуется окружение пользователя минус учётные переменные.** Удаляются `*_API_KEY`, `*_AUTH_TOKEN`,
   `*_ACCESS_TOKEN`, `ANTHROPIC_*`, `OPENAI_*`, `CODEX_*`, `CLAUDE_CODE_*`, `GEMINI_*`,
   `AWS_BEARER_TOKEN_BEDROCK` и `CLAUDE_CODE_USE_{BEDROCK,VERTEX,FOUNDRY}`.
   - Используется denylist, а не allowlist: инструментам агента нужны `PATH`, `JAVA_HOME`, прокси и т. п.
2. **Каталоги вендоров перенаправляются** во всех режимах, кроме подписки своего движка. `CODEX_HOME` и
   `CLAUDE_CONFIG_DIR` указывают на `<StorageRoot>/engines/<engine>/<hex(profileId)>/`.
3. **Движок явно добавляет переменные** по своему `ResolvedAuth`. Только это место кладёт секрет в окружение.
4. **Логирование:** `AI/PROC start engine=… cmd=<имя бинарника> pid=…`, выход, код, длительность. Env и argv
   не логируются. stderr идёт в `D` построчно, через редактирование секретов `core:logging`.

### Движки

| Движок              | Общий ключ (из `CredentialVault`)                                    | Подписка                          | Платформы |
|---------------------|----------------------------------------------------------------------|-----------------------------------|-----------|
| **Native** (Koog)   | Anthropic, OpenAI, Google, OpenRouter, OpenAI-compatible (+`baseUrl`) | —                                 | все       |
| **ACP**             | по таблице `KeyUse.EnvVar` агента (пресет или ручная)                 | **запрещена**                     | desktop   |
| **Codex**           | OpenAI → изолированный `CODEX_HOME` + `account/login/start{apiKey}`  | ChatGPT → системный `CODEX_HOME`  | desktop   |
| **Claude Code**     | Anthropic → `ANTHROPIC_API_KEY`                                      | Claude.ai → системный конфиг      | desktop   |

**1. Native** (`engine-native`, KMP)

- Работает через `PromptExecutor` Koog по провайдеру ключа, как в скилле `ai-koog`. Логирование — через `handleEvents`.
- Инструментов по умолчанию нет. Стриминг — `StreamFrame` → `MessageChunk`.
- Подписку принимать не может: у `descriptor.subscription` значение `null`, секрета подписки не существует.

**2. ACP** (`engine-acp`, JVM)

- **Описание агента** — `AcpAgentSpec(id, command, args, env, keyUse)`. Источник — ACP Registry (только показ,
  установку делает пользователь) или ручной ввод. Запуск через `npx` / загрузка бинарника — только после
  подтверждения с полной командой.
- **Клиентские методы:**
  - `fs/*` и `terminal/*` работают только внутри корня workspace.
  - `session/request_permission` уходит в UI. Если ответа нет, выбирается отказ.
  - Terminal-auth агента открывает системный терминал с командой агента.
- **Ключ.** Если в конфиге `SharedKey`, `KeyUse.EnvVar(name)` кладёт ключ в окружение.
- **Подписки блокируются в три слоя:**
  - (a) EnvPolicy перенаправляет `CODEX_HOME` и `CLAUDE_CONFIG_DIR`, поэтому существующий вход пользователя агенту
    не виден.
  - (b) `authMethods` фильтруются по `SubscriptionAuthDenylist`: известные id подписочного входа
    (`claude-ai-login`, `claude-login`, ChatGPT-методы `codex-acp`). В UI вместо них показывается подсказка «для
    подписки выберите движок Claude Code / Codex».
  - (c) Даже если неизвестный метод залогинит агента в изолированном каталоге, этот вход остаётся внутри песочницы
    агента и не смешивается с системным.

**3. Codex** (`engine-codex`, JVM) — `codex app-server` напрямую, без `codex-acp`: адаптер перезаписывает общий
`auth.json`.

- **Подписка.** Процесс получает системный `CODEX_HOME`, ключевые переменные вычищены. Предпроверка `account/read`
  должна вернуть `chatgpt`: для `apiKey` — `AuthMismatch`, для пустого ответа — `NotLoggedIn`.
  - Вход выполняется или через `account/login/start{chatgpt}` (открываем `authUrl` в браузере), или кнопкой
    «Войти в терминале» (`codex login`).
- **Ключ.** `CODEX_HOME` указывает на изолированный каталог профиля. `config.toml` в нём задаёт
  `cli_auth_credentials_store = "ephemeral"`, и перед `thread/start` вызывается `account/login/start{apiKey}`.
  Системный вход ChatGPT не затрагивается.
- **Приведение событий:** `item/*` → `ToolCall`, `item/agentMessage/delta` → `MessageChunk`,
  `item/*/requestApproval` → `PermissionRequested`.
- Схема фиксируется на поддерживаемую версию: `codex app-server generate-json-schema`, контрактный тест в CI.
  Минимальная версия CLI проверяется при `initialize`.

**4. Claude Code** (`engine-claude-code`, JVM) — запускает **установленный пользователем** `claude`, без
встроенного Agent SDK: это соответствует политике Anthropic.

- **Подписка.** Системный `CLAUDE_CONFIG_DIR`, `ANTHROPIC_*` и `CLAUDE_CODE_OAUTH_TOKEN` вычищены. Предпроверка
  `claude auth status` должна показать вход Claude.ai, иначе `NotLoggedIn` или `AuthMismatch`.
  - Heartbeat не показывает своего окна входа, не читает `.credentials.json` и не хранит токены. Вход выполняется
    только кнопкой «Войти в терминале» с командой `claude auth login`.
- **Ключ.** `ANTHROPIC_API_KEY` в окружении (с `-p` он приоритетнее подписки) плюс изолированный
  `CLAUDE_CONFIG_DIR`, чтобы сессии по ключу не смешивались с системными.
- **Одна сессия = один процесс.** Флаги: `-p --input-format stream-json --output-format stream-json --verbose
  --include-partial-messages --session-id|--resume`, `cwd` = workspace.
- **Разрешения:** `--permission-prompt-tool stdio` — control-протокол, которым пользуется Agent SDK. Он не описан в
  публичной документации, поэтому закрепляется контрактным тестом на версию CLI. Запасной путь — заданный
  пользователем `--permission-mode` без интерактивных запросов.

### Фичи

**`ai-studio`**: машина сессии в `api` (скилл `state-machine`).

```
NoEngine ─SelectEngine─▶ Connecting ─Internal.Ready─▶ Idle ─Send─▶ Running ─TurnEnded─▶ Idle
                             │                          ▲             │  ▲
                             └─Internal.AuthRequired─▶ NeedsAuth      │  └─Answer─ AwaitingPermission
                                                        (OpenConnections → Route agent-connections)
Любое ─Internal.Failed─▶ Failed(reason) ─Retry─▶ Connecting;  Running ─Cancel─▶ Idle
```

- **Эффекты:** `OpenSession`, `Prompt`, `CancelTurn`, `AnswerPermission`, `CloseSession` — `EffectHandler` в `impl`
  поверх `AgentSessions`.
- **Поток текста и tool calls в машину не идёт.** Эффект пишет `AgentEvent` в репозиторий транскрипта (Room фичи,
  ADR-0006), стор подписан на репозиторий, как предписывает `ai-koog`.
- `api` не зависит от `core:ai`. В интентах используются собственные сериализуемые `engineId: String` и `reason`.
- Движок фиксируется при создании сессии. Сменить модель можно (`setOption`), сменить движок — только новой сессией.

**`agent-connections`** (новая): это единственное место управления.

- Ключи: добавить, проверить, заменить, удалить с показом использования.
- Движки: режим авторизации и модель. Селектор показывает только совместимые варианты по `accepts`.
- Статус входа по подпискам и кнопки «Войти в терминале».
- Список ACP-агентов.

Другие фичи открывают её `Navigator.navigate(AgentConnectionsRoute(focus = engineId))`.

### Тоглы, логи, безопасность

- **Тоглы** (ADR-0007): `ai.engine_native`, `ai.engine_acp`, `ai.engine_codex`, `ai.engine_claude_code` — `Flag`,
  по умолчанию `false`. Движок под выключенным тоглом не попадает в `observeEngines()`.
- **Логи:**
  - `AI`: выбор движка, режим авторизации, `open` / `close` сессии, stop reason, usage, длительность.
  - `AI/ACP`, `AI/CODEX`, `AI/CLAUDE`: имена JSON-RPC-методов, id, ошибки.
  - `SEC`: чтение и запись `SecretStore`, только ключ хранилища.
  - Тексты промптов и ответов в release не логируются.
- **Секреты:**
  - Ключи не хранятся в DataStore, аргументах командной строки, логах или транскрипте.
  - Ключ не передаётся в `argv`: он виден в списке процессов.
  - Кандидат в правило detekt `heartbeat`: `Secret` в строковых шаблонах логов.
- **Внешние агенты — это исполнение чужого кода.** Команда агента подтверждается при добавлении; `fs/terminal`
  ограничены workspace; разрешения всегда идут в UI.

### Библиотеки

- `com.agentclientprotocol:acp` 0.30.1 (Apache-2.0, JetBrains). Работает только на JVM и требует JDK 21, поэтому у
  `engine-acp` toolchain 21. Desktop-runtime уже на JDK 21 (ADR-0008).
- `net.java.dev.jna:jna-platform` для `core:secrets:impl` на JVM: Windows DPAPI (`Crypt32Util`) и macOS Keychain
  (`Security.framework`).
- Для Codex и Claude Code отдельные библиотеки не нужны: хватает NDJSON JSON-RPC на kotlinx-serialization в
  `core:ai:process`.

### Порядок реализации

1. `core:secrets`, `core:ai:{api,spi,impl}`, `engine-native`, ключи в `agent-connections`, чат `ai-studio`.
   Работает на всех платформах.
2. `core:ai:process` + `engine-acp` (реестр, разрешения, fs и terminal).
3. `engine-codex`.
4. `engine-claude-code`.

## Альтернативы

| Вариант | Плюсы | Минусы | Почему нет |
|---|---|---|---|
| Всё через ACP, включая Codex (`codex-acp`) и Claude (`claude-agent-acp`) | один протокол, один движок | `codex-acp` с ключом перезаписывает общий `auth.json`; `claude-agent-acp` построен на Agent SDK со встроенным CLI — вход Claude.ai в нём противоречит политике Anthropic; подписка неотделима от «любого ACP-агента» | требование изоляции подписок не выполняется |
| Хранить токены подписок в `SecretStore` и отдавать движкам | единообразно с ключами | запрещено политикой Anthropic; токен можно «одолжить» любому движку | нарушает и политику, и требование |
| Ключ копируется в конфиг каждого движка | проще сделать | ротация в N местах, рассинхронизация | не выполняется требование переиспользования |
| Allowlist окружения процессов | строже | ломает инструменты агента (JAVA_HOME, прокси, SDK) | denylist + перенаправление каталогов вендоров |
| Свой ACP-клиент вместо SDK | JDK 17, нет новой зависимости | большой протокол, отставание от спеки | SDK официальный; заменим, если помешает JDK 21 |
| Встроенный чат через те же CLI | один путь | мобильные платформы не запускают процессы | Koog работает везде |

## Последствия

- **Проще:**
  - Один ключ на провайдера. Ротация и удаление с проверкой использования — в одном месте.
  - Новые ACP-агенты подключаются без кода.
  - UI чата одинаков для всех движков благодаря ACP-образной модели событий.
- **Сложнее:**
  - Три JVM-движка зависят от внешних CLI, которые меняются. Нужны контрактные тесты на закреплённые версии
    (`generate-json-schema`, stream-json) и проверка минимальной версии при старте.
  - Control-протокол разрешений Claude Code не документирован.
- **Нужно проверить при реализации:**
  - Поля JSON `claude auth status`.
  - Точные id `authMethods` у текущего `codex-acp`.
  - Имена `thread/resume` и `turn/interrupt` в app-server.
  - Приоритет `CODEX_API_KEY` над входом ChatGPT: изолированный `CODEX_HOME` делает его неважным, но стоит
    подтвердить.
  - Позицию OpenAI о подписке ChatGPT в сторонних клиентах app-server: в документации явного текста нет, только
    приглашение интеграторам.
- **Обновить после принятия:**
  - `docs/ai/architecture.md` (таблица core-модулей, правило `core:ai:spi`), `docs/ai/tech-stack.md` (ACP SDK, JNA).
  - Скилл `ai-koog`: `SecretStore` уезжает в `core:secrets`, фичи работают с `AgentSessions`, а не с
    `PromptExecutor`.
  - Хук `check-conventions.sh`: импорт `core.ai.spi` вне `engine-*`; `Secret` в логах.
  - `CLAUDE.md` (группы модулей).
