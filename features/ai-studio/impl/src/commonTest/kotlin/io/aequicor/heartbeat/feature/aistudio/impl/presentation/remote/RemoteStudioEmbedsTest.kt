package io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote

import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import io.aequicor.heartbeat.feature.checklist.api.ChecklistRemoteScreens
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireRemoteScreen
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireRemoteScreens
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireViewState
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionKindUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RemoteStudioEmbedsTest {
    @Test
    fun `late projections and intents of replaced handles do not affect the new child`() = runTest {
        val owner = Owner(
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job)),
        )
        val ids = MutableStateFlow(setOf("source"))
        val opened = mutableListOf<Child>()
        val embeds = RemoteStudioEmbeds(
            object : StudioEntries {
                override val questionSources = ids
                override val showsConnections = flowOf(false)
                override val showsProfileSettings = flowOf(false)
            },
            StudioScreenHost.Remote,
            QuestionnaireRemoteScreens { Child().also { opened += it } },
            ChecklistRemoteScreens { error("No checklist requested") },
            owner,
        )
        runCurrent()
        val original = checkNotNull(embeds.states.first().questions["source"])
        val first = opened.single()
        first.next = snapshot(2, "accepted")
        embeds.dispatch(input(original.instance))
        first.states.value = snapshot(1, "old")
        runCurrent()
        assertEquals("accepted", embeds.states.first().questions["source"]?.screen?.questions?.single()?.text)

        first.gate = CompletableDeferred()
        val pending = async { embeds.dispatch(input(original.instance)) }
        runCurrent()
        ids.value = emptySet()
        runCurrent()
        assertFalse(first.isClosed, "its own answer may remove a question before reduction returns")
        ids.value = setOf("source")
        runCurrent()
        val replacement = checkNotNull(embeds.states.first().questions["source"])
        assertNotEquals(original.instance, replacement.instance)
        first.gate?.complete(Unit)
        pending.await()
        assertTrue(first.isClosed)
        embeds.dispatch(input(original.instance))
        assertEquals(0, opened.last().sent)
        assertEquals(replacement, embeds.states.first().questions["source"])
        val last = opened.last()
        last.gate = CompletableDeferred()
        val retiring = async { embeds.dispatch(input(replacement.instance)) }
        runCurrent()
        ids.value = emptySet()
        runCurrent()
        assertFalse(last.isClosed)
        owner.close()
        assertTrue(opened.all { it.isClosed })
        retiring.cancel()
    }

    private fun input(instance: String) = AiStudioScreenIntent.QuestionInput(
        "source",
        instance,
        QuestionnaireScreenIntent.TextChanged("q", "answer"),
    )

    private class Child : QuestionnaireRemoteScreen {
        override val states = MutableStateFlow(snapshot(0, "initial"))
        var next = snapshot(1, "answer")
        var gate: CompletableDeferred<Unit>? = null
        var sent = 0
        var isClosed = false
        override suspend fun dispatch(intent: JsonObject): QuestionnaireViewState {
            sent++
            gate?.await()
            return next
        }
        override fun close() {
            isClosed = true
        }
    }

    private class Owner(override val coroutineScope: CoroutineScope) : OwnedScope {
        override val name = "test-embeds"
        override var isClosed = false
        private val closers = mutableListOf<() -> Unit>()
        override val savedState = object : ScopeSavedState {
            override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
            override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
            override fun unregister(key: String) = Unit
            override fun snapshot() = SavedBundle(emptyMap())
        }
        override fun onClose(action: () -> Unit): DisposableHandle {
            closers += action
            return DisposableHandle { closers -= action }
        }
        override fun close() {
            isClosed = true
            coroutineScope.cancel()
            closers.asReversed().forEach { it() }
        }
    }

    private companion object {
        fun snapshot(revision: Long, text: String): QuestionnaireViewState {
            val state = QuestionnaireScreenState(
                persistentListOf(
                    QuestionUi("q", "Question", null, QuestionKindUi.Text(null, false), true, false, text = text),
                ),
            )
            return QuestionnaireViewState(
                revision,
                StudioViewJson.encodeToJsonElement(QuestionnaireScreenState.serializer(), state).jsonObject,
            )
        }
    }
}
