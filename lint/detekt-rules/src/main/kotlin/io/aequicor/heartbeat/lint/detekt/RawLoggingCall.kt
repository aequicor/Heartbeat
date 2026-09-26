package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * All logging goes through the `core:logging` facade (`Log.tag("X")`), which applies levels, tags and
 * secret redaction. Raw output (`println`, `System.out`, `printStackTrace()`, `android.util.Log`,
 * `NSLog`, direct Napier) bypasses all of that.
 *
 * <noncompliant>
 * println("loaded $id")
 * error.printStackTrace()
 * android.util.Log.d("Chat", "loaded")
 * </noncompliant>
 *
 * <compliant>
 * private val log = Log.tag("ChatRepository")
 * log.d { "loaded id=$id" }
 * </compliant>
 */
class RawLoggingCall(config: Config) :
    Rule(
        config,
        "Logging must go through the core:logging facade (Log.tag(...)), not raw output APIs.",
    ) {

    private val forbiddenCalls: List<String> by config(listOf("println", "print", "NSLog", "NSLogv"))

    private val forbiddenMemberCalls: List<String> by config(listOf("printStackTrace"))

    private val forbiddenQualifiers: List<String> by config(listOf("System.out", "System.err"))

    private val forbiddenImports: List<String> by config(
        listOf(
            "android.util.Log",
            "platform.Foundation.NSLog",
            "io.github.aakira.napier",
            "java.util.logging",
            "org.slf4j",
            "timber.log",
        ),
    )

    /** Packages that implement the facade itself and may use the underlying APIs. */
    private val allowedPackages: List<String> by config(listOf("io.aequicor.heartbeat.core.logging"))

    private var allowedFile = false

    override fun visitKtFile(file: KtFile) {
        allowedFile = allowedPackages.any { file.packageName().isInPackage(it) }
        if (!allowedFile) super.visitKtFile(file)
    }

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val path = importDirective.importedFqName?.asString() ?: return
        if (forbiddenImports.any { path.isInPackage(it) }) {
            val message = "Raw logging API `$path` — use Log.tag(...) from core:logging."
            report(Finding(Entity.from(importDirective), message))
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeName() ?: return
        val qualified = expression.parent as? KtDotQualifiedExpression
        val isMember = qualified != null && qualified.selectorExpression == expression
        when {
            !isMember && name in forbiddenCalls ->
                report(Finding(Entity.from(expression), "`$name(...)` — use Log.tag(...) from core:logging."))

            isMember && name in forbiddenMemberCalls ->
                report(Finding(Entity.from(expression), "`$name()` — log the error: log.e(error) { \"...\" }."))
        }
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val text = expression.receiverExpression.text
        if (text in forbiddenQualifiers) {
            report(Finding(Entity.from(expression), "`$text` — use Log.tag(...) from core:logging."))
        }
    }
}
