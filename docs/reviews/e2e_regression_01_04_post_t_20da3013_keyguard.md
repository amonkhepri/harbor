# E2E Regression Analysis: 01-04 Flows (post t_20da3013 / commit 54b874627)

- Task: `t_c40bcfdb`
- Failing flows: `01-setup-login`, `02-add-contact`, `03-send-receive-message`, `04-conversation-list`
- Evidence: `HarborAgent/agent-tools/screenshots/e2e-20261109T221522Z/` (`maestro.log`, `results.xml`, `install.log`, `gradle.log`, `device_lease.log`)
- Commit under test: `54b874627` (`Merge work/t_20da3013 (0d08dfb4f)`, MX-078 day separators)
- Device: Pixel 8 `43140DLJH00630` (physical, USB)

## Verdict

**Not a product regression.** The phone was asleep with a secure lock screen
(keyguard) showing. Maestro launched the app behind the lock screen, so the
first assertion of every flow (`"Welcome to Harbor" is visible`) could only
see the lock screen. No product code change is needed for this card.

## 1. Gate evidence

- `install.log`: `Performing Streamed Install` / `Success`. The APK installed.
- `gradle.log`: `BUILD SUCCESSFUL`.
- `results.xml`: all four flows fail at the same first UI assertion after
  `launchApp: clearState: true`, each after about 26 s (the assertion's wait
  timeout). Nothing after that step ran. The suite started at
  `2026-11-09T23:17:17` (device local time), overnight, when the phone would
  normally be locked.

Every flow failing at the very first assertion, with the same message, is
what you see when the app UI is never on screen. It does not look like a
broken setup screen.

## 2. Diff audit of 54b874627

Production files changed since the previous merge (`13b498c94..54b874627`):

- `briar-android/.../connector/ConnectorConversationActivity.kt`
- `briar-android/.../connector/ConnectorConversationAdapter.kt`
- `briar-android/.../connector/ConnectorConversationMessageList.kt`
- `briar-android/.../connector/ConnectorConversationSearchState.kt`
- `briar-android/src/main/res/layout/list_item_connector_day_separator.xml`

All of these belong to the connector conversation timeline, which sits behind
the default-off connector gates. None of them touch `SetupActivity`, startup,
the manifest, the application class, or the Briar contact or conversation
screens that flows 01-04 exercise.

## 3. Reproduction on the same device (2026-11-10)

1. Built `:briar-android:assembleOfficialDebug` at `54b874627` and installed
   it on `43140DLJH00630` (`Success`).
2. Device state before the run: `mWakefulness=Dozing`, `isKeyguardShowing=true`.
3. `maestro --device 43140DLJH00630 test agent-files/qa-e2e/flows/01-setup-login.yaml`
   failed with the gate's exact error:
   `Assertion is false: "Welcome to Harbor" is visible`. The failure
   screenshot (`step-004-assertCondition-Welcome_to_Harbor.png`) shows the
   Pixel lock screen (clock, fingerprint prompt, charging status). Harbor is
   not visible.
4. `KEYCODE_WAKEUP` plus `wm dismiss-keyguard` woke the screen
   (`mWakefulness=Awake`), but the keyguard stayed up
   (`isKeyguardShowing=true`; focus moved to `AlternateBouncerView`). The
   device has a secure lock, and getting past it needs the owner's
   credentials. That is outside what an agent may do, so a green on-device
   run of flows 01-04 at this commit is still pending: it needs an unlocked
   device.

## 4. Unit validation of the changed code

With JDK 17 (`JAVA_HOME=/opt/homebrew/opt/openjdk@17`):

```
./gradlew :briar-android:testOfficialDebugUnitTest \
  --tests '*ConnectorConversation*' \
  --tests '*ContactListViewModelTest*' --tests '*ContactListFragmentTest*'
```

Result: 134 tests, 0 failures, 0 errors.

Note: under the Android Studio bundled JBR (Java 21), the same command
reports 78 false failures (`NoClassDefFoundError: android/webkit/RoboCookieManager`,
and Mockito unable to mock `android.app.Application`). This comes from the
JDK and Robolectric toolchain, not the code. Use JDK 17 for this module.

## 5. Recommendation for the E2E gate (operator-owned, not changed here)

Before running flows on a physical device, the gate should check
`dumpsys power` (`mWakefulness=Awake`) and `dumpsys window`
(`isKeyguardShowing=false`). If the device is asleep or locked, the gate should
classify the run as `infra_flake` / device-unavailable instead of opening a
product regression card. Alternatively, it could keep the test phone on a
non-secure lock with stay-awake-while-charging enabled.
