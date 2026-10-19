Разбери входящий issue #{{issue_number}} продуктового цикла Heartbeat.

1. Загрузи навык feedback_triage (harness_skill_load product_lifecycle/feedback_triage).
2. Прочитай issue: gh issue view {{issue_number}} --comments (тело, метки, автор, история).
3. Классифицируй по навыку:
   - дефект → метка type:defect, дальше defect_flow;
   - пожелание/идея → type:feedback; если тянет на фичу — предложи пользователю завести эпик (шаблон feature_kickoff с темой из фидбека), дождись подтверждения;
   - вопрос/дубль → ответь, при необходимости закрой со ссылкой.
4. Оформи результат: поставь метки, ответь автору в issue (что поняли, что дальше), свяжи с эпиком, если фидбек стал фичей.
5. Обнови доску: ui_call product_lifecycle sync_from_github.

Правила: source:user не менять; кратко и вежливо; каждый разобранный issue получает ответ.
