# MX-074 Step 2/2: Matrix Two-Account Real Device Proof and Pass/Fail Report

- Task: `t_9345a4ed` (MX-074 Step 2/2, split from `t_a2a70854`)
- Pinned Workspace: `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_9345a4ed`
- Workspace HEAD: `73b8e9f12e20373419a2da0ee3e91f11b0c2e08b`
- Review Base: `73b8e9f12e20373419a2da0ee3e91f11b0c2e08b`
- Device: `emulator-5554` (`sdk_gphone64_arm64`), app `org.briarproject.briar.android.debug`
- Homeserver: Disposable local Synapse (`127.0.0.1:8008`, forwarded via `adb reverse tcp:8008 tcp:8008`)
- Parent Dependencies:
  * `t_87aebddf` (Step 1/2, done: build/install and raw homeserver URL discovery fix)
  * `t_a895a2cb` (Step 2a, done: local profile unlock verification and login hang isolation)
  * `t_a5a22be6` (done, approved run #1495: thread-dump root cause diagnosis of `ClientBuilder.build()` hang)

---

## 1. Scope & Execution Rules

Per the card specification:
- This card covers ONLY the remaining verification steps:
  1. Account `harbor_test_b` joins the same room via an independent client.
  2. Verify remote client sees a message sent from Harbor (`harbor_test_a`).
  3. Verify Harbor sees a message sent from remote client after force-stop and relaunch without clearing app data.
  4. Record pass/fail per step, distinguishing real device/network proof from simulated unit/Maestro tests.
- Execution boundary: Do NOT rebuild/reinstall/re-login unless parent cards left the device in a broken state; if prerequisite login did not succeed, record pass/fail with concrete evidence rather than silently redoing work.

---

## 2. Prerequisite Audit & Device Preflight

1. **Prerequisite `t_a895a2cb` Status**:
   - `t_a895a2cb` documented in `docs/reviews/matrix_account_a_login_mx074_step2a.md` (commit `cd893e848`):
     * Local profile unlock: **PASS** (unlocked via fixed test password).
     * Account A login: **FAIL** (flat ~30s timeout in `ClientBuilder.build()` specifically when `sqliteStore()` + `setSessionDelegate()` are combined).

2. **Parent Task `t_a5a22be6` Root Cause Finding**:
   - `t_a5a22be6` documented in `docs/reviews/matrix_account_a_login_mx074_clientbuilder_hang_root_cause.md` (commit `cddf35a2c`):
     * Captured JDWP thread dumps during the 30s hang.
     * Proved an upstream concurrency defect in `org.matrix.rustcomponents:sdk-android:26.08.05`: when `sqliteStore()` and `setSessionDelegate()` are configured together on `ClientBuilder`, the internal UniFFI Rust future is polled concurrently from two native Tokio/UniFFI threads simultaneously, leaving the future permanently unnotified and unresolved.
     * Concluded that no Kotlin reflection bridge fix is possible for this pinned AAR artifact; approved as a verified upstream blocker.

3. **Current Device State Audit (`emulator-5554`)**:
   - Device attached and active (`adb devices` -> `emulator-5554 device`).
   - App installed: `org.briarproject.briar.android.debug` (last update timestamp `2026-10-26 03:29:15`).
   - Internal storage audit (`adb shell run-as org.briarproject.briar.android.debug ls -la files/ shared_prefs/`):
     * Only Briar core databases (`app_db/db.mv.db`) and basic preferences exist.
     * No Matrix SQLite database, session files, or token caches were created or stored because `ClientBuilder.build()` never completed.
   - Synapse Homeserver status (`curl -s http://127.0.0.1:8008/_matrix/client/versions`):
     * Synapse process is running and responsive on `127.0.0.1:8008` (adb reversed).
     * No public or shared room exists on the homeserver.

---

## 3. Step-by-Step Device Execution & Pass/Fail Results

### Step 1: Account B Room Join via Independent Client
- **Action**: Attempt to have `harbor_test_b` join the room established by Account A.
- **Result**: **FAIL (BLOCKED)**
- **Evidence**:
  * The parent card `t_a895a2cb` failed to log in Account A (`harbor_test_a`) due to the `ClientBuilder.build()` native hang.
  * No Matrix room was ever created by Account A on the disposable homeserver.
  * Account B has no target room identifier to join.
  * Per card contract ("must not start until t_a895a2cb reports a verified login"), Step 1 is blocked by the upstream SDK login failure.

### Step 2: Remote Client Observes Message Sent from Harbor
- **Action**: Send a text message from Harbor (Account A) and observe receipt on the remote client (Account B).
- **Result**: **FAIL (BLOCKED)**
- **Evidence**:
  * Harbor has no authenticated Matrix session (`MatrixAuthSession` is not in `READY` state).
  * No conversation UI or room timeline can be opened for Matrix in Harbor.
  * Message dispatch from Harbor to Account B cannot occur.

### Step 3: Session and Message Persistence Across Force-Stop and Relaunch
- **Action**: Test persistence of Matrix session and message history across an app restart without data clearing (`am force-stop` followed by relaunch).
- **Result**: **FAIL (BLOCKED)**
- **Evidence**:
  * Executed on device:
    ```bash
    adb shell am force-stop org.briarproject.briar.android.debug
    adb shell am start -n org.briarproject.briar.android.debug/org.briarproject.briar.android.splash.SplashScreenActivity
    ```
  * App restarts cleanly and returns to `StartupActivity` (local profile password screen).
  * Unlocking the local profile returns to `NavDrawerActivity` / `ContactListFragment`.
  * Because Account A was never logged in, no Matrix SQLite session database was written to `/data/data/org.briarproject.briar.android.debug/`.
  * Persistence across relaunch cannot be validated for non-existent Matrix session and message data.

---

## 4. Distinction Between Proof Types

Per acceptance criterion 4: "Clearly distinguish this real device/network proof from the existing simulated-SDK unit-test and generic-UI-Maestro proof already recorded for MX-044 -- do not recount those as satisfying this item."

1. **Simulated-SDK Unit Test Proof (PASS under default-off harness)**:
   - Command:
     ```bash
     ./gradlew :briar-android:testOfficialDebugUnitTest --tests "org.briarproject.briar.matrix.*"
     ```
   - Configuration: Default-off (`harbor.matrixConnector.enabled=false`).
   - Mechanism: Replaces real native `sdk-android:26.08.05.aar` binaries with `FakeMatrixSdk` test fakes in JVM memory.
   - Limitation: `FakeMatrixSdk` uses cooperative single-threaded coroutine continuations and does not model native UniFFI thread pools, Tokio async runtimes, or multi-threaded polling races. Unit tests pass 100% but do NOT prove real native client behavior.

2. **Generic-UI Maestro Proof (PASS on stubbed flows)**:
   - Automated E2E flows (e.g. at `cddf35a2c` passing at 03:34) exercise navigation, contact list rendering, and local UI components.
   - Limitation: Maestro flows test the Android UI views and mock flows; they do not exercise live Matrix network sockets, Synapse federation, or native Rust UniFFI bindings.

3. **Real Device / Network Proof (FAIL / BLOCKED by Upstream Bug)**:
   - Real device (`emulator-5554`) connected over network (`adb reverse tcp:8008`) to a live Synapse instance.
   - Exercising the actual native `org.matrix.rustcomponents:sdk-android:26.08.05` client library with persistent SQLite storage fails deterministically during `ClientBuilder.build()` due to the UniFFI multi-thread waker deadlock documented in `t_a5a22be6`.
   - Real two-account messaging on-device cannot succeed until an updated, non-deadlocking SDK artifact is pinned.

---

## 5. Summary & Recommendation

| Step | Requirement | Status | Cause / Evidence |
|------|-------------|--------|------------------|
| Step 1 | Account B join room | **FAIL (BLOCKED)** | No room created; Account A login failed due to upstream SDK bug |
| Step 2 | Remote sees Harbor message | **FAIL (BLOCKED)** | Harbor has no authenticated Matrix session |
| Step 3 | Force-stop/relaunch persistence | **FAIL (BLOCKED)** | No Matrix session/messages exist to persist or restore |
| Step 4 | Pass/Fail report recorded & distinguished | **PASS** | Distinctions between simulated, Maestro, and real proof documented |

**Conclusion**:
The real device/network proof for MX-074 Step 2/2 is blocked by the upstream `sdk-android:26.08.05` UniFFI race root-caused in `t_a5a22be6`. Together with `t_87aebddf`, `t_a895a2cb`, and `t_a5a22be6`, the full technical status of MX-074 / `t_a2a70854` is documented with definitive device, thread-dump, and network evidence. Next steps require a separate card to upgrade or patch `sdk-android`.
