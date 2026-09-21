# MX-011 cancellation prerequisite — outcome B

Task `t_8a24117c`, 2026-09-21. Evidence and isolated host fixture only.

**Strict cancellation/quiescence is not established.** The supported contract is
cooperative cancellation of the owned suspend invocation. A foreground deadline
can precede cleanup. Retirement must drain that invocation's terminal unwind,
but even that drain is **not** a client-wide native network/store/delegate barrier.
This resolves the investigation, not approval to implement the strict contract.

## Version and source provenance

The [26.08.05 release](https://github.com/matrix-org/matrix-rust-components-kotlin/releases/tag/sdk-v26.08.05)
identifies Matrix Rust SDK commit `b629ba1e689fc45f588e485ee7aec901726cc2a8`.
Its Cargo.lock pins UniFFI `0.31.0` at
`e5f4821410bea19e71984ea5e06a7bc8b11ed9e5`, Tokio `1.53.1`, deadpool-sync
`0.2.0`, and deadpool-runtime `0.3.1`. The Maven POM declares Kotlin `1.9.25`
and coroutines `1.7.3`. The cached AAR SHA-256 is
`89e78c7d457e522367f06d3145b58b67fb29ceffaa22d088e94240489a50ccd1`;
see [the prior bytecode investigation](../sdk-sync-26.08.05/README.md).

[fetch-sources.py](fetch-sources.py) downloads exact commit URLs and crate
archives to `build/mx011-cancellation/sources`; [source-manifest.txt](source-manifest.txt)
records SHA-256 and URLs. Release API metadata is mutable; its recorded body is
used only to establish the release-to-source mapping. No reproducible native
binary rebuild was performed. This is release-matched source plus AAR wrapper
evidence, not an attestation that a rebuilt native binary is byte-identical.

## Native findings

Paths below refer to the exact SDK or UniFFI commits above, downloadable using
the manifest; line numbers refer to those files.

| Layer | Finding and consequence |
| --- | --- |
| UniFFI `uniffi_core/src/ffi/rustfuture/future.rs:202-236` | `poll` synchronously polls the inner future under its mutex. `cancel` changes scheduler state; it does not drop the future. `free` cancels the scheduler then drops the inner future/result under the future mutex. Returning from free establishes that drop, not a latency bound. A synchronous poll, callback, or destructor can delay free. |
| UniFFI `scheduler.rs:17-24, 78-83` and `rustfuture/mod.rs:119-153` | Each registered continuation receives one callback. Cancellation consumes a stored callback and invokes it with Ready; subsequent stores also receive Ready. Scheduler-locked callback delivery is serialized with scheduler cancellation. However, `future.rs:212` also invokes Ready outside the scheduler mutex. There is no general callback-thread join API. Do not promote callback delivery or free to universal callback quiescence. |
| UniFFI `uniffi_bindgen/src/bindings/kotlin/templates/Async.kt:19-41` | Kotlin suspends with a cancellable continuation, and calls free in finally. It does not call native cancel separately. This matches the pinned AAR bytecode. Cancelling the Job requests unwind; waiting for that Job's completion establishes that the finally/free returned. Cancelling an outer waiter alone does not do this. |
| SDK `bindings/matrix-sdk-ffi/src/client.rs:1778-1784` | One-shot sync directly awaits `inner.sync_once`. No background sync service is necessary. UniFFI `uniffi_macros/src/export/scaffolding.rs:274-298` wraps the future in async compatibility machinery, not a joinable client-wide shutdown task. |
| SDK `crates/matrix-sdk/src/client/mod.rs:3102-3148` | One-shot sync performs crypto outgoing requests, sends sync, processes the response, then sends further crypto requests. Dropping its future is neither rollback of completed effects nor acknowledgment from the server that an accepted request stopped. No hard cancellation deadline is specified. |
| SDK `crates/matrix-sdk-sqlite/src/state_store.rs:1301` and `utils.rs:311-324` | `save_changes` copies state into a transaction closure; `with_transaction` runs the closure and commit through `interact`. deadpool-sync `src/lib.rs:123-139` clones ownership into blocking work; deadpool-runtime `src/lib.rs:85-98` awaits Tokio's blocking task. Tokio `src/task/blocking.rs:106-120` documents that started blocking tasks cannot be aborted. **A transaction can outlive the future awaiting it.** Free cannot certify no subsequent store effect. |
| SDK `bindings/matrix-sdk-ffi/src/client.rs:476-493, 2669-2693` | Token retrieval/save callbacks are synchronous callbacks installed on the client. When called inside a poll, they can delay that poll and hence free; they have no bounded cancellation acknowledgment. This does not establish that every possible token callback belongs to this sync future. |
| SDK `bindings/matrix-sdk-ffi/src/client.rs:2652-2661` | Authentication-error delegate delivery is spawned as blocking work without awaiting its handle. It illustrates independent delegate lifetime; sync future free does not drain all client callbacks. |

The SQLite path provides a concrete reason the stronger retirement inference
fails, even without a device timing experiment. These findings do not claim
that every cancellation leaves work running or that every callback runs late.
They show why the universal barrier cannot be promised. Client close, TaskHandle
cancel/isFinished, and SyncService stop are not substitutes for an evidenced
client-wide drain contract.

## Supported coroutine seam and host proof

Use public `kotlinx.coroutines` APIs with an explicitly owned Job and dispatcher.
For the SDK-free reflective module, the seam is
`kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn`: invoke the
verified suspend JVM method with its supplied continuation, return the immediate
result or COROUTINE_SUSPENDED unchanged, and unwrap InvocationTargetException.
This preserves the caller's Job/dispatcher context without reflecting into
coroutine implementation classes. Cancellation-before-start must be checked by
the owned job before invoking native code. The fixture uses a LAZY job installed
in the ownership slot before start; even pre-start cancellation clears it once
through the completion handler.

[BridgeProof.kt](BridgeProof.kt) and [run-proof.py](run-proof.py) compile against
Kotlin `1.9.25` and coroutines `1.7.3`, independently of the product Gradle graph.
[host-proof.txt](host-proof.txt) records library hashes and 14 passing cases:
immediate and suspended success, overlap rejection, immediate and suspended
failure, cancellation-before-start, both deadline/success orderings, logout,
close, login/restore replacement drain, timeout retirement, foreground timeout
preceding cleanup, and thread interruption with restored interrupt status.

The fake invocation deliberately delays its finally block. Tests assert the
slot remains held and retirement cannot finish until cleanup is released, with
one release/retirement and no stale publication. Deadline ordering is controlled
using an injected clock; it is not a stress test or proof of parallel locking.
The model serializes state transitions on one event loop; product code must
provide equivalent synchronization for reservation, invalidation, publication,
and replacement. The interruption test exercises public runBlocking behavior on
a host thread; it does not certify native interruption responsiveness.

These tests prove JVM continuation and ownership behavior only. They neither
load the Android native library nor prove native shutdown. A future product
change must explicitly declare/pin a compatible coroutines dependency, validate
the resolved Android graph, and adapt the model to actual client identities.
No dependency or product-tree file was changed here.

## Exact contract for t_2dcf0601

1. A deadline bounds the logical foreground wait/publication opportunity, subject
   to normal scheduling; it does not bound native termination. Do not wrap the
   native invocation as a structured child of a timeout scope and claim prompt
   return: structured completion itself can wait for cleanup. Own the invocation
   separately, time out its waiter, invalidate publication, and cancel its Job.
2. Maintain one owned invocation per client generation. Release its slot only
   after the actual invocation Job completes, including its finally/free path.
   Refuse overlap while cleanup remains pending; never hold the lifecycle lock
   while draining. Generation checks must cover publication and token persistence.
3. Logout/close/replacement invalidate the old generation, request cancellation,
   then drain terminal unwind before application-owned client retirement. Do not
   let old cleanup clear a replacement client; retire each owned client once.
   Preserve thread interruption while arranging the drain outside the interrupted
   foreground waiter if necessary.
4. This ordering is a **minimum ownership rule**, not proof of native quiescence.
   Do not delete/reuse the old store or assume delegate/network silence solely
   because join/free returned. Separately established isolation/fencing and SDK
   lifetime rules are required wherever late native work could affect replacement
   or logout correctness. A generation check on UI publication alone is insufficient.
5. If the child still requires no native effects after deadline/retirement, it
   remains infeasible on this evidence. A human scope decision must explicitly
   accept cooperative foreground behavior and address residual store/delegate
   lifetime before product implementation; completion of this prerequisite must
   not silently change that requirement.

## Validation and scope

Run from repository root:

```sh
python3 docs/evidence/mx011-cancellation/fetch-sources.py
python3 docs/evidence/mx011-cancellation/run-proof.py
git diff --check
```

Source fetch and host proof passed. Device/live-account timing was not performed;
even successful timing observations would not prove a universal bound. The old
SDK verifier failure documented in the prior evidence remains unresolved and
is not reported as passing. No inbox wiring, background service, SDK upgrade,
Java changes, Matrix protocol implementation, or product behavior changes.

Budget check before this evidence commit: `review_headroom.sh` reports 1898
insertions against master, 3602 headroom, status OK. Existing committed
approved-baseline-to-HEAD evidence already totals 1101 additions, exceeding the
historical 700-line target if all investigation documents count toward it.
This task adds only an isolated evidence package; it does not claim a fresh
700-line product allowance. The child must split/rebaseline its review scope
before implementing any product slice and remeasure its actual immutable range.
