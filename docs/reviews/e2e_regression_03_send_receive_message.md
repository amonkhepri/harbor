# E2E Regression Analysis: 03-send-receive-message (post t_d3b52235 / commit 1768ab676)

- Task: `t_47aabd8d`
- Failing flow: `agent-files/qa-e2e/flows/03-send-receive-message.yaml`
- Evidence: `agent-tools/screenshots/e2e-20261015T102859Z/` (`maestro.log`, `results.xml`)
- Base commit: `1768ab67690462ab7db1d77e93f541662d4024f8`

---

## 1. Context and Evidence Analysis

Following task `t_d3b52235` and commit `1768ab676` (`t_ff6671ac`), the post-delivery Maestro E2E gate reported a failure in flow `03-send-receive-message` in run `20261015T102859Z`:

```xml
<testcase id="03-send-receive-message" name="03-send-receive-message"
          classname="03-send-receive-message"
          file="/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-files/qa-e2e/flows/03-send-receive-message.yaml"
          time="56.415" status="ERROR">
  <failure>Assertion is false: "Settings" is visible</failure>
</testcase>
```

In the same run, the other three flows passed:
- `01-setup-login` (56s) - SUCCESS
- `02-add-contact` (1m 6s) - SUCCESS
- `04-conversation-list` (1m 20s) - SUCCESS

Historical logs in `docs/state/post_delivery_e2e_log.json` indicate that commit `1768ab676` had already passed 4/4 flows in two consecutive earlier runs (`10:16:36Z` and `10:23:33Z`) before flaking at `10:28:59Z`. Later commits (`a5605731e`, `b195f54e3`, `253155c5a`) likewise passed 4/4 in almost all runs, with flow 03 exhibiting an isolated transient failure with the identical error at `20261017T172615Z` before passing on the subsequent run.

---

## 2. Root Cause Investigation

### 2.1 Audit of Commit 1768ab676
Commit `1768ab676` addressed task `t_ff6671ac` ("wait for debug test-data seeding before leaving TestDataActivity"). It modified only:
- `briar-android/src/main/java/org/briarproject/briar/android/test/TestDataActivity.java`
- `briar-api/src/main/java/org/briarproject/briar/api/test/TestDataCreator.java`
- `briar-core/src/main/java/org/briarproject/briar/test/TestDataCreatorImpl.java`

These changes added an `onComplete` callback executed when `TestDataCreator.createTestData()` finishes seeding, preventing `TestDataActivity` from finishing before the database writes complete.

Flow `03-send-receive-message` failed at step 83:
```yaml
- assertVisible:
    text: "Contacts"
- tapOn:
    id: "org.briarproject.briar.android.debug:id/toolbar"
    point: "5%,50%"
- assertVisible:
    text: "Settings"
```
This failure point occurs immediately upon first entering the main screen (`NavDrawerActivity`) from account setup (`SetupActivity`), before reaching Settings > Developer Options > Create test data. Commit `1768ab676` is downstream of this failure point and did not alter any setup or navigation logic.

### 2.2 Lifecycle Audit (SetupActivity -> NavDrawerActivity)
An earlier hypothesis suggested `NavDrawerActivity` was being recreated by `onActivityResult(REQUEST_PASSWORD)` in `BriarActivity.java`, dropping the toolbar tap. Tracing the execution path disproved this:
- Fresh account setup in flow 03 starts from `clearState: true` through `SetupActivity`.
- On completion, `SetupActivity.showApp()` launches `ENTRY_ACTIVITY` (`NavDrawerActivity`) with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_CLEAR_TOP`.
- Because the account is already initialized and signed in by `SetupViewModel`, `BriarActivity.onResume()` finds `briarController.accountSignedIn() == true`.
- Neither `StartupActivity` nor `startActivityForResult(REQUEST_PASSWORD)` is ever invoked on this path; `recreate()` is never called.

### 2.3 The Timing Race in Flow 03
The root cause was an emulator timing race in the Maestro flow definition:
1. `SetupActivity.showApp()` launches `NavDrawerActivity` and triggers an activity transition animation (`overridePendingTransition(R.anim.screen_new_in, R.anim.screen_old_out)`, 300ms).
2. During `ContactListFragment.onCreateView()`, the activity title is set to "Contacts" via `requireActivity().setTitle(R.string.contact_list_button)`.
3. Maestro immediately detects `- assertVisible: text: "Contacts"` and executes the next command:
   `- tapOn: id: "org.briarproject.briar.android.debug:id/toolbar", point: "5%,50%"`.
4. Because the window transition animation is still in progress, the toolbar view is still translating into position (`screen_new_in` translates from `100%p` to `0`), and Android's `filterTouchesWhenObscured` (enforced by `BaseActivity`'s `TapSafeFrameLayout` wrapper) drops touch events when windows overlap during transitions.
5. As a result, the navigation drawer toggle tap is dropped, the drawer does not open, and Maestro's subsequent strict assertion `- assertVisible: text: "Settings"` times out and fails.

### 2.4 Comparison with Flow 04
In contrast, `04-conversation-list.yaml` waits for the contact list empty state before attempting to open the navigation drawer:
```yaml
- assertVisible:
    text: "Contacts"
- assertVisible:
    text: "No contacts to show"
- assertVisible:
    text: "Tap the + icon to add a contact"
- tapOn:
    id: "org.briarproject.briar.android.debug:id/toolbar"
    point: "5%,50%"
- tapOn:
    text: "Settings"
```
Waiting for "No contacts to show" and "Tap the + icon to add a contact" ensures that:
- The database query from `ContactsViewModel` has completed.
- The window transition animation has finished.
- The UI hierarchy is settled and fully receptive to input.
Furthermore, flow 04 uses `tapOn: text: "Settings"`, which benefits from Maestro's built-in retry polling rather than failing on an immediate strict `assertVisible`.

---

## 3. Resolution

1. **Product code (`NavDrawerActivity.java`)**:
   Added `onOptionsItemSelected` delegating to `drawerToggle.onOptionsItemSelected(item)`:
   ```java
   @Override
   public boolean onOptionsItemSelected(MenuItem item) {
       if (drawerToggle.onOptionsItemSelected(item)) {
           return true;
       }
       return super.onOptionsItemSelected(item);
   }
   ```
   This ensures standard options menu / home navigation events properly toggle the navigation drawer.

2. **E2E flow (`03-send-receive-message.yaml` in HarborAgent)**:
   Updated flow 03 to wait for the contact list empty state markers ("No contacts to show", "Tap the + icon to add a contact") before tapping the toolbar, and replaced the strict `assertVisible` with `tapOn: text: "Settings"`, matching flow 04's robust pattern.

3. **Tracking documentation (`harbor_bug_review.md` in HarborAgent)**:
   Documented the resolution under entry `Post-delivery E2E regression (20261015T102859Z, task t_d3b52235)`.
