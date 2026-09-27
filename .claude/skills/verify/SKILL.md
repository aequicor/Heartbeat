---
name: verify
description: "Проверка изменений Heartbeat перед завершением задачи — компиляция затронутых модулей, тесты commonTest (jvmTest), detekt (ktlint + compose-rules), проверка графа зависимостей. Используй после любых правок Kotlin/Gradle-кода и перед тем, как сказать «готово»."
---

# Verify

Цель — доказать, что изменение собирается, тесты зелёные и линт чистый. Отчитывайся фактическим выводом команд.

## 1. Определи затронутые модули

```bash
git status --porcelain
```

Путь файла → Gradle-путь: `features/chat/impl/src/...` → `:features:chat:impl`, `core/network/impl/...` → `:core:network:impl`.
Если изменён `api`-модуль фичи или `core` — затронуты и все зависящие (`Grep` по `projects.features.<name>.api` / `projects.core.<x>` в `**/build.gradle.kts`).

## 2. Компиляция и тесты (быстрый контур — JVM)

```bash
./gradlew :<module>:compileKotlinJvm :<module>:jvmTest --continue
```

- Android-специфичный код: `./gradlew :<module>:compileAndroidMain` (или `:<module>:testAndroidHostTest`).
- Приложения: `./gradlew :desktopApp:compileKotlin` / `:androidApp:assembleDebug` (после миграции — `:platform-main:*`).
- iOS-таргеты компилируются только на macOS: `./gradlew :<module>:compileKotlinIosSimulatorArm64`. На Windows — явно сообщи, что iOS не проверен.

## 3. Lint

```bash
./gradlew detekt --continue
```

- Автоисправимое (ktlint-wrapper): `./gradlew detekt --auto-correct`, затем повтори проверку.
- Правила с type resolution (в т.ч. `SuspendFunSwallowedCancellation`): `./gradlew :<module>:detektMainJvm` (KMP) / `:<module>:detektMain` (JVM).
- Правки в `lint/detekt-rules`: `./gradlew :lint:detekt-rules:test`, затем `./gradlew --stop` перед `detekt` (кэш classloader-а правил в демоне).
- Находки набора `heartbeat` (логирование/ошибки) чини по [logging-policy.md](../../../docs/ai/logging-policy.md#автоматическая-проверка-detekt), не подавляй.
- Остальное чини вручную. Baseline не пополняй без явного согласия пользователя.

## 4. Граф зависимостей

Для изменённых `build.gradle.kts` фич убедись в отсутствии `impl → impl`:

```bash
grep -rnE 'projects\.features\.[A-Za-z0-9]+\.impl' features/*/impl/build.gradle.kts
```

## 5. Политика коммитов

PR содержит фичу целиком, коммиты — логические, собираемые шаги с тестами изменённого поведения.
До создания коммита проверь подготовленные изменения, перед ревью — каждый коммит ветки:

```bash
python -m pip install -r scripts/requirements-commit-policy.txt
python scripts/check_commit_size.py --staged
python scripts/check_commit_size.py --base <base-ref> --head HEAD
```

Предел — **25 000 токенов полного diff на коммит**. Свыше 20 000 токенов или 20 файлов —
предупреждение; 700–1000 изменённых строк — ориентир, а не блокер. Общего лимита размера PR нет.
Методика и ограничения — [commit-policy.md](../../../docs/ai/commit-policy.md).
Не подменяй подсчёт токенов оценкой по строкам и не считай отсутствие зависимости успешной проверкой.

Для изменений только Python-инструмента, workflow и документации Gradle-проверки не нужны;
выполни `python -m unittest discover -s scripts/tests -v` и проверку размера изменений.

## 6. Отчёт

```
Модули: …
Компиляция: OK / FAIL (первая ошибка)
Тесты: N passed, M failed (имена упавших)
Detekt: OK / K issues (правила)
Коммиты: OK / FAIL (SHA, токены, файлы; предупреждения)
Не проверено: iOS (Windows) / …
```

При падении сборки, причина которой неочевидна, — делегируй субагенту `build-doctor`.
