/** Heartbeat's mandatory permission boundary for the pinned Kimi engine. */
import {
  runAcpServer as runUpstreamAcpServer,
  type RunAcpServerOptions,
} from '@moonshot-ai/acp-server';
import {
  IAgentPermissionPolicyService,
  LifecycleScope,
  overrideScopedService,
  type PermissionPolicyEvaluation,
} from '@moonshot-ai/agent-core-v2';

/** Replaces the entire native policy chain, including persisted allows and session approval history. */
export class HeartbeatPermissionPolicy implements IAgentPermissionPolicyService {
  declare readonly _serviceBrand: undefined;

  async evaluate(): Promise<PermissionPolicyEvaluation> {
    const deny = () => ({ kind: 'deny' as const, message: 'Heartbeat permission decision required' });
    return {
      policyName: 'heartbeat',
      result: {
        kind: 'ask',
        resolveApproval: (answer) => answer.decision === 'approved' ? { kind: 'approve' } : deny(),
        resolveError: deny,
        resolveNonInteractive: deny,
      },
    };
  }
}

export function installHeartbeatPermissions(): void {
  // Agent-scoped overrides apply to the main agent and every subsequently created subagent.
  // Missing bindings throw before bootstrap; a changed upstream API must never silently use native policy.
  overrideScopedService(LifecycleScope.Agent, IAgentPermissionPolicyService, HeartbeatPermissionPolicy);
}

export async function runAcpServer(options: RunAcpServerOptions = {}): Promise<void> {
  installHeartbeatPermissions();
  await runUpstreamAcpServer(options);
}
