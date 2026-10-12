package io.aequicor.heartbeat.feature.researchchat.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.browser.api.BrowserEnabled
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserMachineKey
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResearchAccess
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchRecoveryAction
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchSourceRecovery
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * Recovery never exports cookies: it reads the visible document in the context that the human
 * unlocked.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ResearchBrowserRecovery(
    private val storage: ResearchStorage,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val attachments: io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachments,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ResearchSourceRecovery {
    private val log = Log.tag("ResearchBrowserRecovery")

    private val lock = Mutex()
    private val jobs = mutableMapOf<Pair<String, String>, Deferred<Unit>>()
    private val resourceLocks = mutableMapOf<Pair<String, String>, Mutex>()
    private var isRestored = false

    init {
        profile.coroutineScope.launch {
            combine(toggles.observe(BrowserEnabled), toggles.observe(BrowserResearchAccess)) { browser, recovery ->
                browser && recovery
            }.collect { enabled ->
                if (!enabled) lock.withLock { jobs.values.toList() }.forEach { it.cancel() }
            }
        }
        profile.coroutineScope.launch {
            storage.observe().collect { sessions ->
                val existing =
                    sessions
                        .flatMap { session -> session.resources.map { session.id to it.id } }
                        .toSet()
                lock
                    .withLock { jobs.filterKeys { it !in existing }.values.toList() }
                    .forEach { it.cancel() }
            }
        }
    }

    /**
     * Persisted native identifiers are invalid after process restart; resuming always requires a
     * new click.
     */
    override suspend fun restore() = lock.withLock {
        if (isRestored) return@withLock
        storage.read().forEach { session ->
            storage.update(session.id) { current ->
                current.copy(
                    resources =
                        current.resources.map { source ->
                            if (
                                source.loadState != ResearchLoadState.Ready &&
                                source.recoveryAttempt != null
                            ) {
                                source.copy(
                                    loadState = ResearchLoadState.NeedsUserAction,
                                    browserTabId = null,
                                    recoveryAttempt = null,
                                )
                            } else {
                                source
                            }
                        },
                )
            }
        }
        isRestored = true
    }

    override suspend fun recover(sessionId: String, resourceId: String, action: ResearchRecoveryAction) {
        val key = sessionId to resourceId
        val resourceLock = lock.withLock { resourceLocks.getOrPut(key) { Mutex() } }
        if (action == ResearchRecoveryAction.Cancel) {
            resourceLock.withLock {
                withContext(NonCancellable) { cancelResource(sessionId, resourceId) }
            }
            return
        }
        val job =
            resourceLock.withLock {
                lock.withLock {
                    jobs[key]?.takeIf { it.isActive }
                        ?: profile.coroutineScope
                            .async(start = CoroutineStart.LAZY) {
                                perform(sessionId, resourceId, action)
                            }
                            .also {
                                jobs[key] = it
                                it.start()
                            }
                }
            }
        try {
            job.await()
        } finally {
            withContext(NonCancellable) {
                lock.withLock { if (jobs[key] === job && job.isCompleted) jobs.remove(key) }
            }
        }
    }

    /** Reserves only this source until native cancellation acknowledges; unrelated sources remain usable. */
    private suspend fun cancelResource(sessionId: String, resourceId: String) {
        val job = lock.withLock { jobs.remove(sessionId to resourceId) }
        job?.cancel()
        try {
            val previous = storage.read().firstOrNull { it.id == sessionId }
                ?.resources?.firstOrNull { it.id == resourceId }
            if (previous != null && previous.loadState != ResearchLoadState.Ready) {
                storage.update(sessionId) { session ->
                    session.copy(
                        resources = session.resources.map { source ->
                            if (source.id == resourceId) {
                                source.cancelRecoveryAttempt(previous.recoveryAttempt)
                            } else {
                                source
                            }
                        },
                    )
                }
            }
        } finally {
            withContext(NonCancellable) { job?.join() }
        }
    }

    private suspend fun perform(sessionId: String, resourceId: String, action: ResearchRecoveryAction) {
        check(toggles.get(BrowserEnabled) && toggles.get(BrowserResearchAccess)) {
            "Browser recovery disabled"
        }
        val source =
            storage.read().first { it.id == sessionId }.resources.first { it.id == resourceId }
        if (source.loadState == ResearchLoadState.Ready) return
        val attempt = Uuid.random().toString()
        val pending =
            source.copy(
                recoveryAttempt = attempt,
                loadState = ResearchLoadState.Loading,
                loadError = null,
            )
        if (!update(sessionId, source, pending)) return
        var isSettled = false
        try {
            if (action == ResearchRecoveryAction.Open) {
                openSource(sessionId, pending)
            } else {
                captureSource(sessionId, pending)
            }
            isSettled = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException(error::class.simpleName)) {
                "Browser-assisted source loading failed"
            }
            update(
                sessionId,
                pending,
                pending.copy(
                    loadState = ResearchLoadState.NeedsUserAction,
                    loadError = "browser_retry_required",
                ),
            )
            isSettled = true
        } finally {
            withContext(NonCancellable) {
                if (!isSettled) resetCancelled(sessionId, pending)
            }
        }
    }

    private suspend fun resetCancelled(sessionId: String, pending: ResearchResource) {
        update(
            sessionId,
            pending,
            pending.copy(
                loadState = ResearchLoadState.NeedsUserAction,
                recoveryAttempt = null,
                loadError = "cancelled",
            ),
        )
    }

    private suspend fun openSource(sessionId: String, pending: ResearchResource) {
        val attempt = requireNotNull(pending.recoveryAttempt)
        val opened = call(
            BrowserRequest(
                "$attempt-open",
                BrowserOperation.NewTab,
                arguments = buildJsonObject { put("url", pending.sourceUrl ?: pending.value) },
            ),
        )
        val tabId = opened.data.getValue("tabId").jsonPrimitive.content
        update(
            sessionId,
            pending,
            pending.copy(browserTabId = tabId, loadState = ResearchLoadState.NeedsUserAction),
        )
    }

    private suspend fun captureSource(sessionId: String, pending: ResearchResource) {
        val attempt = requireNotNull(pending.recoveryAttempt)
        val tabId = requireNotNull(pending.browserTabId) { "Open the resource in the browser first" }
        val page = call(
            BrowserRequest(
                "$attempt-capture",
                BrowserOperation.CaptureResource,
                tabId,
                arguments = buildJsonObject { put("url", pending.sourceUrl ?: pending.value) },
            ),
        )
        if (page.data["kind"]?.jsonPrimitive?.content == "document") {
            importDocument(sessionId, pending, page.data)
        } else {
            val content = readableRecoveryContent(page.data)
            check(toggles.get(BrowserResearchAccess) && toggles.get(BrowserEnabled)) {
                "Browser recovery disabled"
            }
            update(
                sessionId,
                pending,
                pending.copy(
                    text = content,
                    loadState = ResearchLoadState.Ready,
                    loadError = null,
                    recoveryAttempt = null,
                ),
            )
        }
    }

    private suspend fun importDocument(sessionId: String, pending: ResearchResource, data: JsonObject) {
        val mediaType = data.getValue("mediaType").jsonPrimitive.content
        val bytes =
            kotlin.io.encoding.Base64.decode(data.getValue("contentBase64").jsonPrimitive.content)
        val extension = data.getValue("extension").jsonPrimitive.content
        val files =
            attachments.import(
                listOf(
                    io.aequicor.heartbeat.feature.attachments.api.AttachmentInput.Bytes(
                        "source.$extension",
                        mediaType,
                        bytes,
                    ),
                ),
                LegacyResearchSupport,
            )
        val file = files.single()
        check(toggles.get(BrowserEnabled) && toggles.get(BrowserResearchAccess)) {
            "Browser recovery disabled"
        }
        update(
            sessionId,
            pending,
            pending.copy(
                kind = ResearchResourceKind.Document,
                value = file.resource.id,
                sourceUrl = pending.sourceUrl ?: pending.value,
                text = "",
                attachmentId = file.id,
                attachmentSizeBytes = file.sizeBytes,
                mediaType = mediaType,
                hasAttachmentError = false,
                loadState = ResearchLoadState.Ready,
                recoveryAttempt = null,
                loadError = null,
            ),
        )
    }

    private suspend fun update(sessionId: String, previous: ResearchResource, next: ResearchResource): Boolean {
        var isApplied = false
        storage.update(sessionId) { session ->
            isApplied = session.resources.any { it == previous }
            session.copy(
                resources =
                    session.resources.map {
                        it.applyRecoveryUpdate(previous, next)
                    },
            )
        }
        return isApplied
    }

    private suspend fun call(request: BrowserRequest): BrowserResult = coroutineScope {
        val machine = requireNotNull(machines.find(BrowserMachineKey)) { "Browser unavailable" }
        val result =
            async(start = CoroutineStart.UNDISPATCHED) {
                machine.outputs.first {
                    it is BrowserOutput.Completed && it.result.requestId == request.id
                } as BrowserOutput.Completed
            }
        try {
            check(machine.send(BrowserIntent.Public.Perform(request)) == SendResult.Accepted) {
                "Browser disabled"
            }
            withTimeout(OPERATION_TIMEOUT) { result.await().result }
                .also { check(it.error == null) { "Browser could not read this resource" } }
        } finally {
            result.cancel()
            withContext(NonCancellable) {
                withTimeout(CANCELLATION_TIMEOUT) {
                    val cancelled =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            machine.outputs.first {
                                it is BrowserOutput.Cancelled && it.id == request.id
                            }
                        }
                    machine.send(BrowserIntent.Public.CancelOperation(request.id))
                    cancelled.await()
                }
            }
        }
    }

    private companion object {
        const val OPERATION_TIMEOUT = 45_000L
        const val CANCELLATION_TIMEOUT = 5_000L
    }
}

/** Rejects empty or still-challenged documents before replacing source evidence. */
internal fun readableRecoveryContent(data: JsonObject): String {
    require(data["requiresUserAction"]?.jsonPrimitive?.booleanOrNull != true) { "Page requires user action" }
    val content = data["content"]?.jsonPrimitive?.content.orEmpty()
    require(content.isNotBlank()) { "The page has no readable content" }
    val title = data["title"]?.jsonPrimitive?.content.orEmpty()
    val isBlocked = listOf("verify you are human", "checking your browser", "just a moment", "captcha").any {
        title.contains(it, true) || content.take(CHALLENGE_PREFIX_SIZE).contains(it, true)
    }
    require(!isBlocked) { "The page still requires verification" }
    return content
}

/** Late callbacks cannot overwrite cancellation or new evidence, even when both attempts are null. */
internal fun ResearchResource.applyRecoveryUpdate(
    previous: ResearchResource,
    next: ResearchResource,
): ResearchResource = if (this == previous) next else this

/** An old cancellation cannot discard completed evidence or a newer recovery attempt. */
internal fun ResearchResource.cancelRecoveryAttempt(expectedAttempt: String?): ResearchResource =
    if (loadState != ResearchLoadState.Ready && recoveryAttempt == expectedAttempt) {
        copy(loadState = ResearchLoadState.Failed, loadError = "cancelled", recoveryAttempt = null)
    } else {
        this
    }

private const val CHALLENGE_PREFIX_SIZE = 1_000
