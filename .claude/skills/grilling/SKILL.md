---
name: grilling
description: "Беспощадное интервью о плане, решении или идее: дерево решений, раунды вопросов по фронту, рекомендация к каждому вопросу. Используй, когда пользователь просит прожарить/стресс-тестировать своё мышление или вызывает grill-*."
license: MIT
metadata:
  source: "https://github.com/mattpocock/skills/tree/main/skills/productivity/grilling"
---

# Grilling

Interview the user relentlessly until you reach a shared understanding. Map this as a **design tree**: every decision branches into the decisions that hang off it.

Work the tree in **rounds**. The **frontier** is every decision whose prerequisites are already settled: the questions you can ask _now_ without guessing at answers you haven't heard yet. Ask the whole frontier in one round: number each question and give your recommended answer. Then wait for the user's answers before the next round.

Format a round like so:

```
❓ **Q1** - **<question title>**: <question body, might be multiple paragraphs, including multiple choices>

➡️ <your recommended answer>

---

❓ **Q2** - **<question title>**: <question body, might be multiple paragraphs, including multiple choices>

➡️ <your recommended answer>
```

Word each question so "yes" accepts your recommended answer.

Each round the user answers reshapes the tree: settled decisions push the frontier outward and unblock questions that depended on them. Recompute the frontier and ask the next round. A question whose answer depends on another question still open in this round belongs to a _later_ round, not this one.

Finding _facts_ is your job, never the user's. When a frontier question needs a fact from the environment (filesystem, tools, etc.), dispatch a sub-agent to find it; don't ask the user for anything you could look up yourself. Don't block on it: a running exploration is an unsettled prerequisite, so only the questions downstream of it wait for the sub-agent to report; ask the rest of the frontier now. The _decisions_ are the user's: put each to them and wait.

The session is done when the frontier is empty: every branch of the design tree visited, nothing left silently assumed. Do not act on it until the user confirms you have reached a shared understanding.

## Адаптация под этот рантайм

- Раунд задавай инструментом `questionnaire_ask` (по одному вопросу на `❓`, `kind: FreeText`, если варианты не перечислимы) либо текстом в формате выше. В следующий раунд не переходи, пока не пришли ответы.
- «Dispatch a sub-agent» — это `subagent_spawn`; факты о коде и файловой системе выясняй сам (`read_file`, `grep`, `glob`), не спрашивая пользователя.
- Сессия **stateless**: файлы не пиши, рабочее состояние не оставляй. Единственный результат — более ясная идея у пользователя в голове.
- **Ungrillable**-вопрос (нужно на что-то посмотреть, а не ответить: «одна длинная форма или три страницы?», «как должно ощущаться взаимодействие?») — прекращай допрос, предложи выбросовой прототип и вернись к вопросу после него.
- Если объём слишком велик для одной сессии — предложи разбить на части и прожаривать каждую отдельно.
