# Проверка нативного жизненного цикла JCEF

Standalone harness для согласованных Java/native-артефактов Heartbeat. Нужны JDK 21,
Python 3.11+, Windows или macOS с доступной графической сессией и бинарники **архитектуры
самого runner**. Gradle, сеть для загрузки зависимостей и запущенный Heartbeat не нужны.
Проверка открывает настоящие JFrame с windowed JCEF; headless-режим не подменяет результат.

## Запуск после сборки

Из корня репозитория, заменив `<target>` на `windows-amd64`, `windows-arm64`,
`macosx-amd64` или `macosx-arm64`:

```text
python native/jcef/verification/run_probe.py --jcef-api build/jcef-artifacts/jcef-api-152.0.6-hb1.jar --native-dir build/jcef-source/heartbeat-native-stage --extra-classpath build/jcef-artifacts/jcef-natives-<target>-152.0.6-hb1.jar --extra-classpath build/jcef-source/third_party/jogamp/jar/jogl-all.jar --extra-classpath build/jcef-source/third_party/jogamp/jar/gluegen-rt.jar --output build/jcef-verification
```

`--java` и `--javac` позволяют выбрать абсолютные пути к JDK. `--output` должен быть
**новым каталогом**: существующий путь намеренно не переиспользуется и не очищается.
Повторный запуск получает другое имя. Флаг `--compile-only` проверяет контракт Java,
не загружая нативные библиотеки; такой результат явно имеет `nativeExecuted: false`.

Ожидаемый native-stage:

- Windows: `jcef.dll`, `libcef.dll`, `chrome_elf.dll`, `jcef_helper.exe`, ресурсы CEF и `locales/`.
- macOS: `libjcef.dylib`, `Chromium Embedded Framework.framework`, `jcef Helper.app`
  и остальные helper bundles. macOS bridge-классы должны быть в native JAR из команды выше.

Native loader привязан к переданному каталогу, включая subprocess/framework paths.
Harness не использует другую JCEF-копию из JBR, не скачивает runtime и не подменяет
пропатченный API исходным. Компиляция требует:

```java
CefRequestContext.createContext(CefRequestContextSettings settings, CefRequestContextHandler handler);
CefRequestContext.createContext(CefRequestContext anchor, CefRequestContextHandler handler);
anchor.dispose(CefCompletionCallback completion);
```

## Что проверяется

Python-процесс один раз запускает HTTP server на `127.0.0.1`. Все дочерние JVM используют
один origin и один порт до конца проверки. `--port 0` выбирает свободный порт один раз;
можно передать фиксированный порт явно. Сервер выдаёт только локальный fixture и
синтетические cookies. Внешние сайты и профили пользователя не используются.

Последовательно запускаются отдельные JVM:

1. **write:** создать два профильных anchor; записать persistent cookie, session cookie
   (оба HttpOnly), localStorage, IndexedDB и CacheStorage. Вторая вкладка того же anchor
   видит данные; другой профиль не видит их. Закрытие первой вкладки не нарушает вторую
   или новую вкладку. Закрытие одного профиля не нарушает другой.
2. **restart:** свежая JVM читает все пять видов данных обоих профилей.
3. **clear:** закрыть вкладку A и дождаться `anchor.dispose(callback)`. Пока CefApp и
   профиль B остаются живыми, parent удаляет только каталог браузера A. Повторное
   открытие A должно быть пустым; B остаётся прежним. A получает новый marker.
4. **restart-after-clear:** свежая JVM проверяет новый marker A и прежний B.
5. **delete:** аналогично закрыть A, затем удалить весь его каталог вместе с профильной
   метаинформацией. Повторное открытие A пустое, живой профиль B не затронут.
6. **restart-after-delete:** свежая JVM подтверждает пустой A и сохранность B.

После каждой JVM проверяется код выхода; Java завершается успешно только после callback
`CefAppState.TERMINATED`. Вкладки ждут `onLoadEnd`/`onBeforeClose`, сохранение — callback
освобождения anchor, DOM-операции — CDP futures и browser events. `Thread.sleep`, задержек
для «успевания записи», повторов удаления до успеха и автоматического пропуска ошибок нет.
Таймаут этапа по умолчанию 240 секунд завершает всё дерево процесса и проваливает прогон.

Удаление выполняется только внутри свежего `--output/profiles`; запрос требует случайный
run token. Выход за каталог, symlink/junction и удаление корня profiles отвергаются.
Невозможность удалить файлы после callback считается ошибкой lifecycle, а не ретраится.

## Снимки и координаты

По умолчанию дополнительные JVM `visual-native`, `visual-1`, `visual-1.5`, `visual-2`
проверяют PNG через CDP, размер `viewport × devicePixelRatio`, цвет контрольного пикселя
по координатам DOM и настоящий trusted click через `Input.dispatchMouseEvent` в CSS pixels.
Отпускание кнопки мыши выполняется в `finally`. Сохраняются PNG, геометрия и actual AWT scale.

`native` использует текущий DPI графической сессии. Остальные значения — **принудительный
Chromium device scale factor**, не доказательство наличия физических мониторов с такими DPI.
Harness проверяет, что Chromium действительно применил указанный factor. Для проверки
реальных дисплеев запустить на нужном дисплее с `--scales native` и сравнить отчёты.
Проверка JFrame не заменяет проверки Compose/Swing overlay, диалогов, клавиатурного фокуса,
смены физического монитора и полноэкранного режима в самом Heartbeat.

## Результаты и CI

Успех: exit `0`, `summary.json` с `passed: true`, `nativeExecuted: true`, все phase reports
успешны. Любая ошибка API, startup, storage, shutdown, очистки или координат даёт ненулевой exit.
Отчёт содержит SHA-256 проверенных API/native библиотек. Это provenance результата;
проверка ожидаемых release checksums остаётся задачей packaging pipeline.

CI запускает harness **после упаковки** и сохраняет `*.json`, `*.log`, `*.png` из output
через always-upload. Сборка на кросс-компиляторе не заменяет запуск на целевой архитектуре.

Проверки самого harness без JCEF:

```text
python -m unittest discover -s native/jcef/verification -p test_runner.py -v
javac --release 21 -encoding UTF-8 -d build/probe-json native/jcef/verification/ProbeJson.java native/jcef/verification/ProbeJsonTest.java
java -cp build/probe-json ProbeJsonTest
```

На момент добавления harness прошли эти unit-проверки, синтаксис fixture JavaScript и
компиляция SessionProbe против пропатченного Java API. Нативный прогон остаётся отдельным
обязательным результатом CI; сама компиляция не считается проверкой persistence.
