# Native CPU oracle profile evidence

Input HEAD `e9edcf6` plus per-run dirty input SHA-256 in `inputs.json`. The containing commit includes the final diagnostic classes, scripts and reports. Production wiring is OFF.

This is native-oracle-only evidence, NOT production wall, spawn-bootstrap, feature-lock, T0, vegetation or reopen acceptance.

## Runs

- `../s1c-oracle-vanilla-20260930-a1485c7f/`: 512 columns / 196608 native states; correctness PASS, initialization max 16.3931ms, over40=0.
- `../s1c-oracle-tectonic-20260930-b7119571/`: same complete count; correctness PASS, initialization max 121.7527ms, over40=4, BLOCKED_PERFORMANCE.
- `../s1c-oracle-tectonic-c2me-20260930-60497bbb/`: same complete count; correctness PASS, initialization max 1851.2769ms, over40=4, BLOCKED_PERFORMANCE.

Each ordinary isolated DedicatedServer world uses seed 9282026 and actual dimension [-64,320). Four private noise seeds are 0,1,9282026,Long.MIN_VALUE. Do not describe them as four production-world traversals.

The first two runs predate per-seed CPU/GC instrumentation. The C2ME run includes it. That change only adds evidence, not a different oracle algorithm or a relaxed criterion. The final script's extra complete-count/root-input checks were added after these server runs; saved certificates were verified offline, not rewritten or mislabeled as reruns.

`abi/OracleProfileFixture.before-cpu-metrics.java.txt` is explicitly DERIVED: remove only the later observation additions from the final class, and accept reconstruction only when its raw SHA matches BOTH earlier inputs manifests. `source-reconstruction.json` records the two exact matches (`a8ca9118ab35a7e21410b91bc8b25781f931b795c47fc4646bb2fb3f2a82b485`). It is not described as a source snapshot captured earlier. The C2ME class matches the final source input directly.

`certificate-audit.json` records an independent offline readback of all original file hashes, complete states/wet/dry/seeds, unchanged red certification statuses and the produced jar's exclusion of smoke classes. No raw certificate was edited.

Normal `server-smoke-passed.txt` / Gradle exit 0 only attests startup/ticks/shutdown. An oracle failure is independently enforced by runner status; both third-party profiles are rejected despite normal shutdown.

## ABI

`abi/runtime-c2me-RandomState.class` is copied from this run's mixin export, not an untransformed vanilla file.

`abi/runtime-c2me-RandomState.txt` is local JDK21 javap -p -c against that exported directory. Constructor calls c2me-opts-dfc$postCreate before RETURN; its synchronous finalizeCompilation call is preserved. This is FACT-ABI, not an unmeasured production scheduler claim.

The complete original DFC/GC/safepoint logs are in the C2ME run folder. CPU timing is platform-quantized evidence about scale, not exact per-stage attribution.

## Compile / Unit

`compile-and-unit/inputs.json`, `execution.json`, `verification.json`, raw stdout/stderr and all 56 JUnit XML files:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test assemble compileServerSmokeJava --rerun-tasks --no-build-cache --console=plain --no-daemon
```

Exit 0, BUILD SUCCESSFUL in 1m 41s. 294 tests / 56 suites / 294 actual testcase nodes, failures=errors=skipped=0. All XML timestamps are newer than invocation start.

This command does NOT run `build` or `runGameTest`. Complete S1C build/GameTest, S0 100-round matrix, actual production walls/T0/reopen, 6000 dimension, vegetation, same-JVM lifecycle, per-chunk cost and S5/24h remain NOT_RUN. Tan's profile remains BLOCKED.

Detailed blocker and decision scope: `../../../S1C-阻塞-RandomState初始化.md`.
