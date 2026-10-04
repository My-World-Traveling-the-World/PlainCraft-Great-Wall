# Original-fork surface XZ async regression

This evidence concerns the original RoadWeaver workspace, commit
`2d4e1b4369f2d992ae3147643c315ce6b226a7e5`. It is not S2 acceptance.
GreatWall production remains OFF; Tan's Huge Trees remains BLOCKED.

## Executed regression

The strict offline `:common:test` command exited 0. Its final Gradle Test task
is **NO-SOURCE**; the actual checks ran through the prerequisite JavaExec
regression runners. The four JUnit runner tasks were executed, not UP-TO-DATE:

| Runner | Recorded result | Evidence limitation |
| --- | --- | --- |
| externalPlanningRequestRegression | 9 successful, 0 failed/skipped/aborted | Full console summary |
| roadAsyncRegression | 44 successful, 0 failed/skipped/aborted | Full console summary |
| roadJUnitRegressionSuite | PASS tests=115 | Runner prints successful count, not full discovery/skip summary or XML |
| wallInteropRegression | 9 successful, 0 failed/skipped/aborted | Full console summary |

The recorded successful count is 177. It is not a claim that `:common:test`
produced 177 XML test cases. The separate migration runner recorded 3348
source/contract checks; these are not JUnit cases.

`SavedEndpoint528SimulationTest: PASS (skipped)` is preserved verbatim in the
raw log. It is not evidence of a real saved-world replay and is not counted
among the four JUnit runners above.

## Scope and preservation

S2 specification lines 376-380 require async planning, explicitly scoped
continuations, swept-edge integration and postprocessed path validation.
The surface-XZ correction is a bounded regression of that async path, not
completion of the entire specification.

`original-dirty-and-locks-before.json` and `after.json` preserve matching
fingerprints of the six preexisting dirty files and the captured build inputs.
The raw log SHA256 must match `integrated-execution.json`.
Dependency versions, verification metadata, lock files and repositories were
not changed by this correction. No claim is made that the preexisting user
changes are part of the reviewed correction.

## Remaining

GW-011 complete 12-item matrix, both-way live construction, durable gate
profile/BEGIN, loaded-owner arch before-state/tombstone/ACK, reviewed adapter
ABI allowlist, and full lifecycle cleanup/drain remain unclosed.
Real saved-world replay is NOT_RUN. This fixture regression cannot close S1C,
S2 or S1C-DRAIN-01.
