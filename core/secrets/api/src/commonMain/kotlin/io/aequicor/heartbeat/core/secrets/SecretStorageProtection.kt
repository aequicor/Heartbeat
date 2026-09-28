package io.aequicor.heartbeat.core.secrets

/** Actual storage protection selected by the host; reading this metadata never opens the vault. */
public enum class SecretStorageProtection {
    /** Keys are protected by the operating system's credential storage or encryption. */
    System,

    /** Development-only local files; isolated from production and not protected by the operating system. */
    LocalDevelopment,
}

/** Non-secret host metadata for credential-entry screens. Access never reads or writes credentials. */
public interface SecretStorageInfo {
    /** Protection provided by the actual backend of this application graph. */
    public val protection: SecretStorageProtection
}
