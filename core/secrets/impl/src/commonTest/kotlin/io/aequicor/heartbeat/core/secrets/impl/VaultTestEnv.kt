package io.aequicor.heartbeat.core.secrets.impl

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

internal class VaultTestEnv(test: TestScope) {
    val backend = MemoryVault()
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    private val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }
    var registry = VaultRegistry(backend, dispatchers)
    val scope = TestVaultScope(test)
    fun store(id: String = "alice", owner: TestVaultScope = scope) = ProfileSecretStore(
        registry,
        ProfileId(id),
        owner,
        UnusedDataStores(ProfileId(id)),
    )
    fun restart() {
        registry = VaultRegistry(backend, dispatchers)
    }
}

internal class MemoryVault : ProtectedVault {
    val disk = mutableMapOf<String, ByteArray>()
    var failWrite = false
    var beforeRead: () -> Unit = {}
    override fun <T> transaction(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
        beforeRead()
        val old = if (erase) null else disk[profile]?.copyOf()
        try {
            val update = action(old)
            try {
                if (update.hasChanges) {
                    check(!failWrite) { "plaintext from backend must not escape" }
                    if (update.bytes == null) disk.remove(profile) else disk[profile] = update.bytes.copyOf()
                }
                return update.result
            } finally {
                update.bytes?.fill(0)
            }
        } finally {
            old?.fill(0)
        }
    }
}

internal class TestVaultScope(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test"
    override val savedState: ScopeSavedState get() = error("unused")
    override var isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
}

internal fun Secret.text(): String = use { reveal { it.concatToString() } }

internal class UnusedDataStores(id: ProfileId) : DataStores {
    override val owner = StorageOwner.Profile(id)
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = error("unused")
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
    override suspend fun fire(event: DataEvent) = error("unused")
}
