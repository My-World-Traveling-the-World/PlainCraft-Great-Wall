# Private noise slice bridge evidence

Input sourceCommit: `c298752` plus the exact dirty inputs below. The containing commit records those inputs. No source changed during the successful parity run. Production remains closed.

## Attempts, In Order

| Raw log | Result | Interpretation |
|---|---|---|
| `compile-attempt.log` | exit 1, Java compilation | Native inner-class metadata makes BeardifierMarker protected. No GameTest ran. |
| `lock-failure.log` | exit 1, dependency locking | One exact access widener changes two Loom-generated module names; no version upgrade. |
| `generated-lock-update.log` | exit 1, configuration | Automatic metadata writer requests uncached Linux/macOS natives. Offline remained enabled. |
| `targeted-lock-update.log` | exit 1, configuration | Rebuild exposes missing cached intermediary 1.21.1 checksums. No checksum mismatch for those inputs. |
| `targeted-lock-update-2.log` | exit 1, configuration | Loom regenerates a timestamped mappings ZIP; payload verification performed below. |
| `targeted-lock-update-3.log` | exit 0, 18s | All-configuration lock generator output preserved, then mechanically narrowed to two module renames. Compilation only. |
| `parity-attempt-1.log` | exit 0, 37s | Strict offline, no lock-update flags, 2/2 GameTests, no failure/error/skip. |

Successful run UTC: `2026-09-30T11:42:31.4410651Z` through `2026-09-30T11:43:08.8487357Z`. `parity-attempt-1.xml` was copied only after its LastWriteTimeUtc was checked against run start.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict runGameTest '-PgreatwallGameTestClasses=NoiseBridgeGameTests' --rerun-tasks --no-build-cache --console=plain --no-daemon
```

Native test: 4 seeds / 128 columns per seed / 196608 states, minY=-64, maxYExclusive=320. Independent native and sliced digests:
`c86a7f534f6e886b65fbe8dd1a09f9f3dc5a1a28236a0283377e93e39113f525`.

Measured maxima: constructor 12217400ns; cell selection 106800ns; single slice point 31500ns. These are observed vanilla test samples, not universal bounds or real-world generation cost. Foreign-lane access, reentry and invalid limits are tested separately. No new full JUnit run, no final build, no Tectonic/C2ME/6000 certification, no production generation.

## Offline Build Input Review

Access widener contains only one accessible-class entry for `DensityFunctions$BeardifierMarker`. Original/new common JAR entry comparison changes only:

```text
net/minecraft/world/level/levelgen/DensityFunctions.class
net/minecraft/world/level/levelgen/DensityFunctions$BeardifierMarker.class
```

Client JAR stays byte-identical. The final lock differs from its backup only by `minecraft-clientOnly/common-6c431f37c1` becoming `minecraft-clientOnly/common-faec5fe59d`, with identical versions and configuration membership. The wider generated lock in this directory is historical evidence, NOT the active lock.

Mappings cache rebuild:
- Old trusted ZIP checksum retained: `f47e6927f8c30d2be6db9d8e2ced9bef4bf92fa7343e98c1e511e49d7c025379`.
- New ZIP checksum added: `e5cbeb179ff6c81f800914f0c137a680052bf9662d3be82ca750e9727ad1a2cd`.
- Rebuilt ZIP has only `mappings/` and `mappings/mappings.tiny`, timestamped September 30.
- SHA-256 of extracted mapping payload equals the retained September 19 `mappings-base.tiny`: `e2ccb07abfc7f4a045c638fa746f36be44240fcca3346974c9a0894037342fe7`.
- No semantic mapping difference was found; the complete unmodified mapping source is not redistributed here.
- After checking the exact global lock contained only `disowned`, with no active test/build process, copied its bytes to `stale-loom-lock.disowned` and removed that one stale file. No cache directory was deleted.

Added checksums for existing intermediary v2 JAR/POM, and the four project-generated JAR/POM files. This is explicitly reviewed local-cache provenance, not an upstream signature claim. Existing checksum records, verify-metadata=true, dependency locking and strict verification remain enabled. No network or proxy was used.

## Exact Input SHA-256

```text
src/main/java/local/plaincraft/greatwall/mixin/NoiseSliceMixin.java d48b0b22bba46ea6e6a1740d2df4dea5df2073162faf7c4b032bd3fff6043e3b
src/gametest/java/local/plaincraft/greatwall/gametest/NoiseBridgeGameTests.java 94c50dbd03493a8f862ed2c6b71fb04f8f60e5a9d3f3df20eb176557c5cd87fe
src/main/resources/plaincraft_great_wall.accesswidener 6d663516b1222370603f3409efee24554c03e6ad7813a38577b16ac7b27c3049
build.gradle d3ff82265e970a5c62e46105d27d831a9bc6303e564dc6c0dbd4232657b0b44c
gradle.lockfile 1637d34b53f08a759ac63b4d45240902c9fa0767ee67a977c57be21f40e62d0e
gradle/verification-metadata.xml 2932b799922cf1302f8d4a7cf63b18a46541a8bd90e4c23d9f46e273560a7dd2
src/main/resources/fabric.mod.json f9e13578fb9cf30f508ac94aadb77a08c8f3ef9e4c30a140f0a3a0c03cfb7bde
src/main/resources/plaincraft_great_wall.mixins.json d0f0568971ae170070ab6e401a85f58e12ab587691ca6030e6368653e234e01b
src/gametest/resources/fabric.mod.json 52c6a27bcc91987189f2d56081483c809c748dac6e78a0b0fd9526efc005d599
```

## Evidence SHA-256

```text
before-gradle.lockfile 367ba3559edbec89d075630c6ce9a2b881620de0028ef2b43366fb58640651f4
before-verification-metadata.xml c4e281095c96f26a421fc7d676a6d218f20f9739b40978b061863707f7edcee5
compile-attempt.log 0a3debe972f98c2a4ce57131dd93185565c136511fa77de39aa0bf52350e45db
generated-lock-update.log 8be499aa59c5d5cfcf988f7d1c1eb3139e4e43e74df9fad0b5d9ea8fbc2bb433
gradle-generated-all-configurations.lockfile bcb27f452a01992860c412da0cabd4bdf6baf8a17e58de045a0418c2b436c262
lock-failure.log e290cf82a75d25f9dc6d82c5fd8270f1b6c1c096277b5f1b5e9ecbffd261f3d2
parity-attempt-1.log 12f378e420be63697a39eeebdbefc0008bb5044935d253390625b547ba087dc8
parity-attempt-1.xml a7c3b35c2305a88c732a1ad9f57a15e46a5de8b01234bec1dd49aa4fc1787f7d
stale-loom-lock.disowned d8aed88c117edfde7603d4748a3ceb689638b7d32363710e75db2c9f46b2b381
targeted-lock-update-2.log a18eda51948ca69b83926d2bb9d611cf7ee96325c164306934e97358847bce9f
targeted-lock-update-3.log 6dcae9d672cc0d77e1bbd0a58f73cafb7dc7fc175114f16e3b629994ecb88f7c
targeted-lock-update.log ad3bebd818b9f71353d6cbf7a292b1369a93d6be07f93171e76448b0609f1cad
```
