import io.aequicor.heartbeat.feature.harness.api.github.GitHubEntity
import io.aequicor.heartbeat.feature.harness.api.github.GitHubFailure
import io.aequicor.heartbeat.feature.harness.api.github.GitHubQuery
import io.aequicor.heartbeat.feature.harness.api.github.GitHubReadResult
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

val KEY = "board_state"
val STAGES = listOf("requirements", "design", "spec", "development", "testing", "acceptance", "released")
val STAGE_TITLES = mapOf(
    "requirements" to "Требования", "design" to "Дизайн", "spec" to "Спека",
    "development" to "Разработка", "testing" to "Тестирование", "acceptance" to "Приёмка", "released" to "Релиз"
)
val MARKER = "heartbeat:lifecycle"
val STAGE_LINE = Regex("Стадия:\\s*([a-z]+)")
val TYPE_LINE = Regex("<!--\\s*type:(defect|feedback|feature)\\s*-->")
val SEGMENT = Regex("^[A-Za-z0-9_.-]{1,100}$")

val MAX_FEATURES = 12
val MAX_FEEDBACK = 32
val MAX_PAGES = 4
val DEFAULT_OWNER = "aequicor"
val DEFAULT_NAME = "Heartbeat"

fun rootOf(v: JsonElement?): JsonObject = v as? JsonObject ?: JsonObject(emptyMap())
fun objs(o: JsonObject, k: String): List<JsonObject> = (o[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
fun arrOf(o: JsonObject, k: String): JsonArray = o[k] as? JsonArray ?: JsonArray(emptyList())
fun fStr(o: JsonObject, k: String): String = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
fun fLong(o: JsonObject, k: String): Long? = (o[k] as? JsonPrimitive)?.longOrNull
fun fLongOr(o: JsonObject, k: String, d: Long): Long = fLong(o, k) ?: d

fun argStr(c: ScriptUiCall, k: String): String = (c.arguments[k] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""
fun argLong(c: ScriptUiCall, k: String): Long? = (c.arguments[k] as? JsonPrimitive)?.longOrNull

fun nowStamp(): String = Instant.now().toString()

fun stageTitle(s: String): String = STAGE_TITLES[s] ?: s
fun validStage(s: String): Boolean = s in STAGES

fun stageFromBody(body: String): String? = STAGE_LINE.find(body)?.groupValues?.get(1)?.takeIf { validStage(it) }
fun kindFromBody(body: String): String? = TYPE_LINE.find(body)?.groupValues?.get(1)

fun activeEpic(issue: GitHubEntity.Issue): Boolean = issue.body.contains(MARKER) &&
    issue.state != "closed" && stageFromBody(issue.body) != "released"

// Only identities are consumed by the card and feedback classifier; remote titles stay on GitHub.
fun artifactRefs(artifacts: JsonArray): JsonArray = JsonArray(artifacts.mapNotNull { value ->
    val number = (value as? JsonObject)?.let { fLong(it, "number") }
    number?.let { buildJsonObject { put("number", it) } }
})

fun triagedIds(root: JsonObject): Set<Long> = arrOf(root, "triaged").mapNotNull {
    (it as? JsonPrimitive)?.longOrNull
}.toSet() + objs(root, "feedback").filter { fStr(it, "status") == "triaged" }.mapNotNull { fLong(it, "number") }

fun feedbackRows(root: JsonObject): List<JsonObject> = objs(root, "feedback").filter { fStr(it, "state") != "closed" }
    .sortedWith(compareBy<JsonObject> { if (fStr(it, "status") == "triaged") 1 else 0 }.thenBy { fLongOr(it, "number", Long.MAX_VALUE) })

fun repoOf(root: JsonObject): JsonObject = root["repo"] as? JsonObject
    ?: buildJsonObject { put("owner", DEFAULT_OWNER); put("name", DEFAULT_NAME) }

fun repoLine(root: JsonObject): String {
    val repo = repoOf(root)
    return "${fStr(repo, "owner")}/${fStr(repo, "name")}"
}

fun syncOf(root: JsonObject): JsonObject = root["sync"] as? JsonObject ?: JsonObject(emptyMap())

fun featureJson(epic: Long, title: String, stage: String, state: String, pr: Long, updatedAt: String, artifacts: JsonArray): JsonObject = buildJsonObject {
    put("epic", epic); put("title", title); put("stage", stage); put("state", state)
    put("pr", pr); put("updatedAt", updatedAt); put("artifacts", artifactRefs(artifacts))
}

fun withField(f: JsonObject, stage: String? = null, pr: Long? = null): JsonObject = buildJsonObject {
    put("epic", fLongOr(f, "epic", 0)); put("title", fStr(f, "title"))
    put("stage", stage ?: fStr(f, "stage").ifEmpty { "requirements" })
    put("state", fStr(f, "state").ifEmpty { "open" })
    put("pr", pr ?: fLongOr(f, "pr", 0)); put("updatedAt", nowStamp())
    put("artifacts", artifactRefs(arrOf(f, "artifacts")))
}

fun feedbackJson(number: Long, title: String, state: String, kind: String, status: String): JsonObject = buildJsonObject {
    put("number", number); put("title", title); put("state", state); put("kind", kind); put("status", status); put("updatedAt", nowStamp())
}

fun store(features: List<JsonObject>, feedback: List<JsonObject>, current: Long, root: JsonObject): JsonObject = buildJsonObject {
    put("features", JsonArray(features)); put("feedback", JsonArray(feedback))
    put("current", current); put("repo", repoOf(root)); put("sync", syncOf(root)); put("triaged", arrOf(root, "triaged"))
}

fun view(snapshot: io.aequicor.heartbeat.feature.harness.api.script.ScriptDataSnapshot): JsonObject {
    val root = rootOf(snapshot.value)
    val features = objs(root, "features")
    val current = fLong(root, "current") ?: 0L
    val cur = features.firstOrNull { fLong(it, "epic") == current }
    val curStage = cur?.let { fStr(it, "stage").ifEmpty { "requirements" } } ?: ""
    val openFeedback = feedbackRows(root)
    val line = repoLine(root)
    val sync = syncOf(root)
    return buildJsonObject {
        put("features", JsonArray(features.map { f ->
            val stage = fStr(f, "stage").ifEmpty { "requirements" }
            val epic = fLongOr(f, "epic", 0)
            buildJsonObject {
                put("epic", epic); put("title", fStr(f, "title").ifEmpty { "#$epic" })
                put("stage", stage); put("stage_title", stageTitle(stage))
                put("stage_no", STAGES.indexOf(stage) + 1); put("of_stages", STAGES.size)
                put("state", fStr(f, "state").ifEmpty { "open" })
                put("pr", fLongOr(f, "pr", 0)); put("artifact_count", objs(f, "artifacts").size)
                put("url", "https://github.com/$line/issues/$epic")
                put("is_current", if (epic == current) 1 else 0)
                put("can_pick", if (epic != current) 1 else 0)
                put("can_advance", if (STAGES.indexOf(stage) < STAGES.size - 1) 1 else 0)
            }
        }))
        put("current", current)
        put("current_title", cur?.let { fStr(it, "title") } ?: "")
        put("current_stage", curStage)
        put("current_stage_title", if (curStage.isEmpty()) "" else stageTitle(curStage))
        put("current_stage_no", if (curStage.isEmpty()) 0 else STAGES.indexOf(curStage) + 1)
        put("of_stages", STAGES.size)
        put("can_advance", if (cur != null && STAGES.indexOf(curStage) < STAGES.size - 1) 1 else 0)
        put("in_acceptance", features.count { fStr(it, "stage") == "acceptance" })
        put("feedback_pending", openFeedback.count { fStr(it, "status").ifEmpty { "new" } == "new" }); put("feedback_open", openFeedback.size)
        put("feedback", JsonArray(openFeedback.take(10).map { fb ->
            val kind = fStr(fb, "kind").ifEmpty { "unknown" }
            buildJsonObject {
                put("number", fLongOr(fb, "number", 0)); put("title", fStr(fb, "title"))
                put("kind", kind)
                put("kind_title", mapOf("defect" to "дефект", "feedback" to "фидбек").getOrDefault(kind, "вход"))
                put("status", fStr(fb, "status").ifEmpty { "new" })
                put("url", "https://github.com/$line/issues/${fLongOr(fb, "number", 0)}")
            }
        }))
        put("features_count", features.size)
        put("repo", line)
        put("sync_at", fStr(sync, "at"))
        put("sync_note", fStr(sync, "note"))
        put("revision", snapshot.revision)
    }
}

fun ghMessage(reason: GitHubFailure): String = when (reason) {
    GitHubFailure.Disabled -> "Тогл harness.github выключен (Настройки → Харнессы → GitHub-порт) — включите его"
    GitHubFailure.Unsupported -> "GitHub-порт не поддерживается на этой платформе"
    GitHubFailure.Access -> "gh не залогинен: выполните gh auth login"
    GitHubFailure.RateLimited -> "GitHub просит подождать — повторите позже"
    GitHubFailure.NotFound -> "Репозиторий не найден или недоступен"
    else -> "GitHub недоступен — повторите позже"
}

fun noChat(): ScriptUiResult.Error = ScriptUiResult.Error("Кнопка отправляет шаг в чат — откройте карточку в чате с харнессом")

fun saved(result: ScriptDataWriteResult): ScriptUiResult = when (result) {
    is ScriptDataWriteResult.Written -> ScriptUiResult.Value(view(result.snapshot))
    is ScriptDataWriteResult.Conflict -> ScriptUiResult.Error("Доска изменилась — повторите действие")
}

ui.method(ItemName("snapshot"), "Board snapshot: features, current feature, feedback queue, sync info") {
    ScriptUiResult.Value(view(data.read(KEY)))
}

ui.method(ItemName("add_feature"), "Register feature {title, epic, stage?}; also clears it from feedback") { call ->
    val title = argStr(call, "title")
    val epic = argLong(call, "epic")
    val stage = argStr(call, "stage")
    if (title.isEmpty() || title.length > 120) return@method ScriptUiResult.Error("Название фичи: 1–120 символов")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Укажите номер эпик-issue (положительное число)")
    if (stage.isNotEmpty() && !validStage(stage)) return@method ScriptUiResult.Error("Этап должен быть одним из: ${STAGES.joinToString()}")
    val snapshot = data.read(KEY)
    val root = rootOf(snapshot.value)
    val features = objs(root, "features")
    if (features.any { fLong(it, "epic") == epic }) return@method ScriptUiResult.Error("Фича #$epic уже на доске")
    if (features.size >= MAX_FEATURES) return@method ScriptUiResult.Error("На доске максимум $MAX_FEATURES фич")
    val current = fLong(root, "current") ?: 0L
    val next = store(
        features + featureJson(epic, title, stage.ifEmpty { "requirements" }, "open", 0L, nowStamp(), JsonArray(emptyList())),
        objs(root, "feedback").filter { fLong(it, "number") != epic },
        if (current == 0L) epic else current, root
    )
    saved(data.write(KEY, next, snapshot.revision))
}

ui.method(ItemName("remove_feature"), "Drop completed feature {epic}; active GitHub epics return on sync") { call ->
    val epic = argLong(call, "epic")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Укажите номер эпика")
    val snapshot = data.read(KEY)
    val root = rootOf(snapshot.value)
    val features = objs(root, "features")
    if (features.none { fLong(it, "epic") == epic }) return@method ScriptUiResult.Error("Фича #$epic не на доске")
    val rest = features.filter { fLong(it, "epic") != epic }
    val current = fLong(root, "current") ?: 0L
    val next = store(rest, objs(root, "feedback"),
        if (current == epic) (rest.firstOrNull()?.let { fLongOr(it, "epic", 0) } ?: 0L) else current, root)
    saved(data.write(KEY, next, snapshot.revision))
}

ui.method(ItemName("set_stage"), "Move feature {epic} to {stage}; proof stays on GitHub") { call ->
    val epic = argLong(call, "epic")
    val stage = argStr(call, "stage")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Укажите номер эпика")
    if (!validStage(stage)) return@method ScriptUiResult.Error("Этап должен быть одним из: ${STAGES.joinToString()}")
    val snapshot = data.read(KEY)
    val root = rootOf(snapshot.value)
    val features = objs(root, "features")
    if (features.none { fLong(it, "epic") == epic }) return@method ScriptUiResult.Error("Фича #$epic не на доске")
    val next = store(features.map { if (fLong(it, "epic") == epic) withField(it, stage = stage) else it },
        objs(root, "feedback"), fLong(root, "current") ?: 0L, root)
    saved(data.write(KEY, next, snapshot.revision))
}

ui.method(ItemName("link_pr"), "Attach pull request {pr} to feature {epic}") { call ->
    val epic = argLong(call, "epic")
    val pr = argLong(call, "pr")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Укажите номер эпика")
    if (pr == null || pr <= 0) return@method ScriptUiResult.Error("Укажите номер PR")
    val snapshot = data.read(KEY)
    val root = rootOf(snapshot.value)
    val features = objs(root, "features")
    if (features.none { fLong(it, "epic") == epic }) return@method ScriptUiResult.Error("Фича #$epic не на доске")
    val next = store(features.map { if (fLong(it, "epic") == epic) withField(it, pr = pr) else it },
        objs(root, "feedback"), fLong(root, "current") ?: 0L, root)
    saved(data.write(KEY, next, snapshot.revision))
}

ui.method(ItemName("set_current"), "Pick current feature {epic} for quick actions") { call ->
    val epic = argLong(call, "epic")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Укажите номер эпика")
    val snapshot = data.read(KEY)
    val root = rootOf(snapshot.value)
    val features = objs(root, "features")
    if (features.none { fLong(it, "epic") == epic }) return@method ScriptUiResult.Error("Фича #$epic не на доске")
    saved(data.write(KEY, store(features, objs(root, "feedback"), epic, root), snapshot.revision))
}

ui.method(ItemName("set_repo"), "Set GitHub repository {owner, name}; changing it clears the local board") { call ->
    val owner = argStr(call, "owner")
    val name = argStr(call, "name")
    if (!SEGMENT.matches(owner) || !SEGMENT.matches(name) || owner in listOf(".", "..") || name in listOf(".", ".."))
        return@method ScriptUiResult.Error("owner/name: 1–100 символов (буквы, цифры, . _ -)")
    val snapshot = data.read(KEY)
    val root = rootOf(snapshot.value)
    val same = repoLine(root).equals("$owner/$name", ignoreCase = true)
    val next = buildJsonObject {
        put("features", if (same) arrOf(root, "features") else JsonArray(emptyList()))
        put("feedback", if (same) arrOf(root, "feedback") else JsonArray(emptyList()))
        put("current", if (same) fLong(root, "current") ?: 0L else 0L)
        put("repo", buildJsonObject { put("owner", owner); put("name", name) })
        put("sync", if (same) syncOf(root) else JsonObject(emptyMap()))
        put("triaged", if (same) arrOf(root, "triaged") else JsonArray(emptyList()))
    }
    saved(data.write(KEY, next, snapshot.revision))
}

ui.method(ItemName("mark_feedback"), "Mark completed triage for feedback {number}; remote issue is untouched") { call ->
    val number = argLong(call, "number")
    val snapshot = data.read(KEY)
    val root = rootOf(snapshot.value)
    val feedback = objs(root, "feedback")
    if (number == null || feedback.none { fLong(it, "number") == number }) return@method ScriptUiResult.Error("Issue не в очереди")
    val marked = JsonObject(root + ("triaged" to JsonArray((triagedIds(root) + number).sorted().map { JsonPrimitive(it) })))
    val next = store(objs(root, "features"), feedback.map {
        if (fLong(it, "number") == number) JsonObject(it + ("status" to JsonPrimitive("triaged"))) else it
    }, fLong(root, "current") ?: 0L, marked)
    saved(data.write(KEY, next, snapshot.revision))
}

ui.method(ItemName("feedback_queue"), "Open feedback queue with kind markers") {
    val root = rootOf(data.read(KEY).value)
    val items = feedbackRows(root)
    ScriptUiResult.Value(buildJsonObject {
        put("count", items.size)
        put("items", JsonArray(items.take(20).map { fb ->
            buildJsonObject {
                put("number", fLongOr(fb, "number", 0)); put("title", fStr(fb, "title"))
                put("kind", fStr(fb, "kind").ifEmpty { "unknown" })
                put("status", fStr(fb, "status").ifEmpty { "new" })
            }
        }))
    })
}

ui.method(ItemName("sync_from_github"), "Refresh board from GitHub (read-only, bounded pages); needs harness.github and gh login") { _ ->
    val snapshot = data.read(KEY)
    val root = rootOf(snapshot.value)
    val repo = repoOf(root)
    val ghRepo = GitHubRepository(fStr(repo, "owner"), fStr(repo, "name"))
    val issues = mutableListOf<GitHubEntity.Issue>()
    var pageNumber: Int? = 1
    var incomplete = false
    repeat(MAX_PAGES) {
        val number = pageNumber ?: return@repeat
        when (val result = github.read(ghRepo, GitHubQuery.Issues(number))) {
            is GitHubReadResult.Read -> {
                issues.addAll(result.snapshot.entries.filterIsInstance<GitHubEntity.Issue>())
                pageNumber = result.snapshot.nextPage
            }
            is GitHubReadResult.Failed -> return@method ScriptUiResult.Error(ghMessage(result.reason))
        }
    }
    if (pageNumber != null) incomplete = true
    // An old tracked issue may be outside the bounded discovery window. Refresh it explicitly.
    val tracked = (objs(root, "features").mapNotNull { fLong(it, "epic") } +
        objs(root, "feedback").mapNotNull { fLong(it, "number") }).distinct()
    for (number in tracked.filter { n -> issues.none { it.number == n } }) {
        when (val result = github.read(ghRepo, GitHubQuery.Issue(number))) {
            is GitHubReadResult.Read -> issues.addAll(result.snapshot.entries.filterIsInstance<GitHubEntity.Issue>())
            is GitHubReadResult.Failed -> incomplete = true
        }
    }
    val trackedEpics = (objs(root, "features").mapNotNull { fLong(it, "epic") } +
        issues.filter { activeEpic(it) }.map { it.number }).distinct().take(MAX_FEATURES)
    val artifactsByEpic = mutableMapOf<Long, List<GitHubEntity.Issue>>()
    for (epic in trackedEpics) {
        val artifacts = mutableListOf<GitHubEntity.Issue>()
        var subPage: Int? = 1
        var failed = false
        repeat(MAX_PAGES) {
            val number = subPage ?: return@repeat
            when (val result = github.read(ghRepo, GitHubQuery.SubIssues(epic, number))) {
                is GitHubReadResult.Read -> {
                    artifacts.addAll(result.snapshot.entries.filterIsInstance<GitHubEntity.Issue>())
                    subPage = result.snapshot.nextPage
                }
                is GitHubReadResult.Failed -> { failed = true; subPage = null }
            }
        }
        if (failed || subPage != null) incomplete = true
        else artifactsByEpic[epic] = artifacts.distinctBy { it.number }
    }
    fun artifactsOf(epic: Long): JsonArray = artifactsByEpic[epic]?.map { a ->
        buildJsonObject { put("number", a.number) }
    }?.let { JsonArray(it) } ?: JsonArray(emptyList())
    val features = objs(root, "features").toMutableList()
    val feedback = objs(root, "feedback").toMutableList()
    val moves = mutableListOf<String>()
    for (issue in issues.distinctBy { it.number }) {
        val existing = features.firstOrNull { fLong(it, "epic") == issue.number }
        if (existing != null) {
            val oldStage = fStr(existing, "stage").ifEmpty { "requirements" }
            val newStage = stageFromBody(issue.body) ?: oldStage
            if (newStage != oldStage) moves.add("#${issue.number}: ${stageTitle(oldStage)}→${stageTitle(newStage)}")
            features[features.indexOf(existing)] = featureJson(
                issue.number, issue.title.ifEmpty { fStr(existing, "title") }, newStage, issue.state,
                fLongOr(existing, "pr", 0), nowStamp(),
                if (artifactsByEpic.containsKey(issue.number)) artifactsOf(issue.number) else arrOf(existing, "artifacts")
            )
        } else if (activeEpic(issue)) {
            if (features.size < MAX_FEATURES) features.add(featureJson(
                issue.number, issue.title, stageFromBody(issue.body) ?: "requirements", issue.state, 0L, nowStamp(), artifactsOf(issue.number)
            )) else incomplete = true
        }
    }
    // Also refresh artifacts when the epic itself could not be read.
    for (i in features.indices) {
        val feature = features[i]
        val epic = fLongOr(feature, "epic", 0)
        val refs = if (artifactsByEpic.containsKey(epic)) artifactsOf(epic) else artifactRefs(arrOf(feature, "artifacts"))
        features[i] = JsonObject(feature + ("artifacts" to refs))
    }
    val artifactNumbers = features.flatMap { objs(it, "artifacts") }.mapNotNull { fLong(it, "number") }.toSet()
    feedback.removeAll { fb ->
        val number = fLong(fb, "number")
        features.any { fLong(it, "epic") == number } || number in artifactNumbers ||
            issues.any { it.number == number && (it.state == "closed" || it.body.contains(MARKER)) }
    }
    // Remember processed ids while they are in the bounded observation window, including evicted rows.
    val observedOpen = issues.filter { it.state != "closed" && !it.body.contains(MARKER) && it.number !in artifactNumbers }
        .map { it.number }.toSet()
    val triaged = triagedIds(root).intersect(observedOpen)
    for (issue in issues.distinctBy { it.number }) {
        if (features.any { fLong(it, "epic") == issue.number } || issue.body.contains(MARKER) ||
            issue.number in artifactNumbers || issue.state == "closed") continue
        val existing = feedback.firstOrNull { fLong(it, "number") == issue.number }
        val kind = kindFromBody(issue.body)
        if (existing == null) {
            if (issue.number in triaged) continue
            if (feedback.size >= MAX_FEEDBACK) {
                val processed = feedback.indexOfFirst { fStr(it, "status") == "triaged" }
                if (processed >= 0) feedback.removeAt(processed)
            }
            if (feedback.size < MAX_FEEDBACK) feedback.add(feedbackJson(issue.number, issue.title, issue.state, kind ?: "unknown", "new"))
            else incomplete = true
        } else feedback[feedback.indexOf(existing)] = feedbackJson(
            issue.number, issue.title, issue.state, kind ?: fStr(existing, "kind").ifEmpty { "unknown" }, fStr(existing, "status").ifEmpty { "new" }
        )
    }
    val current = fLong(root, "current") ?: 0L
    val note = (if (incomplete) "Неполная синхронизация; " else "") +
        "issues: ${issues.size}, фич: ${features.size}, фидбек: ${feedback.size}" +
        if (moves.isNotEmpty()) "; этапы: ${moves.joinToString(", ")}" else ""
    val next = buildJsonObject {
        put("features", JsonArray(features)); put("feedback", JsonArray(feedback))
        put("current", if (features.any { fLong(it, "epic") == current }) current else 0L)
        put("repo", repo)
        put("triaged", JsonArray(triaged.sorted().map { JsonPrimitive(it) }))
        put("sync", buildJsonObject { put("at", nowStamp()); put("result", if (incomplete) "partial" else "ok"); put("note", note) })
    }
    // A concurrent edit (including switching repositories) invalidates the entire remote observation.
    when (val written = data.write(KEY, next, snapshot.revision)) {
        is ScriptDataWriteResult.Written -> ScriptUiResult.Value(view(written.snapshot))
        is ScriptDataWriteResult.Conflict -> ScriptUiResult.Error("Доска изменилась во время синхронизации — повторите")
    }
}

ui.method(ItemName("advance_stage"), "Send the stage_advance step to chat for {epic} or the current feature") { call ->
    val session = call.session ?: return@method noChat()
    val root = rootOf(data.read(KEY).value)
    val epic = argLong(call, "epic") ?: fLong(root, "current")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Выберите текущую фичу на доске (кнопка «Текущая»)")
    val feat = objs(root, "features").firstOrNull { fLong(it, "epic") == epic }
        ?: return@method ScriptUiResult.Error("Фича #$epic не на доске — добавьте её")
    val from = fStr(feat, "stage").ifEmpty { "requirements" }
    val idx = STAGES.indexOf(from)
    if (idx < 0 || idx >= STAGES.size - 1) return@method ScriptUiResult.Error("Фича #$epic уже на финальной стадии «${stageTitle(from)}»")
    val to = STAGES[idx + 1]
    val prompt = prompts.render(ItemName("stage_advance"), mapOf("issue" to epic.toString(), "from_stage" to from, "to_stage" to to, "evidence" to argStr(call, "evidence"), "repository" to repoLine(root)))
    sessions.send(session, prompt)
    ScriptUiResult.Value(view(data.read(KEY)))
}

ui.method(ItemName("kickoff_feature"), "Send the feature_kickoff step to chat; {topic?} is clarified in chat when empty") { call ->
    val session = call.session ?: return@method noChat()
    val prompt = prompts.render(ItemName("feature_kickoff"), mapOf("topic" to argStr(call, "topic"), "repository" to repoLine(rootOf(data.read(KEY).value))))
    sessions.send(session, prompt)
    ScriptUiResult.Value(view(data.read(KEY)))
}

ui.method(ItemName("triage_feedback"), "Send the triage_issue step for the oldest open feedback item or {number}") { call ->
    val session = call.session ?: return@method noChat()
    val root = rootOf(data.read(KEY).value)
    val asked = argLong(call, "number")
    val queue = feedbackRows(root)
    val target = if (asked != null && asked > 0) queue.firstOrNull { fLong(it, "number") == asked }
    else queue.filter { fStr(it, "status") == "new" }.minByOrNull { fLongOr(it, "number", Long.MAX_VALUE) }
    if (target == null) return@method ScriptUiResult.Error("Нет необработанного фидбека — синхронизируйте или выберите issue в списке")
    val prompt = prompts.render(ItemName("triage_issue"), mapOf("issue_number" to fLongOr(target, "number", 0).toString(), "repository" to repoLine(root)))
    sessions.send(session, prompt)
    ScriptUiResult.Value(view(data.read(KEY)))
}

ui.method(ItemName("request_acceptance"), "Send the acceptance_report step for the current feature or a feature in acceptance; {epic?, pr?}") { call ->
    val session = call.session ?: return@method noChat()
    val root = rootOf(data.read(KEY).value)
    val features = objs(root, "features")
    val asked = argLong(call, "epic")
    val current = fLong(root, "current")
    val feat = features.firstOrNull { fLong(it, "epic") == asked }
        ?: features.firstOrNull { fLong(it, "epic") == current && fStr(it, "stage") == "acceptance" }
        ?: features.firstOrNull { fStr(it, "stage") == "acceptance" }
        ?: return@method ScriptUiResult.Error("Нет фич на этапе приёмки")
    val epic = fLongOr(feat, "epic", 0)
    val pr = argLong(call, "pr") ?: fLong(feat, "pr") ?: 0L
    if (pr <= 0) return@method ScriptUiResult.Error("У фичи #$epic не указан PR — привяжите его (link_pr)")
    val prompt = prompts.render(ItemName("acceptance_report"), mapOf("pr" to pr.toString(), "issue" to epic.toString(), "repository" to repoLine(root)))
    sessions.send(session, prompt)
    ScriptUiResult.Value(view(data.read(KEY)))
}

ui.method(ItemName("prepare_release"), "Send the release_checklist step to chat; nothing is started automatically") { call ->
    val session = call.session ?: return@method noChat()
    val prompt = prompts.render(ItemName("release_checklist"), mapOf("repository" to repoLine(rootOf(data.read(KEY).value))))
    sessions.send(session, prompt)
    ScriptUiResult.Value(view(data.read(KEY)))
}

agent.tool(
    ItemName("lifecycle_stage"),
    "Product lifecycle board: read or move a feature stage. Without args lists features; {epic} reads one; {epic, stage} moves it (requirements, design, spec, development, testing, acceptance, released) and makes it current. Label swap and proof comment on GitHub stay with the agent.",
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("epic", buildJsonObject { put("type", "integer"); put("description", "Epic issue number") })
            put("stage", buildJsonObject {
                put("type", "string")
                put("description", "New stage; omit to only read")
                put("enum", JsonArray(STAGES.map { JsonPrimitive(it) }))
            })
        })
    }
) { call ->
    val epic = (call.arguments["epic"] as? JsonPrimitive)?.longOrNull
    val stage = (call.arguments["stage"] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""
    if (epic == null) {
        val feats = objs(rootOf(data.read(KEY).value), "features")
        if (feats.isEmpty()) return@tool ScriptToolResult("Доска пуста. Заведите фичу (ui_call add_feature) или синхронизируйте (ui_call sync_from_github).")
        val lines = feats.joinToString("\n") { f ->
            val st = fStr(f, "stage").ifEmpty { "requirements" }
            "#${fLongOr(f, "epic", 0)} [${stageTitle(st)}] ${fStr(f, "title")} (PR ${fLongOr(f, "pr", 0)})"
        }
        ScriptToolResult("Доска product_lifecycle (${feats.size}):\n$lines\nКонвейер: ${STAGES.joinToString(" → ") { stageTitle(it) }}")
    } else if (stage.isEmpty()) {
        val feat = objs(rootOf(data.read(KEY).value), "features").firstOrNull { fLong(it, "epic") == epic }
            ?: return@tool ScriptToolResult("Фича #$epic не на доске. Добавьте: ui_call add_feature {title, epic}.", true)
        val st = fStr(feat, "stage").ifEmpty { "requirements" }
        ScriptToolResult("#$epic «${fStr(feat, "title")}»: ${stageTitle(st)} (${STAGES.indexOf(st) + 1}/${STAGES.size}), PR ${fLongOr(feat, "pr", 0)}, артефактов ${objs(feat, "artifacts").size}.")
    } else {
        if (!validStage(stage)) return@tool ScriptToolResult("Этап должен быть одним из: ${STAGES.joinToString()}", true)
        val snapshot = data.read(KEY)
        val root = rootOf(snapshot.value)
        val features = objs(root, "features")
        if (features.none { fLong(it, "epic") == epic }) return@tool ScriptToolResult("Фича #$epic не на доске. Добавьте: ui_call add_feature {title, epic}.", true)
        val next = store(features.map { if (fLong(it, "epic") == epic) withField(it, stage = stage) else it }, objs(root, "feedback"), epic, root)
        if (data.write(KEY, next, snapshot.revision) is ScriptDataWriteResult.Conflict)
            return@tool ScriptToolResult("Доска изменилась — перечитайте и повторите", true)
        ScriptToolResult("Фича #${epic} → ${stageTitle(stage)} (${STAGES.indexOf(stage) + 1}/${STAGES.size}), сделана текущей. Не забудь: доказательство в эпике и метка stage на GitHub (gh issue edit).")
    }
}
