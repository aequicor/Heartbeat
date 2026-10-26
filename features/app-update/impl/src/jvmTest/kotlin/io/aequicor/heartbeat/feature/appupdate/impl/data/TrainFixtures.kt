package io.aequicor.heartbeat.feature.appupdate.impl.data

internal const val TRAIN_DOWNLOADS = "https://github.com/aequicor/Heartbeat/releases/download"

/**
 * `train-manifest.json` of [train] with the [bundles] `jar to sha256`; a jar named in [urls] states that absolute
 * `url`, as `scripts/train_manifest.py` writes it.
 */
internal fun manifestJson(
    train: String,
    bundles: List<Pair<String, String>>,
    platformApi: Int = 1,
    urls: Map<String, String> = emptyMap(),
): String = bundles.joinToString(
    separator = ",",
    prefix = """{"train": "$train", "platformApi": $platformApi, "dbSchema": 3, "bundles": [""",
    postfix = "]}",
) { (jar, sha) ->
    val url = urls[jar]?.let { """, "url": "$it"""" }.orEmpty()
    """{"id": "${jar.removeSuffix(".jar")}", "version": "1.0", "jar": "$jar", "sha256": "$sha"$url}"""
}

/**
 * A GitHub release JSON of [tag] with assets `name to size`, a pre-release unless [isStable], published at the
 * ISO-8601 instant [publishedAt] when one is given.
 */
internal fun releaseJson(
    tag: String,
    assets: List<Pair<String, Long>>,
    isStable: Boolean = false,
    publishedAt: String? = null,
): String = assets.joinToString(
    separator = ",",
    prefix = """{"tag_name": "$tag", "draft": false, "prerelease": ${!isStable},
        ${publishedAt?.let { """"published_at": "$it", """ }.orEmpty()}
        "html_url": "https://github.com/aequicor/Heartbeat/releases/tag/$tag", "assets": [""",
    postfix = "]}",
) { (name, size) ->
    """{"name": "$name", "size": $size, "browser_download_url": "$TRAIN_DOWNLOADS/$tag/$name"}"""
}
