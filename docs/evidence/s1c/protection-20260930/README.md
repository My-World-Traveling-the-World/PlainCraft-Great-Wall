# Protection snapshot evidence

Date: 2026-09-30 (Asia/Shanghai).
Input sourceCommit: 2100903 plus ProtectionSnapshot, ProtectionSnapshotTest and TerrainSnapshotService.
Command: docs/S1C-1C-1.md section 3.
ExitCode: 0. Duration: 1m52s.
Full JUnit: 282 tests, 55 suites, 0 failures, 0 errors, 0 skipped (each result appears in gradle.log).
New JUnit XML is preserved separately. Diagnostic GameTest: ProductionBarrierTests, 6 required tests passed.
Dedicated cross-chunk reference cases and real terrain profiles remain NOT_RUN.

| File | SHA-256 |
|---|---|
| gradle.log | FDC56FDDC39957D312DC1E1A4779408605CADB92D27BE6863848BD2C9FC351F3 |
| gametest.xml | 6749A91070A5C9CCF0E42777ACAA9D6F61EF879AFA41BE75ED1A7D1D445BDC38 |
| protection-junit.xml | FA0612733D3E43C1AA8CC2382B7512ACD1827A51F005F18359D0C4537A7A3045 |
| src/main/java/local/plaincraft/greatwall/generation/ProtectionSnapshot.java | 2C4EA874E5E49D0EBD3298A24951DD5EE46318E26F72755161E51FF8D56C9374 |
| src/main/java/local/plaincraft/greatwall/terrain/TerrainSnapshotService.java | B5F0AEFB52667C2531417E9A243E8CE851672AA6351EE6FDC94F45AD97DD1B18 |
