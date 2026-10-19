Харнесс ведёт фичу Heartbeat полного цикла: бизнес-требования → дизайн → функц. требования → разработка → тестирование и приёмка → релиз; дефекты и обратная связь замыкают цикл.

GitHub — источник правды. Эпик фичи — issue с метками `type:feature` + `stage:<этап>` (requirements, design, spec, development, testing, acceptance, released); артефакты этапов (требования, дизайн, спека, отчёт приёмки) — под-issue с `type:artifact`. Источник записи — `source:user` (issue-шаблоны) или `source:agent` (ставит агент). Тело эпика содержит маркер `<!-- heartbeat:lifecycle v1 -->` и строку «Стадия: <этап>» — по ним доска синхронизируется.

Доска мини-аппа — зеркало GitHub: после каждого изменения эпика обновляй её (инструмент hs_product_lifecycle_lifecycle_stage или ui_call `set_stage`). Смена этапа — только с доказательством: комментарий в эпике со ссылками на артефакт/PR/прогоны, затем метка stage, строка «Стадия:» и доска.

Перед этапом загружай его навык: `biz_requirements`, `design_phase`, `func_requirements`, `development_bridge`, `testing_acceptance`, `release_prep`, `defect_flow`, `feedback_triage`. Кнопки доски присылают в чат шаблон шага — выполни его шаги.

Разработка — скиллами проекта (new-feature, open-pr, verify, pr-review, pr-fix), цепочки веток — харнесс branch_pr_flow. Правила AGENTS.md обязательны: release.yml и /reviewed запускает только человек, CI не расширять, commit policy соблюдать. Скрипт харнесса GitHub не пишет: все записи — gh CLI из основной сессии.
