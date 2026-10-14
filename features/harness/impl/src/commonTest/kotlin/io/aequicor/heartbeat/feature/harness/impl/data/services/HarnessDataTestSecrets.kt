package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.secrets.SecretUsage

internal class HarnessDataTestSecrets : SecretStore {
    val values = mutableMapOf<SecretKey, String>()
    var beforeWrite: suspend () -> Unit = {}
    var beforeRead: suspend () -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    override suspend fun write(key: SecretKey, value: Secret) {
        beforeWrite()
        values[key] = value.reveal { it.concatToString() }
    }
    override suspend fun read(key: SecretKey): Secret? {
        beforeRead()
        return values[key]?.let { Secret(it.toCharArray()) }
    }
    override suspend fun keys(): List<SecretKey> = values.keys.toList()
    override suspend fun remove(key: SecretKey): SecretRemoval {
        beforeRemove()
        return if (values.remove(key) != null) SecretRemoval.Removed else SecretRemoval.Missing
    }
    override suspend fun bind(usage: SecretUsage, key: SecretKey?) = error("unused")
    override suspend fun readFor(usage: SecretUsage): Secret? = error("unused")
    override suspend fun usages(key: SecretKey): List<SecretUsage> = emptyList()
}

internal fun harnessTestData() = ProtectedHarnessData(HarnessDataTestSecrets())
