package io.aequicor.heartbeat.core.bundles.spike

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Service whose default binding is contributed from another module. */
public interface FixtureService {
    /** Name of the implementation, to tell the bindings apart. */
    public val origin: String
}

/** Public contribution of a library module; a bundle graph removes it with `excludes` and binds its own. */
@ContributesBinding(AppScope::class)
@Inject
public class LibraryFixtureService : FixtureService {
    override val origin: String = "library"
}
