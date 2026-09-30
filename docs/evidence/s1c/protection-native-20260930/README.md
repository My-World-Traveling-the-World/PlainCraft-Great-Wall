# Native protection and contextual ice red / green

Date: 2026-09-30 (Asia/Shanghai).
Input sourceCommit: 781d850 plus CatalogGameTests, ProtectionGameTests and its registration.
BEFORE producer: BlockCatalog at 781d850. AFTER producer: one contextual ice expression restored.
All six test bodies and assertions were unchanged between the real red and green runs.
Commands and exact limits: docs/S1C-1C-1.md section 4.

compile-attempt.log: exit 1 before runGameTest, test Coverage arguments had the wrong order.
stale-gametest-from-prior-run.NOT_EVIDENCE.xml: mistakenly copied prior-run XML. NOT a result for that command.
before.log / before.xml: exit 1, 6 test cases, 1 failure in naturalThinIceAuthorizationStaysInTheCapturedColumn.
after.log / after.xml: exit 0, same 6 cases, 0 failures / 0 errors / 0 skips; duration 34s.
Full JUnit, real noise oracles and real-world wall profiles: NOT_RUN in this batch.

| File | SHA-256 |
|---|---|
| compile-attempt.log | FBA6FB29500C25731E1829CD9C6936279A5E47076078163F0ABE6EC1962A7554 |
| before.log | 5057A9E3077E9E1E176F18F8E947BB4BD6BA97BFC37280EF0DE09A6D2B3343CF |
| before.xml | F007095AEDCC57BCC947E208B9F8F3C17A4C6E159592ED814F2DDC654E5B9837 |
| after.log | AFC1236EFD07DE72C96E1D329F007BDCBCD532DB232EC3AB21E5A65395AD1926 |
| after.xml | A6AA5318E193B510694059C64F9D35F0008DD0F85F6F34DE74533EBE32B7188A |
| src/main/java/local/plaincraft/greatwall/terrain/BlockCatalog.java | 2DCDB23300241945FE70F6339DA7046A6AFCBD9F64554D924BFC918B157792D3 |
