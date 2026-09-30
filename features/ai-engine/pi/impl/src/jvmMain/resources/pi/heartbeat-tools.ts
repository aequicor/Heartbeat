import { Type } from "@sinclair/typebox";

const origin = process.env.HEARTBEAT_AGENT_TOOLS_URL;
const token = process.env.HEARTBEAT_AGENT_TOOLS_TOKEN;
const specs = JSON.parse(process.env.HEARTBEAT_AGENT_TOOL_SPECS ?? "[]") as Array<{
  name: string; description: string; inputSchema: object;
}>;
const instructions = process.env.HEARTBEAT_AGENT_TOOL_INSTRUCTIONS ?? "";

/** Hosted calls have one Heartbeat trust gate, and never use Pi's native approval hook. */
export default function (pi: any) {
  for (const spec of specs) {
    pi.registerTool({
      name: spec.name, label: spec.name, description: spec.description,
      parameters: Type.Unsafe(spec.inputSchema),
      async execute(_id: string, params: object, signal: AbortSignal) {
        if (!origin || !token) throw new Error("Heartbeat tools are unavailable");
        const response = await fetch(`${origin}/execute`, {
          method: "POST",
          headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json" },
          body: JSON.stringify({ name: spec.name, arguments: params }), signal,
        });
        if (!response.ok) throw new Error("Heartbeat tool bridge failed");
        const result = await response.json() as { success: boolean; text: string };
        if (!result.success) throw new Error(result.text);
        return { content: [{ type: "text", text: result.text }], details: { success: true } };
      },
    });
  }
  if (instructions) pi.on("before_agent_start", async (event: any) => ({
    systemPrompt: `${event.systemPrompt}\n${instructions}`,
  }));
}
