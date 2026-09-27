package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/** Checks imports and fully qualified references without type resolution, including aliases and stars. */
class FeatureLayerDependency(config: Config) : Rule(config, "Feature dependencies must point towards domain.") {
    private var source: FeatureLayer? = null
    private var sourcePackage: String = ""

    override fun visitKtFile(file: KtFile) {
        sourcePackage = file.packageName()
        source = FeatureLayer.fromPackage(
            file.packageName(),
        )?.takeIf { it.isContract || it.layer in FeatureLayer.layers }
        super.visitKtFile(file)
    }

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        importDirective.importedFqName?.asString()?.let {
            checkReference(importDirective, if (importDirective.isAllUnder) "$it.*" else it)
        }
    }

    override fun visitUserType(type: KtUserType) {
        super.visitUserType(type)
        if (type.parent !is KtUserType) checkReference(type, type.qualifiedName())
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.getStrictParentOfType<KtImportDirective>() != null ||
            expression.getStrictParentOfType<KtPackageDirective>() != null
        ) {
            return
        }
        if (expression.parent !is KtDotQualifiedExpression) {
            expression.qualifiedName()?.let { checkReference(expression, it) }
        }
    }

    private fun checkReference(element: KtElement, reference: String) {
        val reason = if (reference.isInPackage("io.aequicor.heartbeat.feature.aiengine.facade.api.spi") &&
            !sourcePackage.isInPackage("io.aequicor.heartbeat.feature.aiengine") &&
            !sourcePackage.isInPackage("io.aequicor.heartbeat.platform.dibundle")
        ) {
            "Engine SPI is private to ai-engine adapters and the application bundle"
        } else {
            source?.violation(reference)
        }
        reason?.let { report(Finding(Entity.from(element), "$it: $reference.")) }
    }

    private fun KtUserType.qualifiedName(): String =
        listOfNotNull(qualifier?.qualifiedName(), referencedName).joinToString(".")

    private fun KtExpression.qualifiedName(): String? = when (this) {
        is KtNameReferenceExpression -> getReferencedName()

        is KtCallExpression -> calleeExpression?.qualifiedName()

        is KtDotQualifiedExpression -> {
            val receiver = receiverExpression.qualifiedName()
            val selector = selectorExpression?.qualifiedName()
            if (receiver == null || selector == null) null else "$receiver.$selector"
        }

        else -> null
    }
}
