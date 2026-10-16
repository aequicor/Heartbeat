---
name: adaptive-subagents
description: "Адаптивные субагенты Heartbeat (features:adaptive-subagents): библиотека описаний моделей (оценки качество/скорость/стоимость, варианты effort), автоописание ресерчем (models.dev, поиск, синтез без инструментов), подбор конфигурации субагента AdaptiveModels (скоринг, шорт-лист, LLM-роутер), инструменты subagent_* обычных чатов, подключение в органическом ИИ, харнессах и подключениях. Используй при их изменении или добавлении нового хоста субагентов."
---

# Адаптивные субагенты: как менять фичу

Субсессия (клетка организма, помощник чата, workflow или скрипта харнесса) получает не модель родителя, а
конфигурацию «движок × подключение × модель × reasoning effort», подобранную по приоритету качества, скорости
и стоимости среди моделей, включённых пользователем. Основа — описания моделей в профиле: вручную или ресерчем.
Тоглы (все по умолчанию false): `adaptive_subagents.enabled` (вся фича), `.research` (автоописание),
`.router` (LLM-роутер), `.session_tools` (инструменты `subagent_*`; ещё нужны `scheduler.enabled` и `scheduler.actions`).

## Где что лежит

Пути — от `features/adaptive-subagents/`.

| Что | Где |
|---|---|
| Описание модели: типы, лимиты, нормализация и проверка | `api/.../ModelProfile.kt` (`ModelProfile`, `ProfileVariant`, `Ratings`, `ProfileLimits`, `normalized()`, `problem()`) |
| Машина библиотеки (сохранение по ревизии, предпочтения, задачи ресерча) | `api/.../ModelProfilesContract.kt`, `ModelProfilesMachineSpec.kt` (таблица переходов в KDoc); эффекты — `impl/domain/ModelProfilesEffects.kt`, KV — `impl/data/KeyValueModelProfiles.kt` |
| Автоописание | `api/.../ModelResearch.kt`; конвейер — `impl/data/research/WebModelResearch.kt`, факты — `ModelsDevFacts.kt`; запросы и отбор страниц — `impl/domain/research/ResearchPlan.kt`, промпт и разбор — `Synthesis.kt` |
| Подбор | контракт — `api/.../AdaptiveModels.kt`, `SubagentRequest.kt`; сервис — `impl/data/ProfileAdaptiveModels.kt`; кандидаты — `impl/data/CatalogCandidates.kt`, `impl/domain/selection/Candidates.kt`; оценки — `Heuristics.kt`, `Scoring.kt`; роутер — `impl/data/router/ShortlistRouter.kt`, `RouteAnswer.kt`; объяснение — `Rationale.kt` |
| Инструменты обычных чатов | `api/.../SubagentTools.kt`; `impl/data/tools/SubagentAgentTools.kt`, `SubagentToolSpecs.kt`, ревью запуска — `SubagentToolSafety.kt`; реестр запусков — `impl/data/runs/KeyValueSubagentRuns.kt` |
| Экраны | `impl/presentation`, `impl/ui` (раздел `SettingsSection.Subagents`, редактор `ModelProfileRoute`) |
| Потребители | organic-ai `OrganismAgentTools.configure`, `CellDriver`; harness `HarnessHelperModels` + `AdaptiveHarnessHelperModels`; connections `LibraryModelDescriptions` (кнопка у модели) |
| Служебные промпты и модель ревью | `IsolatedPrompts`, `PromptRequest.reviewTarget`, `AgentToolContext.reviewModel` (`ai-engine:facade:api`); `HelperModelOptions`, `BackgroundAgents` (`scheduler:api`) |

## Правила

- Задачи, тексты описаний, страницы, промпты и ответы не логируются: только счётчики, стадии, источник выбора.
- `AdaptiveModels.select` не бросает, кроме отмены: сбой каталога — `Inherit`, сбой роутера — лучший по скорингу
  (`RouterFallback`). Потребитель на `Inherit` сохраняет прежнее поведение (модель родителя или профиля).
- Оценки 1–5, `cost` 5 — дешевле. Машина сохраняет только `normalized()` без `problem()` и при совпадении ожидаемой
  ревизии; новые поля `ModelProfile` — со значениями по умолчанию (старые записи KV читаются) и лимитом в `ProfileLimits`.
- Ресерч: в сеть ходит только хост (`SearchEngine`, models.dev). Исследователь получает страницы в nonce-ограде
  через `IsolatedPrompts` без инструментов; источники принимаются только по индексам страниц хоста, effort — только
  из объявленных моделью, одна попытка починки JSON; параллельно не более `ResearchLimits.MAX_JOBS` задач.
- Подбор: жёсткие фильтры (доступность и создание сессии, resume для клеток, потолок доверия для помощников,
  workspace, входы, `excluded`, запрет кросс-движка) скоринг не ослабляет. Шорт-лист — `SelectionLimits.SHORTLIST`;
  при запрете кросс-движка и неизвестной модели родителя выбор возвращает `Inherit`.
  Роутер пропускается при одном кандидате или явном отрыве и ограничен 20 с.
- Потребитель выбирает модель один раз, до получения слота или создания сессии; replay и восстановление модель не
  меняют, явный target главнее. Ход на модели, отличной от родителя, передаёт `reviewTarget` = модель родителя
  (помощники студии ставят его сами), чтобы автоматическое ревью не ослабевало на дешёвой модели.
- `subagent_spawn` — Command: в `AutomaticSafety` запуск разрешает только safe-вердикт `SubagentToolSafety` на модели
  ревью, в Interactive — решение пользователя с полным текстом задачи. Запуск — `BackgroundAgents.start`; результат
  удерживается до `acknowledge` в `subagent_receive`. До запуска `SubagentStarts` резервирует запись реестра;
  заполненный реестр отказывает без вытеснения непрочитанных результатов. Выдача идёт ограниченными пачками;
  до подтверждения scheduler результаты пачки копируются в реестр, чтобы сбой очистки не потерял ответы.
  Ожидание — обычный wake агента на `action.<id>.finished` через `BackgroundAgents.schedule`: завершать ход можно
  только после подтверждения сохранённого пробуждения.

## Добавить хост субагентов

1. Значение в `SubagentHost` и требования хоста к движку в `impl/domain/selection/Candidates.kt`.
2. Порт подбора в domain потребителя и адаптер над `AdaptiveModels` в его data (образец — `AdaptiveHarnessHelperModels`).
3. Target и effort — в создание сессии, `reviewTarget` — в ходы. Тесты: выбор до ресурса, явный target главнее,
   восстановление не выбирает заново, `Inherit` ничего не меняет.
4. Обнови абзац фичи в `CLAUDE.md` и исключение в `.claude/rules/feature-api.md`, если меняется контракт.

## Проверка

```bash
./gradlew :features:adaptive-subagents:api:jvmTest :features:adaptive-subagents:impl:jvmTest
./gradlew :features:organic-ai:impl:jvmTest :features:harness:impl:jvmTest :features:ai-engine:connections:impl:jvmTest
./gradlew :platform-main:di-bundle:jvmTest
```

Плюс detekt затронутых модулей по скиллу `verify`. Вручную (Desktop, тоглы включены): описание модели вручную
и ресерчем, «Пробный подбор» в разделе «Субагенты», `subagent_spawn` → чат-помощник на выбранной модели →
пробуждение родителя → `subagent_receive`, деление клетки организма, workflow с `AgentOptions(adaptive = …)`.
