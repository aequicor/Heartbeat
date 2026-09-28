package io.aequicor.heartbeat.lint.detekt

/** Package-level dependency policy shared by the architecture rules. */
internal data class FeatureLayer(val feature: String, val module: String, val layer: String) {
    val isContract: Boolean get() = module == "api"
    val isDomain: Boolean get() = isContract || layer == "domain"

    fun violation(reference: String): String? {
        val target = fromPackage(reference)
        return when {
            target != null -> featureViolation(target, reference)

            CORE_IMPL.containsMatchIn(reference) -> "Core implementations are visible only to di-bundle"

            reference == "io.aequicor.heartbeat.core.featuretoggles.*" && layer !in setOf("data", "di") ->
                "Import pure toggle types explicitly; wildcard also exposes data services"

            else -> frameworkViolation(reference)
        }
    }

    private fun frameworkViolation(reference: String): String? {
        if (isDomain) return domainViolation(reference)
        return when {
            layer != "di" && layer != "data" && IO_PACKAGES.any(reference::isInPackage) ->
                "Access IO through a domain port implemented in data"

            layer in setOf("data", "presentation") && isUiReference(reference) ->
                "Compose rendering belongs to ui; presentation and data must not depend on it"

            layer == "data" && PRESENTATION_PACKAGES.any(reference::isInPackage) ->
                "Data adapters must not depend on presentation frameworks"

            layer == "ui" && UI_FORBIDDEN.any(reference::isInPackage) ->
                "UI consumes presentation state and events, not business services or machines"

            else -> null
        }
    }

    private fun domainViolation(reference: String): String? {
        if (isContract && reference in PUBLIC_ROUTE_TYPES) return null
        return if (DOMAIN_FORBIDDEN.any(reference::isInPackage)) "Domain must be independent of UI, DI and IO" else null
    }

    private fun featureViolation(target: FeatureLayer, reference: String): String? = when {
        feature == "aiengine.authenticator" && target.feature == "aiengine.facade" ->
            "Authenticator contracts must not depend on the facade"

        target.module == "impl" && (isContract || target.feature != feature) ->
            "Feature implementations are private; depend on the public api"

        layer == "ui" && target.isContract -> uiContractViolation(target)

        target.isContract -> null

        target.layer == "di" && reference.isInPackage("$FEATURE_PREFIX.$feature.impl.di.scope") &&
            layer in setOf("data", "presentation", "ui") -> null

        target.layer !in ALLOWED_LAYERS.getValue(layer) ->
            "$layer must not depend on ${target.layer}; dependencies point towards domain"

        else -> null
    }

    /** Search settings display service contract values directly; this feature has no business state machine. */
    private fun uiContractViolation(target: FeatureLayer): String? =
        if (feature == "searchengine" && target.feature == feature) {
            null
        } else {
            "UI sends presentation events instead of machine intents"
        }

    private fun isUiReference(reference: String): Boolean =
        UI_PACKAGES.any(reference::isInPackage) && !(layer == "presentation" && reference in PRESENTATION_ANNOTATIONS)

    companion object {
        const val FEATURE_PREFIX = "io.aequicor.heartbeat.feature"
        val layers: Set<String> = setOf("domain", "data", "presentation", "ui", "di")
        private val FEATURE_PACKAGE = Regex(
            "^io\\.aequicor\\.heartbeat\\.feature\\.((?:aiengine\\.)?[a-z0-9_]+)\\.(api|impl)(?=\\.|$)(?:\\.([^.]+))?",
        )
        private val CORE_IMPL = Regex("^io\\.aequicor\\.heartbeat\\.core\\.[^.]+\\.impl(?:\\.|$)")
        private val ALLOWED_LAYERS = mapOf(
            "domain" to setOf("domain"),
            "data" to setOf("data", "domain"),
            "presentation" to setOf("presentation", "domain"),
            "ui" to setOf("ui", "presentation", "resources"),
            "di" to layers,
        )
        private val IO_PACKAGES = listOf(
            "io.ktor", "androidx.room", "androidx.sqlite", "androidx.datastore", "okio",
            "java.io", "java.nio", "java.net", "kotlin.io", "kotlinx.io", "platform.Foundation",
            "io.aequicor.heartbeat.core.network", "io.aequicor.heartbeat.core.datastore",
            "io.aequicor.heartbeat.core.ai",
            "io.aequicor.heartbeat.core.featuretoggles.FeatureToggles",
            "io.aequicor.heartbeat.core.featuretoggles.FeatureToggleControl",
        )
        private val UI_PACKAGES = listOf(
            "androidx.compose",
            "org.jetbrains.compose",
            "io.aequicor.heartbeat.ds",
            "io.aequicor.heartbeat.core.navigation.compose",
        )
        private val DOMAIN_FORBIDDEN = IO_PACKAGES + UI_PACKAGES + listOf(
            "android", "androidx.lifecycle", "dev.zacsweers.metro", "com.arkivanov", "pro.respawn.flowmvi",
            "ru.nsk.kstatemachine", "io.aequicor.heartbeat.core.mvi", "io.aequicor.heartbeat.core.di",
            "io.aequicor.heartbeat.core.statemachine.flowmvi",
            "io.aequicor.heartbeat.core.navigation",
        )
        private val PUBLIC_ROUTE_TYPES = setOf(
            "io.aequicor.heartbeat.core.navigation.Route",
            "io.aequicor.heartbeat.core.navigation.ResultContract",
        )
        private val PRESENTATION_PACKAGES = listOf(
            "pro.respawn.flowmvi",
            "com.arkivanov",
            "io.aequicor.heartbeat.core.mvi",
            "io.aequicor.heartbeat.core.navigation",
            "io.aequicor.heartbeat.core.statemachine.flowmvi",
        )
        private val UI_FORBIDDEN = listOf(
            "io.aequicor.heartbeat.core.statemachine",
            "io.aequicor.heartbeat.core.featuretoggles",
        )
        private val PRESENTATION_ANNOTATIONS = setOf(
            "androidx.compose.runtime.Immutable",
            "androidx.compose.runtime.Stable",
        )

        fun fromPackage(name: String): FeatureLayer? = FEATURE_PACKAGE.find(
            name,
        )?.destructured?.let { (feature, module, layer) ->
            FeatureLayer(feature, module, if (module == "api") "domain" else layer)
        }
    }
}
