  it.each([['Write', true], ['Write', false], ['Edit', true], ['Edit', false]] as const)(
    'Heartbeat mandatory gate denies %s of the active plan file with gate registered first %s',
    async (toolName, gateFirst) => {
      let approvals = 0;
      const registerGate = () => disposables.add(executorEvents.executor.onBeforeExecuteTool((event) => {
        event.waitUntil(async () => {
          approvals++;
          return { veto: { output: 'Heartbeat denied', isError: true } };
        });
      }));
      if (gateFirst) registerGate();
      await enterPlan();
      if (!gateFirst) registerGate();
      const decision = await run(hookContext(toolName, {
        args: { path: PLAN_PATH },
        accesses: ToolAccesses.writeFile(PLAN_PATH),
      }));
      expect(approvals).toBe(1);
      expect(decision?.veto?.output).toBe('Heartbeat denied');
    },
  );

