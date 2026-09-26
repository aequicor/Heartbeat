package io.aequicor.heartbeat.core.di

import dev.zacsweers.metro.Qualifier
import kotlin.reflect.KClass

/**
 * Scope of a signed-in profile: child of `AppScope`, parent of every feature scope.
 * Lives from opening a profile session until sign-out or a switch to another profile
 * (`ProfileSessions` in `core:profile-facade`).
 */
@Suppress("AbstractClassCanBeInterface") // same shape as Metro's AppScope: a non-instantiable marker
public abstract class ProfileScope private constructor()

/**
 * Qualifies per-scope instances that exist on every level of the scope tree
 * (`ScopeHandle`, `CoroutineScope`): child graphs inherit parent bindings, so unqualified
 * bindings would clash.
 *
 * ```
 * @Inject class ChatRepositoryImpl(@ForScope(ChatScope::class) private val scope: ScopeHandle)
 * ```
 * `TYPE` lets it qualify multibinding elements, usually through a typealias:
 * `typealias ProfileRouteBinding = @ForScope(ProfileScope::class) RouteEntry<*>` +
 * `@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())` —
 * a qualifier on the contributed class itself is ignored by `@ContributesIntoSet`.
 */
@Qualifier
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.TYPE,
)
public annotation class ForScope(
    /** Scope marker the qualified instance belongs to. */
    val scope: KClass<*>,
)
