# Active execution plans

| Plan | Status | Source and handling |
| --- | --- | --- |
| Calendar v2 / itinerary map | Wheels 0–9 PASS_PUBLISHED; Wheel 10 PASS_LOCAL_AWAITING_PRODUCT_PUBLISH | `calendar-plan-v2-sol-medium-development-test-plan.md`; V89–V92 recurrence, recurring registration and authenticated lossy ICS exchange complete locally; W10 focused 36/36, security-neighbor 102/102 and post-main-merge root regression 1,590 tests / 0 failure / 0 error / 16 skipped; Travel 3B-B remains blocked until product and matching state-only PRs merge and `TR-CALENDAR-W10-MERGED` is READY |
| Travel project and global Conversation Focus | 3B-A completed; stable handoff published | `travel-project-terra-high-development-test-plan.md`; one-off ownership, lifecycle and RLS gates are green under the Calendar Wheel 8 full-regression baseline; 3B-B stays `BLOCKED_BY_CALENDAR_WHEEL_10`; no legacy Schedule ownership and no automatic 3C |
| Booking / commerce execution | B0–B2 completed in laptop worktree; Git handoff pending | `booking-commerce-sol-medium-development-test-plan.md`; V84 durable persistence/RLS, crash recovery, reconciliation and exactly-once-visible terminal foundation PASS; final root regression 1,435 tests / 0 failure / 0 error / 18 skipped. Desktop B3 remains blocked until the handoff SHA is published |
| Laptop × desktop parallel development | Documented; desktop product development blocked pending published handoff | `two-machine-parallel-development-plan.md` and `parallel-development-trigger-registry.md`; producer-owned laptop/desktop JSON state, both lane runbooks and the GPT-5.6 SOL Medium desktop prompt are registered. Booking B2 has PASS evidence in the laptop worktree but no Git-retrievable desktop base SHA yet |
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
