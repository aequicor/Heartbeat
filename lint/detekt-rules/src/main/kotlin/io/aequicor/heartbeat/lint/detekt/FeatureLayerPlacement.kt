package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

/** Requires an explicit architectural layer for handwritten feature implementation code. */
class FeatureLayerPlacement(config: Config) :
    Rule(
        config,
        "Feature implementations must declare their architectural layer.",
    ) {
    override fun visitKtFile(file: KtFile) {
        checkSourcePath(file)
        val source = FeatureLayer.fromPackage(file.packageName())
        if (source != null && !source.isContract && source.layer !in FeatureLayer.layers) {
            report(
                Finding(
                    Entity.from(file),
                    "Feature implementation belongs in domain, data, presentation, ui or di.",
                ),
            )
        }
        checkScopeMarkers(file, source)
        super.visitKtFile(file)
    }

    private fun checkSourcePath(file: KtFile) {
        val path = file.virtualFilePath.replace('\\', '/')
        FEATURE_SOURCE.find(path)?.destructured?.let { (feature, module, relative) ->
            val expectedRoot = "${FeatureLayer.FEATURE_PREFIX}.${feature.replace("-", "")}.$module"
            if (!file.packageName().isInPackage(expectedRoot) ||
                relative.substringBeforeLast('/', "") != file.packageName().replace('.', '/')
            ) {
                report(Finding(Entity.from(file), "Package must match its module and source directory: $expectedRoot."))
            }
        }
    }

    private fun checkScopeMarkers(file: KtFile, source: FeatureLayer?) {
        if (source == null || source.isContract) return
        val scopePackage = "${FeatureLayer.FEATURE_PREFIX}.${source.feature}.impl.di.scope"
        if (file.packageName().isInPackage(scopePackage) && file.declarations.any { !it.isScopeMarker() }) {
            report(Finding(Entity.from(file), "di.scope is reserved for empty scope marker interfaces."))
        }
    }

    private fun org.jetbrains.kotlin.psi.KtDeclaration.isScopeMarker(): Boolean {
        val marker = this as? KtClass ?: return false
        if (!marker.isInterface() || marker.name?.endsWith("Scope") != true) return false
        return listOf(
            marker.declarations.isEmpty(),
            marker.superTypeListEntries.isEmpty(),
            marker.annotationEntries.isEmpty(),
            marker.typeParameters.isEmpty(),
        ).all { it }
    }

    private companion object {
        val FEATURE_SOURCE = Regex("(?:^|/)features/([^/]+)/(api|impl)/src/[^/]+/kotlin/(.+)")
    }
}
