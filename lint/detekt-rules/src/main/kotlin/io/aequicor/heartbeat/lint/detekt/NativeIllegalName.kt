package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Kotlin/Native rejects declaration names with certain characters ("Name contains illegal characters"),
 * while the JVM compiler accepts some of them (comma, parentheses, `$`, `%`, …). A backtick test name such as
 * `` `loads, then saves` `` in `commonTest` passes `jvmTest` and fails only in `compileTestKotlinIos*`,
 * which local verification does not run. The rule checks every declaration in source sets that compile
 * to Kotlin/Native, recognised by the `src/<sourceSet>/kotlin/` path.
 *
 * The character set mirrors `FirNativeIdentifierChecker` of the Kotlin/Native compiler. Modules whose
 * common source sets have no native target (e.g. the wasmJs/jvm Chromium panel) are excluded in config.
 *
 * <noncompliant>
 * // src/commonTest/kotlin/...
 * @Test fun `loads, then saves`() = Unit
 * </noncompliant>
 *
 * <compliant>
 * @Test fun `loads then saves`() = Unit
 * </compliant>
 */
class NativeIllegalName(config: Config) :
    Rule(
        config,
        "Declarations compiled by Kotlin/Native must not contain characters it rejects in names.",
    ) {

    /**
     * Source set name prefixes compiled by Kotlin/Native: `commonTest`, `iosSimulatorArm64Main`, `mobileMain`.
     * A prefix matches the whole name or is followed by an upper-case letter.
     */
    private val nativeSourceSets: List<String> by config(
        listOf(
            "common",
            "native",
            "apple",
            "ios",
            "macos",
            "tvos",
            "watchos",
            "linux",
            "mingw",
            "androidNative",
            "mobile",
        ),
    )

    override fun visitKtFile(file: KtFile) {
        val sourceSet = SOURCE_SET.find(file.virtualFilePath.replace('\\', '/'))?.groupValues?.get(1) ?: return
        if (nativeSourceSets.any { sourceSet.isSourceSetOf(it) }) super.visitKtFile(file)
    }

    override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
        super.visitNamedDeclaration(declaration)
        // A constructor reports the class name, which the class itself already reports.
        if (declaration is KtConstructor<*>) return
        val illegal = declaration.name.orEmpty().filter { it in ILLEGAL_CHARACTERS }.toSet()
        if (illegal.isNotEmpty()) {
            report(
                Finding(
                    Entity.atName(declaration),
                    "Name contains characters Kotlin/Native rejects: " +
                        illegal.joinToString { "'$it'" } + ".",
                ),
            )
        }
    }

    private fun String.isSourceSetOf(prefix: String): Boolean =
        startsWith(prefix) && (length == prefix.length || this[prefix.length].isUpperCase())

    private companion object {
        val SOURCE_SET = Regex("(?:^|/)src/([^/]+)/kotlin/")

        /** `FirNativeIdentifierChecker.invalidChars` (Kotlin 2.4). */
        const val ILLEGAL_CHARACTERS = ".;,()[]{}/<>:\\$&~*?#|§%@"
    }
}
