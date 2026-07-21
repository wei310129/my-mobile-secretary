# Acceptance Rubric

## Apply hard gates

Fail the entire capability when any applicable hard gate fails:

- The intent, action, and deterministic business result are correct.
- Required information is never guessed or invented.
- Dates, amounts, ranges, states, geography, authorization, and mutations are validated by Java.
- Read-only, feedback, rejected, and failed interpretations produce zero unintended mutation.
- Mutation count, transactionality, state transitions, and idempotent replay are exact.
- Workspace, actor, RLS, privacy, and sensitive-data boundaries hold.
- The reply answers the direct user need before limitations or next steps.
- No public response leaks internal identifiers or diagnostics.
- Explicit quoted context is selected correctly and cannot cross actor or workspace boundaries.
- The latency target or required progress-feedback contract is met.
- Neighboring capabilities and permanent regressions remain passing.

An average score cannot compensate for a hard-gate failure.

## Detect information leakage

Seed tests with distinctive internal values and assert that user-visible responses, API bodies, LINE replies, notifications, and error fallbacks do not contain them. Cover:

- Database keys, UUIDs, workspace IDs, actor IDs, external or quoted message IDs.
- Intent enums, capability codes, handler names, Java classes, and packages.
- SQL, table and column names, migration details, and repository method names.
- Stack traces, exception messages, file paths, log lines, trace payloads, and provider errors.
- Prompts, structured-output schemas, router reasons, tokens, credentials, and private URLs.

Allow business numbers such as dates, prices, and user-facing list positions only when the domain response requires them. Do not use a broad “no digits” assertion.

## Measure latency

Measure from accepted inbound request to the first meaningful user-visible response and to the terminal response. Record sample count, median, P95, slowest case, environment, model/provider mode, and warm/cold status.

Use these default targets unless a stricter approved target exists:

| Class | Typical work | Required target |
| --- | --- | --- |
| Low | Deterministic query or mutation | Terminal P95 ≤ 1.5 s |
| Medium | One model interpretation or ordinary lookup | Terminal P95 ≤ 4 s; progress feedback after 2 s |
| High | OCR, multiple providers, large or cross-domain work | Progress feedback within 1–2 s; terminal target normally 15–30 s |
| Long | Batch or externally delayed work | Progress feedback within 1–2 s and exactly one later terminal result |

Treat low- and medium-class overruns as unfinished engineering work. Profile, optimize, rerun correctness tests, and remeasure. For high or long work, verify durable acceptance, timeout behavior, replay safety, pending-state association, and final delivery instead of merely checking acknowledgement text.

## Score user experience

Score every scenario from 1 to 5 in each applicable dimension:

- Directly solves or advances the user’s request.
- Correct, truthful, and free of unsupported claims.
- Natural, concise, and understandable to an everyday user.
- Correctly uses quoted and conversational context without needless repetition.
- Clearly communicates completion, failure, missing input, or ongoing processing.

Require at least 4 in every dimension for every case. Record the reason for any score below 5. Continue the development loop for any score below 4; do not average it away.

## Verify progress feedback

Accept progress feedback only when all conditions hold:

- It is sent within the class budget.
- It says the request was received and is still being processed without claiming success.
- A durable job or application state exists.
- Duplicate delivery cannot create duplicate work or duplicate final responses.
- Exactly one terminal success or failure response is eventually delivered.
- A new user message cannot become attached to the wrong pending work.
- Internal job, message, database, or trace identifiers remain hidden.
