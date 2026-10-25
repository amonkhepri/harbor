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

1. **Matrix & Connector Test Suite**:
   - Executed:
     ```bash
     ./gradlew :briar-android:testOfficialDebugUnitTest \
       --tests "org.briarproject.briar.matrix.*" \
       --tests "org.briarproject.briar.android.matrix.*" \
       --tests "org.briarproject.briar.android.connector.*"
     ```
   - Result: `BUILD SUCCESSFUL in 53s` (73 actionable tasks: 21 executed, 6 from cache, 46 up-to-date, 0 failures).
   - All Matrix authentication session state transitions, homeserver discovery, message send/receive abstractions, typing notices, and connector conversation adapters pass 100% of unit tests.

2. **Proof Distinction**:
   - **Simulated Proof**: PASS (Matrix unit tests with mock/fake SDK clients verify session lifecycle, retry logic, error handling, and message snapshot plumbing).
   - **Real Network Proof**: BLOCKED on prerequisite (Dan disposable account provisioning).

---

## 5. Summary & Prerequisite Handoff

The Harbor product codebase builds and passes all Matrix connector unit tests with the Matrix connector enabled. Real two-account network verification is unblocked on the product side and pending only the external prerequisite of Dan-provisioned disposable homeserver credentials.
