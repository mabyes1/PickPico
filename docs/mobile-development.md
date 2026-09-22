# Phone development workbench

The `dev` MCP tool provides a phone-local JavaScript development loop. It is also
available as `command_run(commandId="dev")`. Existing `node.*`, phone tools and UI
remain separate. Clients that cache tool definitions must refresh discovery.

## Typical workflow

All direct calls include the caller's `agent` identity. Create a PickPico task
before multi-step phone work and finish it afterward to release the screen hold.

1. `dev {action:"project.create", project:"demo"}`
2. `files.write` with `project`, project-relative `path`, and UTF-8 `content`.
   Write `package.json` with the required dependencies and a JS entry file.
3. `install` with `project` returns a `jobId`. Poll `job.status` and read
   `job.logs` with `stream:"stdout"` or `"stderr"`. Use `nextOffset` for paging.
4. `run` with `project`, `entry`, optional `args` and `timeoutMs` returns a job.
   Server readiness must be checked through logs and an HTTP request.
5. `tunnel.open` requires `project`, `serviceJobId`, `port`, `public:true`, and
   optionally `ttlSeconds`. Poll the tunnel job for its HTTPS URL. Request the
   URL from an external client and compare the expected response before sharing.
6. `job.stop` with the tunnel job ID closes exposure; stop the service separately.
   A tunnel also closes when its TTL expires or its owning service stops.

`info`, `project.list`, `files.list`, and `jobs.list` provide discovery.
Job records and logs survive MCP reconnection. Abrupt process loss is reported as
`interrupted` with unknown exit code; a normal exit, exception, or `process.exit`
retains its code. Timeout is 124 and output-budget termination is 125.

## Dependencies and execution

- Embedded nodejs-mobile 18.20.4; bundled npm 10.8.2 Arborist installer.
- Installs dependencies and writes `package-lock.json`. Installation scripts and
  executable links are disabled. Native build/lifecycle requirements are reported
  as unsupported with exit code 2 rather than represented as a successful build.
- JavaScript entries support CommonJS and ES modules. Arbitrary shell-based npm
  scripts, Git, Python, native toolchains and desktop binaries are not bundled.
- Four disposable Android process slots (`:dev0` through `:dev3`) keep project
  crashes and exit calls outside the main phone-control process and legacy `:node`.
- This is crash isolation, not a security sandbox: jobs retain the app's Android
  UID and permissions. Use trusted project code and packages.
- Jobs default to ten minutes (installation: three minutes), configurable up to
  one hour. V8 old-space is capped at 192 MiB per job; this is not total-process
  memory. A watchdog stops jobs when captured output exceeds 8 MiB, sampled every
  500 ms, so the file can briefly exceed that threshold.
- Android can still stop processes under resource pressure. Device sleep policy
  is unchanged; long-running production hosting is outside this initial scope.

## Source versions

`snapshot.create` returns a snapshot UUID. `snapshot.list`, `snapshot.diff` and
`snapshot.restore` use `project`; diff/restore additionally use `snapshotId`.
Snapshots exclude `node_modules` and `.git`. Source is limited to 50 MiB, 12
directory levels and roughly 2,000 entries. Incomplete snapshots are not listed.
Diff reports added/deleted/modified file paths.

Restore preserves the previous project tree, including dependencies, under the
returned `dev-history/backup-*` directory. Reinstall dependencies in the restored
project before running it. File writes, installation and snapshots require the
project's jobs to be stopped. Snapshot storage is retained until explicitly
cleaned up; these snapshots do not provide Git branches or remote synchronization.

## Temporary exposure

The current provider is localtunnel 2.0.2 at `https://localtunnel.me`. The target is
phone loopback; port 8765 (PickPico MCP) is excluded. Exposure lasts 30–3600 seconds
and remains explicitly public. Provider availability and browser consent pages
are outside PickPico's control. `externallyVerified:false` means the tool has not
performed an independent external check; allocation alone never proves reachability.

## Development checks

`npm test --prefix devtools` tests runner execution, stderr and exit semantics.
`scripts/build-debug.ps1` runs Android unit tests and assembles the signed debug APK.
Gradle packages the pinned dependency bundle automatically using host npm.
Real-device checks must additionally cover package installation, concurrent jobs,
phone-control continuity, HTTP reachability, stop/timeout, and snapshot restore.
