# E2E Regression Analysis: 01-setup-login (post t_14c4c7f2 / commit 1155ebf8b)

- Task: `t_08409078`
- Failing flow: `agent-files/qa-e2e/flows/01-setup-login.yaml`
- Evidence: `agent-tools/screenshots/e2e-20261026T204600Z/` (`maestro.log`, `results.xml`, `install.log`, `gradle.log`)
- Base commit reported: `1155ebf8b` (`Merge work/t_9345a4ed (99b061678)`)
- Pinned workspace: `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_08409078`
- Workspace HEAD evaluated: `1155ebf8b3ff1379537b1b1813e9a961b5f47964`

---

## 1. Context and Evidence Analysis

Following the delivery of task `t_14c4c7f2` in commit `1155ebf8b`, the post-delivery Maestro E2E gate reported a failure in flow `01-setup-login` during run `20261026T204600Z`:

```xml
<testsuites>
  <testsuite name="Test Suite" device="Pixel_7" tests="4" failures="1" time="267.159" timestamp="2026-10-26T21:46:36">
    <testcase id="02-add-contact" name="02-add-contact" classname="02-add-contact" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/02-add-contact.yaml" time="62.432" timestamp="2026-10-26T21:46:36" status="SUCCESS"/>
    <testcase id="04-conversation-list" name="04-conversation-list" classname="04-conversation-list" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/04-conversation-list.yaml" time="82.533" timestamp="2026-10-26T21:47:38" status="SUCCESS"/>
    <testcase id="01-setup-login" name="01-setup-login" classname="01-setup-login" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/01-setup-login.yaml" time="40.008" timestamp="2026-10-26T21:49:01" status="ERROR">
      <failure>Element not found: Id matching regex: org.briarproject.briar.android.debug:id/password_confirm</failure>
    </testcase>
    <testcase id="03-send-receive-message" name="03-send-receive-message" classname="03-send-receive-message" file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/03-send-receive-message.yaml" time="82.163" timestamp="2026-10-26T21:49:41" status="SUCCESS"/>
  </testsuite>
</testsuites>
```

In the exact same test run, the other three flows passed completely:
- `02-add-contact` (62.4s) — SUCCESS
- `04-conversation-list` (82.5s) — SUCCESS
- `03-send-receive-message` (82.2s) — SUCCESS

All three passing flows execute the identical fast-path account setup sequence on `SetPasswordFragment`:
1. `assertVisible: text: "Welcome to Harbor"`
2. `tapOn: id: "org.briarproject.briar.android.debug:id/nickname_entry"`
3. `inputText: "MaestroQA"`
4. `hideKeyboard`
5. `tapOn: id: "org.briarproject.briar.android.debug:id/next"`
6. `assertVisible: text: "Choose a Password"`
7. `tapOn: id: "org.briarproject.briar.android.debug:id/password_entry"`
8. `inputText: "MaestroQA123!"`
9. `tapOn: id: "org.briarproject.briar.android.debug:id/password_confirm"`
10. `inputText: "MaestroQA123!"`
11. `hideKeyboard`
12. `tapOn: id: "org.briarproject.briar.android.debug:id/next"`

---

## 2. Root Cause Investigation

### 2.1 Audit of Task t_14c4c7f2 and Product Commit 1155ebf8b

An audit of the git history confirms that task `t_14c4c7f2` was an internal control-plane tooling task on HarborAgent ("consolidate reclaim/release liveness-verification primitive") affecting only `agent-tools/resource_lease.py` and `agent-tools/story_scheduler.py`. Zero lines of Harbor product code or setup/authentication flows were touched.

Product commit `1155ebf8b` merged `work/t_9345a4ed` (`99b061678`), which only added the documentation file `docs/reviews/matrix_two_account_proof_mx074_step2b.md`. Prior post-delivery E2E gate runs at product commit `1155ebf8b` (such as task `t_952e8fa4` at timestamp `20261026T132128Z` recorded in `docs/state/post_delivery_e2e_log.json`) passed 4/4 flows cleanly.

### 2.2 Deep Failure Mechanics (Debug Hierarchy and Screenshot Inspection)

Direct examination of the Maestro test debug run artifacts at `/Users/amunratis/.maestro/tests/2026-10-26_214632/01-setup-login/`:
- In `commands.json`, command sequence 9 tapped `password_entry` at `(540, 1212)`.
- Command sequence 10 executed `inputText: "MaestroQA123!"`.
- Command sequence 11 (`tapOn: id: "org.briarproject.briar.android.debug:id/password_confirm"`) timed out after 17.2 seconds without locating the element.
- Crucially, the captured view hierarchy (`screen-hierarchy/step-012-tapOnElement-org.briarproject.briar.andr.json`) and screenshot (`screenshots/step-012-tapOnElement-org.briarproject.briar.andr.png`) show that the active foreground window was `com.google.android.apps.nexuslauncher/.NexusLauncherActivity` (Nexus Launcher All Apps search view).
- The input field `com.google.android.apps.nexuslauncher:id/input` contained the literal string `"MaestroQA123!"`.

During the transition into `SetPasswordFragment`, an emulator gesture or focus race caused the Harbor application window to be backgrounded and Nexus Launcher's All Apps drawer to be exposed. Because Nexus Launcher was in the foreground when Maestro issued `inputText: "MaestroQA123!"`, the keystrokes went to the Android system launcher search bar. Subsequent attempts by Maestro to find `org.briarproject.briar.android.debug:id/password_confirm` necessarily failed because the Harbor UI was not in the active hierarchy.

### 2.3 Concurrent Test Execution Interleaving

Inspection of the host process tree revealed another contributing factor: concurrent agent sessions (such as `t_ab1e4120`) running Maestro against the same shared emulator (`emulator-5554`). Concurrent test runner execution issues simultaneous `clearState` (`pm clear`) and `am force-stop` commands, which terminate active instrumentation processes (`dev.mobile.maestro` killed with signal 9) and interfere with window focus, producing intermittent runner-level failures.

---

## 3. Validation and Headroom

1. **Product Language Policy & Workspace Hygiene**:
   - `agent-tools/check_product_language_policy.sh`: PASS
   - `agent-tools/check_workspace_hygiene.sh`: PASS
   - No untracked build artifacts, secrets, or policy violations.

2. **Unit Test Suite**:
   - Executed `:briar-android:testOfficialDebugUnitTest` with Java 17 (`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`): PASS.

3. **Headroom Status**:
   - `agent-tools/review_headroom.sh`: 4507 lines headroom remaining (review limit 7500, feature budget 7000, status OK).

---

## 4. Conclusion and Resolution

The reported failure in `01-setup-login` during post-delivery run `20261026T204600Z` was an environmental artifact caused by a transient window focus/launcher overlay race where the Harbor app was backgrounded during password input, not a product code regression.

No product code changes were introduced or required. The application setup and authentication flows remain correct and verified. This analysis documents the root cause and provides the definitive resolution for task `t_08409078`.
