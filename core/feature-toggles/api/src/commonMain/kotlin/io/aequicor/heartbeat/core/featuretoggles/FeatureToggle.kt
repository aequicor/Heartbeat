package io.aequicor.heartbeat.core.featuretoggles

/**
 * Declaration of a feature toggle: a `val` (top-level or in an `object`) in the owning feature — its `api` if other
 * modules read it, otherwise its `impl` — registered for the control panel by a Metro set contribution:
 *
 * ```
 * object ChatToggles {
 *     val StreamingResponses = FeatureToggle.Flag("chat.streaming_responses", "Stream model responses")
 * }
 *
 * @ContributesTo(AppScope::class)
 * interface ChatTogglesContribution {
 *     @Provides @IntoSet fun streaming(): FeatureToggle<*> = ChatToggles.StreamingResponses
 * }
 * ```
 * The provider must return exactly `FeatureToggle<*>` (`Set<FeatureToggle<*>>` for `@ElementsIntoSet`): with
 * `FeatureToggle.Flag` or `FeatureToggle<Boolean>` Metro puts it into another set and the registration is lost.
 *
 * @param T type of the value.
 */
public sealed interface FeatureToggle<T : Any> {
    /**
     * Unique key `<owner>.<name>`: lowercase letters, digits and `_` in dot-separated segments, e.g.
     * `chat.streaming_responses`. Stored on disk — never rename or reuse a removed key.
     */
    public val key: String

    /** Value used while the toggle is not overridden. Unfinished functionality starts with `false`. */
    public val default: T

    /** What the toggle switches, shown in the control panel. */
    public val description: String

    /** Owning feature: the first segment of [key]. The control panel groups toggles by it. */
    public val owner: String get() = key.substringBefore('.')

    /** On / off. */
    public data class Flag(
        override val key: String,
        override val description: String,
        override val default: Boolean = false,
    ) : FeatureToggle<Boolean> {
        init {
            requireDeclaration(key, description)
        }
    }

    /** One of fixed [options] (a mode, a variant, a model). */
    public data class Choice(
        override val key: String,
        override val description: String,
        /** Allowed values, in the order shown by the control panel; non-blank and distinct. */
        public val options: List<String>,
        override val default: String = options.firstOrNull().orEmpty(),
    ) : FeatureToggle<String> {
        init {
            requireDeclaration(key, description)
            require(options.isNotEmpty()) { "$key: options must not be empty" }
            require(options.none { it.isBlank() }) { "$key: options must be non-blank" }
            require(options.distinct().size == options.size) { "$key: options must be distinct: $options" }
            require(default in options) { "$key: default '$default' is not one of $options" }
        }
    }
}

private val TOGGLE_KEY = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
private const val MAX_KEY_LENGTH = 128

private fun requireDeclaration(key: String, description: String) {
    require(TOGGLE_KEY.matches(key) && key.length <= MAX_KEY_LENGTH) {
        "invalid toggle key '$key': expected <owner>.<name> matching ${TOGGLE_KEY.pattern}, " +
            "at most $MAX_KEY_LENGTH chars"
    }
    require(description.isNotBlank()) { "$key: description must not be blank" }
}
