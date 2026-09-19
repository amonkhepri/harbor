# MX-011 bounded foreground sync: final feasibility report

Task `t_c99409c8`, 2026-09-20. Documentation-only synthesis of headroom task
`t_81a8c1b7` and lifecycle task `t_42e32670`.

## Final verdict

**Not feasible to approve for product implementation on the present evidence.**
The 700-line slice fits the review budget, and sdk-android `26.08.05` exposes
an awaitable one-shot operation. However, the investigation does not establish
bounded native cleanup after cancellation. This is an unresolved evidence
precondition, not proof that the SDK cannot support the feature.

A foreground wait deadline with cooperative cleanup is a plausible narrower
contract; it must not be represented as a bound on native shutdown. Cleanup can
outlast the deadline, and client retirement cannot safely abandon that cleanup.
No product behavior was changed by this investigation.

## Grounding and source precedence

The approved Harbor baseline is `fbf9dea066df60b206fc1217a6f2ee1ba52eff3c`.
The [MX-010 completion archive](../../../HarborAgent/docs/archive/mx010_restore_completed_and_backlog_refill_2026-09-09.md)
(in the sibling HarborAgent control repository) records reviewed inbox-triggered
session restore, local room snapshots without sync, and preservation of previous
source rows on load failure. These are the starting behaviors, not server-refresh
proof. Its original 350-line MX-010 target is historical, not the MX-011 cap.

The checked-out source HEAD is `3641af3253895f2f32d0711bb1b71fdfb25059e8`.
Use the [pinned AAR investigation](../evidence/sdk-sync-26.08.05/README.md) and
[lifecycle design](mx011-bounded-foreground-refresh.md) as current evidence.
They supersede the older MX-011 archive's claim that listener-based sync is the
only option and its description of a refresh method absent from current source.
The current design and evidence directories were already untracked when this
report began; they are upstream deliverables, not newly implemented behavior.

## Headroom: fit, with a slice-accounting constraint

The parent task measured the approved HEAD in an isolated bare clone. The
current-HEAD helper was rerun before writing this report and reproduced its
result against `master` at `5be775cb39e6af7fe0d14e0337d0e66b2533b6a8`.

| Measurement | Approved HEAD | Current HEAD |
| --- | ---: | ---: |
| Insertions against master | 797 | 1008 |
| Deletions against master | 2 | 2 |
| Headroom to 5500 review limit | 4703 | 4492 |
| Total after a further 700 insertions | 1497 | 1708 |
| Remaining after those 700 insertions | 4003 | 3792 |
| Remaining beyond 500-line review reserve | 3503 | 3292 |

Both hypothetical totals are strictly below the 5000 feature-freeze threshold.
The approved-to-current committed diff already contains 211 insertions, leaving
**489 of the original 700-line slice**, including tests, if that cap covers all
work since the approved HEAD. Adding a fresh 700 now would total 911 since that
baseline and fail the slice cap despite fitting the branch limits. No exception
is authorized. Budget fit is not functional approval or a measured implementation
size. These helper figures describe committed ranges: untracked evidence/design
files, this report, and any future changes require separate accounting before
inclusion in an immutable review range. Do not treat 489 as final remaining
capacity after adding those files.

## Exact blocker and the candidate sequence

The inspected JVM bridge installs a cancellable continuation and invokes native
future-free on normal or exceptional unwind. The native implementation/contract
is not included in that evidence: it does not establish a shutdown latency bound
or a barrier against subsequent network, store, token-delegate, or poll-callback
work. `TaskHandle.cancel()` has no join; `isFinished()` is not a success result;
`SyncService.stop()` and Client close do not supply an evidenced bounded native
quiescence contract. Switching to those APIs does not resolve the blocker.

The current adapter's `EmptyCoroutineContext` plus latch timeout does not cancel
the SDK future. A supported coroutine runtime integration and lifecycle changes
are therefore also required. The SDK-free module currently declares stdlib only;
reflecting into coroutine internals is not an acceptable substitute.

If the narrower cooperative-cleanup contract is explicitly adopted, the candidate
sequence from the lifecycle design is:

1. Under a short lock reserve one refresh slot for the authenticated client's
   identity and generation, with an owned cancellable Job and completion signal.
   Reject overlap and cancellation-before-start; release the lock before waiting.
2. On a worker construct default `SyncSettingsV2` with `(null, false, 3, null)`
   using its verified default-mask constructor. Invoke `Client.syncOnceV2` once
   with a continuation inheriting the Job and dispatcher. Handle immediate and
   suspended completion and propagate SDK/reflection invocation failures.
3. Apply one monotonic foreground deadline. Timeout, interruption, logout,
   close, or replacement invalidates publication and requests cancellation.
   Drain the actual SDK invocation before releasing its slot or retiring its
   client; cancelling only an outer waiter is insufficient. Preserve interruption.
4. Accept a `SyncResponseV2` only while identity, generation, and deadline remain
   valid. Read/publish snapshots only for that generation. One response does not
   certify full catch-up or every later native storage effect. Retain existing
   per-source failure handling and previous rows on refresh failure.
5. Clear the slot once after unwind. Retain the client for ordinary refresh
   completion/failure; for logout, close, login replacement, or restore replacement,
   cancel/drain the old refresh before retiring the old client. Never hold the
   lifecycle monitor across the drain or allow old cleanup to clear a new client.

This is a design candidate, **not a verified strict bounded call sequence**.
The minimal additional API guards would be the exact public constructor
`SyncSettingsV2(kotlin.ULong, boolean, int, DefaultConstructorMarker)`, the instance
method `Client.syncOnceV2(SyncSettingsV2, Continuation): Object`, and availability
plus eventual instance validation of `SyncResponseV2`. No listener/service/handle
or unused response-getter guards are needed. Keep existing lifecycle guards and
use public coroutine APIs verified by compilation and cancellation tests.

## Recommended smaller bounded follow-up

Limit the next task to a cancellation evidence and bridge proof, with no inbox
wiring or sync service:

- Inspect only the version-matched native future poll/cancel/free implementation
  or an explicit upstream contract for `26.08.05`; record whether cancellation
  acknowledges in-flight callbacks and network/store/delegate work. If evidence
  is unavailable, end with the exact missing contract rather than expanding scope.
- Select a supported coroutine integration seam and exercise a small host harness
  covering immediate/suspended completion, failure, cancellation before start,
  timeout versus success, interruption, and logout/close/replacement retirement.
  Assert one refresh, unwind before release, no stale publication, and exactly-once
  retirement. Fake-based host tests prove ownership logic, not native shutdown.
- When an authorized Android runtime/account is available, test delayed-server
  cancellation and retirement on the pinned SDK. Record device proof as unperformed
  otherwise. Observed timing alone cannot prove a universal hard latency bound.

The follow-up must conclude either with evidence supporting the required contract
or with a clearly stated cooperative-only limitation for a scope decision. Remeasure
all proposed changes against the 700-line slice and review reserve; split or defer
work that does not fit. Subsequent product changes retain default-off, Kotlin-only
scope and the required independent Claude review.

## Validation and scope confirmation

Re-read both parent deliverables and the MX-010 archive; reran
`HarborAgent/agent-tools/review_headroom.sh` (exit 0, status OK), checked current
HEAD and approved-to-HEAD diff, and validated this report's local links and
whitespace. The upstream SDK verifier remains **failing**, due to its source
expectation of two Proxy calls; the AAR inspection found the one-shot API. Do not
claim a passing verifier. A future implementation must replace that obsolete
sync-listener expectation with the one-shot guards while retaining unrelated
checks.

No build, live Matrix test, or Android runtime validation was performed for this
synthesis. No product source, tests, dependency pins, or behavior were changed;
no sdk-android upgrade, reflective guessing, Matrix HTTP/protocol implementation,
or open-ended background sync service was introduced. This report completes the
feasibility investigation, not MX-011 product implementation.
