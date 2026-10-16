package io.aequicor.heartbeat.feature.projectconfig.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigBundles
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigDialogs
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ProjectFolders

/** Mobile platforms have no local projects: project folders are unsupported. */
@ContributesBinding(AppScope::class)
@Inject
internal class MobileProjectFolders : ProjectFolders by UnsupportedConfigFiles

/** Mobile platforms have no file dialogs for bundles: bundles are unsupported. */
@ContributesBinding(AppScope::class)
@Inject
internal class MobileConfigBundles : ConfigBundles by UnsupportedConfigFiles

/** Mobile platforms have no file dialogs for bundles. */
@ContributesBinding(AppScope::class)
@Inject
internal class MobileConfigDialogs : ConfigDialogs by UnsupportedConfigDialogs
