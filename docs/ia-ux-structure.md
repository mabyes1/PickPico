# PickPico IA and UX structure

This structure follows Product & Experience Guideline 1.3. It changes how existing features are grouped and described; it does not change capability behavior, permissions, approval enforcement, or MCP transport.

## Product model

PickPico is an Android-first real-world Mobile Agent Node: the phone is the Agent's sensing, interaction, execution, and human-handoff node in the physical world. The UI should answer four questions in this order:

1. Is the Node available to an agent?
2. What needs the owner's attention?
3. What can the agent access on this phone?
4. How is the product configured?

HUMAN HELP is a signature interaction inside PickPico, not the product's top-level identity. The optional physical Dock is not part of the software navigation.

The Home screen conditionally inserts `NEEDS ATTENTION` directly below Node readiness whenever a HUMAN HELP or approval request is waiting. If nothing needs a response, that section is absent.

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
- Activity places unresolved HUMAN HELP / approval requests above the historical timeline under `NEEDS RESPONSE`.
- Credentials, endpoints, raw Relay state, tool counts, and legacy controls stay in Settings > Developer / Diagnostics.
- Appearance is a product preference, but it is visually subordinate to behavior and connection settings.

## HUMAN HELP interaction modes

HUMAN HELP is one product state with two input modes:

- **Phone only:** the phone owns the response controls. A fixed bottom tray exposes `Approve`, `Reject`, `Voice`, and `Details` while the request content scrolls independently.
- **DUCK connected:** the phone prioritizes request context and shows that DUCK is the primary response surface. The four hardware controls remain fixed as `Approve`, `Reject`, `Voice`, and `Details`. The user can fall back to phone controls at any time.
- If DUCK disconnects during an active request, phone controls return automatically; the request itself must not be interrupted.

`Details` is a universal navigation/control action, not an Agent-defined response. It expands the full HUMAN HELP context and optional note/image tools. Upload, camera, and other request-support tools remain explicit controls inside the page rather than being remapped onto the fourth key.

Capabilities should expose state as well as switches. Use `READY` for available access, `SETUP REQUIRED` when an Android/system grant is still needed, and `OFF` for intentionally inactive product modes such as Hyper or a screen-capture session.

## Functional preservation

- Existing Activity classes and MCP/service entry points remain intact.
- Existing permission toggles still open or revoke the same Android grants.
- Existing Node start/stop, connection copy, Relay configuration, approval modes, update, Inbox storage, and HUMAN HELP behavior remain unchanged.
- `MainActivity` remains available only through Developer / Diagnostics as the legacy engineering console.
