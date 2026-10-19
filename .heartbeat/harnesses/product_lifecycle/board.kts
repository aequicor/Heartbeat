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

fun repoOf(root: JsonObject): JsonObject = root["repo"] as? JsonObject
    ?: buildJsonObject { put("owner", DEFAULT_OWNER); put("name", DEFAULT_NAME) }

fun repoLine(root: JsonObject): String {
    val repo = repoOf(root)
    return "${fStr(repo, "owner")}/${fStr(repo, "name")}"
}

fun syncOf(root: JsonObject): JsonObject = root["sync"] as? JsonObject ?: JsonObject(emptyMap())

fun featureJson(epic: Long, title: String, stage: String, state: String, pr: Long, updatedAt: String, artifacts: JsonArray): JsonObject = buildJsonObject {
    put("epic", epic); put("title", title); put("stage", stage); put("state", state)
    put("pr", pr); put("updatedAt", updatedAt); put("artifacts", artifacts)
}

fun withField(f: JsonObject, stage: String? = null, pr: Long? = null): JsonObject = buildJsonObject {
    put("epic", fLongOr(f, "epic", 0)); put("title", fStr(f, "title"))
    put("stage", stage ?: fStr(f, "stage").ifEmpty { "requirements" })
    put("state", fStr(f, "state").ifEmpty { "open" })
    put("pr", pr ?: fLongOr(f, "pr", 0)); put("updatedAt", nowStamp())
    put("artifacts", arrOf(f, "artifacts"))
}

fun feedbackJson(number: Long, title: String, state: String, kind: String, status: String): JsonObject = buildJsonObject {
    put("number", number); put("title", title); put("state", state); put("kind", kind); put("status", status); put("updatedAt", nowStamp())
}

fun store(features: List<JsonObject>, feedback: List<JsonObject>, current: Long, root: JsonObject): JsonObject = buildJsonObject {
    put("features", JsonArray(features)); put("feedback", JsonArray(feedback))
    put("current", current); put("repo", repoOf(root)); put("sync", syncOf(root))
}

fun view(snapshot: io.aequicor.heartbeat.feature.harness.api.script.ScriptDataSnapshot): JsonObject {
    val root = rootOf(snapshot.value)
    val features = objs(root, "features")
    val current = fLong(root, "current") ?: 0L
    val cur = features.firstOrNull { fLong(it, "epic") == current }
    val curStage = cur?.let { fStr(it, "stage").ifEmpty { "requirements" } } ?: ""
    val openFeedback = objs(root, "feedback").filter { fStr(it, "state") != "closed" }
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
        put("feedback_open", openFeedback.size)
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

fun sendFailed(e: Exception): ScriptUiResult.Error =
    if (e is IllegalStateException) ScriptUiResult.Error("Чат временно не принимает сообщения харнесса (лимит): напишите в чат и повторите")
    else ScriptUiResult.Error("Не удалось отправить шаг в чат")

ui.method(ItemName("snapshot"), "Board snapshot: features, current feature, feedback queue, sync info") {
    ScriptUiResult.Value(view(data.read(KEY)))
}

ui.method(ItemName("add_feature"), "Register feature {title, epic, stage?} on the board; also clears it from the feedback queue") { call ->
    val title = argStr(call, "title")
    val epic = argLong(call, "epic")
    val stage = argStr(call, "stage")
    if (title.isEmpty() || title.length > 120) return@method ScriptUiResult.Error("Название фичи: 1–120 символов")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Укажите номер эпик-issue (положительное число)")
    if (stage.isNotEmpty() && !validStage(stage)) return@method ScriptUiResult.Error("Этап должен быть одним из: ${STAGES.joinToString()}")
    var problem: String? = null
    val saved = data.update(KEY) { previous ->
        val root = rootOf(previous)
        val features = objs(root, "features")
        val feedback = objs(root, "feedback")
        val current = fLong(root, "current") ?: 0L
        when {
            features.any { fLong(it, "epic") == epic } -> { problem = "Фича #$epic уже на доске"; store(features, feedback, current, root) }
            features.size >= MAX_FEATURES -> { problem = "На доске максимум $MAX_FEATURES фич"; store(features, feedback, current, root) }
            else -> store(
                features + featureJson(epic, title, stage.ifEmpty { "requirements" }, "open", 0L, nowStamp(), JsonArray(emptyList())),
                feedback.filter { fLong(it, "number") != epic },
                if (current == 0L) epic else current, root
            )
        }
    }
    problem?.let { return@method ScriptUiResult.Error(it) }
    ScriptUiResult.Value(view(saved))
}

ui.method(ItemName("remove_feature"), "Drop feature {epic} from the board (GitHub issue stays untouched)") { call ->
    val epic = argLong(call, "epic")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Укажите номер эпика")
    var problem: String? = null
    val saved = data.update(KEY) { previous ->
        val root = rootOf(previous)
        val features = objs(root, "features")
        if (features.none { fLong(it, "epic") == epic }) { problem = "Фича #$epic не на доске"; store(features, objs(root, "feedback"), fLong(root, "current") ?: 0L, root) }
        else {
            val rest = features.filter { fLong(it, "epic") != epic }
            val current = fLong(root, "current") ?: 0L
            store(rest, objs(root, "feedback"), if (current == epic) (rest.firstOrNull()?.let { fLongOr(it, "epic", 0) } ?: 0L) else current, root)
        }
    }
    problem?.let { return@method ScriptUiResult.Error(it) }
    ScriptUiResult.Value(view(saved))
}

ui.method(ItemName("set_stage"), "Move feature {epic} to {stage}; proof stays in the epic comments on GitHub") { call ->
    val epic = argLong(call, "epic")
    val stage = argStr(call, "stage")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Укажите номер эпика")
    if (!validStage(stage)) return@method ScriptUiResult.Error("Этап должен быть одним из: ${STAGES.joinToString()}")
    var problem: String? = null
    val saved = data.update(KEY) { previous ->
        val root = rootOf(previous)
        val features = objs(root, "features")
        if (features.none { fLong(it, "epic") == epic }) { problem = "Фича #$epic не на доске"; store(features, objs(root, "feedback"), fLong(root, "current") ?: 0L, root) }
        else store(features.map { if (fLong(it, "epic") == epic) withField(it, stage = stage) else it }, objs(root, "feedback"), fLong(root, "current") ?: 0L, root)
    }
    problem?.let { return@method ScriptUiResult.Error(it) }
    ScriptUiResult.Value(view(saved))
}

ui.method(ItemName("link_pr"), "Attach pull request {pr} to feature {epic}") { call ->
    val epic = argLong(call, "epic")
    val pr = argLong(call, "pr")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Укажите номер эпика")
    if (pr == null || pr <= 0) return@method ScriptUiResult.Error("Укажите номер PR")
    var problem: String? = null
    val saved = data.update(KEY) { previous ->
        val root = rootOf(previous)
        val features = objs(root, "features")
        if (features.none { fLong(it, "epic") == epic }) { problem = "Фича #$epic не на доске"; store(features, objs(root, "feedback"), fLong(root, "current") ?: 0L, root) }
        else store(features.map { if (fLong(it, "epic") == epic) withField(it, pr = pr) else it }, objs(root, "feedback"), fLong(root, "current") ?: 0L, root)
    }
    problem?.let { return@method ScriptUiResult.Error(it) }
    ScriptUiResult.Value(view(saved))
}

ui.method(ItemName("set_current"), "Make feature {epic} the current one for quick actions") { call ->
    val epic = argLong(call, "epic")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Укажите номер эпика")
    var problem: String? = null
    val saved = data.update(KEY) { previous ->
        val root = rootOf(previous)
        val features = objs(root, "features")
        if (features.none { fLong(it, "epic") == epic }) { problem = "Фича #$epic не на доске"; store(features, objs(root, "feedback"), fLong(root, "current") ?: 0L, root) }
        else store(features, objs(root, "feedback"), epic, root)
    }
    problem?.let { return@method ScriptUiResult.Error(it) }
    ScriptUiResult.Value(view(saved))
}

ui.method(ItemName("set_repo"), "Set GitHub repository {owner, name} the board syncs with") { call ->
    val owner = argStr(call, "owner")
    val name = argStr(call, "name")
    if (!SEGMENT.matches(owner) || !SEGMENT.matches(name)) return@method ScriptUiResult.Error("owner/name: 1–100 символов (буквы, цифры, . _ -)")
    val saved = data.update(KEY) { previous ->
        val root = rootOf(previous)
        buildJsonObject {
            put("features", arrOf(root, "features")); put("feedback", arrOf(root, "feedback"))
            put("current", fLong(root, "current") ?: 0L)
            put("repo", buildJsonObject { put("owner", owner); put("name", name) })
            put("sync", syncOf(root))
        }
    }
    ScriptUiResult.Value(view(saved))
}

ui.method(ItemName("feedback_queue"), "Open feedback queue with kind markers") {
    val root = rootOf(data.read(KEY).value)
    val items = objs(root, "feedback").filter { fStr(it, "state") != "closed" }
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

ui.method(ItemName("sync_from_github"), "Refresh board from GitHub (read-only): issues page 1 + sub-issues of tracked epics; needs harness.github toggle and gh login") { _ ->
    val root = rootOf(data.read(KEY).value)
    val repo = repoOf(root)
    val ghRepo = try { GitHubRepository(fStr(repo, "owner"), fStr(repo, "name")) } catch (e: Exception) { null }
        ?: return@method ScriptUiResult.Error("Неверный репозиторий на доске — задайте set_repo")
    val page = try { github.read(ghRepo, GitHubQuery.Issues(1)) } catch (e: Exception) { null }
    val issues: List<GitHubEntity.Issue> = when (val r = page) {
        is GitHubReadResult.Read -> r.snapshot.entries.filterIsInstance<GitHubEntity.Issue>()
        is GitHubReadResult.Failed -> return@method ScriptUiResult.Error(ghMessage(r.reason))
        null -> return@method ScriptUiResult.Error("GitHub-порт недоступен")
    }
    val trackedEpics = objs(root, "features").mapNotNull { fLong(it, "epic") }
    val artifactsByEpic = mutableMapOf<Long, List<GitHubEntity.Issue>>()
    for (epic in trackedEpics) {
        val sub = try {
            when (val sr = github.read(ghRepo, GitHubQuery.SubIssues(epic))) {
                is GitHubReadResult.Read -> sr.snapshot.entries.filterIsInstance<GitHubEntity.Issue>()
                is GitHubReadResult.Failed -> null
            }
        } catch (e: Exception) { null }
        if (sub != null) artifactsByEpic[epic] = sub
    }
    fun artifactsOf(epic: Long): JsonArray = artifactsByEpic[epic]?.map { a ->
        buildJsonObject { put("number", a.number); put("title", a.title); put("state", a.state) }
    }?.let { JsonArray(it) } ?: JsonArray(emptyList())
    val saved = data.update(KEY) { previous ->
        val prev = rootOf(previous)
        val features = objs(prev, "features").toMutableList()
        val feedback = objs(prev, "feedback").toMutableList()
        val moves = mutableListOf<String>()
        for (issue in issues) {
            val tracked = features.firstOrNull { fLong(it, "epic") == issue.number }
            if (tracked != null) {
                val oldStage = fStr(tracked, "stage").ifEmpty { "requirements" }
                val newStage = stageFromBody(issue.body) ?: oldStage
                if (newStage != oldStage) moves.add("#${issue.number}: ${stageTitle(oldStage)}→${stageTitle(newStage)}")
                val i = features.indexOf(tracked)
                features[i] = buildJsonObject {
                    put("epic", issue.number)
                    put("title", issue.title.ifEmpty { fStr(tracked, "title") })
                    put("stage", newStage); put("state", issue.state)
                    put("pr", fLongOr(tracked, "pr", 0)); put("updatedAt", nowStamp())
                    put("artifacts", if (artifactsByEpic.containsKey(issue.number)) artifactsOf(issue.number) else arrOf(tracked, "artifacts"))
                }
            } else if (issue.body.contains(MARKER) && features.size < MAX_FEATURES) {
                features.add(featureJson(issue.number, issue.title, stageFromBody(issue.body) ?: "requirements", issue.state, 0L, nowStamp(), artifactsOf(issue.number)))
            }
        }
        for (issue in issues) {
            if (features.any { fLong(it, "epic") == issue.number } || issue.body.contains(MARKER)) continue
            if (issue.state == "closed") continue
            val existing = feedback.firstOrNull { fLong(it, "number") == issue.number }
            val kind = kindFromBody(issue.body)
            if (existing == null) {
                if (feedback.size < MAX_FEEDBACK) feedback.add(feedbackJson(issue.number, issue.title, issue.state, kind ?: "unknown", "new"))
            } else {
                val i = feedback.indexOf(existing)
                feedback[i] = feedbackJson(issue.number, issue.title, issue.state, kind ?: fStr(existing, "kind").ifEmpty { "unknown" }, fStr(existing, "status").ifEmpty { "new" })
            }
        }
        val current = fLong(prev, "current") ?: 0L
        val stillThere = features.any { fLong(it, "epic") == current }
        val note = "issues: ${issues.size}, фич: ${features.size}, фидбек: ${feedback.size}" +
            if (moves.isNotEmpty()) "; этапы: ${moves.joinToString(", ")}" else ""
        val newSync = buildJsonObject { put("at", nowStamp()); put("result", "ok"); put("note", note) }
        buildJsonObject {
            put("features", JsonArray(features)); put("feedback", JsonArray(feedback))
            put("current", if (stillThere || current == 0L) current else 0L)
            put("repo", repoOf(prev)); put("sync", newSync)
        }
    }
    ScriptUiResult.Value(view(saved))
}

ui.method(ItemName("advance_stage"), "Send the stage_advance step to chat for {epic} or the current feature") { call ->
    val root = rootOf(data.read(KEY).value)
    val epic = argLong(call, "epic") ?: fLong(root, "current")
    if (epic == null || epic <= 0) return@method ScriptUiResult.Error("Выберите текущую фичу на доске (кнопка «Текущая»)")
    val feat = objs(root, "features").firstOrNull { fLong(it, "epic") == epic }
        ?: return@method ScriptUiResult.Error("Фича #$epic не на доске — добавьте её")
    val from = fStr(feat, "stage").ifEmpty { "requirements" }
    val idx = STAGES.indexOf(from)
    if (idx < 0 || idx >= STAGES.size - 1) return@method ScriptUiResult.Error("Фича #$epic уже на финальной стадии «${stageTitle(from)}»")
    val to = STAGES[idx + 1]
    val prompt = try { prompts.render(ItemName("stage_advance"), mapOf("issue" to epic.toString(), "from_stage" to from, "to_stage" to to, "evidence" to argStr(call, "evidence"))) }
        catch (e: Exception) { null } ?: return@method ScriptUiResult.Error("Шаблон stage_advance недоступен")
    val session = call.session ?: return@method noChat()
    try { sessions.send(session, prompt) } catch (e: Exception) { return@method sendFailed(e) }
    ScriptUiResult.Value(view(data.read(KEY)))
}

ui.method(ItemName("kickoff_feature"), "Send the feature_kickoff step to chat; {topic?} is clarified in chat when empty") { call ->
    val prompt = try { prompts.render(ItemName("feature_kickoff"), mapOf("topic" to argStr(call, "topic"))) }
        catch (e: Exception) { null } ?: return@method ScriptUiResult.Error("Шаблон feature_kickoff недоступен")
    val session = call.session ?: return@method noChat()
    try { sessions.send(session, prompt) } catch (e: Exception) { return@method sendFailed(e) }
    ScriptUiResult.Value(view(data.read(KEY)))
}

ui.method(ItemName("triage_feedback"), "Send the triage_issue step for the oldest open feedback item or {number}") { call ->
    val root = rootOf(data.read(KEY).value)
    val asked = argLong(call, "number")
    val queue = objs(root, "feedback").filter { fStr(it, "state") != "closed" }
    val target = if (asked != null && asked > 0) queue.firstOrNull { fLong(it, "number") == asked }
    else queue.firstOrNull { fStr(it, "status") == "new" } ?: queue.firstOrNull()
    if (target == null) return@method ScriptUiResult.Error("Очередь фидбека пуста — нажмите «Синхронизация»")
    val prompt = try { prompts.render(ItemName("triage_issue"), mapOf("issue_number" to fLongOr(target, "number", 0).toString())) }
        catch (e: Exception) { null } ?: return@method ScriptUiResult.Error("Шаблон triage_issue недоступен")
    val session = call.session ?: return@method noChat()
    try { sessions.send(session, prompt) } catch (e: Exception) { return@method sendFailed(e) }
    ScriptUiResult.Value(view(data.read(KEY)))
}

ui.method(ItemName("request_acceptance"), "Send the acceptance_report step for the current feature or a feature in acceptance; {epic?, pr?}") { call ->
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
    val prompt = try { prompts.render(ItemName("acceptance_report"), mapOf("pr" to pr.toString(), "issue" to epic.toString())) }
        catch (e: Exception) { null } ?: return@method ScriptUiResult.Error("Шаблон acceptance_report недоступен")
    val session = call.session ?: return@method noChat()
    try { sessions.send(session, prompt) } catch (e: Exception) { return@method sendFailed(e) }
    ScriptUiResult.Value(view(data.read(KEY)))
}

ui.method(ItemName("prepare_release"), "Send the release_checklist step to chat; nothing is started automatically") { call ->
    val prompt = try { prompts.render(ItemName("release_checklist"), emptyMap()) }
        catch (e: Exception) { null } ?: return@method ScriptUiResult.Error("Шаблон release_checklist недоступен")
    val session = call.session ?: return@method noChat()
    try { sessions.send(session, prompt) } catch (e: Exception) { return@method sendFailed(e) }
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
        var missing = false
        val saved = data.update(KEY) { previous ->
            val root = rootOf(previous)
            val features = objs(root, "features")
            if (features.none { fLong(it, "epic") == epic }) { missing = true; previous }
            else store(features.map { if (fLong(it, "epic") == epic) withField(it, stage = stage) else it }, objs(root, "feedback"), epic, root)
        }
        if (missing) return@tool ScriptToolResult("Фича #$epic не на доске. Добавьте: ui_call add_feature {title, epic}.", true)
        ScriptToolResult("Фича #${epic} → ${stageTitle(stage)} (${STAGES.indexOf(stage) + 1}/${STAGES.size}), сделана текущей. Не забудь: доказательство в эпике и метка stage на GitHub (gh issue edit).")
    }
}
