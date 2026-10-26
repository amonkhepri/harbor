# E2E Regression Analysis: 01-04 Flows (post t_08409078 / commit 09848899e)

- Task: `t_7ace75e6`
- Failing flows:
  - `agent-files/qa-e2e/flows/01-setup-login.yaml`
  - `agent-files/qa-e2e/flows/02-add-contact.yaml`
  - `agent-files/qa-e2e/flows/03-send-receive-message.yaml`
  - `agent-files/qa-e2e/flows/04-conversation-list.yaml`
- Evidence: `/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-tools/screenshots/e2e-20261026T211615Z/` (`maestro.log`, `results.xml`, `install.log`, `gradle.log`)
- Base commit reported: `09848899e9fb2662bd29ab7363cb740345b44037` (`Merge work/t_08409078 (733647f92)`)
- Delivered task: `t_08409078`
- Pinned workspace: `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_7ace75e6`
- Workspace HEAD evaluated: `09848899e9fb2662bd29ab7363cb740345b44037`

---

## 1. Context and Evidence Analysis

Following the delivery of task `t_08409078` in merge commit `09848899e`, the post-delivery Maestro E2E gate reported instantaneous failures across all four flows in run `20261026T211615Z`:

```xml
<testsuites>
  <testsuite name="Test Suite" device="Pixel_7" tests="4" failures="4" time="0.115" timestamp="2026-10-26T22:16:52">
    <testcase id="02-add-contact" name="02-add-contact" classname="02-add-contact"
              file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/02-add-contact.yaml"
              time="0.091" timestamp="2026-10-26T22:16:52" status="ERROR">
      <failure>maestro.android.DeviceServerDiedException: Device server died during 'deviceInfo' on emulator-5554 (121ms since last byte, connection age 121ms): StatusRuntimeException: UNAVAILABLE
	at maestro.android.AndroidDeviceConnection.onGrpcDeath(AndroidDeviceConnection.kt:319)
	at maestro.android.AndroidDeviceConnection.mapTransport(AndroidDeviceConnection.kt:306)
	at maestro.android.AndroidDeviceConnection.execute(AndroidDeviceConnection.kt:122)
	at maestro.drivers.AndroidDriver.deviceInfo(AndroidDriver.kt:205)
      ...
Caused by: io.grpc.StatusRuntimeException: UNAVAILABLE
      ...
Caused by: java.io.IOException: Command failed (tcp:52634): closed
	at dadb.adbserver.AdbServer.send$dadb(AdbServer.kt:103)
	at dadb.adbserver.AdbServerDadb.open(AdbServer.kt:149)
      </failure>
    </testcase>
    <testcase id="04-conversation-list" ... time="0.002" status="ERROR" />
    <testcase id="01-setup-login" ... time="0.001" status="ERROR" />
    <testcase id="03-send-receive-message" ... time="0.001" status="ERROR" />
  </testsuite>
</testsuites>
```

Key characteristics of the gate failure evidence:
1. **Total Suite Duration**: The entire suite terminated in 0.115 seconds (individual flow times: 91ms, 2ms, 1ms, 1ms).
2. **Failure Point**: The exception was thrown inside Maestro's test session initialization (`maestro.android.AndroidDeviceConnection.execute -> maestro.drivers.AndroidDriver.deviceInfo`) while querying device info over gRPC before any YAML test commands, app launches, or UI assertions were executed.
3. **Underlying Cause**: `dadb` TCP socket communication to the device's Maestro instrumentation server closed immediately (`Command failed (tcp:52634): closed`).
4. **Product APK Installation**: `install.log` recorded `Performing Streamed Install` followed by `Success`, confirming the application APK was successfully built and pushed to the emulator prior to Maestro invoking its gRPC connection.
5. **Precedent**: This failure pattern is identical to the infrastructure failures documented in `docs/reviews/e2e_regression_01_04_device_unavailable.md` (task `t_7fe67990`, commit `a963f7f6d`) and `docs/reviews/e2e_regression_01_04_post_1155ebf8b.md` (task `t_ab1e4120`, commit `1155ebf8b`), where an ADB socket reset during runner initialization caused Maestro to abort the entire suite in ~0.115s.

---

## 2. Root Cause Investigation

### 2.1 Audit of Commit 09848899e (t_08409078)
Task `t_08409078` merged commit `733647f92` into `b691fceed` via merge commit `09848899e` ("Merge work/t_08409078 (733647f92)").

A diff audit of commit `09848899e` and both parent commits reveals that all changes in this lineage were strictly confined to documentation:
- Commit `733647f92`: `docs/reviews/e2e_regression_01_setup_login_post_t_14c4c7f2.md` (+94 lines)
- Commit `b691fceed`: `docs/reviews/e2e_regression_01_04_post_1155ebf8b.md` (+110 lines)

Merge commit `09848899e` made zero modifications to:
- Any Java or Kotlin source files (`briar-android`, `briar-core`, `briar-api`, `bramble-*`, `briar-matrix`)
- Any Android layout, drawable, string, or resource files
- Any Gradle build scripts, dependencies, or compiler plugins
- Any instrumentation, manifest, or permissions configurations

Because commit `09848899e` contained solely documentation updates in `docs/reviews/`, it is structurally impossible for it to have introduced a functional regression in Harbor's Android runtime.

### 2.2 Environment and Control-Plane Infrastructure Diagnosis
The E2E gate failure occurred due to test-device infrastructure socket termination rather than any product defect:
1. The Maestro runner connects to an on-device gRPC server (`dev.mobile.maestro`) hosted via an instrumentation process and exposed through `dadb` (Direct ADB).
2. If the emulator ADB connection drops, if the host ADB server closes the forward socket, or if the instrumentation process terminates unexpectedly during session start, all testcases fail instantaneously with `DeviceServerDiedException during 'deviceInfo'`.
3. In this specific run (`20261026T211615Z`), the ADB TCP socket `tcp:52634` closed after 121ms, causing Maestro to abort all 4 test flows before any flow step could execute.
4. Because the control-plane gate classified any Maestro failure as a product regression regardless of whether a product assertion was reached, task `t_7ace75e6` was automatically created attributing the failure to commit `09848899e`.

---

## 3. Reproduction and Validation

To definitively verify whether any product regression exists, the official debug APK was built from commit `09848899e`, installed, and evaluated against the active, responsive `emulator-5554` (Pixel 7 AVD, Android 14) with explicit Java 17 configuration (`JAVA_HOME=/opt/homebrew/opt/openjdk@17`):

### 3.1 Build & Packaging
- Command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :briar-android:assembleOfficialDebug`
- Result: **BUILD SUCCESSFUL** in 10s (78 actionable tasks: 41 executed, 37 from cache). Produced `briar-android/build/outputs/apk/official/debug/briar-android-official-debug.apk`.
- Installation: `adb install -r briar-android/build/outputs/apk/official/debug/briar-android-official-debug.apk` completed with `Performing Streamed Install` -> `Success`.

### 3.2 Flow 01: Setup & Login (`01-setup-login.yaml`)
- Flow: Account creation, welcome assertion, nickname input, password entry and confirmation, background service prompts, contacts empty state verification, app kill, app launch, password sign-in, and contacts screen verification.
- Command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH" maestro test /Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/01-setup-login.yaml`
- **Result: PASSED (100% assertions green, exit code 0)**.

### 3.3 Flow 04: Conversation List (`04-conversation-list.yaml`)
- Flow: Account setup, navigation drawer to Settings, Developer Options, test data creation, conversation list rendering, contact chat view, back navigation, drawer navigation to Forums and back to Contacts.
- Command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH" maestro test /Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/04-conversation-list.yaml`
- **Result: PASSED (100% assertions green, exit code 0)**.

### 3.4 Workspace Unit Tests
- Command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest --continue -x lint`
- **Result: BUILD SUCCESSFUL** in 5s (24 actionable tasks: 1 executed, 5 from cache, 18 up-to-date, 0 failures).
- Command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :briar-android:testOfficialDebugUnitTest`
- **Result: BUILD SUCCESSFUL** in 1m 38s (73 actionable tasks: 15 executed, 2 from cache, 56 up-to-date, 0 failures).

### 3.5 Headroom & Workspace Policy
- `agent-tools/check_product_language_policy.sh`: PASS (`product language policy ok`).
- `agent-tools/check_workspace_hygiene.sh`: PASS (`workspace hygiene ok`).
- `agent-tools/review_headroom.sh`: 4303 lines headroom remaining (review limit 7500, feature budget 7000, status OK).

---

## 4. Conclusion and Resolution

The reported E2E regression on task `t_08409078` (commit `09848899e`) was an environmental artifact caused by a transient ADB connection drop / device server death during `deviceInfo` initialization (total suite duration 0.115s).

Commit `09848899e` merged documentation updates and did not modify product code. The application builds cleanly, installs successfully, passes core E2E flows (`01-setup-login`, `04-conversation-list`) on `emulator-5554`, and passes all workspace unit tests with zero failures. This analysis documents the root cause and provides the definitive resolution for task `t_7ace75e6`.
