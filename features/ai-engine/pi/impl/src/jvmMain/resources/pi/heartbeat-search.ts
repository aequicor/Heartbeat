import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { Type } from "@sinclair/typebox";

const origin = process.env.HEARTBEAT_SEARCH_BRIDGE_URL;
const token = process.env.HEARTBEAT_SEARCH_BRIDGE_TOKEN;

async function call(name: string, args: object, signal: AbortSignal) {
  if (!origin || !token) throw new Error("Heartbeat search bridge is unavailable");
  const response = await fetch(`${origin}/execute`, {
    method: "POST",
    headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ name, arguments: args }),
    signal,
  });
  if (!response.ok) throw new Error("Heartbeat search bridge failed");
  const result = await response.json() as { success: boolean; text: string };
  if (!result.success) throw new Error(result.text);
  return { content: [{ type: "text" as const, text: result.text }], details: { success: result.success } };
}

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "web_search", label: "Web search",
    description: "Find web sources and citation URLs for a query.",
    parameters: Type.Object({ query: Type.String(), count: Type.Optional(Type.Integer({ minimum: 1, maximum: 20 })) }),
    async execute(_id, params, signal) { return call("web_search", params, signal); },
  });
  pi.registerTool({
    name: "web_fetch", label: "Read web page",
    description: "Read the text content of one URL.",
    parameters: Type.Object({ url: Type.String() }),
    async execute(_id, params, signal) { return call("web_fetch", params, signal); },
  });
}
