# Соответствие коннекторов connect-engine

Аудит исходников базы `b410de582`, продолжение PR #645 от 2026-11-02. Это исходная матрица работ, а не подтверждение живого регресса. Источник контрактов — KDoc API; стандарт — `.claude/skills/connect-engine/SKILL.md`, согласованный UI — `README.md` и `COMPOSE.md` в этом каталоге.

## Область и доказательства

Полный Desktop-редизайн и применимые контракты всех шести зарегистрированных коннекторов. Android/iOS: встроенный Koog и явный отказ desktop-процессов; этот PR не добавляет мобильный ACP-транспорт. ACP-interface — общий транспорт, не седьмой коннектор. Целевые версии Heartbeat и коннекторов пока определяются коммитом, отдельной compatibility manifest нет.

В колонке «Реализовано» отмечен только найденный путь исполнения. Наличие кода или теста не означает, что тест выполнен на текущем head. Для каждой строки §9 ниже результат сейчас BLOCKED до воспроизводимого исполнения; отсутствие реализации остаётся пробелом. N/A применяется только к доказанно отсутствующему отдельному бинарю Koog либо отсутствующей Managed-установке External DSH. Условные model capabilities не считаются N/A без проверки точного маршрута.

## Версии, платформы, установка и доступ

| Коннектор | Runtime / транспорт | Desktop | Android / iOS | Источники runtime | Доступ и каталог |
|---|---|---|---|---|---|
| Koog | `1.3.0` из `gradle/libs.versions.toml`; HTTP clients в процессе | macOS, Windows, Linux | Объявлены обе; нужны платформенные проверки | BuiltIn, без отдельного бинаря | OpenAI, Anthropic, Qwen Token Plan, compatible Chat Completions/Messages, Ollama; discovery по точному binding |
| Pi | `0.87.1` из version catalog; RPC/stdio | macOS, Windows, Linux | Unsupported managers/configurators | Bundled, Managed update, custom executable; hash-проверка pinned архива | API-ключи и пресеты, Z.ai Coding Plan/Qwen Token Plan, оба compatible протокола; Pi catalog |
| Codex | app-server; единого runtime pin в регистрации нет | macOS, Windows, Linux | Unsupported transport | Managed, system, custom; upstream release feed | Собственный CLI login OpenAI/ChatGPT; app-server discovery |
| Claude | Claude Code CLI streaming; единого runtime pin нет | macOS, Windows, Linux | Unsupported manager/backend | Managed, system, custom, HostApp; стабильный upstream manifest | CLI login, в том числе runtime приложения Claude; login приложения не импортируется |
| Kimi | `2.4.0-hb7`; source `f26e4d91ef8d5d233f6c20b42a150f96924d4f5e`, permissionGate 1; ACP v1 | macOS, Windows, Linux; пять native targets | Mobile backend отказывает | Только контролируемый Managed pin; retained lookup по версии/target | Собственный CLI login; `kimi-for-coding`, capability-dependent resume |
| DeepSeek Harness | Проверяемая линия `0.2.1-rc.5`; ACP v1 + process overlay MCP | macOS, Windows, Linux | Unsupported backend/manager | External; коннектор не устанавливает/не обновляет DSH | Собственный CLI login; точные модели/режимы из ACP |

Регистрации и платформенные ограничения:
- koog: `features/ai-engine/koog/impl/src/commonMain/kotlin/io/aequicor/heartbeat/feature/aiengine/koog/impl/di/KoogBindings.kt`; настройки установки — соответствующий `*ManagementSpec` в том же модуле.
- pi: `features/ai-engine/pi/impl/src/commonMain/kotlin/io/aequicor/heartbeat/feature/aiengine/pi/impl/di/PiBindings.kt`; настройки установки — соответствующий `*ManagementSpec` в том же модуле.
- codex: `features/ai-engine/codex/impl/src/commonMain/kotlin/io/aequicor/heartbeat/feature/aiengine/codex/impl/data/CodexBindings.kt`; настройки установки — соответствующий `*ManagementSpec` в том же модуле.
- claude: `features/ai-engine/claude/impl/src/commonMain/kotlin/io/aequicor/heartbeat/feature/aiengine/claude/impl/di/ClaudeBindings.kt`; настройки установки — соответствующий `*ManagementSpec` в том же модуле.
- kimi: `features/ai-engine/kimi/impl/src/commonMain/kotlin/io/aequicor/heartbeat/feature/aiengine/kimi/impl/di/KimiBindings.kt`; настройки установки — соответствующий `*ManagementSpec` в том же модуле.
- deepseek-harness: `features/ai-engine/deepseek-harness/impl/src/commonMain/kotlin/io/aequicor/heartbeat/feature/aiengine/deepseekharness/impl/di/DeepSeekBindings.kt`; настройки установки — соответствующий `*ManagementSpec` в том же модуле.

## Возможности и найденные пробелы

| Коннектор | Реализовано в исходниках | Требуется до объявления соответствия |
|---|---|---|
| Koog | `KoogBindings`, `DefaultKoogEngineAdapter`: multi-provider binding/discovery, session/history, cancel, trust, usage, compact, runtime configuration | Provider/model MIME/effort и isolated tool-deny доказать по точному маршруту; diagnostics M18, effective provenance; отсутствие отдельного бинаря не отменяет auth/студийные сценарии |
| Pi | `PiBindings`, `DesktopPiManager`, `DesktopPiConfigurator`: pinned distribution, checksum, profile/project config, MCP/resources с trust и bounds, RPC sessions | Версия Bundled сравнивается в `piCompatibility`; нет полной связки/решения, newest-compatible вместо latest и session stamp; native MCP и hooks требуют регресса |
| Codex | `CodexBindings`, `CodexManagement`, `LocalCodexTransport`: app-server, session trees, native gate, CLI login/install, read-only project assists | `EngineManager.compatibility` остаётся Unknown по умолчанию; offline safety probe не заменяет полный compatibility stamp; внешнее изменение файла и retained resume требуют интеграции |
| Claude | `ClaudeBindings`, `JvmClaudeManager`: source selection без fallback, HostApp discovery, login, observed history/trees, tools bridge, project assists | Нет compatibility override/pin; stable manifest не доказывает newest-compatible; полный native planning и resume/history должны быть подтверждены отдельно |
| Kimi | `KimiRuntimeRelease`, `KimiRuntimeSetup`, `JvmKimiBackend`: manifest/source/hash/native target, pinned/retained runtime, ACP capability negotiation | Точное равенство pin даёт Verified без общей связки; связать negotiation/admission/session stamp и проверить retained history после отката |
| DeepSeek Harness | `JvmDeepSeekManager`, `DeepSeekRuntime`, `DeepSeekHosted`: bounded version probe, ACP, scoped hosted attachment, one-shot correlation | Общая compatibility пока Unknown; fixed supported line не оформлена единым compatibility decision; сохранение native session stamp, capabilities и External fingerprint |

Конфигурация: Pi объявляет полноценный configurator; Codex/Claude подключают `ProjectAssistReader.Schema` (это не эквивалент всех настроек CLI). Koog/Kimi/DSH не объявляют полноценного configurator в регистрации. Отсутствие настройки в UI не доказывает, что upstream не имеет такой возможности.

Общие пробелы:

- `facade:api/EngineManagement.kt`: только Verified/Unverified/Unknown; нет typed Incompatible с доказательством и единой readiness для inspect/connect/create/resume.
- `facade:api/spi/ReleaseFeeds.kt` и managers: resolveRelease возвращает один latest/stable candidate, не перебирает доказанно совместимые кандидаты.
- `facade:impl/SessionIndex.kt`, `RoomSessionIndex.kt`: нет полной connector/runtime/protocol/config/history связки сессии; rollback нельзя объявлять безопасным для всех коннекторов по retained installer одного Kimi.
- `acp-interface:impl/DefaultAcpConnection.kt` уже проверяет protocol v1 и loadSession capability. Переиспользовать эти факты; не создавать второй ACP клиент.
- `connections`: нет edit-operation ключа/endpoint и revision-bound двухаспектных отчётов; сохранение моделей и catalog refresh не являются M18.
- `facade:impl/data/safety/IsolatedServiceTurns.kt`: queue/cleanup входят не во все внешние timeout; перед M18 требуется отдельный lifetime и обязательный tool-deny без feature hooks.
- Нет достоверного общего effective/provenance merge; чтение проекта для сравнения сначала требует trust gate. Секреты и чувствительные инструкции в таблицу не попадают.
- `control-panel` пока имеет две вкладки; connections и ресурсы ещё не объединены. `COMPOSE.md` сохраняет список UI-пробелов и ссылки на утверждённые кадры.
- Studio допускает кешированный Unknown; простое скрытие навсегда уберёт восстановленные модели. Требуется явный cancellable refresh владельца без IO из чтения списка.
- M17 не доказан для macOS DMG/Windows/Linux способов удаления. Нельзя обещать автоматическое удаление runtime с приложением без проверки дистрибутива.

## Регресс §9: применимость и текущий результат

Колонки относятся к Desktop каждой регистрации, отдельно macOS/Windows/Linux. Все BLOCKED ниже означают «живой сценарий ещё не выполнен на текущем head», а не отсутствие поддержки у upstream. Модель, авторизация и runtime-source фиксируются при фактическом прогоне; сейчас они не выбраны. Мобильные процессные сценарии — проверка явного Unsupported, не запуск CLI.

| ID | Koog | Pi | Codex | Claude | Kimi | DSH | Доказательство / зависимость |
|---|---|---|---|---|---|---|
| M01 | N/A: без бинаря | BLOCKED | BLOCKED | BLOCKED | BLOCKED | N/A: External | Installer: `ManagedEngineRuntimeInstallerTest`, `FileManagedInstallStoreTest`; нужны реальные staged install/progress/restart. |
| M02 | N/A: без бинаря | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Startup/manager tests каждого процесса; проверить явный поиск без IO при чтении списка. |
| M03 | N/A: без бинаря | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Launch validation есть; нужен системный picker, неверный путь, отмена и сохранение источника. |
| M04 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | `connections:api/impl` tests; нет полного edit credentials/endpoint и M18. |
| M05 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Проверить stop/unbind и неизменность внешних файлов/login; Koog проверяет отключение binding без удаления provider данных. |
| M06 | N/A: без бинаря | BLOCKED | BLOCKED | BLOCKED | BLOCKED | N/A: External | Installer removal tests; проверить UI confirmation, active turn и сохранение истории/config. |
| M07 | N/A: без бинаря | BLOCKED | BLOCKED | BLOCKED | BLOCKED | N/A: External | Кандидат staging/hash покрыт installer tests; live update/rollback и protocol baseline не доказаны. |
| M08 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Pi configurator и MCP; Codex/Claude launch MCP; ACP routes проверять по negotiated возможностям. Условную неприменимость пока не доказывали. |
| M09 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | `EngineConfigValidationTest`, configurator и credentials; application time и unique fields требуют наблюдения. |
| M10 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Трёх состояний API недостаточно; сначала structured compatibility/admission. |
| M11 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | `AcpConnectionTest` проверяет v1 mismatch/omitted loadSession; для остальных транспортов определить эквивалент baseline. |
| M12 | N/A: без бинаря | BLOCKED | BLOCKED | BLOCKED | BLOCKED | N/A: External | Pin/hash/retained Kimi tests; нужны изолированный home и контроль внешних файлов для каждого Managed runtime. |
| M13 | N/A: без бинаря | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Перепроверить fingerprint/version при новом запуске; старый inspect не является разрешением. |
| M14 | N/A: без бинаря | BLOCKED | BLOCKED | BLOCKED | BLOCKED | N/A: External | Нет общего candidate list/newest-compatible; сначала facade/SPI и policy коннектора. |
| M15 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | История/attach реализованы неодинаково; сначала stamp и admission resume, затем retained/read-only/handoff. |
| M16 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Installer retained не доказывает совместимость mutable home/config; нужен сценарий отката коннектора. |
| M17 | N/A: без бинаря | BLOCKED | BLOCKED | BLOCKED | BLOCKED | N/A: External | Нужна отдельная изолированная установка/удаление каждого способа распространения приложения. |
| M18 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | ModelChecks отсутствует; две изолированные пробы, revisions и общий бюджет от клика до дедлайна не реализованы. |
| E01 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Реальный target/model, несколько ходов, hosted/native tools и проверяемый результат. |
| E02 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Native gate/permission tests существуют; нужны Ask/Automatic/Deny без опасного действия. |
| E03 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Safety command tests; временный проект и безопасные команды bash/git/python. |
| E04 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | PromptInputSupport и тесты вложений; скрытая визуальная метка на точной multimodal модели. |
| E05 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Нужна подтверждённая передача video resource точным runtime/model; изображение не является доказательством. |
| E06 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Codex/Claude trees и DSH subsessions наблюдаются по-разному; проверить parent/child/transcript и полноту. |
| E07 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Наблюдать фактическую модель следующего хода; изменение подписи не является подтверждением. |
| E08 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Каталоги effort Pi/Koog/Claude/Codex; ACP — по capabilities. Проверить request и следующий ход. |
| E09 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Usage/context/quota контракты и tests; Unknown должен оставаться Unknown для точного binding. |
| E10 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Временный Git-проект; проверить cwd/files/commands и неизменность исходного checkout. |
| E11 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Изолированные клетки и hosted tools; нужны реальные permissions/lifecycle и результат. |
| E12 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | `ai-session-engine-transfer` tests; live handoff между каждым применимым маршрутом без повтора хода. |
| E13 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Native+hosted mapping; Koog hosted tools не доказывают наличие отдельного native семейства, применимость уточнить. |
| E14 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | `auto-continue` tests; live done/needs_user/permission/unknown без повторного submit. |
| E15 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Нативный planning и HasModes надо исследовать для каждого runtime; host planning не считать native. |
| E16 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Cancel/reconciliation tests; реальный stop/restart/disconnect без дубликатов и ложного terminal. |
| E17 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | Проверить пересчёт inputs/effort и сохранение истории после смены модели. |

## Внешние основания

- [ACP v1 Initialization](https://agentclientprotocol.com/protocol/v1/initialization), обращение 2026-11-02: общая major protocol version обязательна, omitted capabilities считаются unsupported. Существующий `DefaultAcpConnection` уже проверяет v1.
- [SemVer 2.0.0](https://semver.org/), обращение 2026-11-02: номер версии описывает контракт издателя, но не доказывает совместимость всей связки Heartbeat. Hard ranges по каждому коннектору ещё требуют закреплённых источников и тестов.

## Порядок реализации и проверки

1. Общий typed compatibility/readiness контракт и тесты hard-range/denylist/malformed/protocol/baseline/native target. Диапазоны каждого runtime исследовать по закреплённым upstream-источникам; не угадывать их по SemVer.
2. Связать admission с фактическими transport negotiation, installation candidate selection и session stamps/retained resume. Только затем включать новое поведение панели/мастера.
3. ModelChecks и effective/provenance — самостоятельные контракты до их UI. Не раскрывать feature hooks/проект для служебных проб.
4. Утверждённые вкладки/мастер/диалоги и dropdown без автоматической подмены; classic start и параметры последнего принятого рабочего чата.
5. JVM compilation/tests, detekt с типами и DI; iOS common consumers локально на macOS; Windows/Linux отличия через существующий module-build при необходимости.
6. Живые проверки в тестовом профиле/временном проекте и визуальная сверка обеих тем. Разрешение пользователя на короткие запросы с квотой получено 2026-11-02; это не разрешает опасные действия над данными или новые workflow.

Проверки этой матрицы: локальная проверка ссылок на файлы и политика размера коммита; Gradle для документационного шага не требуется. Исторические зелёные результаты `COMPOSE.md` не перенесены на будущую реализацию.
