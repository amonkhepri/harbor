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

## Verdict

**Not a product regression.** The failure across all four flows was caused by severe emulator host execution lag and rendering starvation during cold startup, not a product defect:

1. The emulator took 76.4 seconds just to restore its `default_boot` snapshot under SwiftShader software Vulkan emulation.
2. Maestro's initial `clearState: true` (`pm clear`) took 37–44 seconds per flow due to device I/O starvation.
3. Cold startup of `SplashScreenActivity` spent ~19 seconds in `BriarApplicationImpl.onCreate` executing Dagger singleton initialization on the main thread, dropping 361 frames (`Choreographer: Skipped 361 frames! The application may be doing too much work on its main thread`).
4. The window manager remained blocked in `BLASTSyncEngine DRAW_PENDING` while EGL/HWUI re-initialized graphics swap configurations.
5. Maestro's 18.6-second assertion timeout for `"Welcome to Harbor" is visible` expired before `SplashScreenActivity` could draw its view hierarchy or transition to `SetupActivity`.
6. The view hierarchy captured at the failure point (`step-004-assertCondition-Welcome_to_Harbor.json`) contained solely `com.android.systemui` status bar icons (clock at 9:36, battery, wifi). 99.96% of the screen pixels in `step-004-assertCondition-Welcome_to_Harbor.png` were completely black (RGB 0,0,0).
7. The subsequent gate run (`t_d40188ed` at `20261114T210836Z`) confirmed total emulator degradation: the emulator aborted with `"emulator attached but never reported boot_completed within 240s"`.
8. Commit `9e2f36c2f` touched only Matrix room notification readback confirmation in `briar-matrix` and unit test fakes, completely outside the application startup, account setup, and authentication code paths.

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

Key observations from the gate execution logs:
- **Build and Installation**: `gradle.log` reports `BUILD SUCCESSFUL in 36s` and `install.log` reports `Performing Streamed Install` followed by `Success`. The APK was successfully compiled and installed on `emulator-5554`.
- **First-assertion Failure Across All Flows**: Every flow failed at step 1 immediately after `launchApp: clearState: true` when asserting `"Welcome to Harbor" is visible`.
- **Anomalous Latencies**: `02-add-contact` took 92.0s, `04-conversation-list` took 88.3s, `01-setup-login` took 73.1s, and `03-send-receive-message` took 62.0s—all without ever getting past the initial app launch and first assertion.

---

## 2. Diff Audit of Commit 9e2f36c2f (t_72fc7241)

Commit `9e2f36c2f` ("fix(matrix): require matching room notification readback (MX-067)") modified 3 files:
1. `briar-matrix/src/main/kotlin/org/briarproject/briar/matrix/ReflectiveMatrixHomeserverDiscoveryClient.kt` (+4 lines):
   Ensures that when updating room notification mode, a matching readback from the Matrix Rust SDK is required before treating the write as confirmed.
2. `briar-android/src/test/kotlin/org/briarproject/briar/matrix/MatrixRoomNotificationsTest.kt` (+14 lines):
   Adds unit test `acknowledged write requires matching server readback`.
3. `briar-android/src/test/kotlin/org/matrix/rustcomponents/sdk/FakeMatrixSdk.kt` (+3 lines):
   Adds an `ignoreUpdate` test knob in the fake SDK.

Zero modifications were made to:
- Account setup or onboarding (`SetupActivity`, `SetupViewModel`, `SetPasswordFragment`, `NicknameFragment`).
- Startup and entry routing (`SplashScreenActivity`, `StartupActivity`, `BriarActivity`, `NavDrawerActivity`).
- Application lifecycle, Android manifest, permissions, or Dagger dependency graphs.
- Contacts, conversations, or messaging UI.
- Matrix connector runtime is default-off, and no Matrix account is configured on fresh install (`clearState: true`).

Commit `9e2f36c2f` is functionally orthogonal to app startup and first-run onboarding.

---

## 3. Deep Failure Mechanics and Device Logcat Analysis

Detailed investigation of Maestro's debug run directory at `~/.maestro/tests/2026-11-14_212932/`:

### 3.1 Cold Boot and Snapshot Restoral Lag
In `emulator.log`:
```
INFO | Loading snapshot 'default_boot'...
INFO | Successfully loaded snapshot 'default_boot' using 76383 ms
```
The emulator required 76.4 seconds just to deserialize its snapshot. Software OpenGL ES translation via SwiftShader (`ANGLE Google, Vulkan 1.3.0 SwiftShader Device LLVM 10.0.0`) was heavily CPU-bound.

### 3.2 Clear State Latency
In `01-setup-login/commands.json`:
- `launchAppCommand` with `clearState: true` took **51.1 seconds** (`timestamp: 1794688512794`, `duration: 51136`).
- In `device-logcat.txt`, `pm clear` triggered extensive package data removal across `ShortcutService`, `MediaProvider`, `FuseDaemon`, and `Icing`, blocking between 21:35:12 and 21:35:56 (44 seconds).

### 3.3 Main Thread Frame Drops & DRAW_PENDING
At 21:35:56.722, Maestro finally issued the launcher intent:
```
11-14 21:35:56.722 I/ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 pkg=org.briarproject.briar.android.debug cmp=org.briarproject.briar.android.debug/org.briarproject.briar.android.splash.SplashScreenActivity}
11-14 21:35:59.645 I/ActivityManager: Start proc 18626:org.briarproject.briar.android.debug/u0a214 for next-top-activity {SplashScreenActivity}
11-14 21:36:01.777 I/BLASTSyncEngine: Window{3b85023 u0 Splash Screen org.briarproject.briar.android.debug} DRAW_PENDING
```
Maestro marked `launchApp` finished at 21:36:03.930 and immediately started its `assertConditionCommand` polling for `"Welcome to Harbor"`.

However, the application process was stalled executing initialization:
- Main thread injection in `BriarApplicationImpl.onCreate` continued until 21:36:18.555 (~19 seconds).
- `11-14 21:36:18.577 I/Choreographer: Skipped 361 frames! The application may be doing too much work on its main thread.`
- `11-14 21:36:18.966 W/HWUI: Failed to choose config with EGL_SWAP_BEHAVIOR_PRESERVED, retrying without...`
- `11-14 21:36:18.977 W/HWUI: Failed to initialize 101010-2 format, error = EGL_SUCCESS`

### 3.4 Assertion Timeout on Blank Screen
At 21:36:22.605 (18.6s after starting the assertion), Maestro timed out with:
`CommandFailed: Assertion is false: "Welcome to Harbor" is visible`

Inspection of the diagnostic artifacts captured at that instant:
- `step-004-assertCondition-Welcome_to_Harbor.json`: The hierarchy contains only the system status bar (`com.android.systemui:id/status_bar_container`, clock `9:36`, battery, wifi). No activity views from `org.briarproject.briar.android.debug` existed in the accessibility hierarchy.
- `step-004-assertCondition-Welcome_to_Harbor.png`: Pixel analysis reveals 2,591,049 of 2,592,000 pixels (99.96%) are black; only 951 pixels belong to status bar icons.
- `SetupActivity` was never started because `SplashScreenActivity` had not yet finished its delayed post transition before Maestro killed the app for the next flow.

### 3.5 Total Host Emulator Hang on Following Run
In `docs/state/post_delivery_e2e_log.json`, the very next run `t_d40188ed` at `20261114T210836Z` recorded:
```json
{
  "task_id": "t_d40188ed",
  "ts": "20261114T210836Z",
  "status": "skipped",
  "detail": "emulator attached but never reported boot_completed within 240s"
}
```
The emulator entered a persistent hang state where even `sys.boot_completed` failed to be reported within the 240-second timeout, confirming host-level virtualization failure.

---

## 4. Historical Gate Stability

Prior and subsequent runs with stable emulator execution verify that the test suite passes cleanly:
- Run `20261114T191237Z` (commit `17f485101`): 4/4 flows PASSED in 265s (`01-setup-login` 56.4s, `02-add-contact` 62.0s, `03-send-receive-message` 74.2s, `04-conversation-list` 72.4s).
- Run `20261113T180442Z` (commit `fa027b1d0`): 4/4 flows PASSED.
- Run `20261113T181555Z` (commit `7828513a9`): 4/4 flows PASSED.
- Run `20261113T205040Z` (commit `3ef0287c4`): 4/4 flows PASSED.
- Run `20261114T183018Z` (commit `792f9fe5a`): 4/4 flows PASSED.
- Run `20261114T185704Z` (commit `45ed7f5a1`): 4/4 flows PASSED.

---

## 5. Validation and Health

1. **Unit Test Verification**:
   - Executed `MatrixRoomNotificationsTest`: 8/8 tests PASSED.
   - Executed `ReflectiveMatrixHomeserverDiscoveryClientReactionTest`: all tests PASSED.
   - Full matrix suite compiled and executed cleanly under Java 17.

2. **Hygiene & Language Policy**:
   - `check_product_language_policy.sh`: PASS
   - `check_workspace_hygiene.sh`: PASS

3. **Headroom Status**:
   - `review_headroom.sh`: 2,053 lines remaining under the 7,500 review limit (feature budget 7,000, insertions 5,447, status OK).

---

## 6. Conclusion and Resolution

The failure of all four flows in run `20261114T201930Z` was an environmental infrastructure failure caused by severe emulator sluggishness and frame-drop delays during cold boot and `pm clear`, leading to assertion timeouts before `SplashScreenActivity` could draw and transition to `SetupActivity`.

No product code defect exists. This documentation provides the formal diagnosis and closes task `t_0da11d68`.
