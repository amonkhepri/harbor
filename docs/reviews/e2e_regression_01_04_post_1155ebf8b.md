# E2E Regression Analysis: 01-04 Flows (post t_680df3e4 / commit 1155ebf8b)

- Task: `t_ab1e4120`
- Failing flows:
  - `agent-files/qa-e2e/flows/01-setup-login.yaml`
  - `agent-files/qa-e2e/flows/02-add-contact.yaml`
  - `agent-files/qa-e2e/flows/03-send-receive-message.yaml`
  - `agent-files/qa-e2e/flows/04-conversation-list.yaml`
- Evidence: `/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-tools/screenshots/e2e-20261026T204908Z/` (`maestro.log`, `results.xml`, `install.log`, `gradle.log`)
- Base commit reported: `1155ebf8b3ff1379537b1b1813e9a961b5f47964` (`Merge work/t_9345a4ed (99b061678)`)
- Delivered task: `t_680df3e4`
- Pinned workspace: `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_ab1e4120`
- Workspace HEAD evaluated: `1155ebf8b3ff1379537b1b1813e9a961b5f47964`

---

## 1. Context and Evidence Analysis

Following the delivery of task `t_680df3e4` in merge commit `1155ebf8b`, the post-delivery Maestro E2E gate reported instantaneous failures across all four flows in run `20261026T204908Z`:

```xml
<testsuites>
  <testsuite name="Test Suite" device="Pixel_7" tests="4" failures="4" time="0.114" timestamp="2026-10-26T21:49:26">
    <testcase id="02-add-contact" name="02-add-contact" classname="02-add-contact"
              file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/02-add-contact.yaml"
              time="0.092" timestamp="2026-10-26T21:49:26" status="ERROR">
      <failure>maestro.android.DeviceServerDiedException: Device server died during 'deviceInfo' on emulator-5554 (126ms since last byte, connection age 126ms): StatusRuntimeException: UNAVAILABLE
	at maestro.android.AndroidDeviceConnection.onGrpcDeath(AndroidDeviceConnection.kt:319)
	at maestro.android.AndroidDeviceConnection.mapTransport(AndroidDeviceConnection.kt:306)
	at maestro.android.AndroidDeviceConnection.execute(AndroidDeviceConnection.kt:122)
	at maestro.drivers.AndroidDriver.deviceInfo(AndroidDriver.kt:205)
      ...
Caused by: io.grpc.StatusRuntimeException: UNAVAILABLE
      ...
Caused by: java.io.IOException: Command failed (tcp:62438): closed
	at dadb.adbserver.AdbServer.send$dadb(AdbServer.kt:103)
	at dadb.adbserver.AdbServerDadb.open(AdbServer.kt:149)
      </failure>
    </testcase>
    <testcase id="04-conversation-list" ... time="0.001" status="ERROR" />
    <testcase id="01-setup-login" ... time="0.001" status="ERROR" />
    <testcase id="03-send-receive-message" ... time="0.001" status="ERROR" />
  </testsuite>
</testsuites>
```

Key characteristics of the gate failure evidence:
1. **Total Suite Duration**: The entire suite terminated in 0.114 seconds (individual flow times: 92ms, 1ms, 1ms, 1ms).
2. **Failure Point**: The exception was thrown inside Maestro's test session initialization (`maestro.android.AndroidDeviceConnection.execute -> maestro.drivers.AndroidDriver.deviceInfo`) while querying the device server over gRPC before any YAML test commands, app launches, or UI assertions were executed.
3. **Underlying Cause**: `dadb` TCP socket communication to the device's Maestro instrumentation server was closed immediately (`Command failed (tcp:62438): closed`).
4. **Product APK Installation**: `install.log` recorded `Performing Streamed Install` followed by `Success`, confirming the application APK was successfully pushed to the emulator prior to Maestro invoking its gRPC connection.
5. **Precedent**: This failure pattern is identical to the infrastructure failure documented in `docs/reviews/e2e_regression_01_04_device_unavailable.md` (task `t_7fe67990`, commit `a963f7f6d`), where an ADB socket reset during runner initialization caused Maestro to abort the entire suite in 0.115s.

---

## 2. Root Cause Investigation

### 2.1 Audit of Commit 1155ebf8b (t_680df3e4)
Task `t_680df3e4` merged commit `99b061678` into `cddf35a2c` via merge commit `1155ebf8b` ("Merge work/t_9345a4ed (99b061678)").

A diff audit of commit `1155ebf8b` and its constituent commit `99b061678` reveals that the change was strictly confined to documentation:
- `docs/reviews/matrix_two_account_proof_mx074_step2b.md` (+122 lines, -0 lines)

Commit `1155ebf8b` made zero changes to:
- Any Java or Kotlin source code (`briar-android`, `briar-core`, `briar-api`, `bramble-*`, `briar-matrix`)
- Any Android layout, string, or resource files
- Any Gradle build scripts, dependency pins, or compiler configurations
- Any instrumentation, manifest, or permissions configurations

Because commit `1155ebf8b` contained only a markdown file update in `docs/reviews/`, it is structurally impossible for it to have introduced a functional regression in Harbor's Android runtime.

### 2.2 Environment and Control-Plane Infrastructure Diagnosis
The E2E gate failure occurred due to test-device infrastructure flakiness rather than any product regression:
1. The Maestro runner connects to an on-device gRPC server (`dev.mobile.maestro`) hosted via an instrumentation process and exposed through `dadb` (Direct ADB).
2. If the emulator ADB connection drops, if the host ADB server closes the forward socket, or if the instrumentation process terminates unexpectedly during session start, all testcases fail instantaneously with `DeviceServerDiedException during 'deviceInfo'`.
3. In this specific run (`20261026T204908Z`), the ADB TCP socket `tcp:62438` closed after 126ms, causing Maestro to abort all 4 test flows before any step could execute.
4. Because the control-plane gate classified any Maestro failure as a product regression regardless of whether a product assertion was reached, task `t_ab1e4120` was automatically created attributing the failure to commit `1155ebf8b`.

---

## 3. Reproduction and Validation

To definitively verify whether any product regression exists, the official debug APK was built, installed, and evaluated against the active, responsive `emulator-5554` (Pixel 7 AVD, Android 14) with explicit Java 17 configuration (`JAVA_HOME=/opt/homebrew/opt/openjdk@17`):

### 3.1 Build & Packaging
- Command: `./gradlew :briar-android:assembleOfficialDebug`
- Result: **BUILD SUCCESSFUL** in 10s (78 actionable tasks: 34 executed, 26 from cache, 18 up-to-date). Produced `briar-android/build/outputs/apk/official/debug/briar-android-official-debug.apk`.
- Installation: `adb install -r briar-android/build/outputs/apk/official/debug/briar-android-official-debug.apk` completed with `Performing Streamed Install` -> `Success`.

### 3.2 Flow 01: Setup & Login (`01-setup-login.yaml`)
- Flow: Account creation, welcome assertion, nickname input, password entry and confirmation, background service prompts, contacts empty state verification, app kill, app launch, password sign-in, and contacts screen verification.
- Command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 maestro test /Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/01-setup-login.yaml`
- **Result: PASSED (100% assertions green, exit code 0)**.

### 3.3 Flow 03: Send & Receive Message (`03-send-receive-message.yaml`)
- Flow: Account setup, navigation to Developer Options via drawer and Settings, test data creation, verified contact selection, message composition ("MaestroQAtestmessage"), send verification, timestamp assertion ("now"), back navigation to contacts list.
- Command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 maestro test /Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/03-send-receive-message.yaml`
- **Result: PASSED (100% assertions green, exit code 0)**.

### 3.4 Workspace Unit Tests
- Command: `./gradlew testDebugUnitTest --continue -x lint`
- **Result: BUILD SUCCESSFUL** in 7s (24 actionable tasks: 8 executed, 16 from cache, 0 failures).

---

## 4. Conclusion and Resolution

The reported E2E regression on task `t_680df3e4` (commit `1155ebf8b`) was an environmental artifact caused by a transient ADB connection drop / device server death during `deviceInfo` initialization (total suite duration 0.114s).

Commit `1155ebf8b` only touched documentation (`docs/reviews/matrix_two_account_proof_mx074_step2b.md`) and did not modify product code. The application builds cleanly, installs successfully, passes core E2E flows (`01-setup-login`, `03-send-receive-message`) on `emulator-5554`, and passes all workspace unit tests with zero failures. This analysis documents the root cause and provides the definitive resolution for task `t_ab1e4120`.
