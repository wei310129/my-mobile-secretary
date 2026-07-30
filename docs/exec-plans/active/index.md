# Active execution plans

| Plan | Status | Source and handling |
| --- | --- | --- |
| Calendar v2 / itinerary map | Calendar W10 尚未發布 matching handoff | `calendar-plan-v2-sol-medium-development-test-plan.md`; W10 仍是 Travel 3B-B 的唯一 Calendar dependency；local PASS、commit、push 或 draft PR 不算 `TR-CALENDAR-W10-MERGED=READY` |
| Travel project and global Conversation Focus | 3B-A completed; stable handoff published | `travel-project-terra-high-development-test-plan.md`; one-off ownership, lifecycle and RLS gates are green under the Calendar Wheel 8 full-regression baseline; 3B-B stays `BLOCKED_BY_CALENDAR_WHEEL_10`; no legacy Schedule ownership and no automatic 3C |
| Booking / commerce execution | B3-Core、B4-Fake 已合併；B3-Durable、B3-Upstream、ADD Core pending | `booking-commerce-sol-medium-development-test-plan.md`; B4-Fake 產品 SHA `513eb117864cedbcaa63a99027cec22f9bd45bbf`，fake／reconciliation／mutation-count 與 root regression evidence 在 desktop state；目前不啟動下一 gate |
| Laptop × desktop parallel development | Legacy ownership active；role swap prepared、尚未啟用 | `two-machine-parallel-development-plan.md`、`machine-lane-role-swap-plan.md` 與 trigger registry；等待 Calendar W10 safe exit 後才可建立 state-only activation PR |
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
