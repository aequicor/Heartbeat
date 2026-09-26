package io.aequicor.heartbeat.core.navigation

/**
 * Maps a deep link to navigation commands. Contributed by feature impls into the registry of their scope, like
 * [RouteEntry] (`binding<ProfileDeepLinkBinding>()` / `binding<AppDeepLinkBinding>()`).
 * [pattern] is a path of literal segments and `{param}` placeholders, e.g. `chat/{chatId}`: it is matched
 * against the host and path of `heartbeat://chat/42` and against the path of `https://<web host>/chat/42`.
 * More literal segments win over placeholders.
 *
 * A deep link is untrusted input: validate parameters (throw [IllegalArgumentException] to reject the link) and
 * only navigate — never perform an action without the user confirming it on screen.
 */
public abstract class DeepLinkEntry(
    /** Path pattern, without scheme and query. */
    public val pattern: String,
) {
    /**
     * Commands applied one by one: each goes to the navigator of the deepest active entry after the previous
     * one, so later commands land inside hosts created by earlier ones.
     */
    public abstract fun commands(params: DeepLinkParams): List<NavCommand>
}

/** A navigation request of a deep link. */
public data class NavCommand(
    /** Route to open. */
    val route: Route,
    /** How to open it. */
    val options: NavOptions = NavOptions(),
)

/** Parameters of a matched deep link: path placeholders and query parameters (percent-decoded). */
public class DeepLinkParams(private val path: Map<String, String>, private val query: Map<String, String>) {
    /** Path placeholder [name]. */
    public fun path(name: String): String = requireNotNull(path[name]) { "no path parameter '$name'" }

    /** Query parameter [name], if present. */
    public fun query(name: String): String? = query[name]
}

/** Outcome of [RootHost.handleDeepLink]. */
public enum class DeepLinkResult {
    /** Commands applied. */
    Handled,

    /** Valid link, but no entry of this tree matches — e.g. a profile link in the guest tree (keep it pending). */
    NoMatch,

    /** Foreign scheme or host, malformed link or invalid parameters. */
    Rejected,
}

/**
 * Accepted deep link origins. `core:navigation:impl` binds a default (`heartbeat://`, no web hosts);
 * the app overrides it with `@ContributesBinding(AppScope::class, priority = 0)`.
 */
public interface DeepLinkConfig {
    /** Custom schemes: `scheme://<first segment>/<rest>`. */
    public val schemes: Set<String>

    /** Hosts of `https` app links: `https://<host>/<segments>`. */
    public val webHosts: Set<String>
}
