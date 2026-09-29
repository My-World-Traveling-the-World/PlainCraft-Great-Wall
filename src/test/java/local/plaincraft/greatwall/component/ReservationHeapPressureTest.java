package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.diagnostics.BudgetReservation;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.TerrainTile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real heap measurements for the admission primitive with real tile/component objects.
 * Does not certify the production capture/compile allocation closure. */
class ReservationHeapPressureTest {
    private record Pinned(List<TerrainTile> tiles, ComponentVoxels component,
                          PlanLease<BudgetReservation.Reservation> terrain,
                          PlanLease<BudgetReservation.Reservation> compiled) implements AutoCloseable {
        @Override public void close() { compiled.close(); terrain.close(); }
    }

    @Test @Timeout(180)
    void fullBudgetPinsBackpressureWhileRealPayloadsStayLive() throws Exception {
        var memory=ManagementFactory.getMemoryMXBean();
        var heapPools=ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==MemoryType.HEAP).toList();
        heapPools.forEach(p->p.resetPeakUsage());
        long baseline=memory.getHeapMemoryUsage().getUsed();
        var peak=new AtomicLong(baseline);
        long terrainPeak=0,compiledPeak=0;
        try(var worker=Executors.newFixedThreadPool(8);
            var sampler=Executors.newSingleThreadScheduledExecutor();
            var terrain=new BudgetReservation(64L<<20,32,worker);
            var compiled=new BudgetReservation(32L<<20,32,worker)) {
            sampler.scheduleAtFixedRate(()->peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(),Math::max),0,1,TimeUnit.MILLISECONDS);
            for(int round=0;round<24;round++) {
                var tasks=new ArrayList<CompletableFuture<Pinned>>();
                for(int slot=0;slot<8;slot++) {
                    int id=round*8+slot;
                    tasks.add(terrain.acquire(8L<<20).thenCompose(t->compiled.acquire(4L<<20)
                            .thenApplyAsync(c->{
                                try{return payload(id,t,c);}
                                catch(Throwable failure){c.close();throw failure;}
                            },worker).whenComplete((v,e)->{if(e!=null)t.close();})).toCompletableFuture());
                }
                CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(30,TimeUnit.SECONDS);
                terrainPeak=Math.max(terrainPeak,terrain.stats().reserved());
                compiledPeak=Math.max(compiledPeak,compiled.stats().reserved());
                var tWait=terrain.acquire(1).toCompletableFuture();
                var cWait=compiled.acquire(1).toCompletableFuture();
                assertEquals(64L<<20,terrain.stats().reserved());
                assertEquals(32L<<20,compiled.stats().reserved());
                assertFalse(tWait.isDone());assertFalse(cWait.isDone());
                for(var task:tasks) {
                    var payload=task.get(5,TimeUnit.SECONDS);
                    assertEquals(8,payload.tiles().size());
                    assertFalse(payload.component().voxels().isEmpty());
                    assertEquals(64,payload.tiles().getFirst().hash().length());
                    payload.close();
                }
                tWait.get(5,TimeUnit.SECONDS).close();cWait.get(5,TimeUnit.SECONDS).close();
                assertEquals(0,terrain.stats().reserved());assertEquals(0,compiled.stats().reserved());
            }
        }
        long poolPeakSum=heapPools.stream().mapToLong(p->p.getPeakUsage().getUsed()).sum();
        String pools=heapPools.stream().map(p->"    \""+p.getName()+"\": "+p.getPeakUsage().getUsed())
                .collect(java.util.stream.Collectors.joining(",\n"));
        String json="""
                {
                  "scope": "reservation primitive; real TerrainTile and EnemyTower payloads; NOT production closure",
                  "rounds": 24,
                  "concurrentPins": 8,
                  "terrainPages": 1536,
                  "compiledComponents": 192,
                  "baselineHeapUsed": %d,
                  "sampledHeapPeak": %d,
                  "heapPoolPeakSumNonSimultaneous": %d,
                  "jvmMaxHeap": %d,
                  "terrainReservedPeak": %d,
                  "compiledReservedPeak": %d,
                  "heapPools": {
                %s
                  }
                }
                """.formatted(baseline,peak.get(),poolPeakSum,memory.getHeapMemoryUsage().getMax(),terrainPeak,compiledPeak,pools);
        Path report=Path.of("build","reports","s1b-memory","reservation-heap.json");
        Files.createDirectories(report.getParent());Files.writeString(report,json);
        System.out.println(json);
        assertTrue(peak.get()>=baseline);
        assertEquals(64L<<20,terrainPeak);assertEquals(32L<<20,compiledPeak);
    }

    private static Pinned payload(int id,PlanLease<BudgetReservation.Reservation> terrain,PlanLease<BudgetReservation.Reservation> compiled) {
        var tiles=new ArrayList<TerrainTile>();
        var palette=new ArrayList<String>();
        for(int n=0;n<256;n++)palette.add("fixture:state_"+n+"{}:fluid=minecraft:empty:be=false");
        for(int page=0;page<8;page++) {
            var key=new TerrainTile.Key("minecraft:overworld",id,page,0,127);
            int[] states=new int[key.volume()];for(int n=0;n<states.length;n++)states[n]=n%palette.size();
            tiles.add(new TerrainTile(key,TemplateFixtures.HASH,palette,states));
        }
        var input=TemplateFixtures.input(SiteKind.ENEMY_TOWER,new Bounds(-5,-33,-5,6,8,6));
        var c=new EnemyTower(new DecayField(0),TemplateFixtures.PALETTE).compile(input);
        return new Pinned(List.copyOf(tiles),c,terrain,compiled);
    }
}
