package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res

/** Reads packaged skills and their references through the generated Compose resource accessor. */
internal suspend fun readAuthoringDocument(resource: String): String =
    Res.readBytes("files/skills/$resource").decodeToString()
