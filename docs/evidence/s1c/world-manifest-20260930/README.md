# World manifest protocol evidence

Input sourceCommit `9d007cb` plus the two classes below, as recorded by the containing commit. No production lifecycle is connected yet.

`attempt-1.log`: exit 1, missing static import of requireHash, no new JUnit XML and no test PASS. Original compile failure preserved.

`after.log` / `after.xml`: exit 0, 12/12 targeted JUnit, 0 failure/error/skip. UTC start `2026-09-30T12:25:01.1551401Z`, finish `2026-09-30T12:25:21.7262338Z`; 20s. XML copied only if newer than command start.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test --tests 'local.plaincraft.greatwall.persistence.WorldManifestTest' --rerun-tasks --no-build-cache --console=plain --no-daemon
```

## Protocol details

- Constructor receives the final production `manifest/` directory, never an S0 directory.
- Schema 1 JSON envelope: schemaVersion, type, payload, sha256, crc32c.
- Payload contains all Creation/WorldRecipe fields, including instance/wallId; those identity fields are NOT accidentally omitted merely because CanonicalCodec.recipe intentionally omits them from geometry semantics.
- configHash is SHA-256 of schema 1 and all GenerationConfig fields in declared order, eight big-endian int fields followed by three long fields.
- Creation must precede world freeze, seed/config/instance must match, wallId and recipeHash must equal the existing canonical semantic recipe hash.
- Same frozen record is idempotent without rewriting. Different frozen identity/geometry is rejected.
- Missing creation while world.json exists is explicitly corrupt permanent evidence, not permission to mint another UUID.
- SHA-256/CRC32C/schema errors propagate; existing files are not reset. IO reads at most 65537 bytes and rejects oversized files.
- File force(true) then mandatory atomic move; no non-atomic fallback. tryLock rejects overlapping writers. S0 root and symbolic-link escape rejected.

## Coverage and boundary

12 actual tests: old unmarked rejection, own creation crash continuation, 3-int coordinates including y=6000, immutable recipe/identity, changed seed/config, corrupt creation, corrupt world, missing creation, unsupported schema/CRC, bounded file read, overlapping file lease, S0 root denial.

These are temporary-directory IO fixtures, NOT evidence of native startup ordering, recipe-before-FEATURES, server close/drain, real 6000 generation or production replay. All those remain NOT_RUN. Full JUnit after this class, final build and GameTest were not run in this batch; previous 282 JUnit evidence belongs to the preceding source. Production stays closed.

## SHA-256

```text
src/main/java/local/plaincraft/greatwall/persistence/WorldManifest.java 580f252d1ae9760663ac1066423927bff28f5fb477b7a2259d675f6adebcc4fa
src/test/java/local/plaincraft/greatwall/persistence/WorldManifestTest.java 5e5f64f905b658d9d3f0d4fe1157b792f175c594a3a7cf3ffcd0c063411f7126
attempt-1.log 059d5232d23c35baec40a87c7d820366e85496e5c4bfb5af960a4d2579ddd3ca
after.log fed3c13e7026b9464654bf7be9a9e9a24f118b58adbcb87b3395158a0ef538c4
after.xml 1226884a108f15fc60e9f26b9ba9cf5eca17e5f619798e1309ae9adeb298ebb5
```
