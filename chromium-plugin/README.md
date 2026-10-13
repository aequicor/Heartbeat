# Heartbeat для Chromium-браузеров

Расширение (Manifest V3) для Google Chrome, Microsoft Edge, Brave, Chromium и Vivaldi — тонкая прослойка между
браузером и desktop-приложением Heartbeat:

- **чат Heartbeat в боковой панели браузера** — список сессий и композер как в студии: разделы «Закреплённые»,
  «Проекты» и «Недавние», поиск, архив, переименование, закрепление и прочитанность; выбор модели, режима
  размышления и подтверждений; потоковые ответы, «Стоп», ответы на запросы разрешений, вложение страницы и выделения;
- **управление браузером агентами Heartbeat** — инструменты `ex_control_*`: агент работает в своих вкладках
  (группа «Heartbeat») и во вкладках пользователя только после явного разрешения.

Вся логика живёт в приложении (`features/chromium-bridge`, `features/browser`), расширение только передаёт
сообщения и исполняет команды браузера.

## Установка (разработка)

1. Запустите Heartbeat на Windows или macOS и включите тогл `chromium_bridge.enabled`
   (для чатов профиля нужен также `ai_studio.engine_runtime`).
2. Heartbeat → «Настройки» → «Браузер Chrome» → «Зарегистрировать» для нужного браузера. Регистрация пишет
   манифест хоста Native Messaging `io.aequicor.heartbeat` (macOS — каталог `NativeMessagingHosts` браузера,
   Windows — ключ реестра `HKCU\Software\<vendor>\NativeMessagingHosts`).
3. Откройте `chrome://extensions` (`edge://extensions`, `brave://extensions`), включите режим разработчика,
   «Загрузить распакованное расширение» и выберите эту папку.
4. Идентификатор расширения должен быть `dngnkjkjppehmiaebojfebjcfkcpdjki` — его закрепляет публичный ключ
   `key` в `manifest.json`, и только его допускает манифест хоста.

Приватный ключ в репозиторий не входит (`*.pem` игнорируется): для распакованного расширения он не нужен.

## Как это устроено

```
service worker ──connectNative──▶ хост (лаунчер Heartbeat в режиме --heartbeat-chromium-host)
                                    │ те же кадры: 4 байта длины (LE) + UTF-8 JSON
                                    ▼
                         Unix domain socket приложения ──▶ features:chromium-bridge
```

- Хост запускает Chrome; хост сообщает `{"kind":"host","state":"waiting"|"connected"}` — запущен ли Heartbeat.
- После `connected` расширение отправляет `hello` (версия протокола, версия расширения, браузер, возможности);
  до успешного `hello` приложение отвечает на всё `not_ready`.
- Сообщения: `req` / `res` / `evt` в обе стороны; кадры приложения больше 1 MB приходят частями `chunk`
  (base64 срезов UTF-8 байтов) и собираются `src/shared/chunks.js`.
- Пока порт открыт, service worker не засыпает; при закрытии порт переоткрывается с паузой (1 с → 1 мин),
  а периодический `chrome.alarms` будит worker для следующей попытки.

| Файл | Назначение |
|---|---|
| `src/shared/protocol.js` | Версия протокола, имя хоста, коды ошибок, `BridgeError` |
| `src/shared/chunks.js` | Сборка `chunk`-кадров |
| `src/background/native-link.js` | Порт Native Messaging: статусы, `hello`, запросы и ответы, переподключение |
| `src/background/service-worker.js` | Сборка расширения |
| `src/background/panel-hub.js` | Порт панели: подписки и белый список методов, которые панель вызывает в приложении |
| `src/sidepanel/store.js` | Состояние панели: разделы списка (`sidebarSections`) и композер (`composerView`) по правилам студии |
| `src/sidepanel/views/chat-list.js`, `views/composer.js` | Список сессий и элементы композера |
| `src/sidepanel/sidepanel.css`, `icons.js` | Токены desktop-приложения (шрифт, цвета светлой и тёмной темы, размеры) и иконки `HbIcons` |

Вид панели повторяет приложение: значения `sidepanel.css` взяты из токенов `design-system` (desktop-пресеты
`HbTypography`, `HbMetrics`, `HbSurfaceColors`), иконки — те же контуры `HbIcons.kt`; `tests/icons.test.js`
сверяет иконки с исходником и не даёт шрифту стать мельче 12px. Меняя токены или иконки приложения, обновите и панель.

## Проверка

```bash
node --test chromium-plugin/tests/
```

Тесты — встроенный раннер Node (≥ 22.12), без npm-зависимостей; API `chrome.*` подменяются фейками
из `tests/fakes/chrome.js`. Код проверяется в редакторе через `// @ts-check` и `jsconfig.json`.
