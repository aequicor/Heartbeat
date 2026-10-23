package io.aequicor.heartbeat.feature.appupdate.impl.data

internal const val TRAIN_DOWNLOADS = "https://github.com/aequicor/Heartbeat/releases/download"

/** `train-manifest.json` of [train] with the [bundles] `jar to sha256`. */
internal fun manifestJson(train: String, bundles: List<Pair<String, String>>, platformApi: Int = 1): String =
    bundles.joinToString(
        separator = ",",
        prefix = """{"train": "$train", "platformApi": $platformApi, "dbSchema": 3, "bundles": [""",
        postfix = "]}",
    ) { (jar, sha) -> """{"id": "${jar.removeSuffix(".jar")}", "version": "1.0", "jar": "$jar", "sha256": "$sha"}""" }

/** A GitHub release JSON of [tag] with assets `name to size`, a pre-release unless [isStable]. */
internal fun releaseJson(tag: String, assets: List<Pair<String, Long>>, isStable: Boolean = false): String =
    assets.joinToString(
        separator = ",",
        prefix = """{"tag_name": "$tag", "draft": false, "prerelease": ${!isStable},
            "html_url": "https://github.com/aequicor/Heartbeat/releases/tag/$tag", "assets": [""",
        postfix = "]}",
    ) { (name, size) ->
        """{"name": "$name", "size": $size, "browser_download_url": "$TRAIN_DOWNLOADS/$tag/$name"}"""
    }
