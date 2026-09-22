# Mobile development verification — 2026-09-22

Development branch: `codex/mobile-dev-workbench`.
Final installed build: `0.17.1-dev` (126), Samsung SM-S9110, Android 16/API 36.
APK SHA-256: `94858f32bd065009f663881dd84ce3a579655d2bf65f13c6fc38c74990f7f602`.
No stable update channel was changed by this work.

## Automated checks

- Android: 212 unit tests passed; signed debug APK assembled successfully.
- Node runner: 4 tests passed (cwd/argv, thrown exception, exitCode, immediate exit).
- Regression tests include project path traversal, backup-preserving snapshot
  restore, UTF-8 paging, dead job/slot reuse and persisted exit receipts.

## Real phone checks

The isolated project `pico-e2e-0922` was created through the new `dev` MCP tool.
The current client cached old tool definitions, so calls used authenticated local
MCP through the phone's existing shell tool. Tokens were not printed or persisted
outside the device. Newly refreshed clients can call `dev` directly.

| Feature | Observed result |
| --- | --- |
| Dependencies | Express 4.21.2 installed on the phone, package-lock written, exit 0, compatible=true. Reinstalled successfully after restore on build 126. |
| Output and errors | Separate stdout and stderr retained; intentional exception included source location and stack, exit 1. Immediate process.exit(7) recorded as exit 7 on final build. |
| Job control | Server and failure fixture used separate process slots. Stopping the old failed job did not stop the new tunnel using its former slot. One-second deadline returned timed_out/124. |
| Source versions | Snapshot excluded node_modules; changing server.cjs produced modified diff. Restore recovered original server source and retained the changed tree in a backup. Restored server ran successfully. |
| Public exposure | External Windows HTTPS request returned HTTP 200 and exact fixture JSON from the phone. Explicit tunnel stop prevented further serving. On final build, stopping the owning server automatically closed its tunnel before TTL. External recheck returned 502 rather than fixture data. |
| Main app continuity | Final build retained the same main process start timestamp throughout testing; relay stayed connected with zero reconnects. Legacy node.status remained stopped with its original workspace entry. |

Final fixture response: `{"pico":"phone-development-0922","version":1}`.
Final tunnel allocated until 07:16:12 UTC; server stopped at 07:16:01 UTC and
tunnel completed/closed at 07:16:03 UTC, proving owner-stop cleanup rather than TTL.
Temporary URLs are deliberately not retained as active links.

All ten development job records were inactive at final inspection. Temporary
desktop APK download servers were stopped after verified download. Test project,
logs and backup remain available for inspection. The screen-hold task was marked
completed after testing; display-timeout settings were never changed.

## Boundaries

This validates the minimal JavaScript development loop, not arbitrary npm/native
toolchains or continuous production hosting. Snapshot versions are not Git.
Localtunnel URL allocation and external verification remain distinct: the external
check was performed during this acceptance test, not inferred by the tool.
