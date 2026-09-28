package io.aequicor.heartbeat.feature.questionnaire.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.impl.domain.QuestionnaireStorage
import kotlinx.serialization.builtins.ListSerializer

private val QuestionnaireSpec = KeyValueSpec("questionnaire")
private val PendingKey = jsonKey("pending", ListSerializer(Questionnaire.serializer()))

/** Open questions in the profile key-value store; they hold question texts, never credentials. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class KeyValueQuestionnaireStorage(
    @ForScope(ProfileScope::class) stores: DataStores,
) : QuestionnaireStorage {
    private val log = Log.tag("QuestionnaireStorage")
    private val store = stores.keyValue(QuestionnaireSpec)

    override suspend fun load(): List<Questionnaire> {
        log.d { "load pending questions" }
        return store.get(PendingKey).orEmpty()
    }

    override suspend fun save(pending: List<Questionnaire>) {
        log.d { "save pending questions count=${pending.size}" }
        if (pending.isEmpty()) store.remove(PendingKey) else store.set(PendingKey, pending)
    }
}
