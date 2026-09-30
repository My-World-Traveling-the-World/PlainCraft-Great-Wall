# Startup handoff red / green evidence

Date: 2026-09-30 (Asia/Shanghai).
Input sourceCommit: d0236ba; working source changes are limited to PlannerScheduler and PlannerSchedulerTest.
The containing Git commit binds the final source and evidence. No production worldgen was enabled.

BEFORE: original production source, new zero-tick counterexample, exitCode 1.
AFTER: startup handoff fix plus final handoff assertion, complete PlannerSchedulerTest, exitCode 0.
The zero-tick request assertion and ten-second request timeout are unchanged.

| File | SHA-256 |
|---|---|
| before.log | EC6A54804F9C2A8B622D39F797F166E759518DC44F47CA393B3C785BA30C6F88 |
| before.xml | 67D4E2BD4BE8F8CEDCB1C717D070C7BE81F67D11D37924B1A9BAD9AA3B324160 |
| after.log | 4C64173EC684B3EF2C018BA11C226731625125288377C3C9A9FEC82C62708921 |
| after.xml | CE98CAE392DE33F2E8291EAF2BF195AACE162D52A40C571FBF4565551F7C50CC |
| src/main/java/local/plaincraft/greatwall/planning/PlannerScheduler.java | AF51507134D090022D73698B83C0D8D18A8C98B9BB458C9F1556CE4677D48866 |
| src/test/java/local/plaincraft/greatwall/planning/PlannerSchedulerTest.java | B84ED5F3E5A46781A85622B8D6448DD5FA1754BB844742ACB6807157E3630AEC |

Commands and scope limits: docs/S1C-1C-1.md section 1.2.
Results: AFTER 12 tests, 0 failures, 0 errors, 0 skipped.
Real CPU terrain, C2ME production wiring, lifecycle resets and vegetation: NOT_RUN.
