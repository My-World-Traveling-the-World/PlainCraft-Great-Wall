package local.plaincraft.greatwall.smoke;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.persistence.WorldManifest;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.terrain.TerrainTile;

/** Offline read-only audit of a marked fixture's original T0; no registry, game or live chunk reads. */
public final class ProtectionEvidenceAudit {
    private ProtectionEvidenceAudit(){}
    public static void main(String[] args)throws Exception {
        if(args.length!=6)throw new IllegalArgumentException("fixtureDirectory minX minZ maxXExclusive maxZExclusive walkY");
        Path allowed=Path.of("build/run/serverSmoke").toAbsolutePath().normalize().toRealPath();
        Path run=Path.of(args[0]).toAbsolutePath().normalize().toRealPath();
        if(!run.startsWith(allowed)||!Files.isRegularFile(run.resolve("greatwall_test_world"))
                ||!Files.isRegularFile(run.resolve("greatwall_s1c_fixture")))
            throw new IllegalArgumentException("only this project's marked isolated S1C fixture is auditable");
        int x0=Integer.parseInt(args[1]),z0=Integer.parseInt(args[2]),x1=Integer.parseInt(args[3]),z1=Integer.parseInt(args[4]);
        int w=Integer.parseInt(args[5]);
        if(x1<=x0||z1<=z0||Math.multiplyExact((long)x1-x0,(long)z1-z0)>65536)
            throw new IllegalArgumentException("finite audit footprint required");
        Path wall=run.resolve("world/greatwall").toRealPath();
        if(!wall.startsWith(run))throw new IllegalArgumentException("fixture evidence escapes its marked run directory");
        try(var paths=Files.walk(wall)){
            var all=paths.limit(5001).toList();
            if(all.size()>5000)throw new IllegalArgumentException("finite evidence tree required");
            for(var path:all)if(!path.toRealPath().startsWith(wall))
                throw new IllegalArgumentException("fixture evidence contains a path escaping its root");
        }
        var recipe=new WorldManifest(wall.resolve("manifest")).read().orElseThrow();
        var plan=new SegmentCacheStore(wall.resolve("cache"),recipe)
                .readPlan(new SegmentKey(recipe.recipeHash(),recipe.dimension(),0)).orElseThrow();
        var fort=plan.sites().stream().filter(s->s.id().kind()==SiteKind.FORT&&s.id().ordinal()==0).findFirst().orElseThrow();
        if(fort.frame().walkY()!=w)throw new IllegalArgumentException("audit height differs from cached root contract");
        int lo=Math.max(w-35,recipe.bounds().minY()),hi=Math.min(w+6,recipe.bounds().maxYExclusive()-1);
        var store=ChunkEvidenceStore.production(wall.resolve("evidence"));
        Path dimension=wall.resolve("evidence").resolve(DeterministicHash.sha256(recipe.dimension().getBytes(StandardCharsets.UTF_8)));
        List<Path> owners;
        try(var stream=Files.list(dimension)){
            owners=stream.filter(p->p.getFileName().toString().matches("-?\\d+_-?\\d+")).limit(257).toList();
        }
        if(owners.size()>256)throw new IllegalArgumentException("audit metadata bound exceeded");
        var metadata=new HashMap<ChunkKey,CaptureMetadata>();var boxes=new ArrayList<Bounds>();
        for(var path:owners){
            var parts=path.getFileName().toString().split("_");
            var key=new ChunkKey(recipe.dimension(),Integer.parseInt(parts[0]),Integer.parseInt(parts[1]));
            var value=store.readCoverage(key);
            if(value.isPresent()){metadata.put(key,value.get());boxes.addAll(value.get().protectedStructures());}
        }
        var tokens=new TreeMap<String,Long>();var boxCounts=new TreeMap<String,Long>();
        var portalTokens=new TreeMap<String,Long>();
        int portalColumns=0,portalProtected=0,portalHighMin=Integer.MAX_VALUE,portalHighMax=Integer.MIN_VALUE;
        var first=new ArrayList<Map<String,Object>>();int protectedColumns=0,cleanColumns=0;
        for(int cz=Math.floorDiv(z0,16);cz<=Math.floorDiv(z1-1,16);cz++)for(int cx=Math.floorDiv(x0,16);cx<=Math.floorDiv(x1-1,16);cx++){
            var key=new ChunkKey(recipe.dimension(),cx,cz);
            var m=Objects.requireNonNull(metadata.get(key),"captured owner missing: "+key);
            var pages=new HashMap<Integer,TerrainTile>();
            for(int base:m.coverage().tileBases()){
                var tileKey=new TerrainTile.Key(recipe.dimension(),cx,cz,Math.max(base,recipe.bounds().minY()),
                        Math.min(base+127,recipe.bounds().maxYExclusive()-1));
                pages.put(base,store.read(tileKey,recipe.recipeHash()).orElseThrow());
            }
            for(int z=Math.max(z0,cz*16);z<Math.min(z1,cz*16+16);z++)for(int x=Math.max(x0,cx*16);x<Math.min(x1,cx*16+16);x++){
                var local=Rasterizer.localCell(fort.frame(),new Point3i(x,w,z));
                boolean portal=local.x()>=-2&&local.x()<2&&local.z()>=-24&&local.z()<24;
                if(portal){
                    portalColumns++;
                    final int atX=x,atZ=z;
                    var column=m.columns().stream().filter(c->c.x()==atX&&c.z()==atZ).findFirst().orElseThrow();
                    portalHighMin=Math.min(portalHighMin,column.highestNonPlantFeet());
                    portalHighMax=Math.max(portalHighMax,column.highestNonPlantFeet());
                }
                boolean found=false;
                for(int y=hi;y>=lo;y--){
                    var pos=new Point3i(x,y,z);
                    var page=Objects.requireNonNull(pages.get(Math.floorDiv(y,128)*128),"missing durable T0 page");
                    String token=page.state(Math.floorMod(x,16),y,Math.floorMod(z,16));
                    Bounds box=null;
                    for(var b:boxes)if(x>=b.minX()&&x<b.maxXExclusive()&&z>=b.minZ()&&z<b.maxZExclusive()
                            &&y>=b.minY()&&y<b.maxYExclusive()){box=b;break;}
                    if(m.immutableBlocks().contains(pos)||m.blockEntities().contains(pos)){
                        tokens.merge(token,1L,Long::sum);
                        if(portal)portalTokens.merge(token,1L,Long::sum);
                        if(first.size()<32)first.add(Map.of("position",pos,"source","CAPTURED_IMMUTABLE_OR_BE","token",token));
                        found=true;break;
                    }
                    if(box!=null){
                        boxCounts.merge(box.toString(),1L,Long::sum);
                        if(first.size()<32)first.add(Map.of("position",pos,"source","STRUCTURE_BOX","box",box));
                        found=true;break;
                    }
                }
                if(found)protectedColumns++;else cleanColumns++;
                if(portal&&found)portalProtected++;
            }
        }
        var report=new TreeMap<String,Object>();
        report.put("scope","original durable T0 immutable/BE/structure first-hit audit; thin-ice-only context is not reconstructed");
        report.put("fixture",run.toString());report.put("recipe",recipe.recipeHash());report.put("root",recipe.axis().origin());
        report.put("metadataOwners",metadata.size());report.put("window",List.of(lo,hi));report.put("candidateColumns",(long)(x1-x0)*(z1-z0));
        report.put("protectedColumns",protectedColumns);report.put("cleanColumns",cleanColumns);
        report.put("firstImmutableTokens",tokens);report.put("firstStructureBoxes",boxCounts);report.put("examples",first);
        report.put("actualCachedFortFrame",fort.frame());report.put("groundRoadColumns",portalColumns);
        report.put("groundRoadProtectedColumns",portalProtected);report.put("groundRoadFirstImmutableTokens",portalTokens);
        report.put("groundRoadRecordedHighestNonPlantFeet",List.of(portalHighMin,portalHighMax));
        report.put("heightSummaryCaveat","recorded highest nonplant is not SurfaceProbe's certified foundation height; no grade acceptance claimed");
        var writer=new java.io.PrintWriter(new java.io.OutputStreamWriter(System.out,StandardCharsets.UTF_8),true);
        writer.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
}
