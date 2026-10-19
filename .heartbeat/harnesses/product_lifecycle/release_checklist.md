Репозиторий шага: `{{repository}}`. Все операции GitHub выполняй явно в нём: для gh issue/pr/workflow/release добавляй `--repo {{repository}}`, для gh api — путь `repos/{{repository}}/...` или соответствующие owner/name в GraphQL. Это правило действует и для загруженных навыков. Перед локальной разработкой, verify и git log сверь origin рабочего каталога с этим репозиторием; при несовпадении подготовь правильный checkout.

Подготовь релиз Heartbeat. Запуск релиза — только решение и действие администратора; ничего не запускай сам.

1. Загрузи навык release_prep (harness_skill_load product_lifecycle/release_prep) и следуй ему.
2. Собери состав: фичи, прошедшие приёмку (доска и эпики со stage:acceptance), закрытые дефекты с прошлого тега (git log <последний тег>..HEAD --oneline).
3. Определи версию: последний стабильный тег vX.Y.Z и heartbeat.version из gradle.properties; предложи patch/minor/major с обоснованием.
4. Прогони verify на master.
5. Выведи в чат чек-лист релиза: состав изменений, уровень версии, риски, заметка об откате.
6. Дай администратору команды (не выполняй):
   - пробный прогон: gh workflow run release.yml --repo {{repository}} --ref master -f release_level=<patch|minor|major> -F dry_run=true
   - релиз: Actions → Release → Run workflow (ветка master, release_level) или gh workflow run release.yml --repo {{repository}} --ref master -f release_level=<patch|minor|major>
7. Напомни: после публикации релиза — перевести фичи в stage:released (stage_advance с доказательством-ссылкой на релиз) и проверить входящие дефекты новой версии.
