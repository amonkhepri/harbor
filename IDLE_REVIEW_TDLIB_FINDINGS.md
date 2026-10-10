# Idle review: TDLib concurrency, client lifecycle and account deletion

- Date: 2026-10-10
- Area: `tdlib_concurrency_and_deletion`
- Mode: read-only. No product code was changed; this file is the only change.
- Base: `5be775c` (Merge PR #3, feature-matrix-connector)

## Summary

| # | Area | Verdict |
|---|------|---------|
| 1 | `TelegramTdlibAccessGate` locking semantics | Clean |
| 2 | Lock ordering / deadlock between gate, key provider, `AccountManager` and lifecycle | Clean |
| 3 | Login client lifecycle (create / close / recreate, stale callbacks) | Clean |
| 4 | Message client lifecycle (per-call client) | Clean for use-after-close/double-close; see F3 |
| 5 | Shutdown ordering (`stopService` vs in-flight calls) | **F1 (Low-Medium)**: shutdown can be blocked for minutes |
| 6 | Main-thread access to the gate | **F2 (Low)**: `StartupViewModel` calls `getSnapshot()` on the UI thread |
| 7 | Signed-in deletion (`signOut(deleteAccount=true)`) | Clean ordering; **F3 (Low)**: unconfirmed message-client close can outlive deletion |
| 8 | Pre-sign-in deletion (`StartupViewModel.deleteAccount`) | Clean (safe only because no key is available before sign-in) |
| 9 | Database key handling and key material after deletion | Clean (note on heap residue) |
| 10 | tdlib directory placement and legacy migration vs deletion | Clean |
| — | Out-of-scope observation | **O1**: Telegram login from the startup screen cannot pass `SetTdlibParameters` |

There is no use-after-close, double-close or deadlock. The three findings are about latency and best-effort cleanup. None of them leaks data that can be read after an account is deleted.

---

## 1. `TelegramTdlibAccessGate` locking semantics: Clean

**Traced:** `briar-core/src/main/kotlin/org/briarproject/briar/telegram/TelegramTdlibAccessGate.kt:1-20` and the test `briar-android/src/test/kotlin/org/briarproject/briar/telegram/TelegramTdlibAccessGateTest.kt`.

- `run`, `start` and `stop` all synchronize on one private monitor. `run` checks `acceptingCalls` and runs the call inside the lock, so calls are fully serialized across the login session and the message client.
- `stop` sets `acceptingCalls = false` and runs `close()` inside the same critical section. So `stop` waits for any in-flight `run` to finish, and no `run` can start between the flag flip and the close. Any `run` that gets the monitor after `stop` returns its fallback.
- If `close()` throws (`ServiceException` from `TelegramAuthSessionImpl.stopService`), the flag has already been set to false, so the gate stays closed. This is the safe direction.
- `start()` reopens the gate. It is called only from `TelegramAuthSessionImpl.startService`. Before the lifecycle starts, the gate is open by default (`acceptingCalls = true`). The pre-sign-in login flow depends on this.
- The gate is a Dagger `@Singleton` (`TelegramModule.kt:58-60`), and the same instance is injected into both `ReflectiveTelegramTdlibMessageClient` (`TelegramModule.kt:76-84`) and `TelegramAuthSessionImpl` (`TelegramModule.kt:104-119`). It is not accidentally per-consumer. The constructor defaults (`= TelegramTdlibAccessGate()`) are only used in tests.

## 2. Lock ordering / deadlock: Clean

**Traced:** the gate monitor, `ProtectedConnectorStoreKeyProvider.getStoreEncryptionKey` (`@Synchronized`, `briar-core/.../connector/ConnectorStoreKeyProvider.kt:37-56`), `AccountManagerImpl.getDatabaseKey/deleteAccount` (`bramble-core/.../account/AccountManagerImpl.java:73-75, 189-196`), `LifecycleManagerImpl.stopServices` (`bramble-core/.../lifecycle/LifecycleManagerImpl.java:186-224`), and the TDLib update-handler threads in `TelegramTdlibReflection.kt:44-63`.

- The only nested acquisition is gate → key-provider monitor, through `createTelegramTdlibParametersRequest`, which is only ever called inside `gate.run`. `getDatabaseKey()` is a plain field read with no lock, so `stateChangeLock` is never taken under the gate.
- `deleteAccount` holds `stateChangeLock` and never touches the gate or the key provider. No code path takes the gate (or the provider monitor) while holding `stateChangeLock`.
- TDLib callback threads (`onResult` proxies) only write volatiles and count down `PendingAuthorizationUpdate` latches. They never take the gate, so `stop { closeForShutdown() }` waiting on a close confirmation under the gate cannot deadlock against the callback that delivers it.
- The Matrix connector uses its own `ProtectedConnectorStoreKeyProvider` instance with a different `stateName`, so it has a separate monitor. There is no cross-connector lock interaction.

## 3. Login client lifecycle: Clean

**Traced:** `TelegramAuthSessionImpl.kt:12-63` (session wrapper) and `ReflectiveTelegramTdlibLoginClient`, `TelegramAuthSessionImpl.kt:89-583`. In particular: `start` (129-135), `createTdlibClient` (401-424), `closeTdlibClient` (523-571), `sendForResult` (447-464), and every caller of `tdlibClient!!`.

- **Double-close:** `closeTdlibClient` reads `tdlibClient`, sets it to `null`, and only then sends `Close`. Later calls return `true` at line 529 without touching TDLib. All callers run under the gate, so there is no concurrent double-close.
- **Use-after-close:** the only dereference is `tdlibClient!!` in `sendForResult`. Each caller guards it:
  - `submitIdentifier` restarts when the client is null. `ensureAtWaitPhoneNumber` returns non-null after any path that closed the client, either through `awaitPreparedAuthorizationStateClassName` → `""` → `recoverablePersistedSessionIdentityUnverified`, or through key-unavailable and error paths.
  - `resendCode` and `submitCredential` check `tdlibClient == null` up front.
  - `requestQrAuthorization` runs only when `ensureAtWaitPhoneNumber` returned `null`, which needs `lastAuthorizationStateClassName == WaitPhoneNumber`. Every close resets that field (line 525) and then overwrites it with `Closed` or `""` (line 553 via `awaitAuthorizationUpdate`).

  I found no reachable path where the client is null and the state still reads `WaitPhoneNumber`.
- **Stale callbacks after close/recreate:** every create and close bumps `activeClientGeneration` (lines 403-404, 524). The update handler drops anything from an old generation except an explicitly awaited `AuthorizationStateClosed` (lines 408-413), so a closing instance cannot advance the state machine of a new one.
- **Field visibility:** `tdlibClient` and `nextClientGeneration` are not volatile, but they are only read and written under the gate. Fields that the callback threads touch are `@Volatile`.

## 4. Message client lifecycle: Clean (see F3)

**Traced:** `ReflectiveTelegramTdlibMessageClient.kt:32-136, 269-287`.

- Each public call creates a TDLib client inside `withReadyClient` and closes it in `finally` (line 134). `read(client)` only runs inside the `try`, so the client cannot be used after close. Each client is closed exactly once. If `createTdlibClient` throws, `client` stays `null` and nothing is closed.
- Each call gets a fresh `AtomicReference<PendingAuthorizationUpdate?>`, so callbacks from a previous call's still-closing instance cannot complete the current call's latches.
- `tdlibReadLock` is redundant with the gate, which already serializes everything. It is harmless.

## 5. Shutdown ordering: **F1, Low-Medium: in-flight connector reads can block `stopServices()` for minutes**

**Traced:** `LifecycleManagerImpl.stopServices` (`LifecycleManagerImpl.java:186-224`), `TelegramAuthSessionImpl.stopService` (`TelegramAuthSessionImpl.kt:59-62`), `ReflectiveTelegramTdlibMessageClient.withReadyClient` (`:103-136`), `getRecentThreads` (`:34-44`), `loadRecentChatIds` (`:86-101`), and the callers `ContactListViewModel.kt:107-127` and `TelegramConversationActivity.kt:216-250` (both on `ioExecutor`).

**Issue:** `stopService` runs `gate.stop { ... }`, which must get the gate monitor. A connector read holds that monitor for its whole duration:

- client start (up to 30 s)
- `SetTdlibParameters` (up to 30 s) plus a wait for the next state (up to 30 s)
- an unconditional `Thread.sleep(3_000L)` (line 124)
- up to 5 × (`LoadChats` + `GetChats`) at 30 s each
- one `GetChat` per chat, up to 20 for the contact list, at 30 s each
- close (up to 30 s)

If TDLib or the network stalls, one `getRecentThreads(20)` can hold the gate for more than 10 minutes. `ContactListViewModel` calls `isAuthorized()` and then `getRecentThreads()`, which is two full client lifecycles back to back.

`LifecycleManagerImpl` interrupts executors (`e.shutdownNow()`) only *after* every `stopService` returns, so the interrupt that would end the read early never arrives while `stopService` is blocked. `synchronized` is not interruptible in any case. Meanwhile `BriarControllerImpl.signOut` waits in `service.waitForShutdown()` while holding a wake lock.

**Impact:** sign-out, and sign-out-and-delete, can hang for minutes. If Android kills the process in the meantime, deletion is skipped (`BriarControllerImpl.java:168-171`). That is the safe direction: no partial deletion. But the user asked for deletion and it did not happen. There is no data-safety impact.

**Repro (conceptual):** sign in with an authorized Telegram store. Open the contact list so that `getRecentThreads(20)` starts. Throttle the network so that `GetChat` requests time out at the 30 s limit. Choose "Sign out". Shutdown stays in `STOPPING` until the read loop finishes. Even with a healthy TDLib, every sign-out during a read waits at least the 3 s sleep plus client start and stop.

**Direction (not implemented):** have `stop()` signal in-flight calls to cancel, for example a volatile flag checked between requests in the read loops plus interrupting the holder thread. Or replace the 3 s sleep with an event-driven wait.

## 6. Main-thread gate access: **F2, Low: `StartupViewModel` calls `getSnapshot()` on the UI thread**

**Traced:** `StartupViewModel.java:99-104` (constructor), `:108-114` (`onCleared`), and `TelegramAuthSessionImpl.getSnapshot` (`TelegramAuthSessionImpl.kt:21-29`, which goes through `accessGate.run`).

**Issue:** `ViewModelProvider.get` (`StartupActivity.java:46`) and `onCleared` both run on the main thread. If an auth action on `telegramAuthExecutor` holds the gate at that moment, the main thread blocks on the gate monitor. While TDLib responds, those actions are short. When it stalls, a single action can last tens of seconds: `submitIdentifier` can chain start + parameters + phone number, with each step up to 30 s, and `close` waits up to 30 s for confirmation.

**Repro (conceptual):** on the Telegram login screen, with a stalled TDLib or no network, press "Continue" on the identifier step and immediately press Back so the activity finishes. `onCleared` → `getSnapshot()` blocks the UI thread until the action releases the gate. A wait over 5 s triggers an ANR.

**Impact:** ANR risk only. No correctness or data impact. It is Low because O1 makes most pre-sign-in actions fail fast today.

## 7. Signed-in deletion ordering: Clean; **F3, Low: an unconfirmed message-client close can outlive deletion**

**Traced:** `BriarActivity.signOut` (`BriarActivity.java:211-227`), `BriarControllerImpl.signOut` (`BriarControllerImpl.java:140-176`), `LifecycleManagerImpl.stopServices`, `TelegramAuthSessionImpl.stopService` and `closeForShutdown` (`:349-354`, `closeTdlibClient(waitUntilConfirmed = true)` with a 60 s deadline), `AccountManagerImpl.deleteAccount`, and `AndroidAccountManager.deleteAccount/deleteAppData` (`AndroidAccountManager.java:52-102`).

**Ordering (clean):**

1. `stopServices` → `gate.stop` waits for in-flight calls and closes the gate. No later connector or login call reaches TDLib or the key provider, so the seed or marker cannot be recreated after this point.
2. `closeForShutdown` loops until TDLib confirms `AuthorizationStateClosed` or 60 s pass. If it is not confirmed, it throws `ServiceException`, so `StopResult.ERROR`.
3. `signOut` calls `accountManager.deleteAccount()` only when `waitForShutdown() == SUCCESS` (commit `841a493`). So deletion never runs while the login client's TDLib instance is still running.
4. `deleteAccount` deletes the key directory (seed and marker) and the DB directory (which contains `tdlib/`) under `stateChangeLock`, and sets `databaseKey = null`.

**F3, the gap:** step 2 only covers the *login* client. The message client's per-call close (`ReflectiveTelegramTdlibMessageClient.closeTdlibClient`, `:269-287`) waits for confirmation once, for up to 30 s, and then gives up silently. If TDLib did not confirm in that time, the instance may still be shutting down natively after the gate is released. `stopService` does not know about it, so `StopResult` can be `SUCCESS` and `deleteAccount` can run while that instance still has `tdlib/database` open. It could then recreate files under the deleted directory.

**Impact:** Low. It needs a TDLib `Close` that takes more than 30 s. Any recreated files are encrypted with a key that cannot be derived after deletion: the seed file and `databaseKey` are both gone. The leftover `tdlib/` directory is removed the next time the key provider runs (`resetUnmarkedStore`, `ConnectorStoreKeyProvider.kt:69-81`), because the marker file no longer exists. The process exits right after sign-out, so the stray instance does not survive.

**Repro (conceptual):** make TDLib `Close` hang for more than 30 s during a contact-list read, then sign out and delete. With a real TDLib this is hard to trigger.

**Direction (not implemented):** have the gate or session track message-client instances whose close was not confirmed, and make `stopService` wait for them or report `ServiceException`, the same way the login client does.

## 8. Pre-sign-in deletion (`StartupViewModel.deleteAccount`): Clean

**Traced:** `StartupViewModel.java:329-338`, `runTelegramAuthAction` (`:310-320`), `awaitTelegramQrAuthorization` (`:236-248`), `BriarActivity.signOut` (else branch, `:223-225`), and `ProtectedConnectorStoreKeyProvider.getStoreEncryptionKey`.

- Deletion runs on the single-threaded `PoliteExecutor` `telegramAuthExecutor`: first `telegramAuthSession.close()` (through the gate), then `accountManager.deleteAccount()`. Earlier auth actions on that executor finish first.
- `deleteAccount()` does not bump `telegramAuthGeneration`, and `accountDeletionInProgress` only guards `showTelegramLoginPlaceholder`. So an auth action queued *after* the deletion still runs once deletion finishes, for example after a quick double tap on a submit button. I traced this case. Such an action can only create a TDLib client that is still in `WaitTdlibParameters` state. `getStoreEncryptionKey` returns `null` at `accountManager.databaseKey ?: return null` (line 42). That happens *before* `resetUnmarkedStore` and `readOrCreateSeed`, and `createTelegramTdlibParametersRequest` returns before `mkdirs()`. Nothing is written to disk, and `recoverableDatabaseKeyUnavailable` closes the client. Neither the store nor the seed can be recreated.
- Latent note, not a current bug: if pre-sign-in login could ever reach `QR_WAITING` (see O1), a running `awaitTelegramQrAuthorization` loop would delay the queued deletion by up to `qrAuthorizationTimeoutMs` (300 s), because deletion does not bump the generation that ends the loop.
- `BriarActivity`'s not-signed-in branch calls `deleteAccount()` without closing the Telegram session. Before sign-in, no TDLib instance can have the store open (same reason as above), so this is clean.

## 9. Database key handling and key material: Clean

**Traced:** `TelegramTdlibDatabaseKeyProvider.kt:1-37`, `ConnectorStoreKeyProvider.kt:28-112`, `TelegramTdlibParameters.kt:5-34`, `AccountManagerImpl.deleteAccount`.

- The key is derived on demand on every use and never cached in a field. It is HMAC(databaseKey, seed), then strengthened by the Keystore. It returns `null` (fail closed) before sign-in and after `deleteAccount` sets `databaseKey = null`.
- The seed and marker live in `databaseKeyDirectory`, which `deleteAccount` deletes, so the store key cannot be reconstructed after deletion.
- A version mismatch or missing marker resets the store (`resetUnmarkedStore`), so a store is never opened with a different key.
- A `SetTdlibParameters` with the wrong key surfaces as `TDLIB_DATABASE_KEY_MISMATCH` (`TelegramAuthSessionImpl.kt:184-185`). The message client just returns its fallback.
- Heap residue (informational, not a finding): `seed`, `boundSeed` and the strengthened key `ByteArray` are not zeroed, and the key also sits in the `SetTdlibParameters` request object until GC. This matches Briar's own handling of `databaseKey` (also not zeroed), and the process exits after sign-out and delete. I don't consider it actionable.

## 10. tdlib directory placement and legacy migration: Clean

**Traced:** `TelegramModule.kt:25-56`, `AppModule.java:164-165` (`getDir("db")` and `getDir("key")`), `AndroidAccountManager.deleteAppData` (`:64-102`, `PROTECTED_DIR_NAMES` at `:36-37`).

- The nested `app_db/tdlib` directory is removed by `deleteFileOrDir(databaseDirectory)`.
- If migration falls back to the legacy sibling (`<dataDir>/tdlib`), that directory is still removed on Android: `deleteAppData` deletes every entry of `dataDir` except `cache`, `code_cache`, `lib` and `shared_prefs`. TDLib's `files/` subdirectory is not covered by `databaseEncryptionKey`, but it is removed in both layouts.
- `tdlibDirectory()` runs once per provider (twice in total). The second call sees the legacy directory already migrated and returns `nested`. If a rename fails, both calls fall back to `legacy` the same way, so the two consumers agree on the directory.

---

## O1 (out of scope, functional): Telegram login from the startup screen cannot complete

This doesn't fit the concurrency/deletion brief, but it is a direct consequence of the database-key handling, so I'm recording it here.

The only Telegram login entry point is the button in `PasswordFragment` (`PasswordFragment.java:93-98`). It is only visible in state `SIGNED_OUT`, where `accountManager.hasDatabaseKey()` is false (`StartupViewModel.updateState`). Since P317-001 (`64b9498`), `getStoreEncryptionKey` returns `null` whenever `databaseKey` is null, by design. A fresh TDLib instance always starts in `AuthorizationStateWaitTdlibParameters`. So `ensureAtWaitPhoneNumber` always ends in `recoverableDatabaseKeyUnavailable()` → `RECOVERABLE_ERROR`/`NONE`, and identifier or QR login cannot get past that step. Briar exits the process on sign-out, so `databaseKey` is not left over from an earlier session.

Confidence: medium-high from static tracing. I have not confirmed it on a device. If this is the intended "placeholder" behaviour during the freeze, no action is needed. Otherwise the login flow must move after sign-in, or the key binding must allow a pre-sign-in bootstrap. That is a product decision, not a bug fix.
