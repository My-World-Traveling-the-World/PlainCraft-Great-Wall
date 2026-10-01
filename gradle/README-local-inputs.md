# Machine-local compile-only inputs

`libs/` holds jars that are **compile-only** soft dependencies. They are never
bundled into the release artifact and never enter the dependency lock state.

## `libs/roadweaver-interop-compile-only.jar`

Provides the S2 RoadWeaver interop ABI (`net.shiroha233.roadweaver.api.interop.*`)
that GreatWall's `compat/road` classes compile against.

Regenerate it from the RoadWeaver checkout after changing the interop SPI:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
Set-Location -LiteralPath 'D:\云游四海-Mod源码\roadweaver'
.\gradlew.bat --offline --dependency-verification=strict build -x test --no-daemon --console=plain
Copy-Item -LiteralPath (Get-ChildItem 'fabric\build\libs' -Filter 'roadweaver-fabric-*.jar' |
    Where-Object { $_.Name -notmatch 'sources' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName `
  -Destination 'D:\云游四海-Mod源码\plaincraft-great-wall\libs\roadweaver-interop-compile-only.jar' -Force
```

### Why reflection rather than direct calls

RoadWeaver ships **remapped** Minecraft names. A GreatWall source set using
Mojang mappings cannot reference `ServerLevel` through RoadWeaver's remapped
method signatures. Only the interop DTOs (which carry no Minecraft types) are
referenced at compile time; the server-level calls in `RoadWeaverAdapter` go
through reflection so the two mappings never have to agree.

### Absence behaviour

If `libs/roadweaver-interop-compile-only.jar` is missing, `build.gradle` simply
does not add it and the `compat/road` classes fail to compile. To build without
RoadWeaver integration, exclude the S2 interop classes from the source set.
