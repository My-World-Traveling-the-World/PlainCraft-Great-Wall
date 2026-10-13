# Private noise slice bridge evidence — 2026-10-13 parity restore

Input sourceCommit: `79dbbaa2` plus the exact dirty inputs below. The containing commit records those
inputs (the `NoiseSliceMixin` Z-bound fix plus the audit batch 1/2 changes). No source changed during
the successful parity run. Production remains closed.

## What This Run Proves

The 2026-09-30 baseline recorded `c86a7f534f6e886b65fbe8dd1a09f9f3dc5a1a28236a0283377e93e39113f525`
as the digest of BOTH the native and the sliced walk.

`68c7a0a7` (2026-10-09, `fix(startup): bound a column walk and the root-search log`) then narrowed the
Z bound in `greatwall$nextZ()` from `<=` to `<` to stop a lane spinning at 100 % CPU for minutes while
writing a 134 MB log. The narrowing is wrong: the slice arrays hold `cellCountXZ + 1` Z boundary rows
(mirroring the `cellCountY + 1` Y values the same walk fills, see `NoiseSliceMixin.java` lines 61 and
83), and vanilla `selectCellYZ` reads row `cellZ` **and** row `cellZ + 1`. Stopping at row
`cellCountXZ - 1` left the final row all zeroes, so every column whose Z fraction was non-zero
interpolated towards zero density and disagreed with the native terrain. The Z walk is now bounded at
`<= cellCountXZ`, which is the bound the arrays actually need; the runaway bound that `68c7a0a7`
wanted is kept for the column walk and the root-search log, where it belongs.

Guard: `NoiseBridgeGameTests.slicedFullHeightMatchesNativeStatesForFourSeedsAnd128Columns` — it must
stay green and must keep printing `sha256=c86a7f534f6e886b…`.

Pre-fix observation (failing run XML as reported by the external audit and re-read here before the
fix; that XML is not redistributed in this directory):

```text
(12669265, -60, -4594872) sliced state mismatch seed=0 column=6 xyz=16,62,-17 native=Block{minecraft:stone} sliced=Block{minecraft:water}[level=0]
```

## Attempts, In Order

| Raw log | Result | Interpretation |
|---|---|---|
| `parity-attempt-1.log` | exit 0, 36s | Strict offline, no lock-update flags, 2/2 GameTests, 196608 states per walk, digest equals the 2026-09-30 baseline. No failure, error or skip. |

Successful run UTC: `2026-10-13T12:51:23.2680259Z` through `2026-10-13T12:51:59Z`.
`parity-attempt-1.xml` was copied only after its LastWriteTimeUtc (`2026-10-13T12:51:59Z`) was checked
against the run start. It contains two `<testcase>` elements and no `<failure>` element; the same run
with the pre-fix input contained one `<failure>` for the sliced testcase.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict runGameTest '-PgreatwallGameTestClasses=NoiseBridgeGameTests' --rerun-tasks --no-build-cache --console=plain --no-daemon
```

Native test: 4 seeds / 128 columns per seed / 196608 states, minY=-64, maxYExclusive=320. Independent
native and sliced digests are equal again:

```text
S1C_NOISE_PARITY seeds=4 columns=512 blocks=196608 minY=-64 maxYExclusive=320 sha256=c86a7f534f6e886b65fbe8dd1a09f9f3dc5a1a28236a0283377e93e39113f525 maxConstructNanos=4972700 maxCellNanos=122100 maxSlicePointNanos=30800 scope=native-noise-only-not-worldgen-acceptance
```

Measured maxima are observed vanilla test samples from this run, not universal bounds and not
real-world generation cost. Foreign-lane access, reentry and invalid limits are still tested
separately. No full JUnit run in this directory, no final build, no Tectonic/C2ME/6000 certification,
no production generation.

## Exact Input SHA-256

```text
src/main/java/local/plaincraft/greatwall/mixin/NoiseSliceMixin.java 97570093df7b51b5747d45d17e59c021ccd1046e24814eea85fa27e9005f051a
src/gametest/java/local/plaincraft/greatwall/gametest/NoiseBridgeGameTests.java 032a3c3fe106620112bf695c16ecbc5c8fe28007cba502b5725d7ef844f50cb4
src/main/resources/plaincraft_great_wall.accesswidener 6d663516b1222370603f3409efee24554c03e6ad7813a38577b16ac7b27c3049
build.gradle d9e545efbb1bb0ad767493038b96a04622bb0a9cdc4d28645c06e278ad5cf66c
gradle.lockfile 1637d34b53f08a759ac63b4d45240902c9fa0767ee67a977c57be21f40e62d0e
gradle/verification-metadata.xml a6dff12ae4548b12d4c9fc3bf0d4c981af0f661c508688c6e1eb546da9d803bc
src/main/resources/fabric.mod.json 3b322ac358ce5850bac20f98c1b6ee75218df0e72152a643c257e5ebf3cbdaa2
src/main/resources/plaincraft_great_wall.mixins.json e59b9e14fc25c02b9354ab651d9e8a64504244b414030c523238fb69e0de51e1
src/gametest/resources/fabric.mod.json 5f5ef13fbd3ccf060e36e2626cd4c820dbb1f94d0b148955d27f9a401613b2d7
```

Every difference against the 2026-09-30 input list is accounted for by a recorded commit; the file was
not edited during this run.

- `NoiseSliceMixin.java`: differs from `d48b0b22bba46ea6e6a1740d2df4dea5df2073162faf7c4b032bd3fff6043e3b`
  by the Z-bound fix and its comment. Last commit `68c7a0a7` (2026-10-09) introduced the defect; this
  directory's containing commit restores the bound.
- `NoiseBridgeGameTests.java`: the 2026-09-30 digest `94c50dbd03493a8f862ed2c6b71fb04f8f60e5a9d3f3df20eb176557c5cd87fe`
  was taken while the file was still dirty, 32 minutes before it was committed as `9d007cb1`
  (2026-09-30T12:15:56Z). That commit changed 3 lines and removed 2: it wrapped the foreign-lane body
  in `helper.runAfterDelay(1, () -> …)`. The digest-printing line and the mismatch message are
  unchanged since the baseline.
- `build.gradle` and `gradle/verification-metadata.xml`: last changed by `3263e683` (2026-10-13).
- `fabric.mod.json` and `plaincraft_great_wall.mixins.json`: last changed by `3f1a307e` (2026-10-08).
- `gradle.lockfile` and `plaincraft_great_wall.accesswidener`: byte-identical to the 2026-09-30 inputs.

## Evidence SHA-256

```text
parity-attempt-1.log 1b27474a5a4ffa39bfa2f77c7256bfeed0b6e315d264469d2a64bf871ad7105f
parity-attempt-1.xml ac8d5dab6ef9cdfd25ea3c0ede631c29d8af67fdf1ee5b1698062bbc70c97089
```
