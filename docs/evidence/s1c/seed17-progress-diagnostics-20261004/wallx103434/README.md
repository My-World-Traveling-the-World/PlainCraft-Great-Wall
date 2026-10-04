# Seed17 isolated real traversal: failed, not S1C acceptance

Source baseline b862978; seed17, vanilla, representativeBlocks=100.
The original 505-item scan and 39-minute fixture / 40-minute task budgets
were unchanged. The command was strictly offline with strict dependency
verification. Production OFF; Tan's Huge Trees BLOCKED.

Actual command exit 1, elapsed 2412.2892782 seconds. The last progress
snapshot is 32/505, voxels=24736, walkwayFeet=2331:
25 recorded OWNER rows and 7 recorded NON_OWNER rows. The next requested
traversal chunk is (12,62), ordinal 33. It is a pending chunk, not a
coordinate identified by a construction-contract exception.

The progress file's failure field is empty because it is the last periodic
checkpoint from 11:04:52, not a successful completion certificate.
At 11:14:04 the fixture threw its finite 39-minute budget exception.
At 11:14:05 stopping the server threw Exception chunk generation/loading,
caused by production pipeline closed. Gradle then reported the original
40-minute task timeout. No final real-wall certificate or STOPPED snapshot
was produced. These failures are retained; the run is not PASS and did not
advance beyond the historical voxels=24736 milestone.

The 7 directly recorded NON_OWNER coordinates are:
(15,57), (15,58), (15,59), (15,60), (15,61), (10,62), (11,62).
The separate planner-local diagnostic uses this run's own verified saved
recipe/plan: every requested-cell list is empty, as is the inverse capture
owner list over cells 0..35. That does not grant permission to revise the
user's NON_OWNER=0 acceptance criterion or remove scan items.

The STOPPING snapshot has flights=0, admitted=0, leases=0 and featureLeases=0.
Because stopping threw, no STOPPED snapshot exists, and prior lifecycle
counterexamples remain, S1C-DRAIN-01 is OPEN. Zero counters in this snapshot
are not a normal-shutdown, restart or same-JVM lifecycle certificate.

Snapshot counters and session counters are separate domains. The STOPPING
session has written-voxels=24736 and PRODUCTION_APPLY_EXIT=32; neither is
used to replace the progress file's 25 OWNER / 7 NON_OWNER classification.
APPLY max is 48.2681ms; it is not a CPU oracle 40ms steady-state result.
Successful-path per-chunk cost and 40ms/25ms certificates: NOT_RUN.

Two read-only jcmd thread samples show a RUNNABLE T0 IO lane in
SnapshotColumnView.protectedAt's full metadata structure-box scan, advancing
from resolve to inspect. thread-105805.txt was actually written at
10:57:20; its filename is not the sampling timestamp. thread-105808.txt
was written at 10:58:10. These are samples, not a complete profiler or
deadlock diagnosis. The side-line protection statistics and proposed
column prefilter have not changed production source or protection rules.

342 captured source/config/tool inputs were unchanged. execution.json's
run.log SHA256 matches the actual file:
935f6ac24a05dc282c85649724b018447264bbf27c5421096b76491385c460e8.
The progress, STOPPING diagnostics, manifests, crash report and server log
were copied byte-for-byte. Original world/T0/manifest remain at the
isolated build/run/serverSmoke/vanilla/wallx103434 path; not migrated,
backfilled, removed or overwritten. events-STOPPING retains the original
bounded trace, whose existing truncation counters preclude a complete
event-history claim.
