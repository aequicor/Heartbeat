package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.bundles.impl.BundleHost
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermissionGuide

/**
 * What the profile graph of the foundation uses of the `studio` bundle; implemented by the host with lazy proxies.
 * Without the bundle there is no studio to fall back to, so an unavailable bundle surfaces as
 * `BundleUnavailableException` where a service is first used.
 */
internal interface FoundationStudioImports {
    /** Studio screens that the Chromium side panel opens and drives. */
    val studioRemoteScreens: StudioRemoteScreens

    /** Catalog of the profile's attachments. */
    val attachmentsCatalog: AttachmentsCatalog

    /** Reads the bytes of attachments and other prompt resources of the profile. */
    val resourceResolver: ResourceResolver
}

/** What the application graph of the foundation uses of the `studio` bundle. */
internal interface FoundationStudioAppImports {
    /** Coordinates exclusion of native session windows from computer-use screenshots and input. */
    val capturePresentation: ComputerUseCapturePresentation

    /** The macOS permission grant the desktop host guides with a draggable application tile. */
    val permissionGuide: ComputerUsePermissionGuide
}

/** Bridges the services of the `studio` bundle into the profile graph of the foundation. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object StudioImportBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    fun studioImports(
        host: BundleHost,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
    ): FoundationStudioImports = host.foundationProfileImports(FoundationStudioImports::class, scope)

    @Provides
    fun studioRemoteScreens(imports: FoundationStudioImports): StudioRemoteScreens = imports.studioRemoteScreens

    @Provides
    fun attachmentsCatalog(imports: FoundationStudioImports): AttachmentsCatalog = imports.attachmentsCatalog

    @Provides
    fun resourceResolver(imports: FoundationStudioImports): ResourceResolver = imports.resourceResolver
}

/** Bridges the application-level services of the `studio` bundle into the application graph of the foundation. */
@ContributesTo(AppScope::class)
@BindingContainer
internal object StudioAppImportBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun studioAppImports(host: BundleHost): FoundationStudioAppImports =
        host.foundationImports(FoundationStudioAppImports::class)

    @Provides
    fun capturePresentation(imports: FoundationStudioAppImports): ComputerUseCapturePresentation =
        imports.capturePresentation

    @Provides
    fun permissionGuide(imports: FoundationStudioAppImports): ComputerUsePermissionGuide = imports.permissionGuide
}
