# MX-074 Step 2a/2: Local Profile Unlock + Matrix Account A Login Verification

- Task: `t_a895a2cb` (MX-074 step 2a/2, split from `t_9345a4ed`)
- Pinned Workspace: `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_a895a2cb`
- Workspace HEAD at time of verification: `73b8e9f12e20373419a2da0ee3e91f11b0c2e08b` (no code change required for this step; see §4)
- Device: `emulator-5554` (`sdk_gphone64_arm64`), app `org.briarproject.briar.android.debug`
- Homeserver: disposable local Synapse (`~/.local/state/harbor-matrix-disposable/`), `server_name: disposable.local`, listening on `127.0.0.1:8008`, forwarded to the device via `adb reverse tcp:8008 tcp:8008`

## 1. Scope

Per the card split, this step covers only: unlock/reset the local Harbor profile lock (unrelated secret, volatile-memory key per `AccountManagerImpl.databaseKey`) and verify Matrix account `harbor_test_a` login against the disposable homeserver, with evidence, then stop. Room join / message / restart persistence remain out of scope (`t_9345a4ed`).

## 2. Local profile unlock — PASS

- Device foreground on resume was `StartupActivity` (`edit_password` / `btn_sign_in`), confirming the local profile lock was active, not the Matrix screen.
- Unlocked with the fixed test password `123456` (same convention as the repo's own `UiTest.java`), already recorded in `~/.local/state/harbor-matrix-disposable/local_profile.env` from prior work on this lineage; not duplicated here or in git/board history.
- Evidence: `adb shell dumpsys window | grep mCurrentFocus` moved from `StartupActivity` to `NavDrawerActivity` immediately after submitting the password, with no error dialog.

## 3. Matrix account A login — FAIL (new, reproducible root cause; not the previously-fixed discovery issue)

### 3.1 What was verified to work

- Navigated fresh to the "Matrix account" setup screen (Settings → Matrix account), which starts at the `HOMESERVER` step, confirming no stale/partial Matrix session existed for this lineage.
- Entered the raw homeserver URL `http://127.0.0.1:8008` (explicit scheme + port, per the known fix from `t_87aebddf`/run #1469/#1472: use `homeserverUrl()` directly, not bare `serverName()` discovery). **Discovery completed in well under 3 seconds** and advanced to the `CREDENTIALS` step. This independently confirms the previously-fixed `server_name`/`.well-known` discovery issue is *not* the cause of the current failure — the fix in `1bb3d9877` is working correctly.
- `curl http://127.0.0.1:8008/_matrix/client/versions` from the host returns a healthy Synapse version list; the Synapse process was live throughout (`ps aux | grep synapse`).

### 3.2 What fails

- Entering username `harbor_test_a` + the account A password from `~/.local/state/harbor-matrix-disposable/creds.env` and submitting ("Sign in") reliably shows `Connecting…` and then, after a flat **~30 second** delay, fails with:

  > Could not connect. Check your details and try again.

- This reproduced identically on **three separate attempts** (two before this workspace state was reset to a fresh `HOMESERVER` step, one after, using the confirmed-good raw loopback URL both times). Logcat shows the same exception every time:

  ```
  org.briarproject.briar.matrix.ReflectiveMatrixHomeserverDiscoveryClient$DiscoveryAdapterFailure
      at ReflectiveMatrixHomeserverDiscoveryClient.buildClient(ReflectiveMatrixHomeserverDiscoveryClient.kt:1371)
      at ReflectiveMatrixHomeserverDiscoveryClient.login(ReflectiveMatrixHomeserverDiscoveryClient.kt:250)
      at MatrixAuthSessionImpl.submitCredentials(MatrixAuthSessionImpl.kt:70)
      at MatrixSetupViewModel$submitCredentials$1.invoke(MatrixSetupViewModel.kt:56)
      ...
  ```

- `buildClient()`'s own `latch.await(discoveryTimeoutMs, ...)` (30_000L, see line 73) times out and throws `DiscoveryAdapterFailure` — i.e. the SDK's suspend `ClientBuilder.build()` call, invoked from `login()`, never calls back via its `Continuation`, every time.

### 3.3 Isolation: this is specific to the login-path client builder, not to network reachability

`login()`'s builder chain differs from the already-working `discover()` builder chain in exactly two ways: it calls `applyStore()` (configures `sqliteStore(SqliteStoreBuilder(dataPath, cachePath).passphrase(...))` instead of `inMemoryStore()`) and `installSessionDelegate()` (`ClientBuilder.setSessionDelegate(...)`) before `buildClient()`. Both builders are given the identical `homeserverUrl` string. Since:

- the `discover()` builder (same URL, `inMemoryStore()`, no session delegate) returns in <3s, and
- the `login()` builder (same URL, `sqliteStore()` + `setSessionDelegate()`) hangs for the full 30s timeout every time,

the hang is attributable to the persistent-store and/or session-delegate configuration added for login, not to the homeserver URL or network path.

- `adb shell top -p <pid>` sampled every ~4s throughout a full "Connecting…" window shows the app process at a flat **0.0% CPU** and an unchanged cumulative `TIME+` the entire time — the process is genuinely blocked (consistent with a native call that never resumes its continuation), not doing slow local computation (e.g. key-derivation) that would eventually finish given more time.

### 3.4 Conclusion

Account A login against the disposable homeserver: **FAIL**, with a concretely diagnosed, reproducible cause distinct from anything in the prior diagnosis history for this lineage (not the server_name/.well-known issue; not an emulator/profile-lock state issue; not a reachability issue). The hang is isolated to `ReflectiveMatrixHomeserverDiscoveryClient.login()`'s client build when `sqliteStore()` + `setSessionDelegate()` are applied together.

No Kotlin fix is included in this commit: the two candidate culprits (`applyStore`'s native SQLite/passphrase store initialization vs. `installSessionDelegate`'s `ClientSessionDelegate` — matrix-rust-sdk's cross-process session lock is known to invoke `retrieveSessionFromKeychain` during client setup in some configurations, and `DirectMatrixClientSessionDelegateFactory.retrieveSessionFromKeychain` reads a sidecar file that does not exist yet on a first-ever login) cannot be distinguished without either SDK source access this module intentionally has zero compile-time dependency on, or an isolated instrumented repro — both out of scope for this bounded verification card. Recommend a dedicated follow-up card to bisect `applyStore` vs. `installSessionDelegate` in isolation before attempting a fix, rather than guessing against the reflective/JNI boundary.
