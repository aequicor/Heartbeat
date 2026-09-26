package io.aequicor.heartbeat.core.navigation.impl

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ResultContract
import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Navigator of one entry; the component created for the entry receives it. */
internal class EntryNavigator(val host: HostNode, val entry: Entry) : Navigator {

    private val log = Log.tag(LOG_TAG)

    val path: String = "${host.path}/${entry.id}"

    /** Hosts created by the component of this entry (most recent last); used to descend for deep links. */
    val hosts: MutableList<HostNode> = mutableListOf()

    override fun navigate(route: Route, options: NavOptions) = host.dispatch(route, options, request = null)

    override fun <R : Any> navigateForResult(route: Route, contract: ResultContract<R>, options: NavOptions) =
        host.dispatch(route, options, ResultRequest(requester = path, contract = contract.name))

    override fun <R : Any> results(contract: ResultContract<R>): Flow<R> = host.tree.results.results(path, contract)

    override fun <R : Any> finishWithResult(contract: ResultContract<R>, result: R) {
        val request = entry.request
        if (request?.contract == contract.name) {
            host.tree.results.put(request.requester, contract, result)
        } else {
            log.w { "$path: result ${contract.name} dropped — the entry was not opened for it" }
        }
        close()
    }

    override fun close() = host.remove(entry.id)
}

/** [HostNode.navigator]: resolution starts in the host, everything else acts on behalf of the owner entry. */
internal class HostNavigator(private val host: HostNode) : Navigator {

    private val log = Log.tag(LOG_TAG)

    override fun navigate(route: Route, options: NavOptions) = host.dispatch(route, options, request = null)

    override fun <R : Any> navigateForResult(route: Route, contract: ResultContract<R>, options: NavOptions) {
        val owner = host.owner
        if (owner == null) {
            log.w { "${host.path}: the root has no owner to receive ${contract.name}, opening without a result" }
            host.dispatch(route, options, request = null)
        } else {
            host.dispatch(route, options, ResultRequest(requester = owner.path, contract = contract.name))
        }
    }

    override fun <R : Any> results(contract: ResultContract<R>): Flow<R> = host.owner?.results(contract) ?: emptyFlow()

    override fun <R : Any> finishWithResult(contract: ResultContract<R>, result: R) {
        host.owner?.finishWithResult(contract, result) ?: log.w { "${host.path}: the root cannot finish with a result" }
    }

    override fun close() {
        host.owner?.close() ?: log.w { "${host.path}: the root cannot be closed" }
    }
}
