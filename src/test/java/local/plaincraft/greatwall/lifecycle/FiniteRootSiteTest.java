package local.plaincraft.greatwall.lifecycle;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class FiniteRootSiteTest {
    private static final DimensionBounds WORLD = new DimensionBounds(-64,320,30_000_000);
    private static final ColumnSample DRY = new ColumnSample(70,OptionalInt.empty(),OptionalInt.empty(),
            "minecraft:plains",Quality.REFERENCE,false);
    private static final List<ChunkKey> SPAWN = List.of(new ChunkKey("minecraft:overworld",0,0));

    @Test void shiftedPortUsesRealTemplateIncludingItsFarCornerAndWholeHeight() {
        var east=new Axis(new Point3i(400,65,1000),1<<30,0);
        var t=new RootEndpointSite(east,8000,0,0,2,0);
        // 世界 +X 从唯一端口出发，楼体须向 -X 伸 90 格；预告不能围绕中点或楼体中心。
        assertEquals(new Bounds(-3690,-15,954,-3599,43,1047),t.template);
        var cols=new HashSet<RootSearch.ColumnKey>();
        for(int i=0;i<t.size();i++) {
            assertTrue(cols.add(t.column(i)));
            // 中点是 Y65，平台最高是 80，端口是 70，三者不能混为一个高度。
            var k=t.column(i);
            var h=k.x()==-3600 && k.z()==1000 ? DRY : new ColumnSample(80,OptionalInt.empty(),
                    OptionalInt.empty(),"minecraft:plains",Quality.REFERENCE,false);
            assertNull(t.record(h,WORLD));
        }
        assertEquals(93*91,cols.size());
        assertEquals(new Bounds(-3690,70,954,-3599,138,1047),t.protection());
        assertEquals(new Bounds(-3698,70,946,-3591,138,1055),t.protection(8));
        assertEquals(70,t.selectedMidpoint().origin().y());
        assertEquals(-3600,local.plaincraft.greatwall.model.WallRecipeRules.centerFiniteAxis(
                t.selectedMidpoint(),8000,3).origin().x());
        var wet=new ColumnSample(63,OptionalInt.of(64),OptionalInt.of(63),"minecraft:river",Quality.REFERENCE,false);
        assertEquals("REJECTED_WATER",new RootEndpointSite(east,8000,0,0,2,0).record(wet,WORLD));
    }

    @Test void sanctuaryIsProtectedAlongBothHalvesAndJustBeyondTheEndsOnly() {
        var axis = new Axis(new Point3i(0,70,0),1<<30,0);
        // 长 8000 时末端是 ±4000；再向外保留完整的 2600 格圣域和 960 格余量。
        for (int sign : new int[]{-1,1}) {
            assertTrue(RootEndpointSite.crossesSanctuary(axis,8000,
                    new local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance(sign*3000,3000,2600)));
            assertTrue(RootEndpointSite.crossesSanctuary(axis,8000,
                    new local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance(sign*7560,0,2600)));
            assertFalse(RootEndpointSite.crossesSanctuary(axis,8000,
                    new local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance(sign*7561,0,2600)));
        }
    }

    @Test void unlimitedRayProtectsTheFutureAndTheEndTowerButNotTheDistantRear() {
        var axis = new Axis(new Point3i(1200,70,0),1<<30,0);
        var spawn = new Point3i(0,64,0);
        // 圣域在未来 10 万格仍需避让；端口背后的近楼体也算入原有外扩。
        var nearRear = new local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance(-2000,0,2600);
        assertFalse(RootEndpointSite.allowed(axis,0,spawn,SPAWN,WORLD,nearRear));
        assertFalse(RootEndpointSite.allowed(axis,0,spawn,SPAWN,WORLD,
                new local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance(101200,0,2600)));
        assertTrue(RootEndpointSite.allowed(axis,0,spawn,SPAWN,WORLD,
                new local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance(-10000,0,2600)));
        // 有限墙长为一、方向取整成同一点时，不能因 0/0 漏保护。
        var diagonal = new Axis(new Point3i(0,70,0),759250125,759250125);
        assertTrue(RootEndpointSite.crossesSanctuary(diagonal,1,nearRear));
    }

    @Test void independentlyConfiguredForwardWindowChangesOnlyTheUnlimitedRootCacheKey() {
        var config = ProductionRecipeVersions.newWorld();
        var a = RootAxisWindow.of(0, 2048);
        assertEquals(4096, RootAxisWindow.of(0,2049).forwardBlocks());
        assertNotEquals(ProductionRecipeVersions.rootAxisParameters(config,a),
                ProductionRecipeVersions.rootAxisParameters(config,RootAxisWindow.of(0,2049)));
        assertEquals(ProductionRecipeVersions.rootAxisParameters(config,RootAxisWindow.of(8000,2048)),
                ProductionRecipeVersions.rootAxisParameters(config,RootAxisWindow.of(8000,2049)));
    }

    @Test void unlimitedValidatesTheRotatedEndTowerAtTheCandidateItself() {
        var r=new RootSearch(new Point3i(0,70,0),WORLD,SPAWN,
                (o,c)->{throw new AssertionError("无限模式不能读旧三层地基");},true,
                local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance.none(),true,0);
        try(var oracle=new AnalyticTerrainOracle((x,z)->DRY,true)) {
            while(!r.needsStructureScreen()) {
                assertTrue(r.pendingCoarseReads().isEmpty());
                assertNotEquals(WorkState.DONE,r.advance(oracle,256));
            }
            var vote = r.pendingStructureCandidates().getFirst();
            var rect = vote.endpointBounds();
            var p = vote.origin();
            assertTrue(p.x()>=rect.minX() && p.x()<rect.maxXExclusive()
                    && p.z()>=rect.minZ() && p.z()<rect.maxZExclusive());
            assertEquals(93*91,(rect.maxXExclusive()-rect.minX()-2*local.plaincraft.greatwall.config.GreatWallConfig.parameters().obstacleMarginBlocks())
                    *(rect.maxZExclusive()-rect.minZ()-2*local.plaincraft.greatwall.config.GreatWallConfig.parameters().obstacleMarginBlocks()));
        }
    }

    @Test void earlyDomainIsExcludedOnBothHalvesWithoutRejectingAClearParallelWall() {
        var through=new Axis(new Point3i(1000,70,0),1<<30,0);
        assertTrue(SpawnDomain.disjointFuture(through,SPAWN));
        assertFalse(RootEndpointSite.canOccupy(through,8000,SPAWN,WORLD));
        assertTrue(RootEndpointSite.canOccupy(new Axis(new Point3i(1000,70,1000),1<<30,0),8000,SPAWN,WORLD));
    }

    @Test void endpointVoteRejectedAtOneOrientationContinuesToNextInsteadOfPublishingOrStopping() {
        // 普通地表有效但旧三层证书取不到；串行或预取任一路去取旧证书都会让本例失败。
        var oldProofCalls = new java.util.concurrent.atomic.AtomicInteger();
        var r=new RootSearch(new Point3i(0,70,0),WORLD,SPAWN,(o,c)->{oldProofCalls.incrementAndGet(); return false;},true,
                local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance.none(),true,8192);
        try(var oracle=new AnalyticTerrainOracle((x,z)->DRY,true);
            var spare=new AnalyticTerrainOracle((x,z)->DRY,true)) {
            int prefetched=0;
            int count=0;
            RootStructureScreen.Candidate accepted=null;
            while(r.advance(oracle,256)!=WorkState.DONE) {
                if(!r.needsStructureScreen()) {
                    if(prefetched==0) for(var key:r.pendingFootprintReads().stream().limit(8).toList()) {
                        assertFalse(key.requireFoundation());
                        if(r.claimPrefetch(key)) { r.prefetch(key,spare); prefetched++; }
                    }
                    continue;
                }
                var pending=r.pendingStructureCandidates();
                assertEquals(1,pending.size());
                var c=pending.getFirst();
                assertNotNull(c.endpointBounds());
                int i=c.endpointAxis();
                var box=c.endpointBounds();
                assertFalse(c.origin().x()>=box.minX() && c.origin().x()<box.maxXExclusive()
                        && c.origin().z()>=box.minZ() && c.origin().z()<box.maxZExclusive());
                var reasons=new java.util.ArrayList<>(java.util.Collections.nCopies(5,"NOT_INSPECTED"));
                reasons.set(i,count==0?"STRUCTURE_CONFLICT:test":"CLEAR");
                r.acceptStructureVotes(List.of(new RootStructureScreen.Vote(c.radiusIndex(),c.angleIndex(),
                        count==0?List.of():List.of(i),reasons)));
                if(++count==2) accepted=c;
                assertTrue(count<=2);
            }
            assertNotNull(accepted);
            assertTrue(prefetched>0);
            assertEquals(0,oldProofCalls.get());
            assertEquals(accepted.axes().get(accepted.endpointAxis()),r.finish().axis());
            assertTrue(r.audit().stream().anyMatch(a->"STRUCTURE_CONFLICT:test".equals(a.result())));
        }
    }
}
