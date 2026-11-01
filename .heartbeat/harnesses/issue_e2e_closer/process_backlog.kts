workflow { input ->
    val issueNumber = (input["issue_number"] as? JsonPrimitive)?.content?.trim().orEmpty()
    val target = if (issueNumber.matches(Regex("[0-9]+"))) {
        "issue #$issueNumber"
    } else {
        "автоматический выбор следующей подходящей открытой issue"
    }
    val brief = skill(ItemName("process_issue")) + "\n\n" +
        prompt(ItemName("process_next"), mapOf("run_mode" to target))
    val report = agent(brief, AgentOptions("Heartbeat issue + e2e: $target"))
    buildJsonObject {
        put("target", target)
        put("report", report)
    }
}
