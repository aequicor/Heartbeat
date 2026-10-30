package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import okio.Path

/**
 * Платформенные примитивы provisioning: файловая блокировка против параллельного provisioning нескольких
 * экземпляров лончера и проверка свободного места. JVM-реализация — java.nio; нативные — вместе с лончером.
 */
internal expect object ProvisioningPlatform {
    /** Свободные байты на файловой системе, содержащей [path]. */
    fun freeSpaceBytes(path: Path): Long

    /** Restores only ordinary read/write and executable bits; privileged ZIP mode bits are never applied. */
    fun applyFileMode(path: Path, unixMode: Int)

    /**
     * Берёт эксклюзивную блокировку файла [path] без ожидания или возвращает null, если её держит другой процесс.
     * Блокировка освобождается ОС при смерти владельца: зависший provisioning не блокирует следующие запуски.
     */
    fun tryLock(path: Path): ProvisionLockHandle?
}

/** Держит блокировку до [release]; повторный release безопасен. */
interface ProvisionLockHandle {
    fun release()
}
