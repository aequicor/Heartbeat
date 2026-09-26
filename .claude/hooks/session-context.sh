#!/usr/bin/env bash
# SessionStart hook: prints a compact inventory of Gradle modules and features into Claude's context.

set -u
root="${CLAUDE_PROJECT_DIR:-$PWD}"
cd "$root" 2>/dev/null || exit 0

echo "## Heartbeat: состояние проекта"

if [ -f settings.gradle.kts ]; then
  modules="$(grep -oE 'include\("[^"]+"\)' settings.gradle.kts | sed -E 's/include\("([^"]+)"\)/\1/' | tr '\n' ' ')"
  echo "Gradle-модули: ${modules:-<нет>}"
fi

if [ -d features ]; then
  feats="$(find features -mindepth 1 -maxdepth 1 -type d -exec basename {} \; 2>/dev/null | sort | tr '\n' ' ')"
  echo "Фичи: ${feats:-<нет>}"
  keys="$(grep -rhoE 'object [A-Za-z0-9]+MachineKey' features --include='*.kt' 2>/dev/null | sed 's/object //' | sort -u | tr '\n' ' ')"
  [ -n "$keys" ] && echo "Машины (MachineKey): $keys"
else
  echo "Фичи: ещё нет (шаблон; целевая раскладка — docs/ai/architecture.md)"
fi

if [ -d build-logic ]; then
  ids="$(grep -hoE 'heartbeat-[a-z-]+ = \{ id = "[^"]+"' gradle/libs.versions.toml 2>/dev/null | sed -E 's/.*id = "([^"]+)"/\1/' | tr '\n' ' ')"
  echo "build-logic: convention-плагины ${ids:-<нет> }| правила detekt heartbeat — lint/detekt-rules"
else
  echo "build-logic ещё не создан — detekt/convention-плагины не подключены."
fi

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Ветка: $(git branch --show-current 2>/dev/null); изменённых файлов: $(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
fi
exit 0
