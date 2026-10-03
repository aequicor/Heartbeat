package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Requests to the network and reads/writes of storage go through repositories and data sources;
 * each public operation logs its entry: routine/high-frequency IO at `log.v`, meaningful operations at `log.d/i`.
 *
 * Applies to non-private functions with a body in classes whose name matches `classNamePattern`
 * (default: `*Repository`, `*DataSource`, `*Storage`, `*Api`, `*Client`, optionally with `Impl`).
 *
 * <noncompliant>
 * class ChatRepositoryImpl(private val dao: ChatDao) : ChatRepository {
 *     override suspend fun delete(chatId: ChatId) = dao.delete(chatId)
 * }
 * </noncompliant>
 *
 * <compliant>
 * class ChatRepositoryImpl(private val dao: ChatDao) : ChatRepository {
 *     override suspend fun delete(chatId: ChatId) {
 *         log.d { "delete chatId=$chatId" }
 *         dao.delete(chatId)
 *     }
 * }
 * </compliant>
 */
class DataAccessNotLogged(config: Config) :
    Rule(
        config,
        "Repository / data source operations (network, DB, DataStore) must log their calls.",
    ) {

    private val loggerReceiverPattern: String by config(DEFAULT_LOGGER_RECEIVER_PATTERN)

    private val classNamePattern: String by config(".*(Repository|DataSource|Storage|Api|Client)(Impl)?")

    /** Function names that are not operations (object plumbing). */
    private val ignoredFunctions: List<String> by config(listOf("toString", "hashCode", "equals", "close"))

    private val logMatcher by lazy { LogCallMatcher(loggerReceiverPattern, DEFAULT_LOG_METHODS) }
    private val classRegex by lazy { Regex(classNamePattern) }

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (klass.isInterface() || klass.isAnnotation()) return
        val name = klass.name ?: return
        if (!classRegex.matches(name)) return

        klass.body?.functions.orEmpty()
            .asSequence()
            .filter { it.isOperation() }
            .filterNot { logMatcher.containsLogCall(it) }
            .forEach { function ->
                val functionName = function.nameAsSafeName.asString()
                report(
                    Finding(
                        Entity.atName(function),
                        "`$name.$functionName` does not log — add log.d { \"$functionName <key params>\" } " +
                            "on entry, or log.v for routine/high-frequency IO (errors: log.e(e)).",
                    ),
                )
            }
    }

    private fun KtNamedFunction.isOperation(): Boolean = hasBody() &&
        !hasModifier(KtTokens.PRIVATE_KEYWORD) &&
        !hasModifier(KtTokens.PROTECTED_KEYWORD) &&
        name !in ignoredFunctions
}
