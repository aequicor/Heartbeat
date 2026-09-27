package io.aequicor.heartbeat.core.secrets

/**
 * An owned sensitive value. Neither string interpolation nor equality reveals its contents.
 * Call [close] after use. Not thread-safe; never share an instance across concurrent callers.
 */
public class Secret(chars: CharArray) : AutoCloseable {
    private val value: CharArray = chars.copyOf()
    private var isClosed: Boolean = false

    /**
     * Gives [block] a temporary copy, cleared even if the block throws.
     * Do not retain it or log it. Strings created by the caller cannot be erased by this class.
     */
    public fun <T> reveal(block: (CharArray) -> T): T {
        check(!isClosed) { "Secret is closed" }
        val copy = value.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill('\u0000')
        }
    }

    /** Erases the owned buffer; repeated calls are harmless. */
    override fun close() {
        value.fill('\u0000')
        isClosed = true
    }

    /** Always redacted, including after disposal. */
    override fun toString(): String = "Secret(***)"
}
