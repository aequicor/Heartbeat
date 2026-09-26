package io.aequicor.heartbeat.core.di

import dev.zacsweers.metro.DefaultBinding

/**
 * Address of an object shared by several features within a profile (upload session, player, heavy cache).
 * Declared as an `object` in the api module of the owning feature. Not for business state that must
 * survive process death — that belongs to a state machine.
 */
public interface SharedKey<T : Any> {
    /** Unique name; equals the `@StringKey` of the matching [SharedFactory]. */
    public val name: String
}

/**
 * Creates a shared object. Contributed by the owning feature's impl:
 *
 * ```
 * @ContributesIntoMap(ProfileScope::class)
 * @StringKey("upload-session") // == UploadSessionKey.name
 * @Inject
 * internal class UploadSessionFactory(private val api: UploadApi) : SharedFactory<UploadSession> { … }
 * ```
 */
@DefaultBinding<SharedFactory<*>>
public interface SharedFactory<T : Any> {
    /** Creates the object; [scope] lives exactly as long as the object is shared. */
    public fun create(scope: ScopeHandle): T
}

/**
 * Reference-counted shared objects of the active profile: created on the first [acquire], closed when
 * the last [Lease] is released. Use `retainedShared` from `core:di:ext` in components. Main thread only.
 */
public interface SharedScopes {
    /** Returns the object for [key], creating it if nobody holds it. */
    public fun <T : Any> acquire(key: SharedKey<T>): Lease<T>
}

/** A hold on a shared object; [close] releases it (idempotent). */
public interface Lease<T : Any> : AutoCloseable {
    /** The shared object. */
    public val value: T
}
