---
name: pr-review
description: "Агентское ревью PR Heartbeat перед мержем — по коммитам (каждый отдельно и параллельно), инкрементально после новых push, затем сквозная проверка ветки и verify на head. Без блокеров оставляет от имени владельца комментарий `/reviewed <sha>`, который превращается в approve. Используй, когда просят проверить/отревьюить PR или подготовить его к мержу."
---

# PR review

Цель — честное ревью конкретного head SHA. `/reviewed` = подпись владельца: ставь её только когда сам
проверил этот SHA и блокеров нет. Механизм approve — раздел «Ревью PR» в `AGENTS.md`.

## 1. Зафиксируй объект ревью

```bash
gh pr view <N> --json number,headRefOid,baseRefName,isDraft,state,title,body,commits
git fetch origin <base> "pull/<N>/head:refs/remotes/pr/<N>"
```

- `HEAD_SHA` = `headRefOid`; `BASE` = `git merge-base origin/<base> $HEAD_SHA`. Дальше работай только с этими SHA,
  рабочую копию не переключай (`git show`, `git diff`, `git log` по SHA).
- Коммиты: `git rev-list --reverse $BASE..$HEAD_SHA`. Draft/закрытый PR — не ревьюь, сообщи.

## 2. Инкрементальность — не ревьюь повторно

Прошлые ревью лежат в комментариях PR с маркером `<!-- pr-review reviewed: <patch-id>... -->`:

```bash
gh pr view <N> --json comments --jq '.comments[].body' | grep -o 'pr-review reviewed: [^>]*'
git show <sha> | git patch-id --stable      # patch-id не меняется при rebase без правок
```

Коммит с уже отревьюенным patch-id без блокеров пропускай. Всё остальное — в очередь на шаг 3.

## 3. Ревью по коммитам (параллельно)

Каждый коммит ≤ 25 000 токенов diff (политика репо) — он помещается в контекст целиком. На каждый коммит из
очереди запусти субагентов **одним сообщением**, чтобы они шли параллельно:

- `architecture-reviewer` — всегда для `.kt`/`.kts`/Gradle.
- `ui-reviewer` — если коммит трогает Compose UI или `design-system`.
- `general-purpose` — корректность: баги, гонки, обработка ошибок, тесты изменённого поведения.
  Для коммитов только в `scripts/`, `.github/`, `.claude/`, `.agents/` достаточно его одного.

Промпт субагенту: SHA коммита, команда `git show <sha>` (не `git diff HEAD`), сообщение коммита, цель PR (title/body),
список применимых `.claude/rules/` по путям. Попроси вернуть находки с `file:line`, уровнем
**blocker / major / minor** и обоснованием. Код вне diff не ревьюь, если только diff его не ломает.

Проверь и сам коммит как логический шаг: одна цель, сообщение соответствует содержимому,
тесты идут вместе с поведением, нет fixup-коммитов, которые следовало объединить
(коммиты с трейлером `Addresses-Review:` от `pr-fix` — допустимый ответ на ревью, не fixup).

## 4. Сквозная проверка ветки

Отдельно от коммитов (дёшево — по `git diff --stat $BASE $HEAD_SHA` и точечному чтению):

- Проблема из коммита A исправлена в B → не блокер по A, но fixup нужно объединить (major),
  если B не помечен `Addresses-Review:`.
- Итоговое состояние: нет недоделок между коммитами, KDoc/правила агентов обновлены, новая функциональность за тоглом.
- `python scripts/check_commit_size.py --base $BASE --head $HEAD_SHA`.
- `gh pr checks <N>` — все обязательные проверки зелёные (упавший или незавершённый CI = не подписывать).
- Скилл `verify` на head во временном worktree: `git worktree add <scratchpad>/pr-<N> $HEAD_SHA`,
  после — `git worktree remove`. Покоммитную сборку не гоняй — только если коммит трогает Gradle/build-logic
  или есть явное подозрение, что промежуточный коммит не собирается.

## 5. Вердикт и комментарий

Перед публикацией убедись, что head не сдвинулся: `gh pr view <N> --json headRefOid`. Сдвинулся — вернись к шагу 1.

**Блокер** — любая находка blocker, нарушение жёстких правил `CLAUDE.md`, красный verify/CI/commit-size.
Находки major при отсутствии блокеров подпись не отменяют, но перечисли их.

Один комментарий; `review-approve` читает только первую строку, поэтому команда — первой строкой и только без блокеров:

```bash
gh pr comment <N> --body-file <scratchpad>/review-<N>.md
```

```markdown
/reviewed <полный HEAD_SHA>

**Agent review** — <N> коммитов (<M> новых, <K> пропущено по patch-id), verify: ✅, CI: ✅

| Коммит | Итог | Находки |
|---|---|---|
| `abc1234` Subject | ✅ | — |

<details><summary>Находки major/minor</summary> … `file:line` — суть … </details>

<!-- pr-review reviewed: <patch-id> <patch-id> ... -->
```

Есть блокеры — та же структура **без** строки `/reviewed`, блокеры первыми; в маркер включай только чистые коммиты.
Не подписывай, если какую-то проверку выполнить не удалось, — напиши какую и почему.

Пользователю в ответе: вердикт, блокеры, ссылка на комментарий. После `/reviewed` проверь, что workflow
`Review approval` поставил approve: `gh pr view <N> --json reviewDecision` → `APPROVED`.
