package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * The runtime bundle registry (`core:bundles`) is a service locator that exists at the border of the bundles only.
 * Feature code never reaches it, and the platform code never reaches into a bundle's graph: a bundle shows itself
 * through its exports, which the foundation imports through the typed accessors of `core:bundles`.
 *
 * - `io.aequicor.heartbeat.feature.*` must not import `io.aequicor.heartbeat.core.bundles` or a bundle
 *   (`io.aequicor.heartbeat.bundles`).
 * - `io.aequicor.heartbeat.platform.*` must not import a bundle or cast to a graph type (`as FooGraph`): graphs are
 *   reached through accessor interfaces (`graph as FooAccessors`), never through the generated graph types.
 *
 * <noncompliant>
 * package io.aequicor.heartbeat.feature.chat.impl
 * import io.aequicor.heartbeat.core.bundles.BundleRegistry
 * </noncompliant>
 *
 * <compliant>
 * package io.aequicor.heartbeat.platform.dibundle
 * val access = session.graph as WorkerProfileAccess
 * </compliant>
 */
class BundleBoundary(config: Config) :
    Rule(
        config,
        "The bundle registry belongs to the platform border, never to features; platform code uses accessors.",
    ) {
    private var packageName = ""

    override fun visitKtFile(file: KtFile) {
        packageName = file.packageName()
        super.visitKtFile(file)
    }

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val path = importDirective.importedFqName?.asString() ?: return
        val isBundle = path.isInPackage(BUNDLES)
        val isRegistry = path.isInPackage(REGISTRY)
        val message = when {
            packageName.isInPackage(FEATURES) && (isBundle || isRegistry) ->
                "Features never use the bundle registry or a bundle"

            packageName.isInPackage(PLATFORM) && isBundle ->
                "Platform code reaches a bundle through its exports, not through its classes"

            else -> return
        }
        report(Finding(Entity.from(importDirective), "$message: $path."))
    }

    override fun visitBinaryWithTypeRHSExpression(expression: KtBinaryExpressionWithTypeRHS) {
        super.visitBinaryWithTypeRHSExpression(expression)
        if (!packageName.isInPackage(PLATFORM)) return
        val operation = expression.operationReference.getReferencedName()
        if (operation != "as" && operation != "as?") return
        val type = expression.right?.text?.substringBefore('<').orEmpty()
        if (type.split('.').any { it.endsWith("Graph") }) {
            report(Finding(Entity.from(expression), "Cast to a graph type `$type`: cast to an accessor interface."))
        }
    }

    private companion object {
        const val FEATURES = "io.aequicor.heartbeat.feature"
        const val PLATFORM = "io.aequicor.heartbeat.platform"
        const val BUNDLES = "io.aequicor.heartbeat.bundles"
        const val REGISTRY = "io.aequicor.heartbeat.core.bundles"
    }
}
