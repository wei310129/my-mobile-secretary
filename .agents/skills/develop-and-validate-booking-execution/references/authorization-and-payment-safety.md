# Authorization and Payment Safety

Keep itinerary materialization consent separate from purchase authorization. Authorization binds actor,
workspace, travellers, selected offer or substitution scope, price/currency limits, provider allowlist,
non-refundable acceptance and expiry.

Support per-item, batch and policy-bounded confirmation; support exact, equivalent and goal-directed
substitution. Any field outside the selected scope requires a new quote and authorization.

External providers do not share one transaction. Revalidate quotes and record every step. On partial success,
stop, preserve completed orders and present cancellation or replacement choices. Never auto-cancel without
a separate applicable grant.

Use provider-hosted/tokenized card collection. Never store full card number or CVC. Keep traveller identity
and passport fields in a separately encrypted vault. Never expose secrets, OTP, 3DS, CAPTCHA, credentials,
browser storage state or raw booking data to an LLM, log, LifeRecord or handoff.

Playwright is a white-listed fallback after API and official checkout. Stop on unexpected DOM, price, terms
or verification, and hand control to the user.

