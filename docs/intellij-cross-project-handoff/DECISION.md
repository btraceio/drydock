# Cross-project tabs: decision (revised)

**Go.** This replaces the earlier "park" call. Nothing in the spike rules the feature out.

## What the spike showed
- **H1 is a go.** IDE MCP calls that name their project return the right tree or a clean error, and the MVP now injects the routing header (MVP fix 1).
- **H2 is a go.** Variant (b), a new console with the buffered output replayed into it, kept the same PID and lost 0 lines. A warm jump took about 11 ms.
- **H3 failed the memory test, which was the wrong test.** Hosting costs about the same as opening windows: 106 % at N=3, and N=5 was noisy. The feature exists to cut window clutter and context switching, not to save memory, so cost parity is acceptable. jdtls starts only when a hosted agent first asks for usages, so idle hosted sessions don't pay for it. MVP fix 3 cuts the cold first answer from about 70 s to about 10 s.
- **H4:** file links from a hosted tab open in the owning window: about 0.3–0.8 s warm, about 3 s cold.

## Costs we accept
- Agent processes move from per-window ownership to an app-level registry. The window becomes a view of the session.
- New lifecycle rules for the cases in H5:
  - **Closing the host window:** ask first; hosted sessions keep running.
  - **Restart:** hosted tabs aren't restored.
  - **Attention:** raises the window currently showing the console.
  - **One console per session:** already covered by MVP fix 2.
  - **Stale open files:** refreshed when the agent's turn ends.
  - **Missing folder:** marks the session gone.
- The replay buffer needs a size cap.

## Open before build
- One human pass of the real-keyboard key matrix, including Esc, on a moved console.
- Reproduce the "second window empties IDE MCP text search" bug on a non-sandbox IDE. It affects the MVP too.
