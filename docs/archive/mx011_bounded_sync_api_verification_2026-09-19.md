# MX-011 Bounded Matrix Sync API Verification

Date: 2026-09-19. Verify card: `t_31b905af`.

## Scope and method

Pure investigation task: verify the pinned `org.matrix.rustcomponents:sdk-android`
`26.08.05` API surface to determine if bounded foreground refresh is safely possible
without exceeding headroom constraints. No product behavior is changed, per acceptance criteria.

Evidence was gathered with the same technique this repo already uses for
real-AAR proofs (see `build/matrix-delegate-proof/DirectDelegateProof.java`):
extracting the cached pinned AAR's `classes.jar` and disassembling with
`javap` (Android Studio's bundled JBR 21), plus small standalone Java
harnesses compiled/run against the real AAR classes + `kotlin-stdlib` on the
classpath to reproduce the exact reflective call sequences used by
`ReflectiveMatrixHomeserverDiscoveryClient.refreshRooms()`. No network/Matrix
protocol I/O was performed; no SDK version change; no reflective method names
were guessed — every signature below is read directly from the pinned AAR's
bytecode or from a runnable reproduction against it.

AAR location used: `~/.gradle/caches/modules-2/files-2.1/org.matrix.rustcomponents/sdk-android/26.08.05/a061eb68cfaf0aeca4bc356ed7bb0add4c8aad5b/sdk-android-26.08.05.aar`.

## Headroom (review_headroom.sh, before any edit)

Run from the control workspace against this pinned product workspace:

```
$ /Users/amunratis/AndroidStudioProjects/HarborAgent/agent-tools/review_headroom.sh --repo Harbor-autowork            # base: master (default)
insertions: 1294  deletions: 2  review_limit: 5500  feature_freeze_threshold: 5000
headroom: 4206    review_fix_reserve: 500  feature_budget: 5000  status: OK

$ /Users/amunratis/AndroidStudioProjects/HarborAgent/agent-tools/review_headroom.sh --repo Harbor-autowork --base fbf9dea066df60b206fc1217a6f2ee1ba52eff3c
insertions: 497   deletions: 0  review_limit: 5500  feature_freeze_threshold: 5000
headroom: 5003    review_fix_reserve: 500  feature_budget: 5000  status: OK
```

Both baselines report `OK`. A follow-up slice capped at 700 added lines
(1294 + 700 = 1994, or 497 + 700 = 1197 depending on which is the review
unit) stays far under the 5000-line feature-freeze budget and preserves the
500-line review-fix reserve either way. Headroom is not the constraint on
the next slice.

## API surface confirmed on the pinned AAR

```
Client.syncV2(SyncSettingsV2, SyncListenerV2): TaskHandle          // NOT suspend, returns immediately
Client.rooms(): List<Room>                                        // already used by getJoinedRooms
Client.logout(Continuation<Unit>): Unit                            // suspend, throws ClientException
Client.close(): Unit                                               // synchronized, AutoCloseable

SyncListenerV2 (interface): abstract void onUpdate(SyncResponseV2) // sole callback method, no onError/onFailure
SyncResponseV2: { nextBatch: String, rooms: SyncResponseRoomsV2 }  // success payload only, no status/error field

TaskHandle implements TaskHandleInterface, AutoCloseable, Disposable:
  cancel(): Unit         // abstract on TaskHandleInterface
  isFinished(): Boolean
  close(): Unit  -> destroy()   // close() is exactly destroy(); does NOT call cancel() first
  destroy(): Unit               // frees the native handle via UniffiCleaner once per handle
```

Confirmed via `javap -c TaskHandle.class`: `close()`'s bytecode is a single
call to `destroy()`; `destroy()` only releases the FFI/cleaner resource. It
never calls `cancel()`. **Cancelling an in-flight `syncV2` loop requires an
explicit `cancel()` call before `close()`** — relying on `close()`/GC alone
is not documented or evidenced to abort the underlying async loop. The
already-shipped `cancelAndCloseQuietly()` in
`ReflectiveMatrixHomeserverDiscoveryClient.kt` already does `cancel()` then
`close()` in that order — this is correct and should not change.

**Failure signalling**: `SyncListenerV2` exposes only `onUpdate`; there is no
error/failure callback on this listener shape in the pinned AAR. A sync
attempt that fails or stalls server-side is invisible to the caller except
as *silence* (no `onUpdate` call). The shipped design — a `CountDownLatch`
counted down only by the first `onUpdate`, with a local timeout as the only
other unblock path — is therefore the correct/only shape obtainable from
this listener interface; there is no missed richer callback being ignored.
(A separate, heavier `SyncService`/`SyncServiceBuilder`/`SyncServiceStateObserver`
API exists on the AAR with an explicit state machine, but it is a persistent
background sync service, not a bounded foreground refresh, and is out of
scope per the card's non-goals.)

**Success signal**: `onUpdate(SyncResponseV2)` carries no
"caught up"/"is-final" flag — any single callback (even one with no room
diffs) is "a sync round-trip happened." The shipped code treats the first
`onUpdate` as success for `refreshRooms()`'s purposes; that is a deliberate,
reasonable choice for "bounded foreground refresh," not a gap.

## Critical finding: the shipped `SyncSettingsV2` construction is broken against the real SDK

`ReflectiveMatrixHomeserverDiscoveryClient.refreshRooms()` builds the sync
settings via:

```kotlin
val constructor = settingsClass.constructors.first { it.parameterTypes.size == 4 }
val settings = constructor.newInstance(0L, false, 3, null)
```

This selects `SyncSettingsV2`'s Kotlin-default-args synthetic constructor
(`(ULong, Boolean, Int, DefaultConstructorMarker)`, mask=3 meaning "both
args use defaults"). On the **pinned AAR**, `javap -v` shows this
constructor's real descriptor is:

```
(Lkotlin/ULong;ZILkotlin/jvm/internal/DefaultConstructorMarker;)V
```

i.e. the first parameter's declared type is the *boxed reference type*
`kotlin.ULong`, not primitive `long`. Reflectively passing `0L` (which boxes
to `java.lang.Long`) is not assignable to `kotlin.ULong` and Java reflection
rejects it before the constructor body (and its default-value bitmask
logic) ever runs.

Reproduced directly against the real AAR + `kotlin-stdlib-2.2.0.jar` on the
classpath (harness compiled/run with Android Studio's bundled JBR 21):

```
Chosen ctor: public org.matrix.rustcomponents.sdk.SyncSettingsV2(kotlin.ULong,boolean,int,kotlin.jvm.internal.DefaultConstructorMarker)
THREW: java.lang.IllegalArgumentException: argument type mismatch
Caused by: java.lang.ClassCastException: Cannot cast java.lang.Long to kotlin.ULong
```

That exception is caught by `refreshRooms()`'s own `catch (e: Exception) { false }`,
so **`refreshRooms()` unconditionally returns `false` against the real pinned
SDK**, and `MatrixRoomConnector.getRecentThreads()` unconditionally throws
`RuntimeException("Matrix room sync failed or timed out")` for every
authorized Matrix session. `Client.syncV2` is never actually reached in
production.

This is masked in the existing test suite because `FakeMatrixSdk.kt`
declares its own `SyncSettingsV2(val timeoutMs: ULong = 0u, val fullState:
Boolean = false)`. Inspecting this repo's own compiled test double
(`briar-android/build/tmp/kotlin-classes/officialDebugUnitTest/org/matrix/rustcomponents/sdk/SyncSettingsV2.class`)
shows its 4-arg constructor descriptor is `(JZILkotlin/jvm/internal/DefaultConstructorMarker;)V`
— `ULong` erased to primitive `long`, unlike the real AAR's boxed
`kotlin.ULong`. The fake's shape does not match the pinned AAR's shape for
this constructor, so this exact defect passes all existing unit tests and
would only surface against a real client/homeserver.

## Verified working call sequence (reproduced against the real AAR)

```
val ulongClass = Class.forName("kotlin.ULong")
val boxImpl = ulongClass.getMethod("box-impl", Long::class.javaPrimitiveType)
val boxedTimeout = boxImpl.invoke(null, timeoutMs)              // kotlin.ULong.box-impl(Long): ULong

val markerClass = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
val ctor = settingsClass.getConstructor(ulongClass, Boolean::class.javaPrimitiveType, markerClass)
val settings = ctor.newInstance(boxedTimeout, fullState, null)  // bypasses the buggy 4-arg/mask ctor entirely

val handle = client.javaClass.getMethod("syncV2", settingsClass, listenerClass)
    .invoke(client, settings, listener) as Any                  // unchanged from the shipped code

// on timeout / interrupt / logout / close / client replacement:
handle.javaClass.getMethod("cancel").invoke(handle)
handle.javaClass.getMethod("close").invoke(handle)
```

Reproduced end to end against the real AAR:

```
boxed ULong: 0 class=class kotlin.ULong
SUCCESS constructing via 3-arg ctor: SyncSettingsV2(timeoutMs=0, fullState=false)
readback timeoutMs (raw long): 0
fullState: false
```

`kotlin.ULong.box-impl(long): ULong` is a `public static final` method on
`kotlin.ULong` (part of `kotlin-stdlib`, already a transitive dependency),
so this requires no new dependency, no SDK upgrade, and no HTTP/protocol
code — only a corrected reflective construction of one settings object.

## Lifecycle constraints for the implementer

- `syncV2` is synchronous/non-suspend; call it directly, no `Continuation`
  bridging needed (the shipped code already does this correctly).
- Guard every path that can retire or replace the retained client
  (`login`, `restore`, `logout`, `close`) with `cancel()` then `close()` on
  any outstanding `TaskHandle` before dropping the reference — `close()`
  alone does not cancel (verified above). The shipped `cancelPendingSync()`/
  `cancelAndCloseQuietly()` already does this and should be preserved as-is.
- There is no SDK-side failure callback for `syncV2`; a bounded refresh's
  only failure signal is its local timeout. Do not add speculative
  `onError`-handling code — the interface has no such method to implement.
- The one required code change is narrowly scoped: replace the 4-arg/mask
  `SyncSettingsV2` construction with the verified `box-impl` + 3-arg-ctor
  sequence above, and update/replace `FakeMatrixSdk.kt`'s `SyncSettingsV2`
  fake so its constructor shape (boxed `ULong`) matches the real AAR, so a
  regression like this is caught by the existing test suite rather than only
  by a real client.

## Conclusion

**Feasible.** The pinned SDK supports a bounded foreground refresh on the
retained authenticated client (`Client.syncV2` + `TaskHandle.cancel()`), and
it can be safely cancelled on timeout, interruption, logout, close, and
client replacement using the handle lifecycle confirmed above — the
already-shipped cancellation/replacement guarding logic in
`ReflectiveMatrixHomeserverDiscoveryClient` is correct and needs no redesign.

The one blocker is a concrete, narrow defect, not a design gap: the shipped
`SyncSettingsV2` construction throws against the real pinned AAR (verified
by direct reproduction) because it passes a raw `Long` to a constructor
parameter the real AAR declares as boxed `kotlin.ULong`, so `refreshRooms()`
always returns `false` in production. The fix is the ~5-line reflective
sequence verified above (`ULong.box-impl` + the AAR's public 3-arg
constructor), well inside the measured headroom on both candidate review
bases. Recommend scoping the next card to exactly that fix plus a
`FakeMatrixSdk.kt` shape correction (so the test double matches the AAR's
boxed-`ULong` constructor and this defect class is caught locally), rather
than any broader sync-service or continuous-background-sync work.
