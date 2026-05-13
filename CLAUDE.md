# LLM Agent Rules — yuyu-clauds Fork

This is a fork of [`danielealbano/android-remote-control-mcp`](https://github.com/danielealbano/android-remote-control-mcp) maintained by yuyu-clauds for an Android remote control app on yuyu's OnePlus Ace 5 Pro (ColorOS 15). We're modifying the transport layer to Supabase Realtime + Storage (replacing upstream's HTTP server + Cloudflare/Ngrok tunnel approach) while keeping the 55 MCP tools intact.

**This fork will NOT be pushed back to upstream.** Upstream's strict dev workflow — `docs/plans/`, mandatory `plan-reviewer` / `code-reviewer` subagents, no-TODO rule, full-feature-at-once, mandatory tests-with-each-change — applies to upstream maintainer's contribution path. We have removed those rules for this private fork doing transport surgery; skeleton commits and incremental development are explicitly OK.

The original upstream CLAUDE.md is preserved in git history.

---

## Rules (binding for this fork)

1. **No AI attribution** in commits, code comments, PRs, or anywhere in this repo. No `Co-Authored-By`, no `Generated with`, no Claude/Anthropic/AI tooling references. This rule is preserved from upstream.
2. **Tests required at "done"** — skeleton commits with TODOs are OK during incremental development; tests must be added before declaring a user story / feature complete.
3. **Lint clean at done** — `ktlint` + `detekt` must pass before declaring a feature done. Skeleton commits can defer.
4. **No partial code at "done"** — TODO markers are OK during incremental work, must be resolved before marking the feature done.
5. **Reference doc**: `/home/admin/claude-memory/android-app/implementation-plan-v0.md` is the working plan for this fork. Upstream's `docs/plans/` is preserved but not used.

## Workflow (relaxed from upstream)

- Skeleton commit: OK during incremental development. Mark TODOs explicitly with `TODO(day N)` for traceability.
- Plan: linear plan in `implementation-plan-v0.md` outside this repo (in `/home/admin/claude-memory/android-app/`). Update it as design evolves.
- No subagent gates: upstream's `plan-reviewer` / `code-reviewer` subagents are upstream tooling we don't run.
- Branch naming: `feature/<short-name>` (current: `feature/supabase-transport`).
- Commit at logical boundaries; push to `origin` (the yuyu-clauds fork) regularly.

## Upstream rules retained as good practice (when practical)

Preserved in git history under `upstream/main`. We follow these where practical, but they are not enforced as ABSOLUTE during incremental development:

- **Service lifecycle**: foreground services call `startForeground()` within 5s, clean up resources in `onDestroy()`.
- **Idempotency**: MCP tool calls safe to retry.
- **Settings repository pattern**: DataStore access through `SettingsRepository`.
- **Anti-prompt-injection**: device-derived content tools use `McpToolUtils.untrustedTextResult()` / `untrustedImageResult()`.
- **No root**: never implement functionality requiring root or hidden APIs.
- **No git stash before checkout**, no destructive git operations without explicit consent.

For the full rationale and detailed style rules on these topics, see upstream's CLAUDE.md in git history (`git show upstream/main:CLAUDE.md`).

## Our transport surgery scope

- Replace: `services/mcp/McpServerService.kt` Ktor HTTP server → adds a parallel Supabase Realtime client subscription as alternative entry to the same MCP tool registry.
- Add: `services/transport/SupabaseRealtimeClient.kt`, `services/transport/RealtimeMcpBridge.kt`, `services/transport/HeartbeatScheduler.kt`, `services/security/SensitivePageDetector.kt`.
- Keep: all 55 MCP tool implementations in `services/accessibility/`, `services/apps/`, `services/camera/`, `services/notification/`, etc. — fully reused.
- See `implementation-plan-v0.md` for the file-by-file diff plan.
