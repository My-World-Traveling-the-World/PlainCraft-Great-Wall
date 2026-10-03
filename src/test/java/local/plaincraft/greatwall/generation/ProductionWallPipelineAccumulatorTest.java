package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.TerrainSnapshotService.CapturedTerrain;
import local.plaincraft.greatwall.terrain.TerrainTile;
import org.junit.jupiter.api.Test;

class ProductionWallPipelineAccumulatorTest {
    private static final String H="a".repeat(64);

    @Test void emptyCellAndIndependentCellsDoNotShareMutableMaps() throws Exception {
        var first=new Accumulator();var second=new Accumulator();
        assertNotSame(first.tiles(),second.tiles());assertNotSame(first.metadata(),second.metadata());
        first.add(capture(0,"first"));
        var empty=second.freeze();
        assertTrue(empty.tiles().isEmpty());assertTrue(empty.metadata().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->empty.tiles().putAll(first.freeze().tiles()));
    }

    @Test void fiftySevenOwnersAndDuplicateKeysMatchTheLegacyOrderedPutAllFold() throws Exception {
        var accumulated=new Accumulator();
        var tiles=accumulated.tiles();var metadata=accumulated.metadata();
        var legacy=new CapturedTerrain(Map.of(),Map.of());
        for(int owner=0;owner<57;owner++){
            var one=capture(owner,"first");legacy=legacyMerge(legacy,one);accumulated.add(one);
            assertSame(tiles,accumulated.tiles(),"no replacement of the cumulative tile map");
            assertSame(metadata,accumulated.metadata(),"no replacement of the cumulative metadata map");
        }
        var replacement=capture(13,"last");
        legacy=legacyMerge(legacy,replacement);accumulated.add(replacement);
        var frozen=accumulated.freeze();
        assertEquals(legacy.tiles(),frozen.tiles());assertEquals(legacy.metadata(),frozen.metadata());
        var tile=replacement.tiles().values().iterator().next();
        var evidence=replacement.metadata().values().iterator().next();
        assertSame(tile,frozen.tiles().get(tile.key()),"last tile wins without copying its payload");
        assertSame(evidence,frozen.metadata().get(evidence.coverage().chunk()),"last metadata wins intact");
        assertThrows(UnsupportedOperationException.class,()->frozen.tiles().clear());
        assertThrows(UnsupportedOperationException.class,()->frozen.metadata().clear());
        assertEquals(1,accumulated.freezes.get());
    }

    @Test void sourceMapsAndLaterAccumulatorMutationCannotAliasTheFrozenOutput() throws Exception {
        var original=capture(0,"original");var replacement=capture(0,"replacement");
        var tiles=new HashMap<>(original.tiles());var metadata=new HashMap<>(original.metadata());
        var input=new CapturedTerrain(tiles,metadata);var accumulated=new Accumulator();
        accumulated.add(input);
        tiles.clear();metadata.clear();
        var frozen=accumulated.freeze();
        assertEquals(original,input);assertEquals(original,frozen);
        accumulated.add(replacement);
        assertEquals(original,input);assertEquals(original,frozen);
        assertNotSame(accumulated.tiles(),frozen.tiles());assertNotSame(accumulated.metadata(),frozen.metadata());
    }

    @Test void serialFutureHandoffsPublishMergesAndDoNotFreezeAFailedPrefix() throws Exception {
        for(boolean failSecond:List.of(false,true)){
            try(var worker=Executors.newSingleThreadExecutor();var producer=Executors.newSingleThreadExecutor()){
                var accumulated=new Accumulator();var starts=new AtomicInteger();
                var sources=new ArrayList<CompletableFuture<CapturedTerrain>>();
                var entered=new ArrayList<CompletableFuture<Void>>();
                var captures=List.of(capture(0,"first"),capture(0,"last"),capture(1,"next"));
                CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
                for(int n=0;n<captures.size();n++){
                    var source=new CompletableFuture<CapturedTerrain>();sources.add(source);
                    var entry=new CompletableFuture<Void>();entered.add(entry);
                    chain=chain.thenComposeAsync(ignored->{
                        starts.incrementAndGet();
                        var merged=source.thenAccept(accumulated::add);entry.complete(null);return merged;
                    },worker);
                }
                var result=chain.thenApplyAsync(ignored->accumulated.freeze(),worker);
                awaitValue(entered.getFirst());
                assertEquals(1,starts.get());assertFalse(entered.get(1).isDone());
                awaitValue(CompletableFuture.runAsync(()->sources.getFirst().complete(captures.getFirst()),producer));
                awaitValue(entered.get(1));
                assertEquals(2,starts.get());assertFalse(entered.get(2).isDone());
                if(failSecond){
                    var failure=new IllegalStateException("second capture failed");
                    awaitValue(CompletableFuture.runAsync(()->sources.get(1).completeExceptionally(failure),producer));
                    assertSame(failure,assertThrows(ExecutionException.class,()->awaitValue(result)).getCause());
                    assertEquals(2,starts.get());assertFalse(entered.get(2).isDone());
                    assertEquals(0,accumulated.freezes.get(),"failed prefix is never published");
                }else{
                    awaitValue(CompletableFuture.runAsync(()->sources.get(1).complete(captures.get(1)),producer));
                    awaitValue(entered.get(2));
                    awaitValue(CompletableFuture.runAsync(()->sources.get(2).complete(captures.get(2)),producer));
                    var frozen=awaitValue(result);
                    var legacy=new CapturedTerrain(Map.of(),Map.of());
                    for(var one:captures)legacy=legacyMerge(legacy,one);
                    assertEquals(legacy,frozen);assertEquals(1,accumulated.freezes.get());
                }
                assertEquals(awaitValue(CompletableFuture.supplyAsync(()->Thread.currentThread().threadId(),producer)).longValue(),
                        accumulated.mergeThread.get());
            }
        }
    }

    private static <T> T awaitValue(CompletableFuture<T> future) throws InterruptedException,ExecutionException {
        var done=new CountDownLatch(1);
        var value=new AtomicReference<T>();var failure=new AtomicReference<Throwable>();
        future.whenComplete((result,error)->{value.set(result);failure.set(error);done.countDown();});
        assertTrue(done.await(5,TimeUnit.SECONDS),"future observation must complete within five seconds");
        var error=failure.get();
        if(error!=null){
            if(error instanceof CompletionException&&error.getCause()!=null)error=error.getCause();
            throw new ExecutionException(error);
        }
        return value.get();
    }

    private static CapturedTerrain legacyMerge(CapturedTerrain prior,CapturedTerrain one) {
        var tiles=new HashMap<>(prior.tiles());tiles.putAll(one.tiles());
        var metadata=new HashMap<>(prior.metadata());metadata.putAll(one.metadata());
        return new CapturedTerrain(tiles,metadata);
    }
    private static CapturedTerrain capture(int x,String token) {
        var owner=new ChunkKey("minecraft:overworld",x,-3);
        var key=new TerrainTile.Key(owner.dimension(),x,-3,6000,6000);
        var tile=new TerrainTile(key,H,List.of(token),new int[key.volume()]);
        var coverage=new Coverage(owner,List.of(5888),new Bounds(x*16,6000,-48,x*16+16,6001,-32),H);
        var point=new Point3i(x*16,6000,-48);
        var metadata=new CaptureMetadata(coverage,List.of(new ColumnEvidence(point.x(),point.z(),6000,false)),
                List.of(coverage.readBounds()),Set.of(point),Set.of(point),H);
        return new CapturedTerrain(Map.of(key,tile),Map.of(owner,metadata));
    }

    // Reflection keeps the production accumulator private rather than adding a test-facing API.
    private static final class Accumulator {
        private final Object value;
        private final Method add,freeze;
        private final Field tiles,metadata;
        private final AtomicInteger freezes=new AtomicInteger();
        private final AtomicLong mergeThread=new AtomicLong();
        private Accumulator() throws ReflectiveOperationException {
            var type=Class.forName("local.plaincraft.greatwall.generation.ProductionWallPipeline$CellCaptureAccumulator");
            var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);value=constructor.newInstance();
            add=type.getDeclaredMethod("add",CapturedTerrain.class);add.setAccessible(true);
            freeze=type.getDeclaredMethod("freeze");freeze.setAccessible(true);
            tiles=type.getDeclaredField("tiles");tiles.setAccessible(true);
            metadata=type.getDeclaredField("metadata");metadata.setAccessible(true);
        }
        private void add(CapturedTerrain captured) {
            try{add.invoke(value,captured);mergeThread.set(Thread.currentThread().threadId());}
            catch(ReflectiveOperationException error){throw new AssertionError(error);}
        }
        private CapturedTerrain freeze() {
            try{var result=(CapturedTerrain)freeze.invoke(value);freezes.incrementAndGet();return result;}
            catch(ReflectiveOperationException error){throw new AssertionError(error);}
        }
        private Object tiles() throws IllegalAccessException{return tiles.get(value);}
        private Object metadata() throws IllegalAccessException{return metadata.get(value);}
    }
}
