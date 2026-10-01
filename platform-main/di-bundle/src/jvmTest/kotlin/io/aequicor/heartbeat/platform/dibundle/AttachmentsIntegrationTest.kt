package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsMachineKey
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsOutput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentsIntegrationTest {
    @Test
    fun `profile startup resolves durable files across reopening with isolation and flag disabled`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        try {
            val flags = (app as TestToggleAccessors).toggleControl
            assertTrue(AttachmentsEnabled in flags.registered)
            assertEquals(false, AttachmentsEnabled.default)
            assertNull(app.machines.find(AttachmentsMachineKey))
            val alice = app.profileSessions.open(ProfileId("attachment-alice"))
            val access = alice.graph as TestAttachmentAccessors
            val bytes = "original user material\nРусский текст".encodeToByteArray()
            flags.setOverride(AttachmentsEnabled, true)
            val machine = assertNotNull(app.machines.find(AttachmentsMachineKey))
            val file = withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    machine.state.first { it is AttachmentsState.Ready }
                    coroutineScope {
                        val result = async(start = CoroutineStart.UNDISPATCHED) {
                            machine.outputs.first { it is AttachmentsOutput.Imported && it.requestId == "import" }
                                as AttachmentsOutput.Imported
                        }
                        assertEquals(
                            SendResult.Accepted,
                            app.machines.send(
                                AttachmentsMachineKey,
                                AttachmentsIntent.Public.Import(
                                    "import",
                                    listOf(AttachmentInput.Bytes("notes.txt", "text/plain", bytes)),
                                    PromptInputSupport.TextDocuments,
                                ),
                            ),
                        )
                        result.await().attachments.single()
                    }
                }
            }
            assertContentEquals(bytes, assertNotNull(access.resolver.resolve(file.resource)).bytes)
            app.profileSessions.close()
            assertNull(app.machines.find(AttachmentsMachineKey))
            val bob = app.profileSessions.open(ProfileId("attachment-bob"))
            assertNull((bob.graph as TestAttachmentAccessors).catalog.get(file.id))
            app.profileSessions.close()
            flags.setOverride(AttachmentsEnabled, false)
            val restored = app.profileSessions.open(ProfileId("attachment-alice"))
            val reopened = restored.graph as TestAttachmentAccessors
            assertEquals(file, reopened.catalog.get(file.id))
            assertContentEquals(bytes, assertNotNull(reopened.resolver.resolve(file.resource)).bytes)
        } finally {
            (app.appScope as OwnedScope).close()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }
}

@ContributesTo(ProfileScope::class)
interface TestAttachmentAccessors {
    val catalog: AttachmentsCatalog
    val resolver: ResourceResolver
}
