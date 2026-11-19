package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.compat.road.WallRoadGuard;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import local.plaincraft.greatwall.terrain.RealizationPlanner;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** 使用正式实现器和合法夹具检查接口之外的产物；不修改玩家存档。 */
public final class DeepAuditWallProbe {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        phase();
        straightPhase();
        duplicateBuildings();
        projectionWork();
        highTerrain();
        roadDoor();
    }
    private static void phase() {
        var f = ImportedProductionPlanTest.fixture(Asset.GATE, 0);
        var blocks = new HashMap<Point3i,VoxelIntent>();
        var planner = ImportedProductionPlanTest.planner(f.recipe());
        for (int cell = 6; cell <= 10; cell++) {
            var output = planner.resolve(ImportedProductionPlanTest.input(f, cell, List.of()));
            for (var component : output.components()) for (var voxel : component.voxels()) blocks.put(voxel.pos(), voxel);
        }
        var ports = ImportedSiteGeometry.layout(f.site()).ports();
        int mismatches = 0;
        for (int port = 0; port < ports.size(); port++) {
            StringBuilder actual = new StringBuilder(), expected = new StringBuilder();
            for (int step = 17; step <= 28; step++) {
                var p = ports.get(port).cell(step, 5, 2);
                var voxel = blocks.get(p);
                boolean solid = voxel != null && voxel.kind() == IntentKind.SOLID;
                boolean wanted = step % 4 != 0;
                actual.append(solid ? 'M' : '_'); expected.append(wanted ? 'M' : '_');
                if (solid != wanted) mismatches++;
            }
            System.out.println("AUDIT_WALL_PHASE port=" + port + " steps=17..28 expected=" + expected + " actual=" + actual);
        }
        System.out.println("AUDIT_WALL_PHASE mismatches=" + mismatches);
    }
    private static void duplicateBuildings() {
        var f = ImportedProductionPlanTest.fixture(Asset.CHENGHAI_TOWER, 0);
        var templates = new EnumMap<SiteKind,ComponentTemplate>(SiteKind.class);
        templates.putAll(S2TemplateSet.create(f.recipe().seed(),
                (i,p,l,w,s,h) -> S1TemplateSet.material(i.family(),i.redSandstone(),p)));
        var delegate = templates.get(SiteKind.FORT);
        var count = new AtomicInteger();
        long[] solids = {0};
        templates.put(SiteKind.FORT, new ExactFootprintTemplate() {
            public Bounds localBounds(ComponentInput input) { return delegate.localBounds(input); }
            public Bounds worldBounds(ComponentInput input) { return ((ExactFootprintTemplate)delegate).worldBounds(input); }
            public ComponentVoxels compile(ComponentInput input) {
                count.incrementAndGet();
                var result = delegate.compile(input);
                solids[0] += result.voxels().size();
                return result;
            }
        });
        var planner = new RealizationPlanner(f.recipe(),new local.plaincraft.greatwall.generation.SurfaceProbe(),templates);
        int published = 0;
        for (int cell=0;cell<=2;cell++) {
            var result = planner.resolve(ImportedProductionPlanTest.input(f,cell,List.of()));
            published += result.components().stream().filter(c->c.id().equals(f.site().id())).count();
        }
        System.out.println("AUDIT_BUILDING_DUPLICATION compileCalls=" + count.get() + " bodyOutputs=" + published
                + " compiledVoxels=" + solids[0]);
        require(count.get()==2&&published==1,"必须确实走两次完整编译而只发布一次");
    }
    private static void straightPhase() {
        var f=ImportedProductionPlanTest.fixture(Asset.GATE,0);
        var id=new SiteId(f.recipe().wallId(),SiteKind.GATE,1);
        var frame=new Frame(new Point2q(128,128),1<<30,0,79,false);
        var site=new SiteRecord(id,512L*256,frame,ImportedSiteGeometry.layout(id,frame).boundsWithStraightWalls(0),
                SiteState.PLANNED,Optional.empty(),Optional.empty());
        var stations=new ArrayList<Station>();
        for(int s=400;s<=480;s++)stations.add(new Station(s*256L,new Point2q((s-512L)*256+128,128),79,0));
        var columns=new HashMap<Point3i,local.plaincraft.greatwall.generation.SurfaceProbe.ColumnView>();
        var flat=f.columns().values().iterator().next();
        for(int z=-10;z<=10;z++)for(int x=-125;x<=-25;x++)columns.put(new Point3i(x,0,z),flat);
        var wallId=new SiteId(f.recipe().wallId(),SiteKind.WALL,7);
        var wall=new SiteRecord(wallId,448L*256,frame,site.bounds(),SiteState.PLANNED,Optional.empty(),Optional.empty());
        var input=new ComponentInput(wall,stations,Map.of(),new DecaySample(DecayState.INTACT,0,20),
                PaletteFamily.STONE,false,List.of(),List.of(),new GroundEntranceContract.Evidence(
                GroundEntranceContract.Source.DURABLE_T0,f.recipe().bounds(),columns));
        var compiled=new ImportedRibbonSection(f.recipe().seed()).compileIntervals(input,
                List.of(new ArcInterval(416L*256,480L*256)),List.of(),
                ignored->new DecaySample(DecayState.INTACT,0,20),List.of(),List.of(site));
        var blocks=new HashMap<Point3i,VoxelIntent>();
        for(var v:compiled.voxels())blocks.put(v.pos(),v);
        var port=ImportedSiteGeometry.layout(site).ports().getFirst();
        var actual=new StringBuilder();var floors=new StringBuilder();
        for(int step=17;step<=28;step++){
            var roof=blocks.get(port.cell(step,5,2));
            actual.append(roof!=null&&roof.kind()==IntentKind.SOLID?'M':'_');
            var floor=blocks.get(port.cell(step,0,-1));
            floors.append(floor!=null&&floor.walkable()?'F':'_');
        }
        System.out.println("AUDIT_STRAIGHT_PHASE steps=17..28 expected=MMM_MMM_MMM_ actual="+actual+" floors="+floors);
        require(floors.toString().equals("FFFFFFFFFFFF"),"先保证墙列真实存在，再判垛口节奏");
        require(actual.toString().equals("MMM_MMM__MMM"),"当前版本应复现第 24 格之后的连续双缺");
    }
    private static void projectionWork() throws Exception {
        var f=ImportedProductionPlanTest.fixture(Asset.GATE,0);
        var projection=new ImportedRibbonProjection(f.plan().stations().stations(),List.of(f.site()));
        var field=ImportedRibbonProjection.class.getDeclaredField("edges");field.setAccessible(true);
        int edges=((List<?>)field.get(projection)).size(),count=0;
        long checksum=0;
        long started=System.nanoTime();
        for(var p:f.columns().keySet()){
            var value=projection.contract(p.x(),p.z());
            if(value!=null)checksum+=value.globalSQ8();
            count++;
        }
        System.out.println("AUDIT_PROJECTION columns="+count+" edges="+edges+" edgeLoopVisits="+((long)count*edges)
                +" elapsedUs="+((System.nanoTime()-started)/1000)+" checksum="+checksum);
        var index=new ProjectionIndexPrototype(projection);
        int compared=0;
        for(var p:f.columns().keySet()){
            require(java.util.Objects.equals(projection.contract(p.x(),p.z()),index.contract(p.x(),p.z())),
                    "空间索引不能改变定点投影、同距优先级或端面外里程");
            compared++;
        }
        System.out.println("AUDIT_PROJECTION_INDEX compared="+compared+" exactEqual=true leafEdgeVisits="+index.totalVisits);
    }
    private static void highTerrain() {
        var f = ImportedProductionPlanTest.fixture(Asset.GATE,0);
        var r=f.recipe();
        var candidate=new WorldRecipe(r.instance(),r.wallId(),r.seed(),r.dimension(),r.frozenSpawn(),r.axis(),
                new DimensionBounds(-64,320,r.bounds().horizontalLimit()),r.config(),r.terrainHash(),r.paletteHash(),
                r.templatesHash(),r.recipeHash());
        String hash=local.plaincraft.greatwall.geometry.DeterministicHash.sha256(
                local.plaincraft.greatwall.geometry.CanonicalCodec.recipe(candidate));
        var recipe=new WorldRecipe(candidate.instance(),candidate.wallId(),candidate.seed(),candidate.dimension(),
                candidate.frozenSpawn(),candidate.axis(),candidate.bounds(),candidate.config(),candidate.terrainHash(),
                candidate.paletteHash(),candidate.templatesHash(),hash);
        var old=f.plan().geometry();
        var candidateGeometry=new SegmentGeometry(new SegmentKey(hash,r.dimension(),old.key().index()),old.points(),old.localSQ8(),
                old.water(),old.terminal(),old.start(),old.end(),old.logicalColumns(),old.degradation(),old.hash());
        // 更换合法维度边界后同步摘要，排除夹具凭证失配导致的假失败。
        var geometry=new SegmentGeometry(candidateGeometry.key(),old.points(),old.localSQ8(),
                old.water(),old.terminal(),old.start(),old.end(),old.logicalColumns(),old.degradation(),
                local.plaincraft.greatwall.geometry.DeterministicHash.sha256(
                        local.plaincraft.greatwall.geometry.CanonicalCodec.geometry(candidateGeometry)));
        var h = new HeightContractPlanner(recipe);
        var samples = new HashMap<Point2q,ColumnSample>();
        for (var p : h.samplePoints(geometry,f.plan().prefix(),List.of(f.site())))
            samples.put(p,AnalyticTerrainOracle.dry(280));
        try {
            var result = h.solve(geometry,f.plan().prefix(),List.of(f.site()),samples);
            System.out.println("AUDIT_HIGH_TERRAIN breaks=" + result.breaks());
            throw new AssertionError("当前版本应在高地硬平台上报维度上界错误");
        } catch (local.plaincraft.greatwall.model.WallFailure failure) {
            require(failure.code()==FailureCode.OUT_OF_BOUNDS
                    &&failure.getMessage().equals("hard W outside dimension"),"必须命中高度上界而非其他前置失败");
            System.out.println("AUDIT_HIGH_TERRAIN maxY=320 terrain=280 failure=" + failure.code() + " message=" + failure.getMessage());
        }
    }
    private static void roadDoor() {
        var f = ImportedProductionPlanTest.fixture(Asset.GATE,0);
        var output = ImportedProductionPlanTest.planner(f.recipe())
                .resolve(ImportedProductionPlanTest.input(f,8,List.of()));
        var body = output.components().stream().filter(c->c.id().equals(f.site().id())).findFirst().orElseThrow();
        var layout = ImportedSiteGeometry.layout(f.site());
        var localDoor = new Point3i(56,1,16);
        var p = ImportedGateGeometry.gate().place(localDoor,layout.feet(),layout.rotation());
        boolean actualDoor = body.voxels().stream().anyMatch(v->v.pos().equals(p)&&v.kind()==IntentKind.OWNED_VOID);
        require(actualDoor,"必须测试真实模板门洞，而非随意的未声明空气");
        WallRoadGuard.clear();
        // 与正式 WallPlanSupport 的发布口径相同：所有体素只投影到 XZ 列。
        for(var voxel:body.voxels()) WallRoadGuard.markColumn(voxel.pos().x(),voxel.pos().z());
        System.out.println("AUDIT_ROAD_DOOR actualVoid=" + actualDoor + " blocked="
                +local.plaincraft.greatwall.api.WallRoadApi.blocks(p.x(),p.y(),p.z())+" pos="+p);
        System.out.println("AUDIT_GUARD_MARKING voxelCalls="+body.voxels().size()+" distinctColumns="
                +body.voxels().stream().map(v->new Point3i(v.pos().x(),0,v.pos().z())).distinct().count());
        require(local.plaincraft.greatwall.api.WallRoadApi.blocks(p.x(),p.y(),p.z()),
                "当前二维保护 API 会把真实门洞也判为受保护列");
        WallRoadGuard.clear();
    }
    private static void require(boolean value,String reason) {
        if(!value)throw new AssertionError(reason);
    }
}
