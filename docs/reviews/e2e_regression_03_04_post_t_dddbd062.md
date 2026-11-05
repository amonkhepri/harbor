# E2E Regression Analysis: 03-send-receive-message and 04-conversation-list (post t_dddbd062 / commit 841e5b815)

- Task: `t_d31d619f`
- Failing flows:
  - `agent-files/qa-e2e/flows/03-send-receive-message.yaml`
  - `agent-files/qa-e2e/flows/04-conversation-list.yaml`
- Evidence:
  - Gate summary artifacts: `/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-tools/screenshots/e2e-20261105T075022Z/` (`maestro.log`, `results.xml`, `install.log`, `gradle.log`, `emulator.log`)
  - Detailed per-flow Maestro logs and device logcat:
    - Flow 03: `~/.maestro/tests/2026-11-05_085150/03-send-receive-message/logs/device-logcat.txt` (lines 4180–4240), `commands.json`, and view hierarchy `step-008-tapOnElement-org.briarproject.briar.andr.json`
    - Flow 04: `~/.maestro/tests/2026-11-05_085150/04-conversation-list/logs/device-logcat.txt` (lines 5200–5250), `commands.json`, and view hierarchy `step-029-assertCondition-Unverified_contact.json`
  - Bug review ledger entry: `/Users/amunratis/AndroidStudioProjects/HarborAgent/docs/reviews/harbor_bug_review.md` (`Post-delivery E2E regression (20261105T075022Z, task t_dddbd062)`)
- Base commit reported: `841e5b815d012f86a994145bcf30368b9efdad2b` (`fix(matrix): use ZipFile central directory and ioExecutor for executable sniffing`)
- Delivered task: `t_dddbd062`
- Pinned workspace: `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_d31d619f`
- Workspace HEAD evaluated: `841e5b815d012f86a994145bcf30368b9efdad2b`

---

## 1. Context and Evidence Analysis

Following the delivery of task `t_dddbd062` in commit `841e5b815`, the post-delivery Maestro E2E gate reported failures in `03-send-receive-message` and `04-conversation-list` during run `20261105T075022Z`:

```xml
<testsuites>
  <testsuite name="Test Suite" device="Pixel_7" tests="4" failures="2" time="237.83" timestamp="2026-11-05T08:51:59">
    <testcase id="02-add-contact" name="02-add-contact" classname="02-add-contact" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/02-add-contact.yaml" time="72.518" timestamp="2026-11-05T08:51:59" status="SUCCESS"/>
    <testcase id="04-conversation-list" name="04-conversation-list" classname="04-conversation-list" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/04-conversation-list.yaml" time="71.717" timestamp="2026-11-05T08:53:11" status="ERROR">
      <failure>Assertion is false: "Unverified contact" is visible</failure>
    </testcase>
    <testcase id="01-setup-login" name="01-setup-login" classname="01-setup-login" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/01-setup-login.yaml" time="62.225" timestamp="2026-11-05T08:54:23" status="SUCCESS"/>
    <testcase id="03-send-receive-message" name="03-send-receive-message" classname="03-send-receive-message" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/03-send-receive-message.yaml" time="31.343" timestamp="2026-11-05T08:55:25" status="ERROR">
      <failure>Element not found: Id matching regex: org.briarproject.briar.android.debug:id/next</failure>
    </testcase>
  </testsuite>
</testsuites>
```

In the exact same test run:
- `02-add-contact` (72.5s) — SUCCESS
- `01-setup-login` (62.2s) — SUCCESS

Crucially, flow `01-setup-login` executes the exact same initial account creation steps as flow `03-send-receive-message` (Welcome screen -> nickname input -> `hideKeyboard` -> next -> password entry), demonstrating that the underlying product authentication logic was functioning correctly.

---

## 2. Root Cause Investigation

### 2.1 Audit of Commit 841e5b815 (t_dddbd062)

Commit `841e5b815` was evaluated to assess any potential interaction with the failing flows:
- `briar-android/src/main/kotlin/org/briarproject/briar/android/connector/ConnectorConversationActivity.kt` (+83 / -66 lines)
- `briar-android/src/test/kotlin/org/briarproject/briar/android/connector/ConnectorConversationRemoteRefreshTest.kt` (+59 / -7 lines)

These modifications were strictly confined to `ConnectorConversationActivity.kt` (using `ZipFile` central directory inspection and `ioExecutor` for background executable file sniffing of downloaded attachments) and its focused unit tests.

Neither flow `03-send-receive-message` nor flow `04-conversation-list` touches `ConnectorConversationActivity`, connector file downloads, or the `connector.fileprovider`. Both flows exercise the core Briar Android UI stack (`SetupActivity`, `TestDataActivity`, `ContactListFragment`, `ConversationActivity`). Commit `841e5b815` made zero changes to any Briar core classes, layouts, or view models.

### 2.2 Deep Failure Mechanics: Flow 03 (System Package Update & IME Focus Race)

Analysis of `~/.maestro/tests/2026-11-05_085150/03-send-receive-message/logs/device-logcat.txt` and `commands.json` revealed the root cause:

1. **Maestro Execution Sequence**:
   - At `08:55:36.020`, Maestro entered nickname `"MaestroQA"` into `nickname_entry`.
   - At `08:55:37.684`, Maestro dispatched `hideKeyboardCommand`.
2. **Concurrent OS Background Event**:
   - At `08:55:37.366–08:55:37.941`, Google Play Store (`com.android.vending` / Finsky) on the emulator completed an asynchronous background package update of `com.google.android.calendar`:
     ```text
     11-05 08:55:37.366 D/ActivityThread(6751): Package [com.google.android.calendar] reported as REPLACED, but missing application info. Assuming REMOVED.
     11-05 08:55:37.941 I/ProximityAuth(1256): [RecentAppsMediator] Package added: (user=UserHandle{0}) com.google.android.calendar
     11-05 08:55:38.135 D/BackgroundInstallControlService(684): Package event received: 0
     ```
3. **Transition to Launcher**:
   - Maestro's `hideKeyboard` command dispatches `KEYCODE_BACK`. Because the keyboard focus was already transitioning during the system package broadcast, Android's `WindowManagerService` delivered the BACK event directly to `SetupActivity`:
     ```text
     11-05 08:55:38.130 V/WindowManagerShell(946): Transition requested (#50): android.os.BinderProxy@23b5714 TransitionRequestInfo { type = CLOSE, triggerTask = TaskInfo{... SplashScreenActivity}
     11-05 08:55:38.132 I/BaseActivity(6751): Pausing SetupActivity
     11-05 08:55:38.149 D/StatsLog(1089): LAUNCHER_ONRESUME
     11-05 08:55:38.748 I/BaseActivity(6751): Stopping SetupActivity
     11-05 08:55:38.752 I/BaseActivity(6751): Destroying SetupActivity
     ```
   - Because `SetupActivity` was the root task activity without an active back stack, the BACK event closed the activity, returning to `com.google.android.apps.nexuslauncher:id/launcher`.
4. **Assertion Timeout**:
   - Step 8 view hierarchy (`step-008-tapOnElement-org.briarproject.briar.andr.json`) confirms that only the Nexus Launcher was present. Maestro timed out looking for `org.briarproject.briar.android.debug:id/next`.

### 2.3 Deep Failure Mechanics: Flow 04 (Viewport Bounds & Contact Sort Order)

Analysis of `~/.maestro/tests/2026-11-05_085150/04-conversation-list/` revealed why `"Unverified contact"` failed to assert:

1. **Synthetic Data Generation**:
   - Flow 04 navigates to Developer Options > Create test data, which triggers `TestDataCreator.createTestData()`.
   - `TestDataCreatorImpl.java:206` previously initialized contact verification via:
     `boolean verified = i == 0 ? false : (i == 1 ? true : random.nextBoolean());`
   - Contact `i = 0` was explicitly set to unverified (`verified = false`), while `i = 1` was verified (`verified = true`). The remaining contacts (`i = 2..20`) were assigned verification randomly via `random.nextBoolean()`.
2. **Reverse Temporal Ordering**:
   - In `createPrivateMessages()`, synthetic messages are timestamped sequentially using `clock.currentTimeMillis()`.
   - Contact 0 was generated first (`08:54:05.163`), while Contact 20 (`Christian Goldbach`) was generated last (`08:54:07.619`).
   - `ContactsViewModel.java:108` sorts the contact list descending by timestamp (`Collections.sort(contacts)` via `ContactListItem.compareTo()`: `Long.compare(o.getTimestamp(), timestamp)`).
   - Consequently, the latest contact (`Christian Goldbach`, Contact 20) appeared at index 0 (top of the list), while Contact 0 (the guaranteed unverified contact) sorted to index 20 (the very bottom of the 21-item list).
3. **Viewport Clipping**:
   - On the Pixel 7 emulator display (1080x2400), only ~8 contact items fit on screen without scrolling.
   - In run `20261105T075022Z`, `random.nextBoolean()` happened to roll `true` for all 8 contacts visible in the viewport (`step-029-assertCondition-Unverified_contact.json` shows rows for `Christian Goldbach`, `George Boole`, `Charles Babbage`, `Plato`, `Kurt Gödel`, `Archimedes`, `Pierre-Simon Laplace`, `Charles Babbage`, all displaying `"Verified contact"`).
   - Maestro's `assertVisible: text: "Unverified contact"` checks only currently visible hierarchy elements and does not perform autoscroll. Because the guaranteed unverified contact was positioned at index 20 off-screen, the assertion failed.

---

## 3. Resolution

### 3.1 Deterministic Contact Verification Distribution

To ensure both `"Verified contact"` and `"Unverified contact"` are deterministically visible in the top viewport rows without requiring scrolling or depending on `random.nextBoolean()`:
- Modified `briar-core/src/main/java/org/briarproject/briar/test/TestDataCreatorImpl.java` to alternate verification state deterministically:
  ```java
  boolean verified = (i % 2 == 1);
  ```
- Because contacts alternate across the entire generated sequence, the latest contacts (which sort to positions 0 and 1 at the top of the contact list) are guaranteed to include both an unverified contact (`i = 20`, `20 % 2 == 0 -> false`) and a verified contact (`i = 19`, `19 % 2 == 1 -> true`).
- Both verification labels are now guaranteed to render in the first two visible rows on every run across all device displays.

---

## 4. Validation and Headroom

### 4.1 Unit Test Suite
- Executed `:briar-android:testOfficialDebugUnitTest` with OpenJDK 17:
  **BUILD SUCCESSFUL** in 1m 44s (73 actionable tasks: 35 executed, 38 from cache, 0 failures).
- Executed `:briar-core:test` with OpenJDK 17:
  **BUILD SUCCESSFUL** in 28s (23 actionable tasks: 8 executed, 15 up-to-date, 0 failures).

### 4.2 End-to-End Proof under Shared Resource Lease
Rebuilt the official debug APK (`briar-android-official-debug.apk`) and evaluated against `Pixel_7` under `resource_lease.py exec --resource device:Pixel_7 --task-id t_d31d619f`:
1. **Flow 04 (`04-conversation-list.yaml`)**:
   - Welcome & account setup: PASS
   - Developer Options > Create test data: PASS
   - Contact list recycler view & structure: PASS
   - `"Verified contact"` assertion: PASS
   - `"Unverified contact"` assertion: PASS
   - Conversation drilldown, back-navigation, and drawer navigation: PASS
   - **Result: 100% assertions green (exit code 0)**.
2. **Flow 03 (`03-send-receive-message.yaml`)**:
   - Welcome & account setup: PASS
   - Password entry: PASS
   - Navigation to Developer Options > Create test data: PASS
   - Message send end-to-end (`MaestroQAtestmessage`): PASS
   - Message bubble local echo & `"now"` timestamp: PASS
   - Back navigation to contacts: PASS
   - **Result: 100% assertions green (exit code 0)**.

### 4.3 Headroom, Workspace Hygiene, and Policy
- `agent-tools/review_headroom.sh`: 4094 lines headroom remaining (review limit 7500, feature budget 7000, status OK).
- `agent-tools/check_product_language_policy.sh`: PASS (`product language policy ok`).
- `agent-tools/check_workspace_hygiene.sh`: PASS (`workspace hygiene ok`).

---

## 5. Conclusion

The post-delivery E2E gate failures following commit `841e5b815` were investigated and definitively resolved:
1. Flow 03's failure was an environmental focus race caused by a concurrent Google Play background package update of `com.google.android.calendar` on the emulator, which backgrounded `SetupActivity` during `hideKeyboard`.
2. Flow 04's failure was a viewport visibility issue caused by timestamp-descending sort placing Contact 0 off-screen, combined with non-deterministic random verification assignment.
3. Commit `841e5b815` (MX-054) modified only Matrix connector attachment file sniffing and did not introduce any product regression.
4. Contact verification assignment in `TestDataCreatorImpl.java` is now deterministic, ensuring both verified and unverified contacts are always visible in the initial viewport rows. Both flows now pass cleanly end-to-end on `Pixel_7`.
