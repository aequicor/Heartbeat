package io.aequicor.heartbeat.feature.researchchat.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatEnabled
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatIntent
import io.aequicor.heartbeat.feature.researchchat.api.ResearchQuestion
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceScope
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import io.aequicor.heartbeat.feature.researchchat.api.ResearchWorkspace
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachments
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchRepository
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchRun
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchStorage
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Accepted generations survive screen destruction, and every run reads an immutable source snapshot. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProfileResearchRepository(
    private val storage: ResearchStorage,
    private val facade: EngineFacade,
    private val search: SearchEngine,
    private val toggles: FeatureToggles,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val attachments: ResearchAttachments = ResearchAttachments.Legacy,
) : ResearchRepository {
    private val log = Log.tag("ResearchRepository")
    private val running = MutableStateFlow<Set<String>>(emptySet())
    private val executionLock = Mutex()
    private val recoveryLock = Mutex()
    private val handles = mutableMapOf<String, ActiveSession>()
    private val stops = mutableSetOf<String>()

    override fun observe() = combine(
        storage.observe(),
        running,
    ) { sessions, active ->
        log.d { "Observe research profile workspace" }
        ResearchWorkspace(sessions, active)
    }

    override suspend fun prepare(target: EngineTarget): ResearchWorkspace {
        log.i { "Prepare research workspace" }
        requireEnabled(target)
        migrateSources()
        recoverPending()
        if (storage.read().none { it.target == target }) createSession(target)
        return observe().first()
    }

    override suspend fun createSession(target: EngineTarget): ResearchSession {
        requireEnabled(target)
        log.i { "Create projectless research session" }
        val session = ResearchSession(newId(), "", target, listOf(ResearchQuestion(newId())))
        storage.add(session)
        return session
    }

    override suspend fun createQuestion(sessionId: String): String {
        requireEnabled(session(sessionId).target)
        log.i { "Create independent research question" }
        val question = ResearchQuestion(newId())
        storage.update(sessionId) { it.copy(questions = it.questions + question) }
        return question.id
    }

    override suspend fun addResource(
        sessionId: String,
        questionId: String,
        input: ResearchChatIntent.Public.AddResource,
    ) {
        requireEditable(sessionId, questionId)
        require(input.value.isNotBlank()) { "Empty resource" }
        log.i { "Import research source kind=${input.kind}" }
        val imported = importResource(input)
        val source = if (input.kind == ResearchResourceKind.Website) {
            imported
        } else {
            check(toggles.get(AttachmentsEnabled)) { "New attachments are disabled" }
            val support = inputSupport(session(sessionId).target)
            require(support.accepts(imported.mediaType)) { "Unsupported research source" }
            attachments.persist(imported, support, migration = false)
        }
        // The flag or running state may have changed while fetching the website.
        requireEditable(sessionId, questionId)
        storage.update(sessionId) { it.attach(questionId, source, input.scope) }
    }

    override suspend fun addAttachments(input: ResearchChatIntent.Public.AddAttachments) {
        requireEditable(input.sessionId, input.questionId)
        check(toggles.get(AttachmentsEnabled)) { "New attachments are disabled" }
        val support = inputSupport(session(input.sessionId).target)
        val files = input.attachments.map { attachments.registered(it.id) }
        require(files.size <= support.maxAttachments && files.sumOf { it.sizeBytes } <= support.maxTotalBytes) {
            "Research attachments exceed limits"
        }
        val sources = files.map { file ->
            require(support.accepts(file.mediaType) && file.sizeBytes <= support.maxFileBytes) {
                "Research source is not supported by this model"
            }
            ResearchResource(
                newId(),
                file.name,
                if (file.mediaType.startsWith("image/")) ResearchResourceKind.Image else ResearchResourceKind.Document,
                file.resource.id,
                file.mediaType,
                attachmentId = file.id,
                attachmentSizeBytes = file.sizeBytes,
            )
        }
        requireEditable(input.sessionId, input.questionId)
        log.i { "Register durable research sources count=${sources.size}" }
        storage.update(input.sessionId) { session ->
            sources.fold(session) { current, source -> current.attach(input.questionId, source, input.scope) }
        }
    }

    private suspend fun inputSupport(target: EngineTarget): PromptInputSupport =
        facade.models.observe(target.engine, target.binding).first { it.isLoaded }.models.firstOrNull {
            it.target == target
        }?.inputSupport ?: PromptInputSupport.TextDocuments

    /** Unique migration keys make reopening after a crash reuse the already committed file. */
    private suspend fun migrateSources() = recoveryLock.withLock {
        storage.read().forEach { session ->
            val migrated = session.resources.map { migrateSource(it) }
            if (migrated != session.resources) {
                storage.update(session.id) { stored ->
                    stored.copy(
                        resources = stored.resources.map { original ->
                            migrated.firstOrNull { it.id == original.id } ?: original
                        },
                    )
                }
            }
        }
    }

    /** A rejected legacy source remains removable and never hides the other saved conversations. */
    private suspend fun migrateSource(source: ResearchResource): ResearchResource = try {
        attachments.persist(source, LegacyResearchSupport, migration = true)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException(error::class.simpleName)) { "Legacy source migration failed" }
        source.copy(hasAttachmentError = true)
    }

    private suspend fun importResource(input: ResearchChatIntent.Public.AddResource): ResearchResource {
        val value = input.value.trim()
        val title = input.title.trim()
        if (value.startsWith("attachment:")) {
            val file = attachments.registered(AttachmentId(value.substringAfter(':')))
            return ResearchResource(
                newId(),
                title.ifBlank { file.name },
                input.kind,
                file.resource.id,
                file.mediaType,
                attachmentId = file.id,
                attachmentSizeBytes = file.sizeBytes,
            )
        }
        return when (input.kind) {
            ResearchResourceKind.Website -> {
                require(isWebUrl(value)) { "An HTTP(S) source is required" }
                val page = search.fetch(value)
                require(page.text.isNotBlank()) { "Empty website content" }
                ResearchResource(
                    newId(),
                    title.ifBlank { page.title ?: value },
                    input.kind,
                    value,
                    "text/html",
                    page.text,
                )
            }

            ResearchResourceKind.Document -> {
                val mediaType = input.mediaType ?: "text/plain"
                require(mediaType in setOf("text/plain", "text/markdown", "application/pdf")) { "Unsupported document" }
                require(mediaType != "application/pdf" || value.startsWith("data:application/pdf;base64,")) {
                    "A PDF must contain imported bytes"
                }
                ResearchResource(newId(), title.ifBlank { "Document" }, input.kind, value, mediaType)
            }

            ResearchResourceKind.Image -> {
                require(value.startsWith("https://") || value.startsWith("data:image/")) { "Unsupported image source" }
                val mediaType = input.mediaType ?: value.takeIf { it.startsWith("data:") }
                    ?.substringAfter("data:")?.substringBefore(';') ?: "image/jpeg"
                require(
                    mediaType in setOf("image/png", "image/jpeg", "image/webp", "image/gif"),
                ) { "Unsupported image" }
                ResearchResource(newId(), title.ifBlank { "Image" }, input.kind, value, mediaType)
            }
        }
    }

    override suspend fun setResourceSelected(
        sessionId: String,
        questionId: String,
        resourceId: String,
        isSelected: Boolean,
    ) {
        requireEditable(sessionId, questionId)
        log.i { "Change question source selection selected=$isSelected" }
        storage.update(sessionId) { session ->
            require(session.resources.any { it.id == resourceId }) { "Unknown resource" }
            session.copy(
                questions = session.questions.map { question ->
                    if (question.id != questionId) {
                        question
                    } else {
                        question.copy(
                            excludedResourceIds = if (isSelected) {
                                question.excludedResourceIds - resourceId
                            } else {
                                question.excludedResourceIds + resourceId
                            },
                            resourceIds = if (isSelected && resourceId !in session.sharedResourceIds) {
                                question.resourceIds + resourceId
                            } else {
                                question.resourceIds
                            },
                        )
                    }
                },
            )
        }
    }

    override suspend fun shareResource(sessionId: String, resourceId: String) {
        requireEnabled(session(sessionId).target)
        log.i { "Share research source across questions" }
        storage.update(sessionId) { session ->
            require(session.resources.any { it.id == resourceId }) { "Unknown resource" }
            session.copy(sharedResourceIds = session.sharedResourceIds + resourceId)
        }
    }

    override suspend fun removeResource(sessionId: String, resourceId: String) {
        requireEnabled(session(sessionId).target)
        log.i { "Remove research source" }
        storage.update(sessionId) { session ->
            session.copy(
                resources = session.resources.filterNot { it.id == resourceId },
                sharedResourceIds = session.sharedResourceIds - resourceId,
                questions = session.questions.map {
                    it.copy(
                        resourceIds = it.resourceIds - resourceId,
                        excludedResourceIds = it.excludedResourceIds - resourceId,
                    )
                },
            )
        }
    }

    override suspend fun run(sessionId: String, questionId: String, prompt: String): ResearchRun {
        if (session(sessionId).questions.first { it.id == questionId }.pendingSegmentStart != null &&
            questionId !in running.value
        ) {
            recoverPending()
        }
        val session = session(sessionId)
        requireEnabled(session.target)
        val question = session.questions.first { it.id == questionId }
        require(prompt.isNotBlank() || session.selectedResources(question).isNotEmpty()) { "Empty research question" }
        check(question.pendingSegmentStart == null) { "Previous research turn requires recovery" }
        val accepted = CompletableDeferred<Boolean>()
        log.i { "Start profile-owned research execution" }
        val completion = executionLock.withLock {
            check(questionId !in running.value) { "Research question is already running" }
            running.update { it + questionId }
            profile.coroutineScope.async(start = CoroutineStart.LAZY) {
                execute(session, question, prompt.trim(), accepted)
            }.also { job ->
                job.invokeOnCompletion { accepted.complete(false) }
                job.start()
            }
        }
        return ResearchRun(accepted, completion)
    }

    private suspend fun execute(
        session: ResearchSession,
        question: ResearchQuestion,
        prompt: String,
        accepted: CompletableDeferred<Boolean>,
    ): Boolean = try {
        val prepared = prepareSources(session, question)
        if (executionLock.withLock { question.id in stops }) {
            true
        } else {
            val active = openQuestion(session, question, prompt)
            executeTurn(prepared, question, prompt, active, accepted)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException(error::class.simpleName.orEmpty())) { "Research generation failed" }
        markFailed(session.id, question.id)
        false
    } finally {
        accepted.complete(false)
        withContext(NonCancellable) {
            release(question.id)
            executionLock.withLock {
                stops.remove(question.id)
                running.update { it - question.id }
            }
        }
    }

    private suspend fun prepareSources(session: ResearchSession, question: ResearchQuestion): ResearchSession {
        log.d { "Prepare selected research sources" }
        val prepared = loadSelectedSources(session, question, search)
        storage.update(session.id) { stored ->
            stored.copy(
                resources = stored.resources.map { source ->
                    val hydrated = prepared.resources.firstOrNull { it.id == source.id && it.text.isNotBlank() }
                    if (source.text.isBlank() && hydrated != null) source.copy(text = hydrated.text) else source
                },
            )
        }
        return prepared
    }

    /** A clean native segment cannot replay attachments excluded from the current source selection. */
    private suspend fun openQuestion(
        session: ResearchSession,
        question: ResearchQuestion,
        prompt: String,
    ): ActiveSession {
        requireEnabled(session.target)
        log.i { "Open research native segment" }
        val active = facade.engines.features(session.target.engine).requireFeature(CreatesSessions)
            .create(CreateSessionRequest(session.target))
        executionLock.withLock { handles[question.id] = active }
        storage.update(session.id) { stored ->
            stored.copy(
                title = stored.title.ifBlank { prompt.take(TITLE_LENGTH) },
                questions = stored.questions.map {
                    if (it.id == question.id) {
                        it.copy(
                            ref = active.ref,
                            hasFailed = false,
                            pendingSegmentStart = question.items.size,
                            title = it.title.ifBlank { prompt.take(TITLE_LENGTH) },
                        )
                    } else {
                        it
                    }
                },
            )
        }
        return active
    }

    private suspend fun executeTurn(
        session: ResearchSession,
        question: ResearchQuestion,
        prompt: String,
        active: ActiveSession,
        accepted: CompletableDeferred<Boolean>,
    ): Boolean = supervisorScope {
        val history = active.features.requireFeature(SessionHistory)
        if (executionLock.withLock { question.id in stops }) {
            finishSegment(session.id, question.id, isFailed = false)
            return@supervisorScope true
        }
        val mirror = ResearchHistory { items -> persistSegment(session.id, question, items) }
        val observation = launch { observeHistory(mirror, history) }
        try {
            val request = PromptRequest(RequestId(newId()), session.promptParts(question, prompt))
            val turn = submit(active, request)
            accepted.complete(true)
            applyPendingStop(question.id, active, turn)
            val isExpectedEnd = awaitTurn(question.id, active, turn)
            observation.cancelAndJoin()
            mirror.refresh(history)
            finishSegment(session.id, question.id, isFailed = !isExpectedEnd)
            isExpectedEnd
        } finally {
            observation.cancel()
        }
    }

    /** Local stop may end the stream without a known remote outcome; only an unsolicited interruption is an error. */
    private suspend fun awaitTurn(questionId: String, active: ActiveSession, turn: TurnId): Boolean {
        val terminal = active.state.first {
            (it is ActiveSessionState.Ready && it.lastTurn?.id == turn) ||
                (it is ActiveSessionState.Unavailable && it.activeTurn == null && it.lastTurn?.id == turn)
        }
        val finished = (terminal as? ActiveSessionState.Ready)?.lastTurn
            ?: (terminal as? ActiveSessionState.Unavailable)?.lastTurn
        return when (finished?.outcome) {
            TurnOutcome.Completed, TurnOutcome.Cancelled -> true
            TurnOutcome.Unknown -> executionLock.withLock { questionId in stops }
            is TurnOutcome.Failed, null -> false
        }
    }

    private suspend fun finishSegment(sessionId: String, questionId: String, isFailed: Boolean) {
        log.d { "Record terminal research segment failed=$isFailed" }
        storage.update(sessionId) { stored ->
            stored.copy(
                questions = stored.questions.map {
                    if (it.id == questionId) it.copy(pendingSegmentStart = null, hasFailed = isFailed) else it
                },
            )
        }
    }

    private suspend fun submit(active: ActiveSession, request: PromptRequest): TurnId = try {
        log.i { "Submit research prompt with selected sources" }
        active.features.requireFeature(SendsPrompts).send(request)
    } catch (error: EngineException) {
        log.w(
            IllegalStateException(error::class.simpleName.orEmpty()),
        ) { "Check native acceptance after submission error" }
        active.state.value.activeTurn()?.takeIf { it.request == request.id }?.id ?: throw error
    }

    private suspend fun observeHistory(mirror: ResearchHistory, history: SessionHistory) {
        try {
            mirror.follow(history)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException(error::class.simpleName.orEmpty())) { "Research history observation failed" }
        }
    }

    private suspend fun persistSegment(sessionId: String, previous: ResearchQuestion, items: List<SessionItem>) {
        val sources = discoveredResources(items)
        storage.update(sessionId) { stored ->
            val changed = stored.copy(
                questions = stored.questions.map {
                    if (it.id == previous.id) {
                        it.copy(
                            items = previous.items + items.map(SessionItem::withoutAttachments),
                        )
                    } else {
                        it
                    }
                },
            )
            sources.fold(
                changed,
            ) { session, source -> session.attach(previous.id, source, ResearchResourceScope.Question) }
        }
    }

    private suspend fun applyPendingStop(questionId: String, active: ActiveSession, turn: TurnId) {
        if (!executionLock.withLock { questionId in stops }) return
        try {
            active.features.requireFeature(CancelsTurns).cancel(turn)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException(error::class.simpleName.orEmpty())) {
                "Cancellation failed; keep observing the accepted research turn"
            }
        }
    }

    /** Reopens only history, never generation. Unknown terminal outcome is shown as interrupted. */
    private suspend fun recoverPending() = recoveryLock.withLock {
        storage.read().forEach { session ->
            session.questions.filter { it.pendingSegmentStart != null && it.id !in running.value }.forEach { question ->
                recoverQuestion(session.id, question)
            }
        }
    }

    private suspend fun recoverQuestion(sessionId: String, question: ResearchQuestion) {
        try {
            log.i { "Recover saved research native history without resubmitting" }
            val ref = requireNotNull(question.ref)
            val start = requireNotNull(question.pendingSegmentStart)
            val baseline = question.copy(items = question.items.take(start))
            val history = facade.sessions.get(ref).features.requireFeature(SessionHistory)
            ResearchHistory { items ->
                val recovered = mergeResearchSegment(question.items.drop(start), items)
                persistSegment(sessionId, baseline, recovered)
            }.refresh(history)
            finishSegment(sessionId, question.id, isFailed = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException(error::class.simpleName.orEmpty())) { "Research recovery remains pending" }
            markFailed(sessionId, question.id)
        }
    }

    override suspend fun stop(questionId: String) {
        val active = executionLock.withLock {
            if (questionId !in running.value) return
            stops += questionId
            handles[questionId]
        } ?: return
        val turn = active.state.value.activeTurn() ?: return
        log.i { "User requested research cancellation" }
        active.features.requireFeature(CancelsTurns).cancel(turn.id)
    }

    private suspend fun release(questionId: String) {
        val active = executionLock.withLock { handles.remove(questionId) } ?: return
        try {
            active.close()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(
                IllegalStateException(error::class.simpleName.orEmpty()),
            ) { "Could not release research native session" }
        }
    }

    private suspend fun markFailed(sessionId: String, questionId: String) = storage.update(sessionId) { stored ->
        stored.copy(questions = stored.questions.map { if (it.id == questionId) it.copy(hasFailed = true) else it })
    }

    private suspend fun requireEditable(sessionId: String, questionId: String) {
        val session = session(sessionId)
        requireEnabled(session.target)
        require(session.questions.any { it.id == questionId }) { "Unknown research question" }
        check(questionId !in running.value) { "Cannot edit sources while their question is running" }
    }

    private suspend fun requireEnabled(target: EngineTarget) {
        require(target.engine == KoogEngineId) { "Research requires Koog" }
        check(toggles.get(ResearchChatEnabled) && toggles.get(KoogEngineEnabled)) { "Research is disabled" }
    }

    private suspend fun session(id: String): ResearchSession = storage.read().first { it.id == id }

    private fun newId(): String = Uuid.random().toString()

    private companion object {
        const val TITLE_LENGTH = 80
    }
}

private fun <F : EngineFeature> EngineFeatures.requireFeature(key: EngineFeatureKey<F>): F = when (
    val access = resolve(
        key,
    )
) {
    is FeatureAccess.Available -> access.feature
    is FeatureAccess.Unavailable -> throw EngineException(access.reason)
    FeatureAccess.Unsupported -> error("Required research engine operation is unsupported")
}

private fun ActiveSessionState.activeTurn(): Turn? = when (this) {
    is ActiveSessionState.Submitting -> turn
    is ActiveSessionState.Running -> turn
    is ActiveSessionState.AwaitingUserAction -> turn
    is ActiveSessionState.Interrupting -> turn
    is ActiveSessionState.Unavailable -> activeTurn
    is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
}
