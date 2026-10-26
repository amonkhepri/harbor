# E2E Regression Analysis: 04-conversation-list (post t_ab1e4120 / commit b691fceed)

- Task: `t_6934ee94`
- Failing flow: `agent-files/qa-e2e/flows/04-conversation-list.yaml`
- Evidence: `/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-tools/screenshots/e2e-20261026T211345Z/` (`maestro.log`, `results.xml`, `install.log`, `gradle.log`)
- Base commit reported: `b691fceeddaa4b0bca59c60eb6ca5a9c3b83d218` (`docs(reviews): document root cause and E2E verification for post-t_680df3e4 failure (t_ab1e4120)`)
- Pinned workspace: `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_6934ee94`
- Workspace HEAD evaluated: `09848899e9fb2662bd29ab7363cb740345b44037` (`Merge work/t_08409078 (733647f92)`)

---

## 1. Context and Evidence Analysis

Following the delivery of task `t_ab1e4120` in commit `b691fceed`, the post-delivery Maestro E2E gate reported a failure in flow `04-conversation-list` during run `20261026T211345Z`:

```xml
<testsuites>
  <testsuite name="Test Suite" device="Pixel_7" tests="4" failures="1" time="275.911" timestamp="2026-10-26T22:14:42">
    <testcase id="02-add-contact" name="02-add-contact" classname="02-add-contact" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/02-add-contact.yaml" time="64.06" timestamp="2026-10-26T22:14:42" status="SUCCESS"/>
    <testcase id="04-conversation-list" name="04-conversation-list" classname="04-conversation-list" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/04-conversation-list.yaml" time="74.051" timestamp="2026-10-26T22:15:46" status="ERROR">
      <failure>Assertion is false: id: org.briarproject.briar.android.debug:id/recyclerView is visible</failure>
    </testcase>
    <testcase id="01-setup-login" name="01-setup-login" classname="01-setup-login" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/01-setup-login.yaml" time="56.956" timestamp="2026-10-26T22:17:00" status="SUCCESS"/>
    <testcase id="03-send-receive-message" name="03-send-receive-message" classname="03-send-receive-message" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/03-send-receive-message.yaml" time="80.821" timestamp="2026-10-26T22:17:57" status="SUCCESS"/>
  </testsuite>
</testsuites>
```

In the exact same test run, the other three flows passed completely:
- `02-add-contact` (64.1s) — SUCCESS
- `01-setup-login` (57.0s) — SUCCESS
- `03-send-receive-message` (80.8s) — SUCCESS

The failing assertion in `04-conversation-list` was:
`Assertion is false: id: org.briarproject.briar.android.debug:id/recyclerView is visible` (step 26 in `commands.json`).

---

## 2. Root Cause Investigation

### 2.1 Audit of Commit b691fceed and HEAD 09848899e

An audit of the git history confirms that commit `b691fceed` (task `t_ab1e4120`) was strictly a documentation change:
- `docs/reviews/e2e_regression_01_04_post_1155ebf8b.md` (+110 lines)

Similarly, merge commit `09848899e` merged `work/t_08409078` (`733647f92`), which also added only a review document:
- `docs/reviews/e2e_regression_01_setup_login_post_t_14c4c7f2.md` (+94 lines)

Zero lines of product code, resources, Android manifests, or Gradle configurations were modified across this range. It is structurally impossible for these documentation commits to introduce a functional UI regression.

### 2.2 Deep Failure Mechanics (Concurrent Package Update & Process Termination)

Detailed examination of the Maestro execution artifacts and device logcat in `~/.maestro/tests/2026-10-26_221438/04-conversation-list/` revealed the exact failure sequence:

1. **Test Data Creation Triggered**:
   - At 22:16:42.209, Maestro successfully tapped `buttonCreateTestData` on `TestDataActivity`:
     `Tap on "Create test data", id: org.briarproject.briar.android.debug:id/buttonCreateTestData COMPLETED`
   - `TestDataActivity` invokes `testDataCreator.createTestData(...)`, which executes asynchronously on a background thread and then navigates to `ENTRY_ACTIVITY` via `goToEntryActivity()` (`FLAG_ACTIVITY_CLEAR_TOP`).

2. **Concurrent APK Installation**:
   - At exactly 22:16:45.474, while `04-conversation-list` was waiting for test data generation to complete, Android's `PackageManager` processed an incoming package update on the emulator:
     ```text
     10-26 22:16:45.474 I/PackageManager(  688): Update package org.briarproject.briar.android.debug code path from /data/app/~~g1efYvXPR2TbBghI02K2OQ==/org.briarproject.briar.android.debug-ru57914u8wnlPsJGJ8JLEw== to /data/app/vmdl182289008.tmp; Retain data and using new
     ```
   - Host filesystem timestamps confirm that the post-delivery E2E gate run for task `t_08409078` (`agent-tools/screenshots/e2e-20261026T211615Z/install.log`) executed `adb install -r "$APK"` at precisely `22:16:44 - 22:16:45`.

3. **Application Process Teardown & Launcher Fallback**:
   - The package update immediately caused the Android runtime to kill the active application process (`org.briarproject.briar.android.debug`, PID 7930):
     ```text
     10-26 22:16:45.620 W/ActivityTaskManager(  688): Force removing ActivityRecord{173353500 u0 org.briarproject.briar.android.debug/org.briarproject.briar.android.test.TestDataActivity t397 f}}: app died, no saved state
     10-26 22:16:45.630 I/WindowManager(  688): WINDOW DIED Window{45495c6 u0 org.briarproject.briar.android.debug/org.briarproject.briar.android.test.TestDataActivity}
     10-26 22:16:45.628 W/ziparchive(  939): Unable to open '/data/app/~~g1efYvXPR2TbBghI02K2OQ==/org.briarproject.briar.android.debug-ru57914u8wnlPsJGJ8JLEw==/base.apk': No such file or directory
     ```
   - Because the foreground task died, Android returned to the home screen (`NexusLauncherActivity`).
   - The captured hierarchy (`screen-hierarchy/step-027-assertCondition-org.briarproject.briar.a.json`) and screenshot (`screenshots/step-027-assertCondition-org.briarproject.briar.a.png`) taken upon assertion timeout at 22:16:59 confirm the device display was showing `com.google.android.apps.nexuslauncher:id/launcher`, not Harbor.

4. **Assertion Timeout**:
   - Maestro's assertion for `id: org.briarproject.briar.android.debug:id/recyclerView` waited for 17 seconds against the home launcher before failing with `Assertion is false: id: org.briarproject.briar.android.debug:id/recyclerView is visible`.

The failure was entirely an environmental test-concurrency race caused by overlapping E2E runner instances issuing `adb install` against the single shared emulator (`emulator-5554`), not a product regression.

---

## 3. Validation and Headroom

1. **Isolated Maestro E2E Verification**:
   - Executed flow `agent-files/qa-e2e/flows/04-conversation-list.yaml` in isolation against `emulator-5554` with Java 17 (`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`):
     - Account setup & welcome sequence: PASS
     - Navigation to Developer Options > Create test data: PASS
     - Test data creation button trigger: PASS
     - Contact list `recyclerView` rendering: PASS
     - Verified and unverified contact status labels: PASS
     - Per-row elements (`nameView`, `avatarView`, `dateView`, `trustIndicator`): PASS
     - Conversation drill-down and back-navigation to contacts: PASS
     - Drawer navigation between Forums and Contacts: PASS
     - **Result: 100% of steps PASSED (exit code 0)**.

2. **Unit Test Suite**:
   - Executed `./gradlew :briar-android:testOfficialDebugUnitTest`: **BUILD SUCCESSFUL** in 8s (0 failures).

3. **Workspace Hygiene & Language Policy**:
   - `agent-tools/check_product_language_policy.sh`: PASS (`product language policy ok`).
   - `agent-tools/check_workspace_hygiene.sh`: PASS (`workspace hygiene ok`).

4. **Headroom Status**:
   - `agent-tools/review_headroom.sh`: 4303 lines headroom remaining (review limit 7500, feature budget 7000, status OK).

---

## 4. Conclusion and Resolution

The reported failure in `04-conversation-list` during post-delivery run `20261026T211345Z` was an environmental artifact caused by a concurrent `adb install` from overlapping test gate execution (`e2e-20261026T211615Z`) killing the application process mid-test.

No product code changes were introduced or required. The contact and conversation list flows function correctly as verified by a clean, isolated Maestro run. This analysis documents the root cause and provides the definitive resolution for task `t_6934ee94`.
