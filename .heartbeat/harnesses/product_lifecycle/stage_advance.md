Репозиторий шага: `{{repository}}`. Все операции GitHub выполняй явно в нём: для gh issue/pr/workflow/release добавляй `--repo {{repository}}`, для gh api — путь `repos/{{repository}}/...` или соответствующие owner/name в GraphQL. Это правило действует и для загруженных навыков. Перед локальной разработкой, verify и git log сверь origin рабочего каталога с этим репозиторием; при несовпадении подготовь правильный checkout.

Переведи фичу #{{issue}} с этапа {{from_stage}} на {{to_stage}} в продуктовом цикле Heartbeat.

Порядок:
1. Проверь готовность завершённого этапа {{from_stage}}: загрузи его навык (requirements → biz_requirements, design → design_phase, spec → func_requirements, development → development_bridge, testing или acceptance → testing_acceptance, released → release_prep). Проверь артефакты и требуемые подтверждения пользователя; смена метки/доски — результат перехода, а не его предусловие. Затем загрузи навык целевого этапа {{to_stage}} для следующей работы.
2. Доказательство перехода: {{evidence}}. Если пусто — собери сам из артефактов завершённого этапа (ссылки на под-issue, PR, прогоны). Нет доказательства — не переводи, спроси пользователя.
3. Оставь комментарий в #{{issue}} с доказательством (ссылки, краткий итог этапа).
4. Смени метку: gh issue edit {{issue}} --repo {{repository}} --remove-label "stage:{{from_stage}}" --add-label "stage:{{to_stage}}". Если метки stage:{{from_stage}} нет — просто добавь новую.
5. Обнови строку «Стадия: {{to_stage}}» в теле эпика (gh issue edit --body, сохранив остальное тело и маркер heartbeat:lifecycle).
6. Обнови доску: ui_call product_lifecycle set_stage {"epic": {{issue}}, "stage": "{{to_stage}}"}.
7. Если {{to_stage}} = development — продолжи по development_bridge; testing — запусти проверки (verify); acceptance — запусти приёмку (шаблон acceptance_report).
