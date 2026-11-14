# E2E Regression Analysis: 01-04 Flows (post t_72fc7241 / commit 9e2f36c2f)

- Task: `t_0da11d68`
- Failing flows:
  - `agent-files/qa-e2e/flows/01-setup-login.yaml`
  - `agent-files/qa-e2e/flows/02-add-contact.yaml`
  - `agent-files/qa-e2e/flows/03-send-receive-message.yaml`
  - `agent-files/qa-e2e/flows/04-conversation-list.yaml`
- Evidence: `/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-tools/screenshots/e2e-20261114T201930Z/` (`maestro.log`, `results.xml`, `install.log`, `gradle.log`, `emulator.log`)
- Debug artifacts: `~/.maestro/tests/2026-11-14_212932/` (`device-logcat.txt`, `step-004-assertCondition-Welcome_to_Harbor.json`, `step-004-assertCondition-Welcome_to_Harbor.png`)
- Base commit reported: `9e2f36c2f691f7c2c6523b0a3bbd842bbafdebdd` (`fix(matrix): require matching room notification readback (MX-067)`)
- Delivered task: `t_72fc7241`
- Device: `Pixel_7` AVD (`emulator-5554`)

---

## Verdict: Provisional Diagnosis — Probable Host Infrastructure Starvation

**Status: Provisional (pending clean device/emulator gate verification).**

The failure across all four flows exhibits strong hallmarks of host virtualization starvation during cold boot and initial package cleanup rather than a functional regression introduced by commit `9e2f36c2f`:

1. **Host Virtualization Delays**: The emulator took 76.4 seconds just to restore its `default_boot` snapshot under SwiftShader software Vulkan emulation.
2. **Package Clear Latency**: Maestro's `clearState: true` (`pm clear`) took 37–44 seconds per flow due to host I/O saturation.
3. **Cold Startup Frame Drops**: `SplashScreenActivity` startup was delayed by ~19 seconds during main thread Dagger initialization in `BriarApplicationImpl.onCreate`, dropping 361 frames (`Choreographer: Skipped 361 frames! The application may be doing too much work on its main thread`).
4. **Rendering Pipeline Stall**: The window manager remained blocked in `BLASTSyncEngine DRAW_PENDING` while EGL/HWUI re-initialized graphics swap configurations without preserved swap behavior.
5. **Timeout on Blank Screen**: Maestro's 18.6-second assertion timeout for `"Welcome to Harbor" is visible` expired before `SplashScreenActivity` could draw its view hierarchy or trigger the transition to `SetupActivity`. The captured view hierarchy (`step-004-assertCondition-Welcome_to_Harbor.json`) contained exclusively system status bar icons, with 99.96% of pixels completely black.
6. **Subsequent Host Hang**: The immediately following gate run (`t_d40188ed` at `20261114T210836Z`) demonstrated total host virtualization failure: the emulator failed to complete boot within the 240-second timeout.
7. **Architectural Orthogonality**: Commit `9e2f36c2f` modified only Matrix room notification readback confirmation in `briar-matrix` and associated unit fakes, with zero coupling to application startup, Dagger entry graphs, or account setup UI.

**Provisional Requirement**: Because the host emulator entered a degraded state that prevented subsequent runs from completing, and the test device is currently unavailable / leased by other pipeline operations, this diagnosis remains **provisional**. A definitive closure requires a successful run of flows `01` through `04` on a healthy emulator or physical device.

---

## 1. Context and Evidence Analysis

Following the delivery of task `t_72fc7241` in commit `9e2f36c2f`, the post-delivery Maestro E2E gate reported failures across all four flows in run `20261114T201930Z`:

```xml
<testsuites>
  <testsuite name="Test Suite" device="emulator-5554" tests="4" failures="4" time="316.019" timestamp="2026-11-14T21:32:11">
    <testcase id="02-add-contact" name="02-add-contact" classname="02-add-contact"
              file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/02-add-contact.yaml"
              time="92.021" timestamp="2026-11-14T21:32:11" status="ERROR">
      <failure>Assertion is false: "Welcome to Harbor" is visible</failure>
    </testcase>
    <testcase id="04-conversation-list" ... time="88.344" status="ERROR">
      <failure>Assertion is false: "Welcome to Harbor" is visible</failure>
    </testcase>
    <testcase id="01-setup-login" ... time="73.084" status="ERROR">
      <failure>Assertion is false: "Welcome to Harbor" is visible</failure>
    </testcase>
    <testcase id="03-send-receive-message" ... time="61.997" status="ERROR">
      <failure>Assertion is false: "Welcome to Harbor" is visible</failure>
    </testcase>
  </testsuite>
</testsuites>
```

Key observations from gate logs:
- **Build and Installation**: `gradle.log` reports `BUILD SUCCESSFUL in 36s` and `install.log` reports `Performing Streamed Install` followed by `Success`. The APK compiled and installed cleanly.
- **Identical Failure Mode Across All Flows**: Every flow failed at step 1 immediately after `launchApp: clearState: true` when asserting `"Welcome to Harbor" is visible`.
- **Anomalous Latencies**: `02-add-contact` (92.0s), `04-conversation-list` (88.3s), `01-setup-login` (73.1s), and `03-send-receive-message` (62.0s) spent their entire duration in app launch and the first UI assertion timeout without ever reaching secondary steps.

---

## 2. Diff Audit & Controlled Architectural Comparison

### 2.1 Audit of Commit 9e2f36c2f (t_72fc7241)
Commit `9e2f36c2f` ("fix(matrix): require matching room notification readback (MX-067)") modified 3 files:
1. `briar-matrix/src/main/kotlin/org/briarproject/briar/matrix/ReflectiveMatrixHomeserverDiscoveryClient.kt` (+4, -1):
   Requires matching readback from the Matrix Rust SDK when setting room notification mode.
2. `briar-android/src/test/kotlin/org/briarproject/briar/matrix/MatrixRoomNotificationsTest.kt` (+14, -0):
   Adds unit test `acknowledged write requires matching server readback`.
3. `briar-android/src/test/kotlin/org/matrix/rustcomponents/sdk/FakeMatrixSdk.kt` (+3, -1):
   Adds `ignoreUpdate` test knob.

### 2.2 Controlled Code Path Comparison: App Startup vs. Commit Changes
Tracing the execution path from cold launch to `"Welcome to Harbor"`:
1. **Entry Intent**:
   The launcher starts `org.briarproject.briar.android.splash.SplashScreenActivity`.
2. **Application Creation (`BriarApplicationImpl.onCreate`)**:
   Dagger components are built (`AndroidComponent`, `BriarServiceComponent`). Matrix components (`MatrixRoomConnector`, `ReflectiveMatrixHomeserverDiscoveryClient`) are lazy-initialized and wired only to connector services.
3. **Splash Activity Routing**:
   `SplashScreenActivity` queries `AccountManager.accountExists()`.
   - On a clean install (`clearState: true`), no Briar account exists in the database.
   - `SplashScreenActivity` schedules a 500ms post-delayed runnable to start `org.briarproject.briar.android.account.SetupActivity`.
4. **Setup Screen Rendering (`SetupActivity`)**:
   `SetupActivity` creates `SetupFragment`, inflating `activity_setup.xml` containing `R.string.welcome_title` ("Welcome to Harbor").
5. **Coupling Analysis**:
   - Zero classes modified in commit `9e2f36c2f` are imported or referenced by `SplashScreenActivity`, `SetupActivity`, `SetupFragment`, `SetupViewModel`, or `AccountManager`.
   - The Matrix connector is default-off, and no Matrix account exists on fresh startup.
   - Therefore, commit `9e2f36c2f` has zero direct or transitive influence on the rendering of `SetupActivity` or `"Welcome to Harbor"`.

---

## 3. Deep Failure Mechanics and Device Logcat Analysis

Investigation of Maestro diagnostic artifacts at `~/.maestro/tests/2026-11-14_212932/`:

### 3.1 Cold Boot and Snapshot Restoral Lag
In `emulator.log`:
```
INFO | Loading snapshot 'default_boot'...
INFO | Successfully loaded snapshot 'default_boot' using 76383 ms
```
The emulator required 76.4s to load `default_boot`. Software OpenGL ES translation via SwiftShader (`ANGLE Google, Vulkan 1.3.0 SwiftShader Device LLVM 10.0.0`) heavily saturated host CPU.

### 3.2 Clear State Latency
In `01-setup-login/commands.json`:
- `launchAppCommand` with `clearState: true` took **51.1 seconds** (`duration: 51136`).
- In `device-logcat.txt`, `pm clear` triggered extensive package cleanup across `ShortcutService`, `MediaProvider`, `FuseDaemon`, and `Icing`, blocking between 21:35:12 and 21:35:56 (44s).

### 3.3 Main Thread Frame Drops & BLASTSyncEngine DRAW_PENDING
At 21:35:56.722, Maestro issued the launcher intent:
```
11-14 21:35:56.722 I/ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 pkg=org.briarproject.briar.android.debug cmp=org.briarproject.briar.android.debug/org.briarproject.briar.android.splash.SplashScreenActivity}
11-14 21:35:59.645 I/ActivityManager: Start proc 18626:org.briarproject.briar.android.debug/u0a214 for next-top-activity {SplashScreenActivity}
11-14 21:36:01.777 I/BLASTSyncEngine: Window{3b85023 u0 Splash Screen org.briarproject.briar.android.debug} DRAW_PENDING
```
Maestro marked `launchApp` finished at 21:36:03.930 and began polling for `"Welcome to Harbor"`.

However, the application process was stalled in initialization:
- Main thread injection in `BriarApplicationImpl.onCreate` continued until 21:36:18.555 (~19 seconds).
- `11-14 21:36:18.577 I/Choreographer: Skipped 361 frames! The application may be doing too much work on its main thread.`
- `11-14 21:36:18.966 W/HWUI: Failed to choose config with EGL_SWAP_BEHAVIOR_PRESERVED, retrying without...`
- `11-14 21:36:18.977 W/HWUI: Failed to initialize 101010-2 format, error = EGL_SUCCESS`

### 3.4 Assertion Timeout on Blank Screen
At 21:36:22.605 (18.6s after starting the assertion), Maestro timed out:
`CommandFailed: Assertion is false: "Welcome to Harbor" is visible`

Inspection of diagnostic artifacts captured at the instant of failure:
- `step-004-assertCondition-Welcome_to_Harbor.json`: The accessibility hierarchy contained only system status bar icons (`com.android.systemui:id/status_bar_container`, clock `9:36`, battery, wifi). No activity views from `org.briarproject.briar.android.debug` had rendered.
- `step-004-assertCondition-Welcome_to_Harbor.png`: 2,591,049 of 2,592,000 pixels (99.96%) were completely black (RGB 0,0,0); only 951 pixels belonged to the status bar.
- `SetupActivity` had not yet been reached because `SplashScreenActivity`'s delayed transition was delayed behind main thread execution when Maestro killed the process.

---

## 4. Gate History Context

### 4.1 Prior Passing Gate Runs
Prior runs with stable emulator execution demonstrated consistent passing performance across all four flows:
- Run `20261114T191237Z` (commit `17f485101`): 4/4 flows PASSED in 265s (`01-setup-login` 56.4s, `02-add-contact` 62.0s, `03-send-receive-message` 74.2s, `04-conversation-list` 72.4s).
- Run `20261114T185704Z` (commit `45ed7f5a1`): 4/4 flows PASSED.
- Run `20261114T183018Z` (commit `792f9fe5a`): 4/4 flows PASSED.
- Run `20261113T205040Z` (commit `3ef0287c4`): 4/4 flows PASSED.
- Run `20261113T181555Z` (commit `7828513a9`): 4/4 flows PASSED.
- Run `20261113T180442Z` (commit `fa027b1d0`): 4/4 flows PASSED.

### 4.2 Subsequent Gate Runs & Host Virtualization Failure
Note on sequence: All passing runs listed above were **prior** to the failing run `20261114T201930Z`.
In the gate history (`docs/state/post_delivery_e2e_log.json`), the only subsequent run recorded was:
```json
{
  "task_id": "t_d40188ed",
  "ts": "20261114T210836Z",
  "status": "skipped",
  "detail": "emulator attached but never reported boot_completed within 240s"
}
```
This confirms that the host emulator entered a persistent hang condition where even `sys.boot_completed` failed to be reported within 240 seconds. No successful gate run subsequent to `20261114T201930Z` has yet executed on this device/AVD.

---

## 5. Local Unit & Robolectric Validation

To verify the integrity of the codebase in the absence of an available emulator:

1. **Setup & Onboarding Component Tests**:
   - `SetupViewModelTest`: PASSED (verifies account creation state transition to `CREATED`).
   - `SetupActivityTest` (Robolectric): PASSED (verifies password matching, button enabling, and progress indicators).
   - `MatrixSetupViewModelTest`: PASSED.
   - Command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :briar-android:testOfficialDebugUnitTest --tests '*Setup*'`
   - Result: 12/12 tests PASSED (0 failures, 0 ignored).

2. **Matrix Notification Component Tests**:
   - `MatrixRoomNotificationsTest`: PASSED (3/3 tests covering confirmed readback, server mute persistence, and missing session handling).
   - Command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :briar-android:testOfficialDebugUnitTest --tests 'org.briarproject.briar.matrix.MatrixRoomNotificationsTest'`
   - Result: 3/3 tests PASSED (0 failures, 0 ignored).

3. **Workspace Hygiene & Policies**:
   - `check_product_language_policy.sh`: PASS
   - `check_workspace_hygiene.sh`: PASS
   - `review_headroom.sh`: 2,053 lines remaining under review limit (status OK).

---

## 6. Conclusion and Resolution Criteria

### 6.1 Status: Provisional Diagnosis
The empirical timing data (76s snapshot load, 44s `pm clear`, 19s startup latency, 361 skipped frames, blank screen hierarchy) and subsequent 240s emulator boot hang strongly indicate that the failure was environmental infrastructure starvation rather than a product defect.

However, because:
1. Every listed passing run in gate history predated `20261114T201930Z`, and
2. The affected commit (`9e2f36c2f`) has not yet completed a green post-delivery run on a healthy emulator,

the diagnosis is maintained as **provisional**.

### 6.2 Definitive Resolution Criteria
Final closure of this regression will be achieved when:
1. The host emulator environment recovers, and
2. A post-delivery E2E gate execution of flows `01-setup-login`, `02-add-contact`, `03-send-receive-message`, and `04-conversation-list` at or above commit `9e2f36c2f` completes with 100% passing assertions.

No product code revert or modification is recommended, as architectural audit and unit tests confirm zero overlap between Matrix room notification readback and the onboarding UI.
