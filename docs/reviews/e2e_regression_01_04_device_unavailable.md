# E2E Regression Analysis: 01-04 Flows (post t_7f1549df / commit a963f7f6d)

- Task: `t_7fe67990`
- Failing flows:
  - `agent-files/qa-e2e/flows/01-setup-login.yaml`
  - `agent-files/qa-e2e/flows/02-add-contact.yaml`
  - `agent-files/qa-e2e/flows/03-send-receive-message.yaml`
  - `agent-files/qa-e2e/flows/04-conversation-list.yaml`
- Evidence: `agent-tools/screenshots/e2e-20261015T064309Z/` (`maestro.log`, `results.xml`, `install.log`, `gradle.log`)
- Base commit reported: `a963f7f6d` (`Merge work/t_7f1549df (1cc561d59)`)
- Pinned workspace: `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_7fe67990`
- Workspace HEAD evaluated: `41c01799b595ae3d269db131f1eaad0ff222a54b`

---

## 1. Context and Evidence Analysis

Following the delivery of task `t_7f1549df` in commit `a963f7f6d`, the post-delivery Maestro E2E gate reported failures across all four flows in run `20261015T064309Z`:

```xml
<testsuites>
  <testsuite name="Test Suite" device="Pixel_7" tests="4" failures="4" time="0.115" timestamp="2026-10-15T08:51:58">
    <testcase id="02-add-contact" name="02-add-contact" classname="02-add-contact"
              file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/02-add-contact.yaml"
              time="0.089" timestamp="2026-10-15T08:51:58" status="ERROR">
      <failure>maestro.android.DeviceServerDiedException: Device server died during 'deviceInfo' on emulator-5554 (122ms since last byte, connection age 122ms): StatusRuntimeException: UNAVAILABLE
    ...
Caused by: io.grpc.StatusRuntimeException: UNAVAILABLE
    ...
Caused by: java.io.IOException: Command failed (tcp:54631): closed
      </failure>
    </testcase>
    <testcase id="04-conversation-list" ... time="0.002" status="ERROR" />
    <testcase id="01-setup-login" ... time="0.001" status="ERROR" />
    <testcase id="03-send-receive-message" ... time="0.002" status="ERROR" />
  </testsuite>
</testsuites>
```

Key characteristics of the gate failure evidence:
1. **Total Suite Duration**: The entire suite terminated in 0.115 seconds (individual flow times: 89ms, 2ms, 1ms, 2ms).
2. **Failure Point**: The exception was thrown inside Maestro's test session initialization (`maestro.android.AndroidDeviceConnection.execute -> maestro.drivers.AndroidDriver.deviceInfo`) while querying the device server over gRPC before any YAML test commands, app launches, or UI assertions were executed.
3. **Underlying Cause**: `dadb` TCP socket communication to the device's Maestro instrumentation server was closed immediately (`Command failed (tcp:54631): closed`).
4. **Product APK Installation**: `install.log` recorded `Performing Streamed Install` followed by `Success`, confirming the application APK was successfully pushed to the emulator prior to Maestro invoking its gRPC connection.

---

## 2. Root Cause Investigation

### 2.1 Audit of Commit a963f7f6d (t_7f1549df)
Task `t_7f1549df` was delivered via commit `1cc561d59` ("Fix F3 regression: message-client close timeout during ordinary reads poisons access gate"), which was merged into master at `a963f7f6d`.

A full diff audit of commit `1cc561d59` reveals modifications strictly limited to:
- `briar-core/src/main/kotlin/org/briarproject/briar/telegram/ReflectiveTelegramTdlibMessageClient.kt`
- `briar-core/src/test/kotlin/org/briarproject/briar/telegram/ReflectiveTelegramTdlibMessageClientTest.kt`
- `briar-core/src/test/kotlin/org/briarproject/briar/telegram/TelegramConnectorMessageTest.kt`

The change guarded unconfirmed close reports against `accessGate.isStopped` during ordinary background Telegram read calls. Commit `a963f7f6d` made zero changes to:
- Account setup or authentication flows (`SetupActivity`, `StartupActivity`, `BriarActivity`)
- Contact discovery or distance linking (`ContactListFragment`, `AddContactActivity`)
- Messaging UI or thread list rendering (`ConversationActivity`, `NavDrawerActivity`)
- Android manifest, Gradle dependencies, permissions, or instrumentation.

### 2.2 Environment and Control-Plane Infrastructure Diagnosis
The E2E gate failure occurred due to test-device infrastructure flakiness rather than any product regression:
1. The Maestro runner connects to an on-device gRPC server hosted via an instrumentation process (`dadb`).
2. If the emulator adb connection drops, restarts, or if the instrumentation server crashes during initialization, all testcases fail instantaneously with `DeviceServerDiedException during 'deviceInfo'`.
3. In this specific run, the adb TCP socket `tcp:54631` closed after 122ms, causing Maestro to abort all 4 test flows before any step could execute.
4. Because the control-plane gate classified any Maestro failure as a product regression regardless of whether a product assertion was reached, task `t_7fe67990` was created attributing the failure to commit `a963f7f6d`.
5. Historical gate records in `docs/state/post_delivery_e2e_log.json` confirm that preceding commits (e.g. `62ad74e30`) and subsequent commits (e.g. `1768ab676` and current HEAD `41c01799b` at run `20261020T055214Z`) executed and passed all 4/4 flows when the device connection was stable.

---

## 3. Reproduction and Validation

To definitively verify whether any product regression exists, all four flows were executed against the active, responsive `emulator-5554` with explicit Java 17 configuration (`JAVA_HOME=/opt/homebrew/opt/openjdk@17`):

### 3.1 Flow 01: Setup & Login (`01-setup-login.yaml`)
- Flow: Account creation, welcome assertion, nickname input, password entry and confirmation, background service prompts, contacts empty state verification, app kill, app launch, password sign-in, and contacts screen verification.
- **Result: PASSED (100% assertions green, exit code 0)**.

### 3.2 Flow 02: Add Contact (`02-add-contact.yaml`)
- Flow: Account creation, contacts screen assertion, FAB tap, distance contact link generation, link copying, pasting link into recipient input, link validation confirmation ("Enter your contact's link, not your own").
- **Result: PASSED (100% assertions green, exit code 0)**.

### 3.3 Flow 03: Send & Receive Message (`03-send-receive-message.yaml`)
- Flow: Account setup, navigation to Developer Options via drawer and Settings, test data creation, verified contact selection, message composition ("MaestroQAtestmessage"), send verification, timestamp assertion ("now"), back navigation to contacts list.
- **Result: PASSED (100% assertions green, exit code 0)**.

### 3.4 Flow 04: Conversation List (`04-conversation-list.yaml`)
- Flow: Account setup, test data creation, contact list item verification (verified & unverified contacts, avatars, timestamps, trust indicators), chat thread navigation, back press, drawer navigation to Forums and back to Contacts.
- **Result: PASSED (100% assertions green, exit code 0)**.

### 3.5 Workspace Unit Tests
- Executed `./gradlew testDebugUnitTest --continue -x lint` in `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_7fe67990`.
- **Result: BUILD SUCCESSFUL (24 actionable tasks, 10 executed, 14 from cache, 0 failures)**.

---

## 4. Conclusion and Resolution

The reported E2E regression on task `t_7f1549df` (commit `a963f7f6d`) was an environmental artifact caused by a transient ADB connection drop / device server death during `deviceInfo` initialization (suite duration 0.115s). 

No product code was implicated or regressed. All four E2E flows (`01-setup-login`, `02-add-contact`, `03-send-receive-message`, `04-conversation-list`) as well as all local unit tests pass cleanly against the current HEAD. This analysis documents the root cause and provides the definitive resolution for task `t_7fe67990`.
