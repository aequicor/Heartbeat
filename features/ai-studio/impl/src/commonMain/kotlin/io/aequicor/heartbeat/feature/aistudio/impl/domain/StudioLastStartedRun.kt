package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import kotlinx.coroutines.flow.Flow

/** Last confirmed ordinary human native submission, owned by the profile rather than a screen or engine probe. */
internal interface StudioLastStartedRun {
    /** Observes the one saved snapshot; reading never starts a native session. */
    fun observe(): Flow<StudioSessionSettings?>

    /** Latest acceptance since profile creation, available before any observer receives it. */
    fun latest(): StudioSessionSettings? = null

    /** Captures acceptance synchronously; profile-owned persistence never delays native ownership. */
    fun capture(settings: StudioSessionSettings)
}
