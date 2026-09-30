# Interrupted S1B acceptance evidence

- Archived locally on 2026-09-30 14:10 Asia/Shanghai before any new build.
- Source checkpoint: `253a1d85b07978bbdab125899e3480a7a0b0cb3c`; working tree was clean.
- Command: JDK21, `gradlew.bat --offline --dependency-verification=strict build runGameTest --continue --rerun-tasks --no-build-cache --console=plain --no-daemon`.
- No final build marker or process exit code was recovered. Do not label the run PASS.
- All three sources stop at 13:40:17, CURVED_SITES index=60 PASS. Earlier curved-site exceptions remain in full.
- Copy verification: each archived file has the same SHA-256 as its source.

| Original source | Archived file | SHA-256 |
|---|---|---|
| build/s1b-acceptance-complete.log | s1b-acceptance-complete.log | 77E3B28B25D80E95A89B67C7E0A5049E2A1D2BA24D60AD1BA8756B2E91DCD463 |
| build/run/gameTest/logs/latest.log | latest.log | D5FEAFAE74712F4E2373F772AE7074630F37B7B90FF5CF618548B63574EDD252 |
| build/run/gameTest/logs/debug.log | debug.log | F28C75AEB7599AF223DEBA55B38BA528D5A7AFA6EE281B889B642C5C02E5CBD1 |

Process disappearance cause: not established. The last line is not a failure or a normal shutdown marker.
