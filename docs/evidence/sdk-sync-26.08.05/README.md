# sdk-android 26.08.05 sync API investigation

Task: `t_7081668c`. Date: 2026-09-20. Investigation only; no product changes.

The pinned SDK exposes three distinct entry points: awaitable one-shot
`Client.syncOnceV2`, callback-based `Client.syncV2`, and `SyncService`.
The older `docs/archive/mx011_bounded_sync_api_verification_2026-09-19.md`
missed `syncOnceV2`; its claim that a listener plus local timeout is the only
available approach must not guide downstream implementation.

## Reproduction and scope

Run from the repository root:

```sh
python3 docs/evidence/sdk-sync-26.08.05/reproduce.py
```

The script runs the existing HarborAgent SDK verifier, extracts only
`classes.jar` from the cached 26.08.05 AAR, and uses `javap -p -c` on the
explicit class list in the script. It does not load SDK classes, use reflection,
contact a homeserver, upgrade dependencies, or implement protocol handling.
`MATRIX_AAR`, `MATRIX_VERIFIER`, and `JAVAP` override local paths.
[identity.txt](identity.txt) records the artifact/verifier hashes and JDK version;
[signatures.txt](signatures.txt) contains exact JVM declarations.

[verifier.txt](verifier.txt) records exit 1: the source guard expects two
`Proxy.newProxyInstance` occurrences (TimelineListener and SyncListenerV2),
whereas the current reflection source contains one. No missing AAR signature
is reported. This is **not a passing verifier** and its final generic API-shape
failure message does not establish that the sync APIs below are absent.
The verifier does not itself check all these APIs; the dumps supply that evidence.

## Exact public surface

All SDK types below are in `org.matrix.rustcomponents.sdk`. These are JVM
signatures, with fully qualified Kotlin continuation types retained. Suspend
methods return either the result or the coroutine suspended marker at the JVM
boundary; `Object` is not the Kotlin result type.

```java
// Client (syncOnceV2 is a Kotlin suspend function returning SyncResponseV2)
public Object syncOnceV2(SyncSettingsV2 settings,
    kotlin.coroutines.Continuation<? super SyncResponseV2> continuation)
    throws ClientException;
public TaskHandle syncV2(SyncSettingsV2 settings, SyncListenerV2 listener);
public SyncServiceBuilder syncService();

// SyncServiceBuilder (suspend, result SyncService)
public Object finish(kotlin.coroutines.Continuation<? super SyncService> continuation)
    throws ClientException;

// SyncService (start/stop are suspend, result kotlin.Unit)
public Object start(kotlin.coroutines.Continuation<? super kotlin.Unit> continuation);
public Object stop(kotlin.coroutines.Continuation<? super kotlin.Unit> continuation);
public TaskHandle state(SyncServiceStateObserver observer);

// Callback interfaces
public abstract void onUpdate(SyncResponseV2 response); // SyncListenerV2
public abstract void onUpdate(SyncServiceState state); // SyncServiceStateObserver

// TaskHandle
public void cancel();
public boolean isFinished();
public void destroy();
public synchronized void close();
```

Sources: [Client](Client.javap.txt), [builder](SyncServiceBuilder.javap.txt),
[service](SyncService.javap.txt), [handle](TaskHandle.javap.txt), and
[interfaces](signatures.txt). There is no join/await/result method on
`TaskHandleInterface`, nor a separate sync-completion await on
`SyncServiceInterface`. Awaiting `start()` must not be interpreted as awaiting
one successful server refresh: it returns Unit, not a response.

## Completion, errors, threading, and cancellation

| Operation | Observable completion | Failure and cancellation | Evidence |
| --- | --- | --- | --- |
| `syncOnceV2(settings, continuation)` | Await yields `SyncResponseV2`; no listener or TaskHandle is returned. | Native error status is lifted through `ClientException.ErrorHandler`; coroutine bridge propagates failure and frees the future on exceptional exit, including cancellation. | `Client.javap.txt`, `Client$syncOnceV2$3` through `$6.javap.txt`, `Matrix_sdk_ffiKt.javap.txt` |
| `syncV2(settings, listener)` | Synchronous creation returns TaskHandle; `onUpdate(response)` is a response signal. Handle creation alone is not refresh completion. | Listener has no error callback. `isFinished()` gives a Boolean, not a success/error result. Explicit `cancel()` is available. Call/setup failures can still throw; silence is not proof of a particular error. | `Client.javap.txt`, `signatures.txt`, `TaskHandle.javap.txt` |
| `client.syncService()` then `builder.finish()` | Synchronous builder creation, then await yields SyncService. | `finish` uses `ClientException.ErrorHandler` and the same cancellable coroutine bridge. | `Client.javap.txt`, `SyncServiceBuilder.javap.txt` |
| `service.start()` / `stop()` | Await yields Unit for the respective native operation. | No declared typed ClientException handler: both use `UniffiNullRustCallStatusErrorHandler`. Bridge/native internal errors can still throw. Cancelling the await frees its future; it is not evidence that cancellation of `start` is equivalent to calling `stop`. | `SyncService.javap.txt`, `SyncService$start$5.javap.txt`, `SyncService$stop$5.javap.txt`, shared bridge |
| `service.state(observer)` | Returns subscription TaskHandle; observer receives enum values IDLE, RUNNING, TERMINATED, ERROR, OFFLINE. | ERROR is an exposed state, with no error details on this callback. Native transition order, timing, and replay behavior are not established. Cancel subscription with its handle; that handle does not prove ownership of the service's sync loop. | `SyncService.javap.txt`, `signatures.txt` |
| `handle.cancel()` / `isFinished()` | Synchronous native void / Boolean result. | Neither exposes a join or typed failure result. No proof that cancel waits for all callbacks to finish. | `TaskHandle.javap.txt` |

`SyncResponseV2` contains `nextBatch: String` and `rooms: SyncResponseRoomsV2`;
room fields are string lists `invited`, `joined`, `left`, `knocked`. There is
no final/caught-up/error flag in these payloads. A returned/delivered response
is the available success signal, not proof of every later storage/UI effect.
See [response](SyncResponseV2.javap.txt) and [rooms](SyncResponseRoomsV2.javap.txt).

The generated callback adapter looks up the listener, lifts the payload, and
calls `onUpdate` directly on the native-to-JVM callback thread. It does not
post to Android's main thread or an executor. Callback thread identity,
serialization, and whether delivery can precede the entry call's return are
not established by these dumps. See
[listener adapter](uniffiCallbackInterfaceSyncListenerV2$onUpdate.javap.txt),
[listener invocation](uniffiCallbackInterfaceSyncListenerV2$onUpdate$callback$makeCall$1.javap.txt),
and [state invocation](uniffiCallbackInterfaceSyncServiceStateObserver$onUpdate$callback$makeCall$1.javap.txt).
Callback exceptions are converted to FFI error status by the adapter; they
are not a sync failure callback to the original caller.

For suspend operations, the [shared bridge](Matrix_sdk_ffiKt.javap.txt)
constructs an intercepted `CancellableContinuationImpl`, initializes
cancellability, polls the Rust future, checks completion status, lifts the
result, and invokes the future-free function on normal and exceptional exits.
It does not select an Android dispatcher. Resume dispatch is delegated to the
continuation machinery; no fixed callback/resume thread is promised here.
For one-shot sync, `$3` polls, `$4` completes, `$5` frees the Rust-buffer
future, and `$6` lifts the response. The service start/stop free functions use
the void-future equivalent. There is no public per-call cancel handle for
`syncOnceV2`: the exposed cancellation mechanism is its coroutine context.
These findings establish wrapper cancellation/cleanup, **not a measured bound
on native network shutdown or completion of side effects**.

## Object lifetime

- Create settings as a value object. The constructor dump shows nullable boxed
  `kotlin.ULong` timeout and Boolean fullState, with defaults null and false.
  Public JVM constructors are `(kotlin.ULong, boolean, int,
  DefaultConstructorMarker)` and `(kotlin.ULong, boolean,
  DefaultConstructorMarker)`. The former is the default-mask overload.
  Do not confuse boxed ULong with `java.lang.Long`. No close/dispose method
  exists on settings or response values. Evidence: `SyncSettingsV2.javap.txt`
  and response dumps. The timeout field alone does not prove a wall-clock bound.
- Obtain handles from `syncV2` or `state`, and builder/service objects through
  `syncService`/`finish`. Their dumps show native handle cloning and guards,
  not a requirement to construct native pointer wrappers manually.
- Client, builder, service, and TaskHandle expose `close()` -> `destroy()`.
  Destruction uses an atomic once flag and call counter; cleanup is deferred
  while wrapper calls are active. Calls after completed destruction throw
  `IllegalStateException` ("object has already been destroyed"). Do not reuse
  wrappers after close or rely on racing close against another call.
  Evidence: their respective `destroy`, `close`, and guarded method dumps.
- Handle `cancel()` calls the native cancel entry point; `close()` does not
  call that entry point in JVM bytecode. Explicitly cancel an owned active task
  before releasing its handle, and explicitly stop an owned running service
  before releasing it. This is a conservative ownership recommendation;
  native destructor side effects are not proven by javap. In particular, the
  older report's unconditional claim that close cannot cancel native work is
  stronger than this evidence supports. `TaskHandle$UniffiCleanAction.javap.txt`
  shows native free, whose internal behavior is opaque here.
- Callback lowering inserts a strong reference into `UniffiHandleMap`; the
  native callback free trampoline removes it. Thus returning from registration
  does not itself drop the listener. Actual native release timing and late
  callback exclusion after cancel/stop remain unverified. Evidence:
  `FfiConverterCallbackInterface.javap.txt`, `UniffiHandleMap.javap.txt`, and
  `uniffiCallbackInterfaceSyncListenerV2$uniffiFree.javap.txt`.
- The asynchronous client call clones a native handle before starting its
  future and then releases the wrapper call counter; keeping the Java wrapper
  alive is not a demonstrated native-future completion barrier. As an ownership
  policy, finish/cancel owned work before retiring the session/client. No claim
  is made that closing a Client automatically cancels all derived work.

## Limits and downstream use

This evidence establishes signatures and generated JVM behavior. Native
scheduler identity, cancellation acknowledgment timing, callback quiescence,
service transition semantics, server timeout interpretation, and exact
server/storage completion guarantees cannot be certified from these class
bodies. Do not replace those unknowns with reflective guesses or infer that
`isFinished == true`, RUNNING, or a completed `start()` means a successful
refresh. `syncOnceV2` is the available awaitable response/error API to evaluate
for a bounded refresh. Product integration and runtime/native verification
remain separate work.
