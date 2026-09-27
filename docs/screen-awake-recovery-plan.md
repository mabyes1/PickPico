# Screen-awake recovery proposal

Status: proposed, not implemented in 0.17.1. Source reviewed 2026-09-28.

## Confirmed code paths

AgentTaskRuntime.awakeTaskCount counts every non-terminal task indefinitely.
The two-minute Home/Pico presence lease does not govern screen retention.
McpNodeService acquires an unbounded screen wake lock when any such task exists.
The keep-awake overlay also has no expiration. A command that never reaches its
finally block can leave activeAgentCommandCount positive and prevent idle release.
These paths explain how stale work can retain the screen, but device incident
logs have not been collected to identify which path occurred on September 28.

## Proposed behavior

- Separate task history, process lifetime and screen-retention permission.
- Give each task/operation a monotonic, expiring screen lease. Suggested starting
  default: five minutes without caller activity. Renew only the identified task
  through a new operation or explicit caller heartbeat; unrelated requests,
  relay transport heartbeats and local process polling do not renew stale work.
- Tool calls without a task receive their own bounded operation lease. Replace
  anonymous command counters with operation IDs/deadlines so late completions
  cannot release a newer call's lease or keep an old one alive.
- Long operations declare bounded deadlines. Expiry releases the screen, not the
  underlying job. Background development servers never retain the display merely
  because their process exists. Human-help waits receive a bounded display lease.
- Use timed Android wake locks only. Apply the same expiry to overlay retention.
  The timer must execute without another incoming tool request. A timer posted
  only to the main service Looper does not protect against that Looper hanging;
  for that failure class, move display ownership/watchdog into a small dedicated
  service process with no project code and no synchronous waits on the MCP host.
- On expiry, mark the screen lease expired and retain the task/history. Resume
  explicitly on later authorized activity. Normal finish releases immediately.
- Respect manual locking. Add a local Release screen hold control that revokes
  existing leases and rejects their automatic renewal until explicitly resumed.
- Expose lease owner, expiry, last activity and release reason in diagnostics.
  Release means restore Android's configured timeout, not force-lock the device.

## Focused acceptance checks

1. Caller disappears without task completion: display hold expires independently.
2. Command hangs or a stale completion arrives: new task leases remain correct.
3. Active caller renews its lease: screen stays on through a long operation.
4. Local revoke/manual lock wins over old task/process/transport activity.
5. Background server survives screen release; underlying timeout is unchanged.

Decide the five-minute default and whether to include dedicated-process ownership
in the first fix before implementing. Do not advertise this proposal as shipped.
