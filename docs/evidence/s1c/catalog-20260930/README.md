# Frozen block catalog evidence

Date: 2026-09-30 (Asia/Shanghai).
Input sourceCommit: fdf3892 plus BlockCatalog, the three block tags, CatalogGameTests and its test entrypoint.
The containing commit identifies the exact tested input; no further production changes were made during this run.
Command: docs/S1C-1C-1.md section 2.
ExitCode: 0. Duration: 3m10s.
JUnit: 280 tests / 54 suites / 0 failures / 0 errors / 0 skipped.
GameTest: 13 required tests passed, selected CatalogGameTests, WriterGameTests, ProductionBarrierTests, ProductionPipelineTests.
This is a diagnostic subset, not full S1B or S1C acceptance.

| File | SHA-256 |
|---|---|
| gradle.log | AFDEE08812693AE3D0C4BCF1A361986977AAE12B8BF6CCBAE6C39D1D62C5EE3A |
| gametest.xml | 608DE09298D91C5B09FE9D3F5A807451531014B704E971E4D8E3CF45A03D8FFC |
| src/main/java/local/plaincraft/greatwall/terrain/BlockCatalog.java | C51C073CB0BAB08E0913DC903895309BCE8EAD195519024E3D2E26666FAB8088 |
| src/gametest/java/local/plaincraft/greatwall/gametest/CatalogGameTests.java | A5F54B6F3D37DFD2AE390183ADD2BCEA332879C8E95C74BA15097DC7E2D4C097 |

junit/ preserves the whole run's original XML files.
Real terrain adapters, production activation and slow combination matrices: NOT_RUN.
