package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import dev.detekt.api.valuesWithReason
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Network requests, storage access, configuration, navigation and state changes are logged centrally by
 * `core:*` adapters (Ktor Logging, DataStore/Room wrappers, the `heartbeatStore` plugin, the machine listener).
 * Creating the underlying primitives elsewhere bypasses that logging.
 *
 * Each `forbidden` entry is `<symbol> -> <package allowed to use it>` with a reason (the replacement).
 * `<symbol>` with a dot is an import prefix (`pro.respawn.flowmvi.dsl.store`); without a dot it is a call name
 * (`HttpClient`, `PreferenceDataStoreFactory`).
 *
 * <noncompliant>
 * package io.aequicor.heartbeat.feature.chat.impl
 * val client = HttpClient(OkHttp)
 * </noncompliant>
 *
 * <compliant>
 * package io.aequicor.heartbeat.feature.chat.impl
 * class ChatApi @Inject constructor(private val client: HttpClient) // provided by core:network
 * </compliant>
 */
class LoggingInfrastructureBypass(config: Config) :
    Rule(
        config,
        "Network/storage/state primitives must come from core:* modules that log them.",
    ) {

    private val forbidden by config(
        valuesWithReason(
            "HttpClient -> io.aequicor.heartbeat.core.network" to
                "inject HttpClient from core:network (Ktor Logging with secret redaction)",
            "PreferenceDataStoreFactory -> io.aequicor.heartbeat.core.datastore" to
                "use the logging DataStore from core:datastore",
            "DataStoreFactory -> io.aequicor.heartbeat.core.datastore" to
                "use the logging DataStore from core:datastore",
            "databaseBuilder -> io.aequicor.heartbeat.core.database" to
                "use HeartbeatDatabase from core:database",
            "inMemoryDatabaseBuilder -> io.aequicor.heartbeat.core.database" to
                "use the test factory from core:database",
            "pro.respawn.flowmvi.dsl.store -> io.aequicor.heartbeat.core.mvi" to
                "use heartbeatStore { } from core:mvi (logging plugin)",
            "pro.respawn.flowmvi.dsl.lazyStore -> io.aequicor.heartbeat.core.mvi" to
                "use heartbeatStore { } from core:mvi (logging plugin)",
            "createStateMachine -> io.aequicor.heartbeat.core.statemachine" to
                "create machines via core:state-machine (logging listener + MachineRegistry)",
            "createStdLibStateMachine -> io.aequicor.heartbeat.core.statemachine" to
                "create machines via core:state-machine (logging listener + MachineRegistry)",
        ),
    ) { values ->
        values.mapNotNull { entry ->
            val parts = entry.value.split("->").map(String::trim)
            if (parts.size != 2 || parts.any(String::isEmpty)) return@mapNotNull null
            Bypass(symbol = parts[0], allowedPackage = parts[1], replacement = entry.reason)
        }
    }

    private var packageName = ""

    override fun visitKtFile(file: KtFile) {
        packageName = file.packageName()
        super.visitKtFile(file)
    }

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val path = importDirective.importedFqName?.asString() ?: return
        forbidden.filter { it.isImport && path.isInPackage(it.symbol) }
            .forEach { reportBypass(importDirective, path, it) }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeName() ?: return
        forbidden.filter { !it.isImport && it.symbol == name }
            .forEach { reportBypass(expression, name, it) }
    }

    /** Factory objects used as receivers: `PreferenceDataStoreFactory.createWithPath { }`. */
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val name = (expression.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return
        forbidden.filter { !it.isImport && it.symbol == name }
            .forEach { reportBypass(expression.receiverExpression, name, it) }
    }

    private fun reportBypass(element: KtElement, symbol: String, bypass: Bypass) {
        if (packageName.isInPackage(bypass.allowedPackage)) return
        val hint = bypass.replacement?.let { " — $it" }.orEmpty()
        report(Finding(Entity.from(element), "`$symbol` outside ${bypass.allowedPackage} bypasses logging$hint."))
    }

    private data class Bypass(val symbol: String, val allowedPackage: String, val replacement: String?) {
        val isImport: Boolean get() = '.' in symbol
    }
}
