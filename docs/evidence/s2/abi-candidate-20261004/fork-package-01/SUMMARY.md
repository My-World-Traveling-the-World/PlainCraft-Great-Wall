# RoadWeaver candidate artifact for ABI review

S2 specification line 356 requires protocol1 and a reviewed artifact SHA
allowlist before integration. This is an inspection candidate, not that
approval and not GW-011 or S2 acceptance.

## Build and provenance

The original fork at `2d4e1b4369f2d992ae3147643c315ce6b226a7e5`,
including the six retained preexisting dirty entries, ran
`:fabric:remapJar --offline --dependency-verification=strict --max-workers=1`.
The command exited 0; captured tracked production sources and build inputs
were unchanged. Exact source status, command, input hashes and log hash are
preserved in this directory.

Candidate artifact:

```text
D:/云游四海-Mod源码/roadweaver/fabric/build/libs/
roadweaver-fabric-2.3.1-1.21.1-plaincraft-direct-core.0.8.405-audit-io-degradation-and-counter-cleanup.jar
SHA256 6acfedb7781030f67cdd44835146cbe9bf88a5af3afc668acfcf4ccdda7aac55
```

The version label is unchanged; it does not identify the new source revision
alone. The source hash inventory and dirty provenance must accompany review.
No candidate was installed in mods or a real save. Dependencies, locks,
verification metadata and repository configuration were not edited.

## Actual class inspection

The runtime artifact and current GreatWall compile-only jar each contain
15 interop classes. Fourteen class files are byte-identical. Only
PlanningInteropRegistry differs; the per-entry hashes and complete public
descriptors are recorded, not inferred from source declarations.

The runtime jar contains `protocolVersion()I`, `hasProviders()Z`, the four
level/session methods, `registerStructureEndpointOnce(... )Z` and the
four-argument `ensureConnection(... )V`.
Level/position descriptors use intermediary `class_3218` / `class_2338`.
This supports the existing reflection-only boundary for native objects; it
does not authorize compile-time ServerLevel calls across jars.

`artifact-abi.json`, `public-abi.javap.txt` and
`compile-only-abi.javap.txt` preserve the artifact SHA, class hashes and
actual descriptors. No classes were loaded into a game to make this
inspection, and neither jar was changed.

## Still required

Reviewed allowlist resource: NOT_CREATED / NOT_APPROVED.
Actual adapter construction/calls with this exact artifact: NOT_RUN.
Loom development-remapped artifact identity and real runtime CodeSource:
NOT_RUN; do not assume the distributable SHA is the runtime CodeSource SHA.
GW-011 12-item matrix, durable gate/backfill/ACK and live two-way construction
remain unclosed. S1C production OFF and Tan's BLOCKED remain unchanged.
