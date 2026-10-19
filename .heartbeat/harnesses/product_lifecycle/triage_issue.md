Репозиторий шага: `{{repository}}`. Все операции GitHub выполняй явно в нём: для gh issue/pr/workflow/release добавляй `--repo {{repository}}`, для gh api — путь `repos/{{repository}}/...` или соответствующие owner/name в GraphQL. Это правило действует и для загруженных навыков. Перед локальной разработкой, verify и git log сверь origin рабочего каталога с этим репозиторием; при несовпадении подготовь правильный checkout.

Разбери входящий issue #{{issue_number}} продуктового цикла Heartbeat.

1. Загрузи навык feedback_triage (harness_skill_load product_lifecycle/feedback_triage).
2. Прочитай issue: gh issue view {{issue_number}} --repo {{repository}} --comments (тело, метки, автор, история).
3. Классифицируй по навыку:
   - дефект → метка type:defect, дальше defect_flow;
   - пожелание/идея → type:feedback; если тянет на фичу — предложи пользователю завести эпик (шаблон feature_kickoff с темой из фидбека), дождись подтверждения;
   - вопрос/дубль → ответь, при необходимости закрой со ссылкой.
4. Оформи результат: поставь метки, ответь автору в issue (что поняли, что дальше), свяжи с эпиком, если фидбек стал фичей.
5. После завершённого триажа отметь issue на доске: ui_call product_lifecycle mark_feedback {"number": {{issue_number}}}; затем sync_from_github. Если ответ/классификация ещё не закончены, отметку не ставь.

Правила: source:user не менять; кратко и вежливо; каждый разобранный issue получает ответ.
