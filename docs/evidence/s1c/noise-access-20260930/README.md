# Noise access bridge ABI and load evidence

Date: 2026-09-30 (Asia/Shanghai).
Input sourceCommit: 49e824b plus NoiseCursorBridge, NoiseInterpolatorAccess, NoiseGeneratorAccess and common mixin registration.
Command: docs/S1C-1C-1.md section 5.
ExitCode 0; duration 33s; SkeletonGameTests 1/1.
This checks compilation and a normal test-server load only.
Private slice behavior, actual filler invocation and C2ME transformations: NOT_RUN.

named-abi.txt was generated using C:\Program Files\Java\jdk-21\bin\javap.exe -p against the already cached 1.21.1 official-mapped merged JAR.
JAR SHA-256: 3E65C0EABBFC166B454301481945FA295F422B4B889C077BA2CCE8C20C01B04A.

| File | SHA-256 |
|---|---|
| gradle.log | AB224C5706C14EFC8E9EE8AAB9566EDE084EDDD9D1AE6E4642ADF59F314C9A93 |
| gametest.xml | 361204358800021386934E2BFE0B473CAC4DD1A1B10521DC5D641FC87F31BF05 |
| named-abi.txt | 76D8D12A50E3728177531E5E3E9970C793EEDC377D1E8727F23D519223A8DE42 |
