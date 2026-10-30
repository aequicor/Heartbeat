package io.aequicor.heartbeat.platform.nativelauncher

/**
 * Логирование лончера без зависимости от платформенных модулей: core:logging собран не для всех desktop-таргетов,
 * поэтому JVM делегирует в него, а native пишет в stderr. Уровни как в core:logging: значимые итоги — i, сбои — w.
 */
internal expect object LauncherLog {
    fun i(tag: String, message: () -> String)
    fun w(tag: String, error: Throwable, message: () -> String)
}

/**
 * Псевдоним с именем в паттерне логгеров правил detekt (`launcherLog.w(error)`):
 * регулярка набора `heartbeat` узнаёт получателей вида `chatLog`/`httpLogger`, но не заглавный `LauncherLog`.
 */
internal val launcherLog: LauncherLog get() = LauncherLog
