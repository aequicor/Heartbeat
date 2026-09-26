package io.aequicor.heartbeat.core.datastore

private val STORAGE_NAME = Regex("[a-z][a-z0-9_]{0,63}")

/** Storage names become file names: only lowercase letters, digits and `_`, starting with a letter. */
internal fun requireStorageName(name: String) {
    require(STORAGE_NAME.matches(name)) { "invalid storage name '$name': expected ${STORAGE_NAME.pattern}" }
}
