# Active execution plans

| Plan | Status | Source and handling |
| --- | --- | --- |
| Calendar W11-H two-machine execution | Approved; coordination publish pending | `calendar-w11-h-two-machine-development-test-plan.md`; laptop remains the only production writer while desktop may run only route benchmark, sealed holdout, public-response and its own environment evidence after `TR-DESKTOP-ROUTE-BENCHMARK-START` is Git READY and ACKed. Current coordination state remains BLOCKED until the product PR and matching state-only handoff are both merged; external mutation count must remain zero |
| Calendar v2 / itinerary map | Wheels 0–10 PASS_PUBLISHED | `calendar-plan-v2-sol-medium-development-test-plan.md`; PR #9 merged at `c4ade0b88d44457f5200a6849bbc10ea1516f692`; V89–V92 recurrence, recurring registration and authenticated lossy ICS exchange complete; W10 focused 36/36, security-neighbor 102/102 and root regression 1,590 tests / 0 failure / 0 error / 16 skipped; matching `TR-CALENDAR-W10-MERGED` handoff is READY |
| Travel project and global Conversation Focus | 3B-A completed; W10 dependency cleared, awaiting user confirmation | `travel-project-terra-high-development-test-plan.md`; one-off ownership, lifecycle and RLS gates are green under the Calendar Wheel 8 full-regression baseline; `TR-CALENDAR-W10-MERGED` only unlocks Travel 3B-B preflight and does not authorize starting 3B-B or 3C without the required user confirmation |
| Booking / commerce execution | B0–B2, B3-Core and B4-Fake published; B3-Durable schema grant READY | `booking-commerce-sol-medium-development-test-plan.md`; desktop state confirms B3-Core and B4-Fake MERGED with focused/security-neighbor/root gates green and external mutation count 0; one Booking-owned additive V93 migration is granted once from base `280dedaa52567cc0cf09975e39cfe235435b4018`; B3-Upstream remains blocked by the Travel Wheels 6–7 typed handoff |
| Laptop × desktop parallel development | Active; ADD Core published and B3-Durable V93 granted once | `two-machine-parallel-development-plan.md` and `parallel-development-trigger-registry.md`; `TR-ADD-CORE-MERGED` is Git-verifiable at `194413cd78004494a30c6c64a7aba60c6cb525f2`; `TR-SCHEMA-B3-DURABLE-GRANT` reserves V93 for `desktop/booking-b3-durable` only and must be consumed, revoked or marked stale before any other schema handoff |
| Real conversation improvement batches | Active local backlog | `local/conversation-improvement-batches-2026-07-20.md`; ignored because it derives from real LINE conversation logs; never commit its raw contents |

```json
{
  "crossTrackHandoff": "calendar-wheel-8-to-travel-3b-a-to-booking-b2",
  "publishedAt": "2026-07-26",
  "calendar": {
    "phase": "8",
    "status": "PASS",
    "sealedHoldout": "PASS",
    "fullRegression": {"tests": 1417, "failures": 0, "errors": 0, "skipped": 18}
  },
  "travel": {
    "phase": "3B-A",
    "status": "PASS",
    "handoff": "STABLE",
    "scope": "ONE_OFF_CALENDAR_PLAN_OWNERSHIP_ONLY"
  },
  "booking": {
    "phase": "B2",
    "status": "PASS",
    "dependencyState": "SATISFIED",
    "actualFlywayLatest": "V84",
    "sourceClaim": "RELEASED_REACQUIRE_FOR_B3",
    "flywayClaim": "V84_RELEASED",
    "claimTransfer": "NONE"
  },
  "remainingBlocker": "Travel 3B-B remains BLOCKED_BY_CALENDAR_WHEEL_10"
}
```

Follow `docs/agent-context/execution-plan-policy.md`. Register new active plans here and load only the plan required by the task.

```json
{
  "calendarWheel9Handoff": {
    "phase": "9-D",
    "status": "PASS",
    "actualFlywayLatest": "V87",
    "sourceClaim": "RELEASED_REACQUIRE_FOR_W9_E",
    "flywayClaim": "V87_RELEASED",
    "focused": {"tests": 119, "failures": 0, "errors": 0, "skipped": 0},
    "rootRegression": {"tests": 1514, "failures": 0, "errors": 0, "skipped": 18},
    "desktopStart": {
      "status": "BLOCKED_NEEDS_PUBLISH",
      "verifiedOriginMain": "99882c47d66a0018e3cc6b259de4efbd015d9c53"
    },
    "claimTransfer": "NONE",
    "next": "W9-E_REGISTRATION_CAPACITY_OWNERSHIP"
  }
}
```
