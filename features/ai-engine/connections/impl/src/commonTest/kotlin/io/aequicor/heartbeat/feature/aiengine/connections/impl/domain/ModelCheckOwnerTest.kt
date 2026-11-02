package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.connections.api.BindingModels
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReceipt
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.SelectedModelGeneration
import io.aequicor.heartbeat.feature.aiengine.connections.impl.engineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCheckOwnerTest {
    private val target = checkReport().revision.target

    @Test
    fun `a new run and revocation synchronously hide previously stored receipts`() {
        val owner = ModelCheckOwner()
        val context = context()
        val previous = ModelCheckReceipt.from(checkReport())
        assertTrue(owner.accepts(previous, context))
        val run = owner.begin(setOf(target))
        assertFalse(owner.accepts(previous, context))
        val revision = assertNotNull(owner.revision(target, context))
        val current = ModelCheckReceipt.from(checkReport().copy(revision = revision, run = run))
        assertTrue(owner.isCurrent(run, revision, context))
        assertTrue(owner.accepts(current, context))
        owner.revoke(setOf(target))
        assertFalse(owner.isCurrent(run, revision, context))
        assertFalse(owner.accepts(current, context))
    }

    @Test
    fun `retrying another model does not revoke an unchanged model of the same binding`() {
        val owner = ModelCheckOwner()
        val other = target.copy(model = ModelId("other"))
        val context = context(setOf(target, other))
        val run = owner.begin(setOf(target, other))
        val a = assertNotNull(owner.revision(target, context))
        val b = assertNotNull(owner.revision(other, context))
        val newer = owner.begin(setOf(other))
        assertTrue(owner.isCurrent(run, a, context))
        assertFalse(owner.isCurrent(run, b, context))
        assertTrue(owner.isCurrent(newer, b, context))
    }

    @Test
    fun `binding revocation also hides receipts for targets this owner never started`() {
        val owner = ModelCheckOwner()
        val context = context()
        val receipt = ModelCheckReceipt.from(checkReport())
        owner.revoke(target.binding)
        assertFalse(owner.accepts(receipt, context))
        val run = owner.begin(setOf(target))
        val current = ModelCheckReceipt.from(checkReport().copy(run = run))
        assertTrue(owner.accepts(current, context))
    }

    @Test
    fun `unknown auth gets a fresh profile nonce and cannot restore a receipt nonce after reload`() {
        val unknown = CheckSource.copy(info = CheckSource.info.copy(revision = AuthRevision.Unknown))
        val context = context().copy(sources = listOf(unknown))
        val owner = ModelCheckOwner()
        val revision = assertNotNull(owner.revision(target, context))
        assertNotNull(revision.localAuth)
        assertEquals(revision, owner.revision(target, context))
        val receipt = ModelCheckReceipt.from(checkReport().copy(revision = revision))
        assertTrue(owner.accepts(receipt, context))
        val reopened = ModelCheckOwner()
        assertNotEquals(revision, reopened.revision(target, context))
        assertFalse(reopened.accepts(receipt, context))
        owner.revoke(target.binding)
        assertNotEquals(revision.localAuth, owner.revision(target, context)?.localAuth)
    }

    @Test
    fun `known auth and unchanged storage generations can restore a historical completion after reload`() {
        val receipt = ModelCheckReceipt.from(checkReport())
        assertTrue(ModelCheckOwner().accepts(receipt, context()))
        assertNull(ModelCheckOwner().revision(target, context())?.localAuth)
    }

    @Test
    fun `label and default only changes preserve full revision but endpoint and source revision do not`() {
        val owner = ModelCheckOwner()
        val context = context()
        val revision = assertNotNull(owner.revision(target, context))
        val renamed = CheckSource.copy(info = CheckSource.info.copy(label = "private-label"))
        assertEquals(revision, owner.revision(target, context.copy(sources = listOf(renamed))))
        assertEquals(revision, owner.revision(target, context.copy(selection = context.selection.withDefault(target))))
        val changed = listOf(
            CheckSource.copy(scope = CheckSource.scope.copy(origin = EndpointOrigin("https://other.example"))),
            CheckSource.copy(scope = CheckSource.scope.copy(basePath = "/v2")),
            CheckSource.copy(info = CheckSource.info.copy(revision = AuthRevision.Known("revision-2"))),
        )
        changed.forEach {
            assertNotEquals(revision, owner.revision(target, context.copy(sources = listOf(it))))
        }
    }

    @Test
    fun `disable and reenable requires the fresh stored selection generation`() {
        val owner = ModelCheckOwner()
        val context = context()
        val revision = assertNotNull(owner.revision(target, context))
        assertNull(owner.revision(target, context.copy(selection = context.selection.withModel(target, false))))
        val reenabled = context.selection.copy(
            generations = context.selection.generations.map {
                it.copy(value = "33333333-3333-3333-3333-333333333333")
            },
        )
        assertNotEquals(revision, owner.revision(target, context.copy(selection = reenabled)))
        assertFalse(owner.accepts(ModelCheckReceipt.from(checkReport()), context.copy(selection = reenabled)))
    }

    @Test
    fun `missing or ambiguous identity and unconfirmed selection metadata stay unknown`() {
        val owner = ModelCheckOwner()
        val context = context()
        val variants = listOf(
            context.copy(bindings = emptyList()),
            context.copy(bindings = context.bindings + context.bindings),
            context.copy(bindings = context.bindings.map { it.copy(isEnabled = false) }),
            context.copy(bindings = context.bindings.map { it.copy(engine = EngineId("different-engine")) }),
            context.copy(sources = emptyList()),
            context.copy(sources = context.sources + context.sources),
            context.copy(engines = emptyList()),
            context.copy(engines = context.engines + context.engines),
            context.copy(engines = context.engines.map { it.copy(availability = EngineAvailability.Unknown) }),
            context.copy(selection = context.selection.copy(generations = emptyList())),
            context.copy(
                selection = context.selection.copy(
                    generations = context.selection.generations.map {
                        it.copy(value = "invalid")
                    },
                ),
            ),
        )
        variants.forEach { assertNull(owner.revision(target, it)) }
    }

    @Test
    fun `revocation of another binding does not invalidate a current route`() {
        val owner = ModelCheckOwner()
        val context = context()
        val run = owner.begin(setOf(target))
        val revision = assertNotNull(owner.revision(target, context))
        owner.revoke(EngineBindingId("other-binding"))
        assertTrue(owner.isCurrent(run, revision, context))
    }

    @Test
    fun `oversized context is unavailable without throwing or accepting an old receipt`() {
        val owner = ModelCheckOwner()
        val oversized = CheckSource.copy(info = CheckSource.info.copy(revision = AuthRevision.Known("r".repeat(257))))
        assertNull(owner.revision(target, context().copy(sources = listOf(oversized))))
    }

    private fun context(targets: Set<EngineTarget> = setOf(target)): ModelCheckContext {
        val engine = engineInfo()
        val source: AuthSource = CheckSource
        return ModelCheckContext(
            ModelSelection(
                bindings = listOf(BindingModels(target.binding, targets.map { it.model }.toSet())),
                generations = targets.map {
                    SelectedModelGeneration(it.binding, it.model, checkReport().revision.selection.value)
                },
            ),
            listOf(engine.copy(descriptor = engine.descriptor.copy(id = target.engine))),
            listOf(EngineBinding(target.binding, target.engine, source.info.id)),
            listOf(source),
        )
    }
}
