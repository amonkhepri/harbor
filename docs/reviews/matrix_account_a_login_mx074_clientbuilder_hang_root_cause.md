# MX-074: Root Cause of the `sqliteStore()+setSessionDelegate()` `ClientBuilder.build()` Hang

- Task: `t_a5a22be6`
- Pinned workspace: `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_a5a22be6`
- Workspace HEAD at time of investigation: `cd893e84818fe817e41d7e871d62204d709f14b9` (no
  Kotlin change required; see conclusion)
- Device: `emulator-5554` (`sdk_gphone64_arm64`)
- App: `org.briarproject.briar.android.debug`, built with
  `./gradlew :briar-android:assembleDebug -Pharbor.matrixConnector.enabled=true`
  (the `MATRIX_CONNECTOR_ENABLED` build flag is off by default; the APK installed
  on this device prior to this task did not have it, so `Settings → Matrix
  account` was not reachable until this rebuild — confirmed via
  `aapt dump xmltree` and by grepping the installed APK's dex for
  `org/matrix/rustcomponents/sdk/*`)
- Homeserver: disposable local Synapse (`~/.local/state/harbor-matrix-disposable/`),
  `127.0.0.1:8008`, forwarded to the device via `adb reverse tcp:8008 tcp:8008`,
  confirmed reachable (`/_matrix/client/versions` → 200) throughout

## 1. Scope

Continuation of `t_a895a2cb`'s diagnosis (`docs/reviews/matrix_account_a_login_mx074_step2a.md`),
which reproduced the hang and bisected it to the `sqliteStore()+setSessionDelegate()`
configuration path but explicitly deferred root-causing it, since that required
"an isolated instrumented repro" outside that card's bounded scope. This task
performs that instrumented repro.

## 2. Repro

Local profile was reset (the previous profile's password did not match
`~/.local/state/harbor-matrix-disposable/local_profile.env`, so it was
recreated via the in-app "Lost Password → Delete" flow rather than guessed at;
the account it held was synthetic developer-menu test data, not real user
data). A fresh account was created, `Settings → Matrix account` was opened,
`http://127.0.0.1:8008` was entered and resolved in under 3 seconds (confirms
`1bb3d9877`'s discovery fix is intact and is not the cause here), and
`harbor_test_a` + its password from `creds.env` were submitted.

Result, reproduced twice in this session (and three times previously in
`t_a895a2cb`, 5/5 total): `Connecting…` for a flat ~30s, zero CPU on the app
process throughout, then `Could not connect. Check your details and try
again.` — i.e. `ReflectiveMatrixHomeserverDiscoveryClient.buildClient`'s
`latch.await(discoveryTimeoutMs, …)` (kt:1354) always times out for this
configuration. This is a deterministic, 100%-reproducible hang, not an
intermittent one.

## 3. Thread-dump capture method

`adb shell am dumpheap`/`kill -3` writes ART's SIGQUIT thread dump to
`tombstoned` under `/data/anr/trace_NN`, which requires root to read back
(`adb shell cat /data/anr/trace_04` → `Permission denied` for `shell`; this
emulator image is not rooted — `adb root` → "adbd cannot run as root in
production builds"). That path was attempted first and abandoned once it
confirmed only that a dump *was* written (`tombstoned: Traces for pid 9202
written to: trace_04` in logcat), not its contents.

Instead: the app is a debug build (JDWP-enabled). `adb forward tcp:8700
jdwp:<pid>` plus the JDK's bundled `jdb -attach localhost:8700` (from
`Android Studio.app/Contents/jbr/Contents/Home/bin`, which ships `jdb`
despite JBR otherwise being stripped-down) can attach to the running process,
`suspend` every thread, and `where all` dumps every thread's real Java/Kotlin
stack — including threads the app itself didn't start. This worked without
root and was run ~6 seconds into the hang window (well inside the 30s
timeout, confirmed blocked, not finished-but-slow-to-render).

## 4. The evidence

Full dump: the calling thread is parked exactly where `buildClient()` should
leave it while waiting for the suspended `build()` coroutine:

```
pool-5-thread-9:
  [1] jdk.internal.misc.Unsafe.park (native method)
  [2] java.util.concurrent.locks.LockSupport.parkNanos (LockSupport.java:269)
  [3] java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire (AbstractQueuedSynchronizer.java:756)
  [4] java.util.concurrent.locks.AbstractQueuedSynchronizer.tryAcquireSharedNanos (AbstractQueuedSynchronizer.java:1,126)
  [5] java.util.concurrent.CountDownLatch.await (CountDownLatch.java:276)
  [6] org.briarproject.briar.matrix.ReflectiveMatrixHomeserverDiscoveryClient.buildClient (ReflectiveMatrixHomeserverDiscoveryClient.kt:1,354)
  [7] org.briarproject.briar.matrix.ReflectiveMatrixHomeserverDiscoveryClient.login (ReflectiveMatrixHomeserverDiscoveryClient.kt:250)
  [8] org.briarproject.briar.matrix.MatrixAuthSessionImpl.submitCredentials (MatrixAuthSessionImpl.kt:70)
  ...
```

That part was expected — Harbor's bridge is doing exactly what it's supposed
to: block on the `Continuation`'s delivery. The interesting part is *why the
delivery never happens*. Two more threads, both bare `java.lang.Thread`
instances Harbor never creates (not `matrix-typing`, not any named
executor — these are spawned by the SDK's own native/Kotlin glue), are
**both** sitting inside the SDK's generated future-poll bridge at the same
instant:

```
Thread-5:
  [1] org.matrix.rustcomponents.sdk.UniffiLib.ffi_matrix_sdk_ffi_rust_future_poll_u64 (native method)
  [2] org.matrix.rustcomponents.sdk.ClientBuilder$build$3.invoke (matrix_sdk_ffi.kt:10,024)
  [3] org.matrix.rustcomponents.sdk.ClientBuilder$build$3.invoke (matrix_sdk_ffi.kt:10,017)
  [4] org.matrix.rustcomponents.sdk.Matrix_sdk_ffiKt.uniffiRustCallAsync (matrix_sdk_ffi.kt:4,982)
  [5] org.matrix.rustcomponents.sdk.Matrix_sdk_ffiKt$uniffiRustCallAsync$1.invokeSuspend (matrix_sdk_ffi.kt:0)
  [6] kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith (ContinuationImpl.kt:33)
  [7] kotlinx.coroutines.DispatchedTaskKt.resume (DispatchedTask.kt:179)
  [8] kotlinx.coroutines.DispatchedTaskKt.dispatch (DispatchedTask.kt:168)
  [9] kotlinx.coroutines.CancellableContinuationImpl.dispatchResume (CancellableContinuationImpl.kt:474)
  [10] kotlinx.coroutines.CancellableContinuationImpl.resumeImpl (CancellableContinuationImpl.kt:508)
  [11] kotlinx.coroutines.CancellableContinuationImpl.resumeImpl$default (CancellableContinuationImpl.kt:497)
  [12] kotlinx.coroutines.CancellableContinuationImpl.resumeWith (CancellableContinuationImpl.kt:368)
  [13] org.matrix.rustcomponents.sdk.uniffiRustFutureContinuationCallbackImpl.callback (matrix_sdk_ffi.kt:4,967)
Thread-6:
  [1] org.matrix.rustcomponents.sdk.UniffiLib.ffi_matrix_sdk_ffi_rust_future_poll_u64 (native method)
  [2] org.matrix.rustcomponents.sdk.ClientBuilder$build$3.invoke (matrix_sdk_ffi.kt:10,024)
  [3] org.matrix.rustcomponents.sdk.ClientBuilder$build$3.invoke (matrix_sdk_ffi.kt:10,017)
  [4] org.matrix.rustcomponents.sdk.Matrix_sdk_ffiKt.uniffiRustCallAsync (matrix_sdk_ffi.kt:4,982)
  ... (identical frames 5-13 to Thread-5)
```

(Full 347-line dump preserved at `/tmp/jdb_dump3.txt` on the build host for
this run; not committed, since it's a throwaway debug artifact tied to one
emulator PID, not project state.)

Both threads independently woke the *same* `ClientBuilder.build()` future
(`uniffiRustFutureContinuationCallbackImpl.callback`, the UniFFI-generated
waker-delivery entry point) and are both, concurrently, back inside the
native `ffi_matrix_sdk_ffi_rust_future_poll_u64` call polling it again. A
`Future::poll` in Rust takes `&mut self` — exclusive access is part of its
contract — so two native threads polling the same future handle at once is
not a supported state for whatever is on the other side of that FFI call.
Neither thread returns from the native poll call in this dump, which is
consistent with `pool-5-thread-9`'s `CountDownLatch` never counting down:
whichever one of them should eventually deliver the real "ready" value and
resume `pool-5-thread-9`'s waiting continuation never does.

## 5. Why this is upstream, not in Harbor's bridge

- `ReflectiveMatrixHomeserverDiscoveryClient` never spawns `Thread-5`/`Thread-6`.
  The only thread this class creates is the single named daemon
  `matrix-typing` (see the constructor's `typingExecutor`). Both racing
  threads are entirely inside SDK-generated code
  (`org.matrix.rustcomponents.sdk.*`) reached only via the single
  `builder.build(continuation)` call Harbor's bridge makes once.
- Harbor's `buildClient()` bridge (kt:1336-1372) already defends against a
  *single* late/duplicate delivery after timeout — the `claimed`
  `AtomicBoolean` CAS in both `resumeWith` and the timeout path ensures
  whichever side (the timing-out caller or a late `resumeWith`) loses the
  race still closes the orphaned `Client` rather than leaking it or acting on
  it twice. (`ReflectiveMatrixHomeserverDiscoveryClientTest`'s `client
  delivered after the adapter times out is still closed` already covers this
  for `discover()`'s simpler path.) That mechanism isn't the problem here:
  the dump doesn't show a late/duplicate *delivery* racing a timeout: it
  shows the *poll never returning at all* on either of the two threads
  racing each other inside it. There's no Kotlin-side delivery to be unsafe
  about — nothing on our side of the FFI boundary ever gets called back.
- `Client` (the built object `build()` would eventually hand back) exposes no
  way to attach a `ClientSessionDelegate` after construction — only
  `setDelegate(ClientDelegate)` (a different type, for sync-state
  notifications) and `setUtdDelegate` exist (confirmed via `javap` against
  `sdk-android-26.08.05.aar`'s `classes.jar`). So there is no supported way
  to build with `sqliteStore()` alone and attach the session delegate
  afterward to dodge whatever internal concurrency this combination
  triggers — the delegate can only be supplied to the builder before `build()`.

## 6. Why a retry or timeout increase are not credible workarounds

The card's brief explicitly warns against "a blind timeout increase without
evidence the build is making progress, just slowly" — this dump is that
evidence, and it says the opposite of "slowly progressing": both racing
threads are parked in a native call with zero CPU, not doing bounded work
that a longer wait would let finish. A longer timeout would hang longer for
no payoff.

A bounded single retry was also considered and rejected: the hang is
deterministic across all 5 reproduction attempts across both this task and
`t_a895a2cb` (not 1-in-N flaky), so there's no reason to expect a second
attempt in the same process to avoid the same race.

## 7. Conclusion and recommendation

This is a concurrency bug in the pinned `org.matrix.rustcomponents:sdk-android:26.08.05`
artifact's UniFFI async future-polling bridge: building a `Client` with both
`sqliteStore()` and `setSessionDelegate()` set causes the resulting
`ClientBuilder.build()` future to be woken and polled from two native threads
at once, and that race leaves the future permanently unresolved. It is not
reachable or fixable from `:briar-matrix`'s reflection bridge, which only
calls `build()` once through the documented suspend-bridging pattern and has
no visibility into, or control over, how many native threads the SDK's own
Tokio-backed future implementation uses to deliver its wakeups.

Recommended follow-up (outside this card's scope — changing the pinned SDK
version is exactly the kind of broader, riskier change this card's brief
excludes): file an upstream issue against `matrix-rust-sdk`'s `sdk-android`
bindings describing the concurrent double-poll of the same `ClientBuilder.build()`
future observed here, and open a separate card to evaluate pinning a
different `sdk-android` version once one is identified that doesn't exhibit
this race for the `sqliteStore()+setSessionDelegate()` configuration.

## 8. Regression test

Not addable to the existing `FakeMatrixSdk` JVM test double: that harness
has no concept of a native Tokio runtime or concurrent waker threads (its
`build()` either returns synchronously or resumes once from a single spawned
`Thread`, by design — see `FakeMatrixSdk.kt:697-719`), so it structurally
cannot reproduce a race between two concurrent native poll callbacks. Per
this card's own allowance for "a clearly marked integration/manual repro
note if it can only be shown against the real SDK," this document plus §2-§4
*is* that note: reset the local profile, build
`:briar-android:assembleDebug -Pharbor.matrixConnector.enabled=true`,
install, sign in to the disposable homeserver with `sqliteStore()` +
`setSessionDelegate()` active (i.e. a normal persistent login, the default
app configuration), and attach `jdb` a few seconds in to confirm the same
two-thread double-poll pattern reappears.

`ReflectiveMatrixHomeserverDiscoveryClientTest`'s existing
`client delivered after the adapter times out is still closed` test already
covers the one piece of this bug class that *is* exercisable on Harbor's own
side of the FFI boundary (a late/duplicate delivery after `buildClient()`
gives up must still be closed, never leaked or double-consumed); no further
Kotlin-level assertion is possible without a fake that can simulate a native
double-poll, which would mean reimplementing the race being diagnosed rather
than guarding against it.
