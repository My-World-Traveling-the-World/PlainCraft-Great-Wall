# CPU oracle evidence

Input sourceCommit `90a057a` plus the source inputs below; the containing commit records them. Production remains closed.

## Actual runs

| Evidence | Result | Scope |
|---|---|---|
| `attempt-1.log`, `attempt-1-junit/` | Gradle exit 0, JUnit 282/282, 55 suites | Initial oracle, before upper-water fix |
| `attempt-1-gametest.xml` | Only four persisted cases although native log reports five passed | Incomplete GameTest evidence, not full five-case acceptance |
| `upper-water-before.log/xml` | Gradle exit 1, four persisted cases, one failure | Upper-water counterexample is genuinely red; other three pass |
| `after.log`, `after-junit/`, `after-gametest.xml` | Gradle exit 0, JUnit 282/282, 55 suites, six GameTests persisted and passed | Current source, no failures/errors/skips |

AFTER UTC `2026-09-30T12:07:20.7385099Z` to `2026-09-30T12:09:16.5579886Z`, duration 1m55s. Reports were copied only when newer than this command's start.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test runGameTest '-PgreatwallGameTestClasses=OracleGameTests,NoiseBridgeGameTests' --rerun-tasks --no-build-cache --console=plain --no-daemon
```

The runner additionally checked that six distinct XML testcases were present; a successful Gradle exit with an incomplete XML would have been marked evidence failure, not PASS.

## Native callback counterexample

`native-gametest-tick-abi.txt` is actual JDK21 javap output. `GameTestInfo.tick` returns immediately when isDone is already true; report listeners are invoked only after tickInternal changes completion. Calling succeed from MinecraftServer.execute outside the test tick therefore can make the native tracker see completion while omitting the XML testcase.

Both async tests now schedule their unchanged conclusion in helper.runAfterDelay(1) on server owner. This fixes test lifecycle, not production or assertions. Original incomplete XML remains untouched. BEFORE already records all four Oracle tests after this harness fix.

## Upper-water five-part record

1. Old implementation censored only a topmost solid, not a topmost water state.
2. Synthetic native settings retain full native height and fluid-picker machinery, with density y-gradient 1 to -1 over y=0..16, sea level at the dimension upper bound, aquifers disabled. The test first asserts the actual waterTop equals that upper bound, then rejects claiming a proven exposed atmospheric surface. Original implementation fails that assertion.
3. One production branch now marks topmost water censored.
4. Same reference algorithm, full aquifer range, budget, support rules, station/window contracts and owner permissions; no getBaseColumn in production. Counterexample assertion remains unchanged. Independent reference reducer also includes top-bound water.
5. AFTER identical counterexample passes; an opaque roof above water is separately tested and must yield no exposed water. Synthetic corner cases do not certify a real Tectonic/6000 profile.

## Actual coverage

- Reference: 4 seeds x 128 columns, independent native-column reduction and independent native support states; 248 wet and 264 dry.
- Noise bridge: all 196608 states, digest `c86a7f534f6e886b65fbe8dd1a09f9f3dc5a1a28236a0283377e93e39113f525`.
- COARSE: 8 boundary columns, aligned 4-block coordinates, native initial density >0.390625, vertical cell-height steps; no REFERENCE/water claim.
- Owner/lifecycle: lane factory works without live-world access; foreign thread/cursor, incomplete finish, invalid limit, overlapping active column, int-minimum bounds and closed cursor are rejected.
- AFTER observed oracle maxima: RandomState 8336000ns; NoiseChunk 6261500ns; cell 2114500ns; slice point 187300ns; block 280800ns; biome 112700ns; over-40ms primitive count 0.
- These are native vanilla-noise diagnostics in an isolated GameTest world, not actual production-wall generation or universal performance bounds.

Tectonic, C2ME-transformed oracle, actual 6000 profile, bootstrap authorization, full real-terrain generation/reopen, vegetation, S5/24h and manual player walking: NOT_RUN. Tan's profile stays BLOCKED.

## Source / key evidence SHA-256

```text
src/main/java/local/plaincraft/greatwall/terrain/CpuTerrainOracle.java d4aa9d78a06a959b033a2e085fb757efe5c9e456b7af8222761ffeaa95283d77
src/gametest/java/local/plaincraft/greatwall/gametest/OracleGameTests.java fc1f0b1be2c61f14ce123c4eba3744a24576795f3a3676d1b1cacce34ceeebf8
src/gametest/java/local/plaincraft/greatwall/gametest/NoiseBridgeGameTests.java 032a3c3fe106620112bf695c16ecbc5c8fe28007cba502b5725d7ef844f50cb4
src/gametest/resources/fabric.mod.json a00e31c6483e1f9070ebe11e544be01580b9a476b5a41392813596ca4421e491
after.log 6ca2ee54cbeb84cdd70d9135ae3a8684f104aa35da9330e89ec0ed41ac9bc1ea
after-gametest.xml 9150d85c0acc5249bd36102922fea655e5c2359377c1652b1b80d509bc95ef11
upper-water-before.log 7908049dae579c090601b087dd0da226f18aad8a18f9f5f04773bb0206e0ae7a
upper-water-before.xml 58baf5445e5efd76d075e709b3b1fcc190bd4d75d47dd2fcd7a7cd92f5bb4cf7
```
