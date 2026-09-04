# PickPico IA and UX structure

This structure follows Product & Experience Guideline 1.3. It changes how existing features are grouped and described; it does not change capability behavior, permissions, approval enforcement, or MCP transport.

## Product model

PickPico is an Android-first Mobile Agent Node. The UI should answer four questions in this order:

1. Is the Node available to an agent?
2. What needs the owner's attention?
3. What can the agent access on this phone?
4. How is the product configured?

HUMAN HELP is a signature interaction inside PickPico, not the product's top-level identity. The optional physical Dock is not part of the software navigation.

## Top-level navigation

| Destination | User question | Content |
| --- | --- | --- |
| Home | Is my PickPico ready? | Node status, connection summary, approval summary, agent-access readiness, recent activity |
| Activity | What happened or needs review? | Agent requests, messages, approval and HUMAN HELP handoff history |
| Capabilities | What can the agent use? | Execute, Sense, Interact, and Hyper access grouped by capability model |
| Settings | How should PickPico behave? | Approval policy, remote connection, appearance, update, advanced diagnostics |

## Hierarchy rules

- Node readiness is the only hero-level status on Home.
- Local and Relay are transport details attached to readiness, not separate product modes.
- Approval Policy, Capabilities, HUMAN HELP, and Android system permission are different concepts and must not be merged.
- Capabilities follow the product model: Execute, Sense, Interact. Hyper is a clearly separated elevated-access layer.
- Activity is history and handoff context. An active HUMAN HELP request still opens its dedicated response screen.
- Credentials, endpoints, raw Relay state, tool counts, and legacy controls stay in Settings > Developer / Diagnostics.
- Appearance is a product preference, but it is visually subordinate to behavior and connection settings.

## Functional preservation

- Existing Activity classes and MCP/service entry points remain intact.
- Existing permission toggles still open or revoke the same Android grants.
- Existing Node start/stop, connection copy, Relay configuration, approval modes, update, Inbox storage, and HUMAN HELP behavior remain unchanged.
- `MainActivity` remains available only through Developer / Diagnostics as the legacy engineering console.
