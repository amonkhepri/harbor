# MX-074 isolated SDK login spike: static evidence

Card `t_6831b86c`, 2026-10-28. Product base
`2b2a9e4b87b167e29e16d64e189cc4fc65807309`.

**Partial result: 26.10.23 is statically rejected; the sole fallback 26.08.11
passes the signature gate and direct delegate compilation. No native workaround
has been demonstrated.** Keep `t_a2a70854` blocked on native login evidence.
This report does not complete the experiment or authorize a dependency change.

## Artifact and compatibility evidence

Downloaded complete AARs from the literal Maven Central coordinates
`org.matrix.rustcomponents:sdk-android:26.10.23` and, only after its rejection,
`org.matrix.rustcomponents:sdk-android:26.08.11`:

| Version | AAR SHA-256 | Signature gate |
| --- | --- | --- |
| 26.10.23 | `9dc2e0214d33beccaaaadff997a85e916e2ca73cbc585e28596b291c3b1c6b22` | FAIL, exit 1 |
| 26.08.11 | `267c562966f47e94469bf564c70358b999f0453de94353d13f8ffd4555385dbc` | PASS, exit 0 |

Artifact URLs follow
`https://repo.maven.apache.org/maven2/org/matrix/rustcomponents/sdk-android/V/sdk-android-V.aar`.
Both AAR manifests declare `android:minSdkVersion="21"`. Both include
`jni/<abi>/libmatrix_sdk_ffi.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and
`x86_64`. These are archive inspections, not native ABI execution proof.

Ran HarborAgent's `agent-tools/verify_matrix_sdk_api_shape.sh --aar <file>`
with `TARGET_REPO` explicitly set to this card's pinned workspace. This selects
this workspace's reflective adapter and direct delegate sources, rather than
the helper's default checkout. The only reported missing signature on 26.10.23:

```text
org.matrix.rustcomponents.sdk.MsgLikeContent:
public final java.util.List<org.matrix.rustcomponents.sdk.Reaction> getReactions();
```

The gate's final status text hardcodes `pinned sdk-android:26.08.05`, even
when `--aar` selects another artifact. The artifact arguments and hashes above
identify what was actually tested. No helper or production API was changed to
make a candidate pass. No third candidate was tried.

Compiled the unchanged production
`DirectMatrixClientSessionDelegateFactory.kt` and its
`MatrixClientSessionDelegateFactory.kt` interface using Kotlin compiler
1.9.25, JVM target 1.8, and `classes.jar` extracted from the complete 26.08.11
AAR, with Kotlin stdlib 1.9.25. Compilation exited 0 with no diagnostics.
This confirms the compiled delegate boundary only; it is not an Android harness
build, Gradle variant proof, or native library execution.

## Recorded phases

Wall durations use the host monotonic clock, in seconds. Initial 26.10.23
download/gate durations were not measured; its timed gate recheck is reported
explicitly rather than inventing initial timings.

| Phase | Seconds | Result |
| --- | ---: | --- |
| 26.10.23 signature gate recheck | 14.776 | exit 1, same missing signature |
| 26.08.11 download | 1.924 | exit 0 |
| 26.08.11 signature gate | 15.229 | exit 0 |
| 26.08.11 direct delegate compilation | 2.102 | exit 0 |

## Native comparison: not executed

Preflight `check-phone-connection.sh` reported `phone_connected: false`,
`authorized_physical_devices: 0`, `adb_status: ok`. This blocks device proof;
it did not prevent the artifact inspections or direct delegate compilation.
No isolated Android harness has been implemented or built in this change.
That host-side work remains outstanding, as well as execution once an authorized
physical device is available. Do not count this report as satisfying those steps.

| Required cell | Authentication / restart / restore | Lifecycle probes | Phase duration |
| --- | --- | --- | --- |
| 26.08.05 / structured | NOT RUN | NOT RUN | N/A |
| 26.08.05 / reflective | NOT RUN | NOT RUN | N/A |
| 26.08.11 / structured | NOT RUN | NOT RUN | N/A |
| 26.08.11 / reflective | NOT RUN | NOT RUN | N/A |

Total login attempts: **0**. Total lifecycle probes: **0** (cap 16).
There are no authenticating cells. Static rejection is not a login failure.
None of the decision doc's runtime interpretation rows applies: there is no
baseline reproduction, candidate runtime failure, or candidate success evidence.

## Isolation, checkpoints, and remaining work

No SDK native library was loaded, isolated Android process started, SQLite store
opened, disposable store created/deleted, or credential file read. There is no
native-process cleanup or store deletion to certify. Public AARs, extracted
classes, gate output and the compiler argument list are under the run-owned
`/tmp/harbor-mx074-t_6831b86c/`; no downloaded binaries are committed.
Production pin remains 26.08.05 and the connector remains default-off.

Hermes task-comment CLI rejected the first checkpoint with exit 1:
`kanban: delegate_task child contexts cannot mutate Kanban tasks via the CLI`.
No environment, permission, transport or lifecycle workaround was attempted.
Checkpoints were instead published through the authorized HarborBoard broker:

- `F-20261028T123355Z-5330af5b`: first-candidate rejection.
- `F-20261028T123509Z-4bbb7704`: fallback gate pass and device absence.
- `F-20261028T123633Z-37a8a1f6`: delegate compile and partial-result boundary.

Continue from these artifacts with one isolated Kotlin debug/instrumentation
harness and separate Gradle dependency selections for 26.08.05 and 26.08.11.
Follow the reviewed decision doc in HarborAgent,
`docs/reviews/mx074_sdk_login_next_decision.md`: exactly four cells; identical
builder configuration; two bounded login attempts per cell; restart/restore
within each attempt; both lifecycle probes for each authenticating cell; stop a
cell at its first hang/crash. An external supervisor must verify process exit
within the five-second cleanup budget before another cell, with no SDK mixing,
no reuse of candidate-written SQLite by baseline, distinct per-attempt stores
and keys, generation fencing and cross-store isolation. Native cancellation
must be observed, not inferred from a Kotlin timeout. Delayed-callback evidence
must be labeled injected. Retain the 16-attempt/probe cap and post each checkpoint.
No further candidate is allowed after 26.08.11 admission.

Preflight review headroom against the immutable base: 0 insertions, 7,500 lines
available, status OK. This checkout contains neither AGENTS.md nor agent-tools;
current HarborAgent runtime, limits and quality guidance were read instead.
No earlier review comment exists on the authoritative card at inspection time;
this is new evidence beyond the unchanged starting HEAD, not a no-op resubmission.
Independent review and any lifecycle handoff remain the adapter's responsibility.
