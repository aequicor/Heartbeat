# core:secrets:impl

Подключается только к `platform-main:di-bundle`. `SecretStore` — singleton ProfileScope;
`VaultRegistry` — AppScope. Все операции выполняются на инжектируемом IO dispatcher и логируются тегом SEC
без содержимого, идентификаторов и текстов исходных исключений.

Защищённый снимок профиля содержит версию схемы, значения и ссылки. Значение на один SecretKey одно.
Обновление снимка атомарно; проверка ссылок и удаление проходят в одной транзакции.

| Платформа | Хранение |
|---|---|
| Android | AES-256-GCM; ключ профиля в Android Keystore; ciphertext в noBackupFilesDir; AtomicFile |
| Windows | DPAPI текущего пользователя через JNA; SHA-256 профиля как entropy; атомарная замена файла в LOCALAPPDATA |
| macOS | Login Keychain через Security.framework/JNA, один generic-password item на профиль, системные ACL |
| iOS | Security.framework SecItem, один generic-password item на профиль |

Apple: service `io.aequicor.heartbeat.secrets.v1`, account — SHA-256 ProfileId,
На iOS — `AfterFirstUnlockThisDeviceOnly`; на macOS — политика блокировки/ACL Login Keychain. Синхронизация iCloud выключена. Имя service нельзя менять без миграции.
Desktop/Android используют блокировку отдельного файла, iOS — open(O_EXLOCK) в sandbox; lock-файл не содержит данных.
Одновременные операции внутри графа сериализует Mutex. На JVM пересечение lock между разными графами
может завершиться ошибкой блокировки; потери обновлений не допускаются. Linux намеренно отклоняется без plaintext fallback.

Закрытие профиля ничего не удаляет. Явный `StorageMaintenance.wipeProfile` вызывает
`SecretsProfileCleaner`; erase пропускает дешифрование и удаляет даже повреждённые данные.
Секреты участвуют в общем lifecycle gate через инжект профильного DataStores: во время wipe новый экземпляр недоступен.
Сбой очистки останавливает удаление обычных данных; повторный wipe безопасен.

`SecretsConfig` позволяет хосту задать отдельный desktop-каталог и Keychain service для тестов.
Нативные тесты используют временный каталог/уникальный service и искусственные значения.

Проверки: commonTest (контракт/изоляция/ротация/ссылки/ошибки), JVM нативный round-trip,
интеграционные тесты Metro и удаления профиля. Нативная компиляция проверяется Gradle; линковка и исполнение iOS, а также прогон macOS Keychain требуют macOS.
