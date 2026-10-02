package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import org.junit.jupiter.api.Test;

/** Read-only reconstruction of the archived request domain, not a world rerun. */
class ArchivedOwnerCoverageTest {
    @Test void archivedRequestsAreComparedWithActualDurableCaptureOwners() throws Exception {
        var root=Path.of("docs/evidence/s1c/entrance-ab-20261002/entrance-ab17-final-20261002-134025");
        var world=root.resolve("world/greatwall");
        var envelope=JsonParser.parseString(Files.readString(world.resolve("manifest/world.json"))).getAsJsonObject();
        var recipe=new Gson().fromJson(envelope.get("payload"),WorldRecipe.class);
        var store=new SegmentCacheStore(world.resolve("cache"),recipe);
        var plan=store.readPlan(new SegmentKey(recipe.recipeHash(),recipe.dimension(),0)).orElseThrow();
        var window=new PlanWindow(List.of(plan));
        var ordered=new LinkedHashSet<ChunkKey>();
        for(var s:plan.stations().stations()) {
            if(s.globalSQ8()>1248L*256)break;
            int x=FixedMath.floorBlock(s.point().xQ8()),z=FixedMath.floorBlock(s.point().zQ8());
            for(int cx=Math.floorDiv(x-36,16);cx<=Math.floorDiv(x+36,16);cx++)
                for(int cz=Math.floorDiv(z-36,16);cz<=Math.floorDiv(z+36,16);cz++)
                    ordered.add(new ChunkKey(recipe.dimension(),cx,cz));
        }
        for(var site:plan.sites()) {
            if(site.globalSQ8()>1248L*256||site.state()==SiteState.SUPPRESSED||site.state()==SiteState.ABSORBED)continue;
            var b=site.bounds();
            for(int x=Math.floorDiv(b.minX(),16);x<=Math.floorDiv(b.maxXExclusive()-1,16);x++)
                for(int z=Math.floorDiv(b.minZ(),16);z<=Math.floorDiv(b.maxZExclusive()-1,16);z++)
                    ordered.add(new ChunkKey(recipe.dimension(),x,z));
        }
        var captured=new HashSet<ChunkKey>();
        var pattern=Pattern.compile("metadataSource=\\w+ owner=ChunkKey\\[dimension=([^,]+), x=(-?\\d+), z=(-?\\d+)\\]");
        try(var lines=Files.lines(root.resolve("run.log"))) {
            lines.forEach(line->{
                var matcher=pattern.matcher(line);
                if(matcher.find())captured.add(new ChunkKey(matcher.group(1),
                        Integer.parseInt(matcher.group(2)),Integer.parseInt(matcher.group(3))));
            });
        }
        var coverage=new CoveragePlanner();
        var nonOwners=new ArrayList<ChunkKey>();
        var violations=new ArrayList<Map<String,Object>>();
        for(var owner:ordered) {
            var cells=coverage.cellsForChunk(owner,window);
            for(var cell:cells) if(!coverage.realizationOwners(cell,window).contains(owner)) {
                var projection=Projection.nearest(owner.x()*16+8,owner.z()*16+8,
                        new GeometryWindow(List.of(plan.geometry()),List.of(plan.prefix())));
                violations.add(Map.of("owner",owner,"cell",cell.arcCell(),
                        "lateralQ8",projection.lateralQ8(),"capturedInLog",captured.contains(owner)));
            }
            if(cells.isEmpty()) nonOwners.add(owner);
        }
        var first=ordered.iterator().next();
        var report=new LinkedHashMap<String,Object>();
        report.put("scope","OFFLINE_ARCHIVE_ONLY; candidate difference is not exact failure identity");
        report.put("sourceCommit","e2959de");report.put("recipeHash",recipe.recipeHash());
        report.put("traversalOwners",ordered.size());report.put("capturedDistinct",captured.size());
        report.put("firstTraversalOwner",first);report.put("nonOwnerTraversalChunks",nonOwners);
        report.put("triggerCaptureViolations",violations);
        report.put("exactFailureOwner","historical pre-fix owner was resolved separately; this test checks the repaired invariant");
        var out=Path.of("build/reports/s1c-owner/offline-owner-difference.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        assertEquals(505,ordered.size());
        assertEquals(133,captured.size());
        assertTrue(violations.isEmpty(),"every dispatched cell must have durable capture ownership");
        assertTrue(nonOwners.stream().allMatch(owner->coverage.cellsForChunk(owner,window).isEmpty()),
                "non-owner traversal chunks must remain outside the dispatch domain");
        System.out.println("S1C_OWNER_OFFLINE first="+first+" captures="+captured.size()
                +" nonOwners="+nonOwners.size()+" violations="+violations.size());
    }
}
