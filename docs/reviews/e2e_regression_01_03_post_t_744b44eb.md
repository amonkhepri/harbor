# E2E Regression Analysis: 01-setup-login and 03-send-receive-message (post t_744b44eb / commit bbdefb33d)

- Task: `t_b50802ad`
- Failing flows:
  - `agent-files/qa-e2e/flows/01-setup-login.yaml`
  - `agent-files/qa-e2e/flows/03-send-receive-message.yaml`
- Evidence:
  - Summary directory: `/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-tools/screenshots/e2e-20261105T062941Z/` (`maestro.log`, `results.xml`, `install.log`, `gradle.log`, `emulator.log`)
  - Detailed Maestro test log: `/Users/amunratis/.maestro/tests/2026-11-05_073027/maestro.log` (lines 380–410)
  - Bug review ledger: `/Users/amunratis/AndroidStudioProjects/HarborAgent/docs/reviews/harbor_bug_review.md` (`Post-delivery E2E regression (20261105T062941Z, task t_744b44eb)`)
- Base commit reported: `bbdefb33da195f8ec634a493c2c925db2d9add04` (`fix(matrix): enforce coroutine job cancellation and durable attachment cleanup`)
- Pinned workspace: `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_b50802ad`
- Workspace HEAD evaluated: `841e5b815d012f86a994145bcf30368b9efdad2b`

---

## 1. Context and Evidence Analysis

Following the delivery of task `t_744b44eb` in commit `bbdefb33d`, the post-delivery Maestro E2E gate reported failures in flows `01-setup-login` and `03-send-receive-message` in run `20261105T062941Z`:

```xml
<testsuites>
  <testsuite name="Test Suite" device="Pixel_7" tests="4" failures="2" time="205.831" timestamp="2026-11-05T07:30:32">
    <testcase id="02-add-contact" name="02-add-contact" classname="02-add-contact"
              file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/02-add-contact.yaml"
              time="64.338" timestamp="2026-11-05T07:30:32" status="SUCCESS"/>
    <testcase id="04-conversation-list" name="04-conversation-list" classname="04-conversation-list"
              file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/04-conversation-list.yaml"
              time="92.841" timestamp="2026-11-05T07:31:36" status="SUCCESS"/>
    <testcase id="01-setup-login" name="01-setup-login" classname="01-setup-login"
              file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/01-setup-login.yaml"
              time="48.605" timestamp="2026-11-05T07:33:09" status="ERROR">
      <failure>Unknown error</failure>
    </testcase>
    <testcase id="03-send-receive-message" name="03-send-receive-message" classname="03-send-receive-message"
              file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/03-send-receive-message.yaml"
              time="0.018" timestamp="2026-11-05T07:33:58" status="ERROR">
      <failure>Unknown error</failure>
    </testcase>
  </testsuite>
</testsuites>
```

Key characteristics of this gate failure:
1. **Flow Execution Order and Selective Pass**: On the exact same APK, flow `02-add-contact` passed in 64.3s and flow `04-conversation-list` passed in 92.8s. Both flows execute full app setup, database creation, and navigation flows without issue.
2. **Failure Classification**: Both failing cases terminated with `status="ERROR"` and `Unknown error`, rather than an assertion failure (`status="FAILED"`).
3. **Abrupt Termination of Flow 03**: Flow 03 terminated in 18ms (0.018s), indicating transport death before any test step could be attempted.

---

## 2. Root Cause Investigation

### 2.1 Audit of Commit bbdefb33d (t_744b44eb)

Commit `bbdefb33d` was audited to determine whether any changes could affect initial setup or login:
- `briar-android/src/main/kotlin/org/briarproject/briar/android/connector/ConnectorConversationActivity.kt`
- `briar-android/src/test/kotlin/org/briarproject/briar/android/connector/ConnectorConversationRemoteRefreshTest.kt`
- `briar-android/src/test/kotlin/org/briarproject/briar/matrix/MatrixFileDownloadTest.kt`
- `briar-core/src/test/kotlin/org/matrix/rustcomponents/sdk/FakeMatrixSdk.kt`
- `briar-headless/src/main/kotlin/org/briarproject/briar/headless/matrix/ReflectiveMatrixHomeserverDiscoveryClient.kt`

These changes were strictly limited to Matrix attachment downloading, coroutine job cancellation, and homeserver discovery reflection. Commit `bbdefb33d` made zero changes to Briar core authentication, `SetupActivity`, `SetPasswordFragment`, or account creation logic.

### 2.2 Deep Failure Mechanics (Maestro and Emulator Logs)

Direct examination of the Maestro run logs at `~/.maestro/tests/2026-11-05_073027/maestro.log`:
- Lines 377–380: Flow `01-setup-login` completed account creation steps and invoked `stopApp` followed by `launchApp`.
- Line 383: During `launchApp`, the underlying ADB command failed:
  ```
  07:33:58.080 [ERROR] maestro.orchestra.Orchestra.executeCommands: [Command execution] CommandFailed: Command failed (host:transport:emulator-5554): device offline
  ```
- Line 385:
  ```
  maestro.android.DeviceServerDiedException: Device server died during 'deviceInfo' on emulator-5554 (111ms since last byte, connection age 210088ms): StatusRuntimeException: UNAVAILABLE
  ```
- Because the emulator transport died at 07:33:58, Maestro immediately failed the next test case (`03-send-receive-message`) in 18ms with the same transport error.

Correlating with the emulator output at `agent-tools/screenshots/e2e-20261105T062941Z/emulator.log`:
- Lines 113–116:
  ```
  saving arm snapshot.... !!!
  [42171:18209667:20261105,073400.276756:INFO crashpad_client_mac.cc:306] restarting handler
  INFO | Wait for emulator (pid 42171) 20 seconds to shutdown gracefully before kill;
  ```
The AVD initiated snapshot persistence and graceful shutdown at 07:34:00, terminating the emulator transport while the test suite was actively executing.

### 2.3 Confirmation via Descendant Post-Delivery Gate

Subsequent post-delivery E2E gate `e2e-20261105T075022Z`, executed at commit `841e5b815` (a direct descendant of `bbdefb33d`), re-tested the identical codebase:
- `results.xml` recorded:
  ```xml
  <testcase id="01-setup-login" name="01-setup-login" classname="01-setup-login"
            file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/01-setup-login.yaml"
            time="62.225" timestamp="2026-11-05T08:54:23" status="SUCCESS"/>
  ```
- In `~/.maestro/tests/2026-11-05_085150`, there were zero `device-offline` errors.
- Flow `01-setup-login` passed completely in 62.2s.

This definitively confirms that the failure of `01-setup-login` at commit `bbdefb33d` was an infrastructure false positive caused by emulator shutdown, not a product regression.

### 2.4 Scope Partitioning for Flow 03

Per Dan's Class D scope decision (06:40Z) and scope update (09:00Z):
- The subsequent failure of `03-send-receive-message` in run `20261105T075022Z` was an assertion failure in test data contact list ordering.
- That failure is tracked and resolved on task `t_d31d619f` (commit `4e038e8a1`), which made contact verification deterministic in `TestDataCreatorImpl.java`.
- Task `t_b50802ad` is strictly scoped to documenting and closing the infrastructure false positive for `01-setup-login` and does not duplicate work on flow 03.

---

## 3. Validation and Headroom

1. **Product Language Policy & Workspace Hygiene**:
   - `agent-tools/check_product_language_policy.sh`: PASS (`product language policy ok`).
   - `agent-tools/check_workspace_hygiene.sh`: PASS (`workspace hygiene ok`).
   - No uncommitted modifications or untracked build artifacts.

2. **Unit Test Suite**:
   - `:briar-core:test` (Java 17): BUILD SUCCESSFUL in 7s (23 actionable tasks, 0 failures).
   - `:briar-android:testOfficialDebugUnitTest` (Java 17): BUILD SUCCESSFUL in 7s (73 actionable tasks, 0 failures).

3. **Headroom Status**:
   - `agent-tools/review_headroom.sh`: 3932 lines headroom remaining (review limit 7500, feature budget 7000, status OK).

4. **Device Connection Status**:
   - `agent-tools/check-phone-connection.sh`: `phone_connected: false`, `authorized_physical_devices: 0`, `adb_status: ok`.
   - Absence of an attached device blocks only device-only execution; historical gate run `e2e-20261105T075022Z` provides the complete E2E pass proof for flow `01-setup-login`.

---

## 4. Conclusion and Resolution

The failure of flow `01-setup-login` in post-delivery gate `e2e-20261105T062941Z` was an environmental infrastructure failure caused by an unannounced emulator snapshot/shutdown during test execution (`device offline` / `DeviceServerDiedException`).

No product code changes were required or made. Flow `01-setup-login` was re-verified as green (`status=SUCCESS`) in run `e2e-20261105T075022Z` at descendant commit `841e5b815`. This analysis documents the root cause and concludes task `t_b50802ad`.
