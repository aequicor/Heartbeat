package io.aequicor.heartbeat.feature.serviceasui.impl.di

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiAuthoringDocument
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiAuthoringKnowledge
import io.aequicor.heartbeat.feature.serviceasui.impl.ui.readAuthoringDocument

/**
 * Connects packaged UI-resource access to the domain port at the composition root. Hosted tools consume
 * [UiAuthoringKnowledge] without depending on UI resources or duplicating the skill in Kotlin source.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class BundledUiAuthoringKnowledge : UiAuthoringKnowledge {
    override suspend fun read(document: UiAuthoringDocument): String {
        val resource = when (document) {
            UiAuthoringDocument.MiniAppDesign -> "mini-app-design/SKILL.md"
            UiAuthoringDocument.ServiceAsUi -> "service-as-ui/SKILL.md"
            UiAuthoringDocument.Layout -> "service-as-ui/references/layout.md"
            UiAuthoringDocument.ActionsData -> "service-as-ui/references/actions-data.md"
            UiAuthoringDocument.WebView -> "service-as-ui/references/webview.md"
            UiAuthoringDocument.VisualPatterns -> "service-as-ui/references/visual-patterns.md"
            UiAuthoringDocument.Examples -> "service-as-ui/references/examples.md"
        }
        return readAuthoringDocument(resource)
    }
}
