# MX-011 bounded foreground refresh: lifecycle design

Task `t_42e32670`, 2026-09-20. Documentation only. Source baseline:
`3641af3253895f2f32d0711bb1b71fdfb25059e8`, sdk-android `26.08.05`.

## Decision

Use `Client.syncOnceV2` as the candidate one-response refresh operation on the
retained authenticated client. It exposes a response/error completion and
coroutine cancellation; it needs neither a sync listener nor a SyncService.
A safe JVM ownership sequence is specified below. It requires a real cancellable
coroutine context and explicit retirement ordering, which the current adapter
does not provide for refresh. Do not implement it by copying the existing latch
timeout helper.

The evidence does **not** certify a strict wall-clock bound on native shutdown
or absence of native side effects after cancellation. If “bounded” requires
those guarantees, implementation remains blocked on native cancellation evidence.
This is an evidence gap, not proof that the SDK lacks a safe implementation.
For a foreground wait deadline plus cooperative cleanup, the sequence below is
the proposed design; cleanup can outlast the deadline and must not be abandoned.

## Evidence and current integration constraints

- [API investigation](../evidence/sdk-sync-26.08.05/README.md) and
  [Client dump](../evidence/sdk-sync-26.08.05/Client.javap.txt),
  `syncOnceV2$suspendImpl`: the native future is passed to `uniffiRustCallAsync`
  with `ClientException.ErrorHandler`. Success yields `SyncResponseV2`, not Unit.
- [Shared bridge](../evidence/sdk-sync-26.08.05/Matrix_sdk_ffiKt.javap.txt),
  `uniffiRustCallAsync`: bytecode offsets 156–173 construct and initialize a
  cancellable continuation; 377–408 invoke future-free on normal/exceptional
  exit. [One-shot free function](../evidence/sdk-sync-26.08.05/Client$syncOnceV2$5.javap.txt)
  calls `ffi_matrix_sdk_ffi_rust_future_free_rust_buffer`. Its native body and
  shutdown latency are not present in this evidence.
- [Current adapter](../../briar-matrix/src/main/kotlin/org/briarproject/briar/matrix/ReflectiveMatrixHomeserverDiscoveryClient.kt):
  `invokeUnit`, `buildClient`, and `invokeCloseableValue` use
  `EmptyCoroutineContext` plus a latch. Stopping the latch wait does not cancel
  the SDK future. `login`, `restore`, `logout`, and `close` synchronize on the
  adapter. `login` calls `close()` before installing its new client; `restore`
  assigns `loginClient` directly. Both replacement paths need the same refresh
  retirement rule. No `refreshRooms()` exists at this baseline: the archived
  report's description of that method is not current source evidence.
- [Module dependencies](../../briar-matrix/build.gradle) keep this module
  SDK-free and currently declare Kotlin stdlib, not kotlinx-coroutines.
  A cancellable bridge needs an explicitly supported coroutine runtime access
  path (a narrow dependency or bridge in the SDK-enabled Android integration).
  Do not assume stdlib's Continuation provides a Job, or reflect into coroutine
  implementation classes. Choosing that integration is downstream product work.

## Ownership and call sequence

1. Under a short lifecycle lock, verify an authenticated client is retained and
   not retiring. Reserve one refresh slot containing the exact client identity,
   session generation, cancellation owner, and a completion signal. Publish the
   slot before starting work. Reject a concurrent refresh rather than create
   overlapping syncs. A cancelled-before-start operation must never invoke SDK
   sync. Do not hold this lock across a wait or native call.
2. On a worker, construct fresh `SyncSettingsV2` with default timeout and
   `fullState=false`. The default-mask JVM constructor accepts
   `(null, false, 3, null)` with parameter types
   `(kotlin.ULong, boolean, int, DefaultConstructorMarker)`; see
   [settings dump](../evidence/sdk-sync-26.08.05/SyncSettingsV2.javap.txt).
   This avoids unsigned boxing machinery. The SDK timeout field is not the
   foreground deadline. Do not pass a boxed Long where ULong is required.
3. Invoke `syncOnceV2(settings, continuation)` once through a suspend bridge
   whose continuation inherits the owned Job and worker dispatcher. Handle both
   immediate return and `COROUTINE_SUSPENDED`, preserving asynchronous failures
   and unwrapping `InvocationTargetException`. The bridge must wait for the SDK
   continuation's terminal unwind; merely cancelling an outer waiting
   continuation is not cleanup acknowledgment.
4. Use one monotonic foreground deadline, including scheduling/wait time. On
   expiry, cancel the operation's Job. Do not start a replacement request or
   release its client until the SDK invocation has unwound and bridge cleanup
   has completed. A worker stuck in native code cannot be forcibly made safe
   by a local timer; apply the limitation in the Decision above.
5. On a non-null `SyncResponseV2`, atomically accept success only if the slot
   is still active, its deadline has not expired, and both client identity and
   generation still match. One accepted response is refresh success, not proof
   of complete catch-up or all subsequent storage/UI updates. Read snapshots
   only for that still-current generation. Propagate the generation check to
   any deferred result publication so replacement cannot publish stale rooms.
6. In all paths, finish the SDK invocation and release the slot exactly once.
   SDK future cleanup belongs to its coroutine bridge; do not invoke native
   free manually. Settings and response are value objects and need no close.
   Preserve the retained client after ordinary success, error, or timeout.
   Cleanup failures must not be reported as successful refresh or permission
   to reuse an operation whose completion is uncertain.

The slot's completion signal must mean the **SDK invocation has unwound**,
not just that its caller stopped waiting or its Job entered cancelling state.
Lifecycle retirement may wait outside the short lock; callback/resume cleanup
must be able to run without acquiring a monitor held by the waiting thread.
Simply adding a synchronized blocking refresh beside the current synchronized
lifecycle methods would prevent logout/close from requesting prompt cancellation.

## Exit and retirement behavior

| Trigger | Required ordering and result |
| --- | --- |
| Success | Accept response only under the identity/generation/deadline gate; finish cleanup; clear slot; retain client. |
| SDK/setup failure | Record failure; unwind any started invocation; clear slot; retain client. Missing API shape fails before starting sync. |
| Timeout | Make result ineligible, request Job cancellation, drain SDK invocation, then clear slot. Return timeout/failure, never a late success. The deadline does not bound native cleanup. |
| Thread interruption | Make result ineligible and cancel exactly as for timeout. Preserve the interruption and restore the thread interrupt flag after cleanup. If cleanup is handed to a worker, that worker retains the slot/client ownership until terminal unwind; interruption alone must not authorize retirement. |
| Logout | Under lock mark generation retiring and invalidate pending publication; prevent new refresh admission. Outside lock cancel/drain its refresh. Only then invoke logout on that exact client and close it in lifecycle cleanup. The existing logout helper's own timeout semantics are a separate pre-existing issue, not fixed by this design. |
| Close | Mark retiring/invalidate, cancel/drain, then close the captured client once and clear its retained ownership. Close itself is not a sync cancellation request. |
| Client replacement by login or restore | Serialize lifecycle transitions; prepare candidate as appropriate, invalidate old generation, cancel/drain old refresh, close old client, then install candidate with a new generation. On failure dispose the uninstalled candidate; never clear or close a newly installed client from an old refresh's cleanup. Restore must use this retirement path too. |

Cancellation racing success is resolved once under the gate: if retirement,
timeout, or interruption wins, discard the value even if the SDK completes
successfully afterward. Discarding a result prevents stale application publication;
it cannot undo native database writes or token-delegate effects already performed.

## Minimal new API-shape guards

For this one-shot design, guard only the SDK calls actually added:

- Exact public `SyncSettingsV2(kotlin.ULong, boolean, int,
  kotlin.jvm.internal.DefaultConstructorMarker)` constructor.
- Exact public instance `Client.syncOnceV2(SyncSettingsV2,
  kotlin.coroutines.Continuation)` returning JVM `Object`; require the
  `SyncResponseV2` class and validate the eventual result is its instance.

Do not add response getter guards unless implementation actually reads those
fields. Do not add SyncListenerV2, TaskHandle, SyncService, service-state,
`isFinished`, or unsigned setter/boxing guards. Retain existing lifecycle guards;
no general reflection refactor is needed. Coroutine support should use its
supported public API, verified by compilation and focused cancellation tests,
not generated/internal API-shape checks.

The existing verifier's “two Proxy calls” source guard is inapplicable to this
one-shot design. Its recorded failure is documented in
[verifier output](../evidence/sdk-sync-26.08.05/verifier.txt); downstream should
replace that narrow sync-listener expectation with the actual one-shot guards,
preserving unrelated timeline checks. It is not currently a passing verifier.

## Exact unresolved guarantee and downstream verification

No inspected API establishes that cancellation/free has a bounded native
shutdown latency or is a barrier against all later network/store/delegate work.
`TaskHandle.cancel()` offers no join; `isFinished()` is no success result;
`SyncService.stop()` supplies no evidenced bounded native-quiescence contract.
Those alternatives therefore do not remove this gap. Closing the Client is
also not an evidenced cancellation barrier: the async call clones its native
handle and releases the wrapper call counter before the future finishes.

To claim strict shutdown/quiescence, obtain version-matched native implementation
or an upstream contract for future cancellation/free, including in-flight poll
callbacks and storage/token-delegate effects. Exercise delayed-server timeout,
interruption, logout, close, and replacement on the pinned Android runtime.
Tests can demonstrate observed behavior, but do not alone establish a universal
hard latency bound. Until this is resolved, do not label the design as proving
strict bounded native cleanup.

For the cooperative design, downstream tests must cover synchronous and suspended
completion, SDK failure, cancellation before start, timeout racing success,
interruption, and each retirement path. Assert a cancellable context, SDK unwind
before client release, one active refresh, no stale publication, and exactly-once
slot retirement. No product implementation or live Matrix validation was performed
for this documentation task.
