# Четыре вкладки панели движка

Утверждённый Desktop-прототип: `ddd1ae48135a6ae9719249b87c4b60d47b38e938`, общий ряд Overview / Connections / Resources / Settings.

- `connections-*`: реальный public route в production DI/bundle graph, пустой изолированный профиль, без авторизации/модельных запросов.
- `resources-unsupported-*`: тот же граф, Koog не объявляет отдельный ресурсный инвентарь.
- `resources-<width>-<isDark>-<isEmpty>`: синтетический профильный inventory, 30 длинных строк либо пустой список; полный panel renderer.

`false` / `true` в имени обозначают светлую / тёмную тему; у supported resources последний bool — пустое состояние.
Реальный embedded child проверен на 1280×800 и 420×240 с прокруткой до действия; inventory — 1280/420×600.
Генераторы: `EnginePanelEmbeddedUiTest` (di-bundle) и `EngineBrowserUiTest` (control-panel:impl).
UI-reviewer визуально проверил новые вкладки в обеих темах; это не live-регресс движков и не согласование мобильного прототипа.

Состояние подключений принадлежит retained child и не пересоздаётся при переключении tabs; fixed engine не меняется при исчезновении из каталога.
Первая async availability observation открывает уже выбранную вкладку, false toggle не создаёт child. Stale tab intent не обходит открытый page/project draft.
