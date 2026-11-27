# ADR-004: Download State Machine — Eleven Internal States Mapped onto Five Public States

**Status:** Accepted (V2 Wave 1, integrator arbitration) · **Date:** 2026-11-27 · **Deciders:** Chief Architect, Squad 2 lead

**Context.** The Rust engine implements five states — Queued, Running, Paused, Completed, Failed (`DlState`, mirrored exactly in `TaskDto.state` and consumed by the frontend, which renders per-state actions and icons). The V2 brief (Squad 2) mandates an eleven-state lifecycle: Queued, Probing, Ready, Running, Pausing, Paused, Retrying, Verifying, Completed, Failed, Cancelled — with legal transitions defined and invalid ones prevented. Wave 0 showed the five-state model is not merely coarse but *harmful*: readiness is a side boolean (`ready`), and its mismanagement is the P0 defect — a probe-failed task is Failed with `ready=false`, and `start()` refuses non-ready tasks, so Retry is a permanent silent no-op. At the same time, the five public states are a shipped contract: the frontend's state matrix, the DTO, persisted `downloads.json`, and user expectations all depend on them. Android independently implements six states (incl. MISSING, approximated on Rust by the `fileMissing` flag), so an eleven-state *public* expansion would also break cross-platform parity rather than create it.

**Decision.** Adopt the eleven states as the engine's **internal** lifecycle (`engine/src/manager.rs`), with a single explicit mapping to the five public DTO states, which **do not change**:

| Internal state | Public `TaskDto.state` | Notes |
|---|---|---|
| Queued | `queued` | Not yet probed, or probed-ready awaiting a slot |
| Probing | `queued` | Probe in flight; UI already shows "Queued" |
| Ready | `queued` | Probe done, segments planned; internal distinction only |
| Running | `running` | Workers active |
| Pausing | `running` | Pause requested, workers not yet unwound — internal state makes the window honest and lets Wave 2 bound it |
| Paused | `paused` | |
| Retrying | `queued` | Transient between a Failed task's accepted retry and its re-probe (per the normative lifecycle spec in DOWNLOAD-ENGINE.md) |
| Verifying | `running` | Post-download size/`.part` reconciliation (Wave 2) before Completed |
| Completed | `completed` | Only after Verifying passes |
| Failed | `failed` | Terminal until user retry |
| Cancelled | — (no DTO) | **Maps to removal**: cancellation is the existing remove path; the internal state exists so workers unwind and the supervisor suppresses completion side-effects (fixes the remove-running resurrection defect) before the task is dropped |

Transitions are defined in the table in DOWNLOAD-ENGINE.md and enforced by a single `transition(task, to)` gate in the manager; any caller attempting an illegal transition gets a logged no-op, never a panic. The `ready` boolean is **deleted** — readiness *is* the Ready state; retry from Failed re-enters Probing, which is the P0 fix expressed structurally. Persisted format stores the internal state with `#[serde(default)]` fallbacks (see ADR-005); on load, transient states (Probing/Pausing/Retrying/Verifying) restore per the recovery contract in DOWNLOAD-ENGINE.md, exactly as Running/Queued do today.

**Alternatives considered.**
- *Expose all eleven states publicly:* honest-looking, but breaks the frontend state matrix, the DTO contract, and Android parity in one move; the UI has no distinct action or icon for Probing vs Queued and would collapse them anyway. Rejected.
- *Keep five states, just fix the `ready` flag:* minimal diff, but leaves Pausing's dishonesty, Retrying's invisibility, and the remove-race structurally unaddressed; the brief's state-machine requirement exists precisely because the boolean-accretion model produced the P0. Rejected.
- *Adopt Android's six-state model publicly (add MISSING):* replaces the working `fileMissing` flag with a state the frontend would need new handling for; parity is behavioral, not literal. Rejected; the flag stays.

**Consequences.** Positive: the P0 deadlock becomes impossible by construction; pause latency and retry behavior become observable and testable; Wave 2's `.part` reconciliation gets a natural home (Verifying); the public contract, the frontend, and all persisted user data are untouched. Negative: two state vocabularies must be kept in sync — mitigated by the mapping living in exactly one function next to the DTO conversion, with a unit test enumerating every internal state. The transition table becomes a review gate: Squad 2 changes to it require Chief Architect sign-off per the coordination protocol.
