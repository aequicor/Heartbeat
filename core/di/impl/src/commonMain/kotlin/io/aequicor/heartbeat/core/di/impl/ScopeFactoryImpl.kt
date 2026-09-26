package io.aequicor.heartbeat.core.di.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.Job

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class ScopeFactoryImpl(private val dispatchers: DispatcherProvider) : ScopeFactory {

    private val log = Log.tag(ScopeHandleImpl.LOG_TAG)

    override fun child(parent: ScopeHandle, name: String, restored: SavedBundle?): OwnedScope {
        check(!parent.isClosed) { "cannot create scope '$name': parent ${parent.name} is closed" }
        val scope = createScope(
            dispatchers = dispatchers,
            name = "${parent.name}/$name",
            parentJob = parent.coroutineScope.coroutineContext[Job],
            restored = restored,
        )
        // child closes with the parent; an explicit close of the child unlinks it from the parent
        val link = parent.onClose(scope::close)
        scope.onClose(link::dispose)
        log.i { "scope ${scope.name} ${if (restored != null) "restored" else "created"}" }
        return scope
    }
}

/** The app scope: root of the tree, no parent, never closed. */
internal fun createRootScope(dispatchers: DispatcherProvider): OwnedScope =
    createScope(dispatchers, ROOT_SCOPE_NAME, parentJob = null, restored = null)
        .also { Log.tag(ScopeHandleImpl.LOG_TAG).i { "scope $ROOT_SCOPE_NAME created" } }

private fun createScope(dispatchers: DispatcherProvider, name: String, parentJob: Job?, restored: SavedBundle?) =
    ScopeHandleImpl(name, parentJob, dispatchers.main, ScopeSavedStateImpl(name, restored))

private const val ROOT_SCOPE_NAME = "app"
