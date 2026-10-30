package io.aequicor.heartbeat.platform.nativelauncher.bootstrap

import kotlinx.serialization.Serializable

/** Общие поля артефакта дистрибутива: адрес, размер и SHA-256 содержимого. */
interface BootstrapArtifactInfo {
    val url: String
    val size: Long
    val sha256: String
}

/** Артефакт platform в bootstrap-манифесте. */
@Serializable
data class BootstrapArtifact(override val url: String, override val size: Long, override val sha256: String) :
    BootstrapArtifactInfo

/** Точный JBR runtime сборки релиза; `id` однозначно определяет содержимое (ОС, архитектура, сборка JBR). */
@Serializable
data class BootstrapRuntime(
    val id: String,
    override val url: String,
    override val size: Long,
    override val sha256: String,
) : BootstrapArtifactInfo

/** Train seed: тот же состав, что в полном установщике (манифест поезда и бандлы). */
@Serializable
data class BootstrapSeed(
    val trainId: String,
    override val url: String,
    override val size: Long,
    override val sha256: String,
) : BootstrapArtifactInfo

/**
 * Bootstrap-манифест тонкого установщика: схема v1. Подписывается отдельным release-ключом (не ключом слотов)
 * как detached Ed25519-подпись ровно от байтов этого JSON; публичный ключ встроен в нативный лончер.
 * Поля неизвестных версий игнорируются, чтобы старшие лончеры читали манифест той же схемы.
 */
@Serializable
data class BootstrapManifest(
    val schema: Int = SCHEMA,
    val version: String,
    val channel: String,
    val os: String,
    val arch: String,
    val minNativeLauncherVersion: Int = 1,
    val runtime: BootstrapRuntime,
    val platform: BootstrapArtifact,
    val seed: BootstrapSeed,
) {
    companion object {
        const val SCHEMA = 1
    }
}

/** Bootstrap protocol compatibility level of this native launcher. */
internal const val NATIVE_LAUNCHER_VERSION = 1
