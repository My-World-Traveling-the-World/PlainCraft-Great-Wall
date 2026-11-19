package local.plaincraft.greatwall.generation;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.planning.PlanningColumnView;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 合成v2计划的真实写盘/重开回归；不把临时夹具宣称为玩家历史存档的完整兼容验收。 */
class LegacyWallContinuationTest {
    private static final String H="a".repeat(64);
    @TempDir Path root;
    @BeforeAll static void bootstrap(){ SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void frozenV2WriterBytesRemainReadableWithoutRegeneratingTheirPlans() throws Exception {
        var evidence=root.resolve("frozen").toAbsolutePath().normalize();
        // 固定旧写入器字节；不能让新读写器一起改格式后仍用“往返成功”自证兼容。
        try(var resource=getClass().getResourceAsStream("/fixtures/legacy-v2-evidence.zip")) {
            assertNotNull(resource);
            try(var zip=new java.util.zip.ZipInputStream(resource)) {
                java.util.zip.ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) {
                    var file=evidence.resolve(entry.getName()).normalize();
                    assertTrue(file.startsWith(evidence));
                    Files.createDirectories(file.getParent());Files.copy(zip,file);
                }
            }
        }
        var store=ChunkEvidenceStore.production(evidence);var world=new World();
        for(int x=0;x>=-2;x--) {
            var key=new ChunkKey("minecraft:overworld",x,0);
            var plan=store.readPlan(key).orElseThrow();
            assertTrue(plan.voxels().size()>1000,"固定记录必须真的包含墙体");
            local.plaincraft.greatwall.geometry.CanonicalCodec.validateWritePlan(plan);
            assertTrue(replay(plan,world,false).complete());
            assertEquals(plan.hash(),world.markers.get(key).planHash());
        }
        save(world);var restarted=load();
        for(int x=-2;x<=0;x++) assertEquals(0,replay(store.readPlan(
                new ChunkKey("minecraft:overworld",x,0)).orElseThrow(),restarted,false).written());
    }

    @Test void reopenedCompletedNeighbourKeepsPlayerEditWhileUnbuiltNeighboursFinish() throws Exception {
        var plans=plans(); assertTrue(plans.size()>=3,"必须真实跨过负坐标的多个区块");
        var store=ChunkEvidenceStore.production(root.resolve("evidence"));
        for(var plan:plans)store.persistPlan(plan);
        var world=new World();
        var built=plans.get(1); assertTrue(replay(built,world,false).complete());
        var edited=built.voxels().stream().filter(v->v.kind()==IntentKind.SOLID).findFirst().orElseThrow().pos();
        world.blocks.put(edited,"minecraft:diamond_block");
        save(world); var reopened=load();
        var disk=ChunkEvidenceStore.production(root.resolve("evidence"));
        for(int i=plans.size()-1;i>=0;i--) {
            var old=plans.get(i);var restored=disk.readPlan(old.owner()).orElseThrow();
            assertEquals(old,restored,"重开必须消费已发布旧计划，不能按新相位重新塑形");
            var result=replay(restored,reopened,false);
            assertTrue(result.complete());
            if(restored.owner().equals(built.owner()))assertEquals(0,result.written(),"已结案邻区不能再写玩家修改");
        }
        assertEquals("minecraft:diamond_block",reopened.blocks.get(edited));
        assertEquals(plans.size(),reopened.markers.size());
        for(var plan:plans) {
            assertTrue(reopened.markers.get(plan.owner()).complete());
            assertEquals(plan.hash(),reopened.markers.get(plan.owner()).planHash());
            for(var v:plan.voxels())if(!v.pos().equals(edited))
                assertEquals(state(v.state()),reopened.read(v.pos()),"所有未建区块按原计划补齐");
        }
        int before=reopened.writes; save(reopened);reopened=load();
        for(var plan:plans)assertEquals(0,replay(plan,reopened,false).written());
        assertEquals(0,reopened.writes,"再次重开必须保持幂等，计数器按进程重新开始");
        assertTrue(before>0);
    }

    @Test void incompleteMarkerSurvivesDiskAndCannotHideMissingBlocks() throws Exception {
        var plan=plans().get(1);var world=new World();
        world.reject=plan.voxels().getFirst().pos();
        var first=replay(plan,world,false);
        assertFalse(first.complete());assertEquals(1,first.failed());
        assertFalse(world.markers.get(plan.owner()).complete());
        var missing=world.reject;assertFalse(world.blocks.containsKey(missing));
        save(world);var restarted=load();
        var retry=replay(plan,restarted,false);
        assertTrue(retry.complete());assertEquals(1,retry.written(),"恢复只补真正缺失的位置");
        assertEquals(state(plan.voxels().getFirst().state()),restarted.blocks.get(missing));
        assertTrue(restarted.markers.get(plan.owner()).complete());
    }

    @Test void wrongPlanHashCannotUseAnOldCompletionButExplicitRepairCanOverrideEdits() throws Exception {
        var plan=plans().get(1);var world=new World();assertTrue(replay(plan,world,false).complete());
        var point=plan.voxels().getFirst().pos();world.blocks.put(point,"minecraft:gold_block");
        var old=world.markers.get(plan.owner());
        world.markers.put(plan.owner(),new TerrainMarkers.Marker(old.recipe(),old.coverageHash(),"b".repeat(64),
                old.gateRevision(),true,old.walkwayFeet(),old.gameplay(),old.spawnSlots(),old.realizedStates()));
        save(world);var restarted=load();
        assertEquals(1,replay(plan,restarted,false).written(),"不同计划不能借旧完成章跳过必要施工");
        restarted.blocks.put(point,"minecraft:emerald_block");
        assertEquals(0,replay(plan,restarted,false).written());
        assertEquals(1,replay(plan,restarted,true).written(),"显式修复仍保留覆盖玩家修改的能力");
    }

    private static List<ChunkWritePlan> plans() {
        var id=new SiteId("legacy-v2-fixture",SiteKind.WALL,0);
        var stations=List.of(new Station(0,new Point2q(-24*256L+128,7*256L+128),79,0),
                new Station(32*256L,new Point2q(8*256L+128,7*256L+128),79,0));
        var columns=new HashMap<Point3i,SurfaceProbe.ColumnView>();
        for(int x=-34;x<19;x++)for(int z=-3;z<18;z++)
            columns.put(new Point3i(x,0,z),new PlanningColumnView(x,z,AnalyticTerrainOracle.dry(64)));
        var site=new SiteRecord(id,32*256L,new Frame(stations.getFirst().point(),1<<30,0,79,false),
                new Bounds(-24,64,2,9,82,13),SiteState.PLANNED,Optional.empty(),Optional.empty());
        var input=new ComponentInput(site,stations,Map.of(),new DecaySample(DecayState.INTACT,0,20),PaletteFamily.STONE,false,
                List.of(),List.of(),new GroundEntranceContract.Evidence(GroundEntranceContract.Source.DURABLE_T0,
                        new DimensionBounds(-64,320,30_000_000),columns));
        var body=new ImportedRibbonSection(11).compileIntervals(input,List.of(new ArcInterval(0,32*256L)),List.of(),
                ignored->input.decay(),List.of(),List.of());
        assertTrue(body.voxels().size()>5000,"必须使用真实v2配套墙产物，不能让空夹具通过重开测试");
        var cell=new RealizationCell(new CellKey(H,"minecraft:overworld",0),List.of(),List.of(body),H,0,H,H);
        var before=new HashMap<Point3i,String>();body.voxels().forEach(v->before.put(v.pos(),"minecraft:air{}"));
        var result=new ArrayList<ChunkWritePlan>();
        for(int x=-2;x<=0;x++)result.add(new ChunkPlanCompiler().compile(new ChunkKey("minecraft:overworld",x,0),H,List.of(cell),before));
        for(var p:result)assertFalse(p.voxels().isEmpty());
        return result;
    }

    private static PublishedPlanPainter.Result replay(ChunkWritePlan plan,World world,boolean forced) {
        world.current=plan;
        var marker=world.markers.get(plan.owner());
        // 与两个正式入口使用同一个判据/落块内核；世界端口只保存可观察的方块和完成章。
        if(marker!=null&&PublishedPlanPainter.preserveCompleted(forced,marker.complete(),marker.planHash(),plan.hash()))
            return new PublishedPlanPainter.Result(true,0,0);
        return PublishedPlanPainter.paint(plan,world);
    }
    private void save(World world)throws Exception {
        var json=new JsonObject();var blocks=new JsonObject();var markers=new JsonArray();
        world.blocks.forEach((p,s)->blocks.addProperty(p.x()+","+p.y()+","+p.z(),s));
        world.markers.forEach((key,marker)->{
            var item=new JsonObject();item.addProperty("x",key.x());item.addProperty("z",key.z());
            item.add("marker",TerrainMarkers.CODEC.encodeStart(JsonOps.INSTANCE,marker).getOrThrow());markers.add(item);
        });
        json.add("blocks",blocks);json.add("markers",markers);
        Files.writeString(root.resolve("chunk-attachments.json"),json.toString());
    }
    private World load()throws Exception {
        var world=new World();var json=JsonParser.parseString(Files.readString(root.resolve("chunk-attachments.json"))).getAsJsonObject();
        json.getAsJsonObject("blocks").entrySet().forEach(e->{
            var xyz=e.getKey().split(",");world.blocks.put(new Point3i(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2])),e.getValue().getAsString());
        });
        for(var element:json.getAsJsonArray("markers")) {
            var item=element.getAsJsonObject();
            world.markers.put(new ChunkKey("minecraft:overworld",item.get("x").getAsInt(),item.get("z").getAsInt()),
                    TerrainMarkers.CODEC.parse(JsonOps.INSTANCE,item.get("marker")).getOrThrow());
        }
        return world;
    }
    private static String state(BlockSpec spec){return spec.id()+new TreeMap<>(spec.properties());}
    private static final class World implements PublishedPlanPainter.World<String> {
        final Map<Point3i,String> blocks=new HashMap<>();
        final Map<ChunkKey,TerrainMarkers.Marker> markers=new HashMap<>();
        ChunkWritePlan current;Point3i reject;int writes;
        public boolean validHeight(int y){return y>=-64&&y<320;}
        public String resolve(BlockSpec spec){return state(spec);}
        public String read(Point3i p){return blocks.getOrDefault(p,"minecraft:air{}");}
        public boolean write(Point3i p,String state){if(p.equals(reject))return false;blocks.put(p,state);writes++;return true;}
        public void finish(Set<Point3i> satisfied,boolean complete){
            var m=current.metadata();
            markers.put(current.owner(),new TerrainMarkers.Marker(current.recipeHash(),m.coverageHash(),current.hash(),
                    m.gateRevision(),complete,current.walkwayFeet(),current.blockEntities(),m.spawnSlots(),m.realizedStates()));
        }
        public void failure(Point3i p,RuntimeException failure){}
    }
}
