package local.plaincraft.greatwall.planning;

import java.util.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** 把真正的斜线/折返落成方块；不能用相位函数自证接线或依赖施工顺序填洞。 */
class ImportedComplexWallTest {
    private static final String H = PlanningFixtures.HASH;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @ParameterizedTest @EnumSource(Asset.class)
    void bentDiagonalApproachesKeepFloorsAndAreIndependentOfCellAndChunkOrder(Asset asset) {
        for (int rotation = 0; rotation < 4; rotation++) {
            var f = ImportedProductionPlanTest.fixture(asset, rotation);
            var port = ImportedSiteGeometry.layout(f.site()).ports().getLast();
            long start = ImportedSiteGeometry.portArc(f.site(), ImportedSiteGeometry.layout(f.site()).ports().size()-1);
            var points = new ArrayList<Point2q>();
            // 接口外保留64格直线，再斜行和折返；建筑仍保持正交朝向。
            for (int[] p : new int[][] {{0,0},{64,0},{112,32},{144,96},{112,160}}) {
                points.add(new Point2q(port.feet().x()*256L+128
                        +256L*(p[0]*port.outward().getStepX()-p[1]*port.outward().getStepZ()),
                        port.feet().z()*256L+128
                        +256L*(p[0]*port.outward().getStepZ()+p[1]*port.outward().getStepX())));
            }
            var arcs = ArcLength.accumulate(points);
            var stations = new ArrayList<Station>();
            for (int i=0;i<points.size();i++) stations.add(new Station(start+arcs.get(i),points.get(i),port.feet().y(),0));
            var cells = cells(f, stations, List.of(f.site()));
            var forward = voxels(cells);
            var reversed = new ArrayList<>(cells); Collections.reverse(reversed);
            assertEquals(forward, voxels(reversed), "逆序编译单元不能改变重叠边界");
            assertTrue(forward.size()>10000, "实际长墙必须非空，缺墙不能让后续循环空转");
            for (int step=1;step<=48;step++) {
                assertFloor(forward,port.cell(step,0,-1));
                var top=forward.get(port.cell(step,5,2));
                assertEquals(step%4!=0,top!=null&&top.kind()==IntentKind.SOLID,"端口节拍不能被后方折返改写");
            }
            // 每条斜边按世界坐标逐格插值验马道，不能只检查两端。
            for (int edge=0;edge<points.size()-1;edge++) {
                var a=points.get(edge); var b=points.get(edge+1);
                int steps=(int)Math.max(Math.abs(b.xQ8()-a.xQ8()),Math.abs(b.zQ8()-a.zQ8()))/256;
                for(int i=1;i<steps;i++) assertFloor(forward,new Point3i(
                        (int)Math.floorDiv(a.xQ8()+(b.xQ8()-a.xQ8())*i/steps,256),
                        port.feet().y()-1,
                        (int)Math.floorDiv(a.zQ8()+(b.zQ8()-a.zQ8())*i/steps,256)));
            }
            var owners=new TreeSet<ChunkKey>(Comparator.comparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z));
            forward.keySet().forEach(p->owners.add(new ChunkKey(f.recipe().dimension(),Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16))));
            var before=new HashMap<Point3i,String>();
            forward.keySet().forEach(p->before.put(p,"minecraft:air{}"));
            var compiled=new ChunkPlanCompiler().window();
            var expected=new HashMap<ChunkKey,ChunkWritePlan>();
            for(var owner:owners) expected.put(owner,compiled.compile(owner,f.recipe().recipeHash(),cells,before));
            var reverseOwners=new ArrayList<>(owners); Collections.reverse(reverseOwners);
            var alternate=new ChunkPlanCompiler().window();
            for(var owner:reverseOwners) {
                var actual=alternate.compile(owner,f.recipe().recipeHash(),reversed,before);
                assertEquals(expected.get(owner),actual,"区块裁切顺序不能改变方块、实体或计划hash");
                assertFalse(actual.voxels().isEmpty());
            }
        }
    }

    @Test void twoRealBuildingPortsJoinForAllFourCycleRemainders() {
        var f=ImportedProductionPlanTest.fixture(Asset.BEACON,0);
        var left=f.site(); var exit=ImportedSiteGeometry.layout(left).ports().getLast();
        long from=ImportedSiteGeometry.portArc(left,1);
        for(int remainder=0;remainder<4;remainder++) {
            int separation=280+remainder;
            var frame=left.frame();
            var shifted=new Frame(new Point2q(frame.center().xQ8()+separation*256L,frame.center().zQ8()),
                    frame.txQ30(),frame.tzQ30(),frame.walkY(),frame.mirror());
            var id=new SiteId(left.id().wallId(),SiteKind.BEACON,2);
            var right=new SiteRecord(id,left.globalSQ8()+separation*256L,shifted,
                    ImportedSiteGeometry.layout(id,shifted).boundsWithStraightWalls(0),SiteState.PLANNED,Optional.empty(),Optional.empty());
            var entrance=ImportedSiteGeometry.layout(right).ports().getFirst();
            long to=ImportedSiteGeometry.portArc(right,0);
            assertEquals((to-from)/256,entrance.feet().x()-exit.feet().x(),"夹具的弧长和真实接口距离必须一致");
            var stations=List.of(new Station(from,new Point2q(exit.feet().x()*256L+128,exit.feet().z()*256L+128),exit.feet().y(),0),
                    new Station(to,new Point2q(entrance.feet().x()*256L+128,entrance.feet().z()*256L+128),entrance.feet().y(),0));
            var blocks=voxels(cells(f,stations,List.of(left,right)));
            int gaps=0,solid=0;
            for(int step=1;step<(to-from)/256;step++) {
                assertFloor(blocks,exit.cell(step,0,-1));
                var top=blocks.get(exit.cell(step,5,2));
                boolean merlon=top!=null&&top.kind()==IntentKind.SOLID;
                if(step<=24)assertEquals(step%4!=0,merlon);
                int back=(int)((to-from)/256)-step;
                if(back<=24)assertEquals(back%4!=0,merlon,"另一端也必须从实际接口接线");
                if(merlon)solid++;else gaps++;
            }
            assertTrue(solid>100&&gaps>30,"双端实物墙不能以整段缺垛满足端点");
        }
    }

    private static List<RealizationCell> cells(ImportedProductionPlanTest.Fixture f,List<Station> stations,List<SiteRecord> sites) {
        var columns=new HashMap<Point3i,SurfaceProbe.ColumnView>();
        var flat=f.columns().values().iterator().next();
        for(int edge=0;edge<stations.size()-1;edge++) {
            var a=stations.get(edge).point();var b=stations.get(edge+1).point();
            int n=Math.max(1,(int)(Math.max(Math.abs(b.xQ8()-a.xQ8()),Math.abs(b.zQ8()-a.zQ8()))/256));
            for(int i=0;i<=n;i++) {
                int x=(int)Math.floorDiv(a.xQ8()+(b.xQ8()-a.xQ8())*i/n,256);
                int z=(int)Math.floorDiv(a.zQ8()+(b.zQ8()-a.zQ8())*i/n,256);
                for(int dx=-9;dx<=9;dx++)for(int dz=-9;dz<=9;dz++)columns.put(new Point3i(x+dx,0,z+dz),flat);
            }
        }
        long from=stations.getFirst().globalSQ8(),to=stations.getLast().globalSQ8();
        var result=new ArrayList<RealizationCell>();
        for(long cell=Math.floorDiv(from,64L*256);cell<=Math.floorDiv(to-1,64L*256);cell++) {
            var id=new SiteId(f.site().id().wallId(),SiteKind.WALL,cell);
            var site=new SiteRecord(id,(cell*64+32)*256,f.site().frame(),f.site().bounds(),SiteState.PLANNED,Optional.empty(),Optional.empty());
            var input=new ComponentInput(site,stations,Map.of(),new DecaySample(DecayState.INTACT,0,20),PaletteFamily.STONE,false,
                    List.of(),List.of(),new GroundEntranceContract.Evidence(GroundEntranceContract.Source.DURABLE_T0,f.recipe().bounds(),columns));
            var wall=new ImportedRibbonSection(f.recipe().seed()).compileIntervals(input,List.of(new ArcInterval(from,to)),
                    List.of(),ignored->input.decay(),List.of(),sites);
            assertFalse(wall.voxels().isEmpty(),"每个单元必须参与真实编译");
            result.add(new RealizationCell(new CellKey(f.recipe().recipeHash(),f.recipe().dimension(),cell),List.of(),List.of(wall),H,0,H,H));
        }
        return result;
    }
    private static Map<Point3i,VoxelIntent> voxels(List<RealizationCell> cells) {
        var result=new HashMap<Point3i,VoxelIntent>();
        for(var cell:cells)for(var component:cell.components())for(var voxel:component.voxels()) {
            var previous=result.putIfAbsent(voxel.pos(),voxel);
            if(previous!=null)assertEquals(previous,voxel,"单元边界不能产生冲突所有权");
        }
        return result;
    }
    private static void assertFloor(Map<Point3i,VoxelIntent> blocks,Point3i point) {
        var floor=blocks.get(point); assertNotNull(floor,"马道缺站："+point);
        assertTrue(floor.walkable(),"预期地板必须可走："+point);
    }
}
