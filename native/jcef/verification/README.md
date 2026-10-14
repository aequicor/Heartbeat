# Проверка нативного жизненного цикла JCEF

Standalone harness для согласованных Java/native-артефактов Heartbeat. Нужны JDK 21,
Python 3.11+, Windows, macOS или Linux с доступной графической сессией и бинарники **архитектуры
самого хоста**. Gradle, сеть для загрузки зависимостей и запущенный Heartbeat не нужны.
Проверка открывает настоящие JFrame с windowed JCEF; headless-режим не подменяет результат.

## Запуск после сборки

Из корня репозитория, заменив `<target>` на `windows-amd64`, `windows-arm64`,
`macosx-amd64`, `macosx-arm64` или `linux-amd64` (Linux — начиная с hb4):

```text
python native/jcef/verification/run_probe.py --jcef-api build/jcef-artifacts/jcef-api-152.0.6-hb3.jar --native-dir build/jcef-source/heartbeat-native-stage --extra-classpath build/jcef-artifacts/jcef-natives-<target>-152.0.6-hb3.jar --extra-classpath build/jcef-source/third_party/jogamp/jar/jogl-all.jar --extra-classpath build/jcef-source/third_party/jogamp/jar/gluegen-rt.jar --output build/jcef-verification
```

`--java` и `--javac` позволяют выбрать абсолютные пути к JDK. `--output` должен быть
**новым каталогом**: существующий путь намеренно не переиспользуется и не очищается.
Повторный запуск получает другое имя. Флаг `--compile-only` проверяет контракт Java,
не загружая нативные библиотеки; такой результат явно имеет `nativeExecuted: false`.

Ожидаемый native-stage:

- Windows: `jcef.dll`, `libcef.dll`, `chrome_elf.dll`, `jcef_helper.exe`, ресурсы CEF и `locales/`.
- macOS: `libjcef.dylib`, `Chromium Embedded Framework.framework`, `jcef Helper.app`
  и остальные helper bundles. macOS bridge-классы должны быть в native JAR из команды выше.
- Linux: `libjcef.so`, `libcef.so`, исполняемый `jcef_helper`, ресурсы CEF и `locales/`.
  Нужны X11 или XWayland; в CI команду оборачивает `xvfb-run --auto-servernum`. Проба не заменяет
  ресурсы системного JDK и использует helper/data paths только переданного bundle.

Native loader привязан к переданному каталогу, включая subprocess/framework paths.
Harness не использует другую JCEF-копию из JBR, не скачивает runtime и не подменяет
пропатченный API исходным. Как и приложение, он передаёт `--no-first-run`: standalone-диалог
первого запуска Chrome не должен блокировать `CefInitialize` встроенного браузера на новом
Linux-профиле. Linux использует `--ozone-platform=x11`, включая сессии Wayland через XWayland.
Компиляция требует:

```java
CefRequestContext.createContext(CefRequestContextSettings settings, CefRequestContextHandler handler);
CefRequestContext.createContext(CefRequestContext anchor, CefRequestContextHandler handler);
anchor.dispose(CefCompletionCallback completion);
CefURLRequest.create(CefRequest request, CefURLRequestClient client, CefRequestContext context);
```

Также нужны lifecycle callbacks `CefLifeSpanHandler.onAfterClose` и `onCreationFailed`.

## Что проверяется

Python-процесс один раз запускает HTTP server на `127.0.0.1`. Все дочерние JVM используют
один origin и один порт до конца проверки. `--port 0` выбирает свободный порт один раз;
можно передать фиксированный порт явно. Сервер выдаёт только локальный fixture и
синтетические cookies. Внешние сайты и профили пользователя не используются.

Последовательно запускаются отдельные JVM:

1. **write:** создать два профильных anchor; записать persistent cookie, session cookie
   (оба HttpOnly), localStorage, IndexedDB и CacheStorage. Вторая вкладка того же anchor
   видит данные; другой профиль не видит их. Закрытие первой вкладки не нарушает вторую
   или новую вкладку. Закрытие одного профиля не нарушает другой. Нативный `CefURLRequest`
   с общим контекстом отправляет обе HttpOnly cookies, а профиль B — ни одной; отмена
   ответа после первого chunk завершается `UR_CANCELED`. Запрос и его shared context
   освобождаются до закрытия anchor.
2. **restart:** свежая JVM читает все пять видов данных обоих профилей.
3. **clear:** закрыть вкладку A и дождаться `anchor.dispose(callback)`. Пока CefApp и
   профиль B остаются живыми, parent удаляет только каталог браузера A. Повторное
   открытие A должно быть пустым; B остаётся прежним. A получает новый marker.
4. **restart-after-clear:** свежая JVM проверяет новый marker A и прежний B.
5. **delete:** аналогично закрыть A, затем удалить его Chromium-каталог и отдельный каталог
   профильной метаинформации. Повторное открытие A пустое, живой профиль B не затронут.
6. **restart-after-delete:** свежая JVM подтверждает пустой A и сохранность B.
7. **visibility-close:** 20 новых вкладок закрываются с ещё не обработанными изменениями
   видимости и размеров; проверяются native cleanup и неизменность соседней вкладки.

На Linux смена родительского X11-окна выполняется в очереди CEF, а EDT ждёт ограниченное
время. Таймаут не должен уничтожать lock и wait condition: `SetParentSync` может начаться
позже. Патч удерживает общее состояние ожидания до выхода и вызывающего EDT, и CEF callback;
visibility-close проверяет жизненный цикл при накопленных изменениях видимости и размеров.

Перед нативными фазами запускается Java-only `DevToolsClientProbe`: десять детерминированных
порядков CDP callbacks проверяют ранний результат до ID, ошибки, закрытие и отмену отдельного
вызова, независимость соседнего вызова, очистку pending entries и отсутствие JNI-вызова под lock.
Нужен JDK без встроенного JCEF module (например Microsoft или Temurin), иначе его пакет
`org.cef.browser` перекроет тестовый класс и проверяемую API-библиотеку.

Первой нативной фазой выполняется **close-remove**: три пары вкладок используют разные
`CefClient`, shared wrappers одного persistent context и общее окно. Первая вкладка заранее
отсоединяется от Swing, сохраняя DOM; вторая остаётся активной. Один EDT-вызов отправляет
`close(true)` обеим вкладкам и сразу выполняет `removeAll()` контейнера активной вкладки,
без ожидания CEF callbacks между операциями. Обязательны оба `onAfterClose`, очистка обоих
client registry, отсутствие оставшихся клиентов, flush/release anchor и `CEF TERMINATED`.
Этот порядок воспроизводит потерю close callback при удалении native peer уже закрывающейся
вкладки. Для отдельной диагностики доступен `--close-remove-only`.

Следующая фаза **anchor-close**: 20 отдельных persistent contexts проходят
создание вкладки, запись данных, закрытие вкладки и flush/release контекста при живой контрольной
вкладке. Во время закрытия отдельный поток читает идентификатор браузера: уже начатый JNI-вызов
должен удерживать native wrapper до его освобождения. Поток прекращает чтение по событию закрытия
и завершается до выхода из проверки. Для отдельной диагностики этой фазы добавьте
`--anchor-close-only`; результат помечается
`focusedPhase`, он не заменяет полный прогон хранения, снимков и ввода. Focused-флаги
взаимоисключающие. `fullSuitePassed: true` появляется только после всех 13 обязательных
native-фаз, включая прежние проверки хранения и четыре масштаба visual; compile-only,
focused, сокращённые наборы масштабов и неуспешные прогоны имеют `fullSuitePassed: false`.

После каждой JVM проверяется код выхода; Java завершается успешно только после callback
`CefAppState.TERMINATED`. Вкладки ждут `onLoadEnd`/`onAfterClose`, сохранение — callback
освобождения anchor, DOM-операции — CDP futures и browser events. `Thread.sleep`, задержек
для «успевания записи», повторов удаления до успеха и автоматического пропуска ошибок нет.
Таймаут этапа по умолчанию 240 секунд завершает всё дерево процесса и проваливает прогон.
Перед завершением зависшей JVM на POSIX harness отправляет `SIGQUIT`, чтобы сохранить
Java thread dump в логе этапа; на вывод диагностики отводятся ещё три секунды.

На macOS callback `onBeforeClose` идёт с AppKit thread, а Swing — с EDT. Harness освобождает
клиент по patched `onAfterClose`, после освобождения wrapper и удаления браузера из registry JCEF.
Размер registry читается рефлексией только для проверки контракта callback, без polling и мутаций.
Терминальный `onCreationFailed` обязан освободить wrapper до callback. Диагностика выводит остаток
клиентов перед shutdown и полный stack trace необработанных callback exceptions.
Повторное нативное создание одного Java browser и отсутствие `TERMINATED` проваливают прогон.

Удаление выполняется только внутри свежего `--output/profiles`; запрос требует случайный
run token. Выход за каталог, symlink/junction и удаление корня profiles отвергаются.
Невозможность удалить файлы после callback считается ошибкой lifecycle, а не ретраится.

В Chrome CEF 152 `cache_path` должен быть самим `root_cache_path` или его **непосредственным**
ребёнком. Harness использует `profiles/profile-a` и `profiles/profile-b`; профильная
метаинформация хранится отдельно в `profiles/profile-a-owner`. Вложенный
`profiles/profile-a/browser` Chromium отвергает с переходом в OffTheRecord и не проверяет
дисковую persistence.

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

Локальный Linux candidate с viewport patch `2e77c988f9f9190c949ca16ffadc7783f6b32abe747b36e359beb2ab18b0a19d`
прошёл прежние 12 фаз harness (до добавления close-remove) и отдельную X11-диагностику:
физическое окно и DOM остаются `400×600`,
bounding shape меняется с `(20,180,340,300)` на `(40,200,280,240)`, DOM сохраняется. Настоящий XTest-клик
передаёт координаты документа, клик внутри JFrame вне clip не попадает в Chromium; закрытие, flush anchor
и завершение CEF проходят. Это локальное свидетельство для candidate, не результат пятихостового release gate.

## Производственное Compose-встраивание

После подготовки runtime отдельная opt-in JVM-проверка использует настоящий
`BrowserWebView` → `SwingPanel` → `DesktopBrowserPanel` → JCEF в `ComposeWindow`:

```text
./gradlew :features:browser:impl:browserComposeNativeTest
```

На Windows используется `gradlew.bat`. Проверка запускается только локально и только этой задачей:
обычный `jvmTest` её пропускает, а задача выполняет один тестовый класс в свежей JVM на JBR
приложения (глобальный CefApp не перезапускается в той же JVM). Так же устроены
`:features:browser:impl:browserNativeTest` (поверхности Chromium без Compose) и
`:platform-main:di-bundle:browserRecoveryNativeTest` (восстановление research-источника через граф профиля).
Нужна активная графическая сессия; Robot использует настоящие мышь и клавиатуру, на macOS
необходимо разрешение Accessibility для Java, а для снимков — Screen Recording.
Тест не заменяет ввод вызовами JavaScript.

Проверяются смена ширины боковой панели на всю область ComposeWindow, скрытие и возврат
той же нативной поверхности, неизменность DOM marker, число загрузок HTTP и созданий runtime.
Поверхность также переживает сужение окна до 320px и переключение light/dark темы хоста.
Затем настоящий ввод проходит через страницу → `HbDialog` → страницу; ввод в диалог не
должен попадать в Chromium. Клик по области Chromium за диалогом закрывает модальное окно,
не увеличивая счётчик кликов страницы. Возврат ввода ждёт фактического скрытия owned-окна,
а не только удаления Compose-контента. Обычный `HbMenu` проверяется поверх Chromium:
видимый пиксель меню, выбор через Enter и неизменное поле страницы. Скриншоты сохраняются в
`features/browser/impl/build/native-ui/compose-native-*.png` (панель, узкое окно, темы и диалог).
Геометрией управляет тестовый контейнер: `StudioBrowserLayout`, переключение чатов и
перенос между физическими мониторами эта проверка не покрывает.

Production bootstrap и тестовые JVM используют `compose.layers.type=WINDOW`, чтобы
диалоги и popup отображались над тяжеловесным Chromium. На Windows x64 выбран OpenGL:
в Skiko 0.150.1 Direct3D синхронно перерисовывает поверхность из `SkiaLayer.reshape`,
что вызывает рекурсивную отрисовку при изменении границ WINDOW-слоя. OpenGL при отказе
драйвера переходит только на программный рендеринг; Windows ARM64 сразу использует
`SOFTWARE_FAST`, поскольку Skiko не поддерживает там OpenGL. macOS сохраняет Metal.
Явный внешний `SKIKO_RENDER_API` имеет приоритет над настройкой приложения.
Desktop `HbDialog` рисует scrim внутри полноразмерного owned-слоя: обычный scrim Compose
остаётся под тяжеловесным Chromium, а маленькое окно диалога не перехватывает клики за
своими границами. Проверка сравнивает пиксели центра диалога и внешней области scrim со
страницей без диалога и сохраняет снимок; одного успешного ввода недостаточно.
Размер тестового окна ограничен рабочей областью монитора. Перед снимком и физическим
вводом проверяется принадлежность foreground тестовому окну или его owned-окну; Windows
дополнительно проверяет native HWND, видимость и отсутствие virtual-desktop cloaking.

## Результаты

Отдельный JVM-процесс `visibility-close` создаёт 20 новых вкладок. Каждая отправляет
пачку изменений видимости непосредственно перед принудительным закрытием и после его
запроса, не дожидаясь очереди CEF/AppKit. Проверяются завершение native close, отсутствие
клиентов после закрытия и неизменность DOM соседней вкладки. Эта фаза воспроизводит окно
гонки queued visibility с уничтожением NSView на macOS; не использует sleep или ретраи.
В каждой вкладке 128 вызовов resize/paint до и после запроса close проходят через настоящий
`CefBrowserWr.paint → doUpdate → UpdateUI` и его отложенный Swing timer. Рисование направлено
в `BufferedImage`, не захватывает экран и проверяет безопасное завершение очереди обновлений
native view; количество вызовов записывается в JSON каждой итерации.

Успех полного набора: exit `0`, `summary.json` с `passed: true`, `nativeExecuted: true`,
`fullSuitePassed: true`, все 13 phase reports
успешны. Любая ошибка API, startup, storage, shutdown, очистки или координат даёт ненулевой exit.
Отчёт содержит SHA-256 проверенных API/native библиотек. Это provenance результата;
проверка ожидаемых release checksums остаётся задачей packaging pipeline.

`jcef-runtime.yml` запускает harness **после упаковки** на пяти нативных хостах: Windows и macOS
двух архитектур и Linux x64. `*.json`, `*.log`, `*.png` из output сохраняются в артефактах
`jcef-probe-<target>`, в том числе при провале. Полный локальный прогон на целевом хосте также
поддерживается; кросс-компиляция и эмуляция полезны для диагностики, но не заменяют
проверку на целевой архитектуре перед публикацией.
Fatal JVM/native crashes сохраняют полный отчёт `hs_err_pid*.log` в том же output.

Проверки самого harness без JCEF:

```text
python -m unittest discover -s native/jcef/verification -p test_runner.py -v
javac --release 21 -encoding UTF-8 -d build/probe-json native/jcef/verification/ProbeJson.java native/jcef/verification/ProbeJsonTest.java
java -cp build/probe-json ProbeJsonTest
```

Unit-проверки, синтаксис fixture JavaScript и компиляция SessionProbe против пропатченного
Java API не заменяют нативный прогон на всех пяти хостах; сама компиляция не считается
проверкой persistence.
