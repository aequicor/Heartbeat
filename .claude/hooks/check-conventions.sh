#!/usr/bin/env bash
# PostToolUse hook (Edit|Write|MultiEdit): fast convention checks for the edited Kotlin/Gradle file.
# Exit 2 + stderr => feedback is shown to Claude so it fixes the violation immediately.
# Heavy checks (detekt, compilation) live in the `verify` skill, not here.

set -u

input="$(cat)"

# Extract tool_input.file_path without jq (not available on every dev machine).
file_path="$(printf '%s' "$input" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
[ -z "$file_path" ] && exit 0

# Normalise Windows paths: C:\\Users\\... -> C:/Users/...
file_path="$(printf '%s' "$file_path" | sed 's#\\\\#/#g; s#\\#/#g')"
project_dir="$(printf '%s' "${CLAUDE_PROJECT_DIR:-$PWD}" | sed 's#\\#/#g')"
rel="${file_path#"$project_dir"/}"

case "$rel" in
  *.kt|*.kts) ;;
  *) exit 0 ;;
esac
[ -f "$file_path" ] || exit 0

violations=()
add() { violations+=("$1"); }

# grep_lines <regex> -> "line: text" (first 5 hits)
grep_lines() { grep -nE "$1" "$file_path" | head -n 5 | sed 's/^/    /'; }

is_test=false
case "$rel" in
  */src/*Test/*|*/src/test/*) is_test=true ;;
esac

# ---------- Kotlin sources ----------
if [[ "$rel" == *.kt ]]; then

  # 1. Logging only through core:logging
  #    (lint/ — реализация правил detekt: упоминает запрещённые API как данные)
  if [[ "$rel" != core/logging/* && "$rel" != lint/* ]]; then
    hits="$(grep_lines '(^|[^A-Za-z_.])(println|print)\(|System\.(out|err)\.|android\.util\.Log|NSLog\(|import io\.github\.aakira\.napier')"
    [ -n "$hits" ] && add "Логирование только через core:logging (Log.tag(...)). См. docs/ai/logging-policy.md
$hits"
  fi

  # 2. Coroutines hygiene (main code)
  if ! $is_test; then
    hits="$(grep_lines 'GlobalScope|runBlocking[[:space:]]*[({]')"
    [ -n "$hits" ] && add "GlobalScope/runBlocking запрещены в main-коде — используй инжектируемый scope и DispatcherProvider.
$hits"
  fi

  # 3. Empty catch blocks (lint/ — тестовые фикстуры правил содержат нарушения намеренно)
  [[ "$rel" == lint/* ]] || hits="$(grep_lines 'catch[[:space:]]*\([^)]*\)[[:space:]]*\{[[:space:]]*\}')"
  [ -n "$hits" ] && add "Пустой catch запрещён: залогируй ошибку (и пробрось CancellationException).
$hits"

  # 4. Colors only from design-system tokens
  if [[ "$rel" != design-system/tokens/* ]] && ! $is_test; then
    hits="$(grep_lines 'Color\(0[xX][0-9A-Fa-f]+|Color\.(Red|Blue|Green|Black|White|Gray|Yellow|Cyan|Magenta)\b')"
    [ -n "$hits" ] && add "Цвета только из токенов HbTheme.colors (docs/ai/design-system.md). Hex допустим лишь в design-system/tokens.
$hits"
  fi

  # 5. Feature boundaries
  if [[ "$rel" =~ ^features/([^/]+)/impl/ ]]; then
    self="${BASH_REMATCH[1]//-/}"
    hits="$(grep -nE 'import io\.aequicor\.heartbeat\.feature\.[a-z0-9_]+\.impl' "$file_path" \
            | grep -vE "feature\.${self}\.impl" | head -n 5 | sed 's/^/    /')"
    [ -n "$hits" ] && add "impl → чужой impl запрещён. Используй api другой фичи (MachineKey / EntryPoint).
$hits"
    hits="$(grep_lines 'import ru\.nsk\.kstatemachine\.')"
    [ -n "$hits" ] && add "KStateMachine — внутренность core:state-machine:impl. Фича описывает машину через machineSpec { } (core:state-machine:api), запускает через MachineLauncher. См. docs/adr/0004-state-machine.md
$hits"
    hits="$(grep_lines 'import (io\.github\.composefluent|dev\.nucleusframework|androidx\.compose\.material3)\.')"
    [ -n "$hits" ] && add "Фичи используют только Hb*-компоненты design-system, не UI-киты напрямую.
$hits"
  fi

  if [[ "$rel" =~ ^features/[^/]+/api/ ]]; then
    hits="$(grep_lines 'import (androidx\.compose|org\.jetbrains\.compose|io\.aequicor\.heartbeat\.ds\.|io\.aequicor\.heartbeat\.core\.(network|database|datastore|ai)\.|io\.aequicor\.heartbeat\.feature\.[a-z0-9_]+\.impl|ru\.nsk\.kstatemachine\.|pro\.respawn\.flowmvi\.)')"
    [ -n "$hits" ] && add "api-модуль фичи: без UI/design-system/IO-модулей core, без impl, KStateMachine и FlowMVI (машина — machineSpec { }). См. .claude/rules/feature-api.md
$hits"
  fi

  if [[ "$rel" == core/* ]]; then
    hits="$(grep_lines 'import io\.aequicor\.heartbeat\.(feature|ds)\.')"
    [ -n "$hits" ] && add "core не зависит от features и design-system.
$hits"
  fi

  # 6. core:*:impl is wired only by platform-main:di-bundle; inside core/<x>/impl only its own impl package is allowed
  if [[ "$rel" != platform-main/di-bundle/* ]]; then
    own='^$'
    [[ "$rel" =~ ^core/([^/]+)/impl/ ]] && own="core\\.${BASH_REMATCH[1]//-/}\\.impl\\."
    hits="$(grep -nE 'import io\.aequicor\.heartbeat\.core\.[a-z0-9]+\.impl\.' "$file_path" | grep -vE "$own" \
            | head -n 5 | sed 's/^/    /')"
    [ -n "$hits" ] && add "core:*:impl видит только :platform-main:di-bundle. Используй контракты из api (ScopeFactory, ProfileSessions, DataStores, MachineLauncher, MachineRegistry…). См. docs/adr/0002-di-scopes.md, docs/adr/0004-state-machine.md
$hits"
  fi
fi

# ---------- Gradle scripts ----------
if [[ "$rel" == *.gradle.kts ]]; then
  hits="$(grep_lines '(implementation|api|ksp|compileOnly|testImplementation)\("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^"]+"\)')"
  [ -n "$hits" ] && add "Координаты зависимостей — только через gradle/libs.versions.toml (libs.*).
$hits"

  if [[ "$rel" =~ ^features/([^/]+)/impl/ ]]; then
    self="${BASH_REMATCH[1]}"
    hits="$(grep -nE '(projects\.features\.[A-Za-z0-9]+\.impl|":features:[a-z0-9-]+:impl")' "$file_path" \
            | grep -vE "(features:${self}:impl|features\.${self//-/}\.impl)" | head -n 5 | sed 's/^/    /')"
    [ -n "$hits" ] && add "impl-модуль фичи не может зависеть от impl другой фичи.
$hits"
  fi
  if [[ "$rel" == core/* ]]; then
    hits="$(grep_lines '(projects\.(features|designSystem)\.|":(features|design-system):)')"
    [ -n "$hits" ] && add "core-модуль не может зависеть от features/design-system.
$hits"
  fi
fi

if [ ${#violations[@]} -gt 0 ]; then
  {
    echo "Нарушены конвенции Heartbeat в $rel:"
    for v in "${violations[@]}"; do echo "- $v"; done
    echo "Исправь до продолжения (правила: CLAUDE.md, .claude/rules/)."
  } >&2
  exit 2
fi
exit 0
