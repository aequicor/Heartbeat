package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.JsonObject

/** Complete adapter-owned call facts. Permission is decided solely by the shared profile gate. */
public data class NativeToolCall(
    val name: String,
    val action: AgentToolAction,
    val paths: List<String> = emptyList(),
    val command: String? = null,
    val arguments: JsonObject? = null,
    /** Actual execution directory pinned by the adapter, not a model-provided workspace claim. */
    val workingDirectory: String? = null,
    /** Exact host-controlled shell launcher, absent for engines/extensions whose executor cannot be attested. */
    val shell: NativeShellExecution? = null,
) {
    override fun toString(): String = "NativeToolCall"
}

/** A trusted launcher's dialect; never inferred from a tool name or supplied in model arguments. */
public enum class NativeShellDialect { PowerShell, Posix, Bash, Cmd }

/**
 * Exact host-owned PowerShell prelude for UTF-8 streams without a BOM. It changes only this process's output
 * encodings and ends with a newline before the proposed source. Adapters must bind these exact bytes in their
 * executor evidence; arbitrary model or configuration prefixes never inherit this initialization contract.
 */
public const val POWER_SHELL_UTF8_INITIALIZATION: String =
    "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(\$false)\n" +
        "\$OutputEncoding = [Console]::OutputEncoding\n"

/**
 * Fixed controlled Windows startup: UTF-8 plus the two system modules used by directory inspection.
 * Absolute engine-owned manifests avoid ambient command discovery. Failed imports terminate the process before
 * proposed source runs, even if that source declares a trap. Adapters bind these exact bytes and their revision.
 */
public const val POWER_SHELL_CONTROLLED_INITIALIZATION: String = POWER_SHELL_UTF8_INITIALIZATION +
    "try {\n" +
    "Microsoft.PowerShell.Core\\Import-Module -Name " +
    "([System.IO.Path]::Combine(\$PSHOME, 'Modules', 'Microsoft.PowerShell.Management', " +
    "'Microsoft.PowerShell.Management.psd1')) -ErrorAction Stop\n" +
    "Microsoft.PowerShell.Core\\Import-Module -Name " +
    "([System.IO.Path]::Combine(\$PSHOME, 'Modules', 'Microsoft.PowerShell.Utility', " +
    "'Microsoft.PowerShell.Utility.psd1')) -ErrorAction Stop\n" +
    "} catch { [System.Environment]::Exit(2) }\n"

/**
 * Host-attested executor used by this native call. The adapter must launch exactly this executable, argument
 * prefix and sanitized environment and prevent discovery/overrides from replacing it. The executor appends the
 * concatenation of [initialization] and the proposed command as one argument. Unknown launchers leave
 * [NativeToolCall.shell] null and cannot inherit proof.
 */
public data class NativeShellExecution(
    val dialect: NativeShellDialect,
    val executable: String,
    val argumentPrefix: List<String>,
    /** Opaque fingerprint of the complete fixed execution environment and implementation revision. */
    val environmentBinding: String,
    /**
     * Exact adapter-owned script prelude. Only empty, [POWER_SHELL_UTF8_INITIALIZATION] or
     * [POWER_SHELL_CONTROLLED_INITIALIZATION] may be trusted for PowerShell. The analyzer must reject every other
     * value, even from a known launcher.
     * The complete fixed prelude participates in [environmentBinding]; model arguments cannot supply it.
     */
    val initialization: String = "",
) {
    override fun toString(): String = "NativeShellExecution(dialect=$dialect)"
}

/** The adapter may execute only after Allow and while its captured native turn is still active. */
public sealed interface NativeVerdict {
    /** The shared profile decision permits this exact call in this turn. */
    public data object Allow : NativeVerdict

    /** Safe user-facing diagnostic; adapters must not treat a denied call as successful execution. */
    public data class Deny(val reason: String) : NativeVerdict {
        override fun toString(): String = "NativeVerdict.Deny"
    }
}

/** Preflight bound to the exact call and turn; Automatic may request user confirmation. */
public sealed interface NativePreparation {
    /** Complete authorization, subject to the adapter's final native lifetime check. */
    public data object Allow : NativePreparation

    /** No interactive approval may override this refusal. */
    public data class Deny(val reason: String) : NativePreparation {
        override fun toString(): String = "NativePreparation.Deny"
    }

    /** A one-shot continuation of this exact preflight; not an execution permission. */
    public data class Ask(val confirmation: NativeConfirmation) : NativePreparation {
        override fun toString(): String = "NativePreparation.Ask"
    }
}

/**
 * Requests the decision without invoking the hook twice. The capability is bound to the original full input,
 * context and turn; it rechecks policy before and after permission and cannot be reused. The adapter must
 * discard it when native call identity or input changes and recheck its native turn before writing Allow.
 */
public fun interface NativeConfirmation {
    /** Consumes this confirmation and revalidates the captured turn and policy around the user decision. */
    public suspend fun authorize(): NativeVerdict
}
