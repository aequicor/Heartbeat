package io.aequicor.heartbeat.feature.serviceasui.api

/** Bounds of the surface library and of host coordination; the spec format has its own limits in the impl. */
public object ServiceUiLimits {
    /** Surfaces in one profile. */
    public const val SURFACES: Int = 64

    /** Surfaces of one owner. */
    public const val SURFACES_PER_OWNER: Int = 16

    /** UTF-8 bytes of one spec. */
    public const val SPEC_BYTES: Int = 2 * 1024 * 1024

    /** UTF-8 bytes of all specs, titles and descriptions of a profile. */
    public const val LIBRARY_BYTES: Int = 16 * 1024 * 1024

    /** Characters of a surface title. */
    public const val TITLE_CHARS: Int = 80

    /** Characters of a surface description. */
    public const val DESCRIPTION_CHARS: Int = 500

    /** Hosts bound at the same time. */
    public const val HOSTS: Int = 64

    /** Panels open at the same time, one per dock. */
    public const val PANELS: Int = 8

    /** Library writes waiting behind the one being saved. */
    public const val QUEUED_WRITES: Int = 16

    /** Characters of a prompt a surface inserts into the composer or sends. */
    public const val PROMPT_CHARS: Int = 16 * 1024

    /** UTF-8 bytes of the arguments a panel is opened with. */
    public const val ARGS_BYTES: Int = 16 * 1024
}
