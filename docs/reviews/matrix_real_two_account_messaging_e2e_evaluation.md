# MX-044 Real Matrix Two-Account Messaging E2E Evaluation

- Task: `t_9ef1e36a` (MX-044)
- Requirement: Prove Real Matrix Two-Account Messaging
- Active Branch: `codex/feature-20261015144741`
- Workspace HEAD: `3ddb358209b3c56def7052e3a30bbc42065e5ca4`
- Pinned Workspace: `/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_9ef1e36a`

---

## 1. Requirement & Scope

The objective of MX-044 is to exercise login, join, bidirectional text delivery, and force-stop session restoration using Dan-provisioned disposable Matrix accounts and a Matrix-enabled build on an emulator.

Per the task contract:
- Expected validation: "Remote client sees Harbor send; Harbor sees remote send after restart. If provisioning is absent, report the prerequisite without fabricating a pass."
- Non-goals: "Account provisioning belongs to Dan; no personal accounts, synthetic-data substitutes or broad auth rewrite."
- Review-headroom safety: "Apply the shared live headroom, 400-line budget and independent-review gates above. Evidence-only work needs no artificial product commit."

---

## 2. Environment & Preflight Audit

1. **Device / Emulator Status**:
   - `adb devices` reports `emulator-5554` attached and in `device` state.
   - Physical phone connection helper (`agent-tools/check-phone-connection.sh`):
     `phone_connected: false`, `authorized_physical_devices: 0`, `adb_status: ok`.
   - Emulator availability is confirmed and ready for device-level execution.

2. **Matrix Homeserver Network Probe**:
   - Local port probe (`lsof -i :8008`, `lsof -i :8448`, `lsof -i :6167`, `lsof -i :8080`) confirms no local Matrix homeserver (Synapse, Dendrite, Conduit) is currently running or listening on host ports.

3. **Dan Provisioning Audit**:
   - Memory inspection of Dan's operational records (`Memory/dan/`) confirms Dan did not provision a disposable homeserver or test accounts (see `N-20261022T193627Z-28d18657`, `N-20261023T063835Z-fdffc09f`, and recent runs up to `N-20261025T131241Z-c0e4ed5e`).
   - Per non-goals, account provisioning belongs exclusively to Dan; workers must not use personal credentials, fabricate synthetic network passes, or bypass auth boundaries.
   - **Prerequisite Status**: Absent. As directed by the task contract, this prerequisite is explicitly reported rather than simulating or fabricating a pass.

---

## 3. Build & SDK Shape Verification

1. **Matrix-Enabled Assembly**:
   - Executed: `agent-tools/build.sh -Pharbor.matrixConnector.enabled=true`
   - Result: `BUILD SUCCESSFUL in 1m 36s` (78 actionable tasks: 46 executed, 32 from cache).
   - Artifact: Verified official debug APK generated at `briar-android/build/outputs/apk/official/debug/briar-android-official-debug.apk` with `MATRIX_CONNECTOR_ENABLED=true` and Matrix SDK components packaged.

2. **SDK API Shape Alignment**:
   - Executed: `agent-tools/verify_matrix_sdk_api_shape.sh`
   - Result: `OK: pinned sdk-android:26.08.05 matches reflective calls, timeline snapshot, and direct ClientSessionDelegate boundaries.`
   - Validates that reflection call sites and direct delegates match the pinned Matrix Rust SDK binary interface.

---

## 4. Focused Unit & Regression Verification

1. **Matrix & Connector Test Suite (Default-Off Configuration)**:
   - Executed:
     ```bash
     TARGET_REPO=/Users/amunratis/AndroidStudioProjects/Harbor-worktrees/t_9ef1e36a \
       agent-tools/test.sh \
       --tests "org.briarproject.briar.matrix.*" \
       --tests "org.briarproject.briar.android.matrix.*" \
       --tests "org.briarproject.briar.android.connector.*"
     ```
   - Configuration Distinction: The unit test command omits `-Pharbor.matrixConnector.enabled=true`. In `briar-android/build.gradle:75-77`, `harbor.matrixConnector.enabled` defaults to `false`. Under lines 252–256, this default-off configuration places `src/matrix/kotlin` into `sourceSets.test.java.srcDirs` where it compiles and executes against the simulated test SDK fake rather than the real Matrix Rust SDK dependency.
   - Result: `BUILD SUCCESSFUL in 32s` (73 actionable tasks: 12 executed, 61 up-to-date, 0 failures).
   - Verifies connector conversation adapters, typing notices, session lifecycle transitions, retry logic, and simulated message plumbing against test fakes.

2. **Proof Distinction**:
   - **Simulated Proof**: PASS (Matrix unit tests with mock/fake SDK clients verify session lifecycle, retry logic, error handling, and message snapshot plumbing under the default-off configuration).
   - **Real Network Proof**: BLOCKED on prerequisite (Dan disposable account provisioning).

---

## 5. Summary & Prerequisite Handoff

The recorded checks verify Matrix-enabled APK packaging (`-Pharbor.matrixConnector.enabled=true`) alongside API shape alignment against the pinned Matrix Rust SDK binary, and confirm that simulated regression coverage passes under the default-off unit test configuration.

However, these checks do not establish real network delivery or live force-stop session restoration. Product readiness for real Matrix two-account messaging remains unverified pending live execution, which is blocked on the prerequisite of Dan-provisioned disposable homeserver credentials.
