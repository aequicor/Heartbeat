package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class StudioHelperPolicyTest {
    private val ref = SessionRef(EngineId("test"), SessionSourceId("local"), "parent")
    private val parent = StudioChatRecord(
        "parent",
        "Parent",
        Instant.DISTANT_PAST,
        ref = ref,
        configuration = StudioSessionSettings("model", approval = ApprovalMode.AutoApprove),
    )
    private val helper = StudioChatRecord(
        "helper",
        "Helper",
        Instant.DISTANT_PAST,
        helper = StudioHelperIdentity(ActionId("workflow"), parent.id, ref, TrustLevel.AutoEdits),
    )

    @Test
    fun `saved Full is capped and a stricter saved trust is never raised`() {
        assertEquals(TrustLevel.AutoEdits, trust(TrustLevel.Full))
        assertEquals(TrustLevel.Ask, trust(TrustLevel.Ask))
        assertEquals(TrustLevel.Ask, trust(null))
        assertEquals(TrustLevel.AutoEdits, trust(TrustLevel.AutoEdits))
    }

    @Test
    fun `current parent configuration overrides persisted approval for every turn`() = runTest {
        val stores = ChecklistTestStores()
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(parent, helper))
        val policy = StudioHelperPolicy(stores)
        val asking = StudioSessionConfiguration(parent.configuration!!.copy(approval = ApprovalMode.Ask))
        assertEquals(TrustLevel.Ask, policy.trust(helper, TrustLevel.Full) { mapOf(parent.id to asking) })
        assertEquals(TrustLevel.AutoEdits, policy.trust(helper, TrustLevel.Full) { emptyMap() })
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(helper))
        assertEquals(TrustLevel.Ask, policy.trust(helper, TrustLevel.Full) { emptyMap() })
    }

    @Test
    fun `trust changes during record IO are read after the suspension for helper and parent`() = runTest {
        for (changed in listOf(helper.id, parent.id)) {
            val backing = ChecklistTestStores()
            backing.keyValue(ChatSpec).set(ChatsKey, listOf(parent, helper))
            val reading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val stores = object : DataStores by backing {
                override fun keyValue(spec: KeyValueSpec): KeyValueStore {
                    val values = backing.keyValue(spec)
                    return object : KeyValueStore by values {
                        override suspend fun <T : Any> get(key: StoreKey<T>): T? {
                            reading.complete(Unit)
                            release.await()
                            return values.get(key)
                        }
                    }
                }
            }
            var configurations = emptyMap<String, StudioSessionConfiguration>()
            val policy = StudioHelperPolicy(stores)
            val result = async { policy.trust(helper, TrustLevel.Full) { configurations } }
            reading.await()
            configurations = mapOf(changed to StudioSessionConfiguration(StudioSessionSettings("model")))
            release.complete(Unit)
            runCurrent()
            assertEquals(TrustLevel.Ask, result.await())
        }
    }

    @Test
    fun `parentless missing unconfigured and ambiguous parents cannot grant extra trust`() {
        val parentless = helper.copy(helper = helper.helper!!.copy(parentChatId = null, parentSession = null))
        assertEquals(TrustLevel.Ask, constrainedHelperTrust(parentless, TrustLevel.Full, listOf(parent), emptyMap()))
        assertEquals(TrustLevel.Ask, constrainedHelperTrust(helper, TrustLevel.Full, emptyList(), emptyMap()))
        assertEquals(TrustLevel.Ask, trust(TrustLevel.Full, listOf(parent.copy(configuration = null))))
        assertEquals(TrustLevel.Ask, trust(TrustLevel.Full, listOf(parent, parent)))
    }

    @Test
    fun `nested helper respects ancestor cap and cyclic ancestry fails closed`() {
        val grandparent = parent.copy(id = "root")
        val limitedParent = parent.copy(
            helper = StudioHelperIdentity(ActionId("outer"), grandparent.id, ref, TrustLevel.Ask),
        )
        assertEquals(TrustLevel.Ask, trust(TrustLevel.Full, listOf(limitedParent, grandparent)))
        val cyclicParent = parent.copy(helper = helper.helper!!.copy(parentChatId = helper.id))
        assertEquals(TrustLevel.Ask, trust(TrustLevel.Full, listOf(cyclicParent, helper)))
    }

    @Test
    fun `ordinary chat retains its explicit or engine default trust`() = runTest {
        val policy = StudioHelperPolicy(ChecklistTestStores())
        assertNull(policy.trust(parent, null) { emptyMap() })
        assertEquals(TrustLevel.Full, policy.trust(parent, TrustLevel.Full) { emptyMap() })
    }

    private fun trust(requested: TrustLevel?, records: List<StudioChatRecord> = listOf(parent)): TrustLevel =
        constrainedHelperTrust(helper, requested, records, emptyMap())
}
