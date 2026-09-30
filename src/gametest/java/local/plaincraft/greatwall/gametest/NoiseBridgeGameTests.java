package local.plaincraft.greatwall.gametest;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.mixin.NoiseGeneratorAccess;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.NoiseCursorBridge;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

public final class NoiseBridgeGameTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=12000)
    public void slicedFullHeightMatchesNativeStatesForFourSeedsAnd128Columns(GameTestHelper helper) {
        new Parity(helper).next();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void privateCursorRejectsReentryInvalidLimitsAndForeignLane(GameTestHelper helper) {
        var generator=generator(helper);
        var settings=generator.generatorSettings().value();
        var state=RandomState.create(settings,helper.getLevel().registryAccess().registryOrThrow(Registries.NOISE).asLookup(),1);
        var chunk=new NoiseChunk(1,state,-4,-4,settings.noiseSettings(),DensityFunctions.BeardifierMarker.INSTANCE,
                settings,((NoiseGeneratorAccess)(Object)generator).greatwall$fluidPicker().get(),Blender.empty());
        var bridge=(NoiseCursorBridge)(Object)chunk;
        bridge.greatwall$beginSlices();
        helper.assertTrue(rejects(bridge::greatwall$beginSlices),"second begin must fail");
        helper.assertTrue(rejects(()->bridge.greatwall$advanceSlices(0)),"zero points must fail");
        helper.assertTrue(rejects(()->bridge.greatwall$advanceSlices(257)),"limit above 256 must fail");
        CompletableFuture.supplyAsync(()->rejects(()->bridge.greatwall$advanceSlices(1)))
                .orTimeout(10,TimeUnit.SECONDS).whenComplete((rejected,error)->helper.getLevel().getServer().execute(()->{
                    try {
                        if(error!=null)helper.fail(error.toString());
                        helper.assertTrue(Boolean.TRUE.equals(rejected),"foreign lane must fail before touching native arrays");
                        while(!bridge.greatwall$advanceSlices(7)) {}
                        chunk.selectCellYZ(settings.noiseSettings().height()/settings.noiseSettings().getCellHeight()-1,0);
                        helper.succeed();
                    } finally {chunk.stopInterpolation();}
                }));
    }

    private static boolean rejects(Runnable action) {
        try {action.run();return false;}catch(IllegalStateException|IllegalArgumentException expected){return true;}
    }
    private static NoiseBasedChunkGenerator generator(GameTestHelper helper) {
        var registries=helper.getLevel().registryAccess();
        var preset=registries.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        return new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(preset),
                registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD));
    }

    /** Only a single column runs per GameTest tick; a seed's mutable reference state stays server-owner confined. */
    private static final class Parity {
        private static final List<Long> SEEDS=List.of(0L,1L,9282026L,Long.MIN_VALUE);
        private static final List<Integer> LIMITS=List.of(1,2,7,256);
        private final GameTestHelper helper;
        private final NoiseBasedChunkGenerator generator;
        private final NoiseGeneratorSettings settings;
        private final LevelHeightAccessor height;
        private final MessageDigest reference,sliced;
        private final HashMap<BlockState,byte[]> tokens=new HashMap<>();
        private RandomState state;
        private int seedIndex,columnIndex;
        private long blocks,maxConstructNanos,maxCellNanos,maxSlicePointNanos;
        private Parity(GameTestHelper helper) {
            this.helper=helper;generator=generator(helper);settings=generator.generatorSettings().value();
            var noise=settings.noiseSettings();
            height=LevelHeightAccessor.create(noise.minY(),noise.height());
            try {reference=MessageDigest.getInstance("SHA-256");sliced=MessageDigest.getInstance("SHA-256");}
            catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        }
        private void next() {
            if(seedIndex==SEEDS.size()) {
                String expected=HexFormat.of().formatHex(reference.digest()),actual=HexFormat.of().formatHex(sliced.digest());
                helper.assertTrue(expected.equals(actual),"independent native and sliced state digests differ");
                System.out.println("S1C_NOISE_PARITY seeds=4 columns=512 blocks="+blocks
                        +" minY="+height.getMinBuildHeight()+" maxYExclusive="+height.getMaxBuildHeight()
                        +" sha256="+actual+" maxConstructNanos="+maxConstructNanos
                        +" maxCellNanos="+maxCellNanos+" maxSlicePointNanos="+maxSlicePointNanos
                        +" scope=native-noise-only-not-worldgen-acceptance");
                helper.succeed();return;
            }
            if(columnIndex==0)state=RandomState.create(settings,
                    helper.getLevel().registryAccess().registryOrThrow(Registries.NOISE).asLookup(),SEEDS.get(seedIndex));
            int x,z;
            if(columnIndex<8) {
                int[] edge={-1_000_000,-17,-16,-1,0,1,16,1_000_000};
                x=edge[columnIndex];z=edge[7-columnIndex];
            } else {
                x=-4096+Math.floorMod(columnIndex*977,8192);
                z=-4096+Math.floorMod(columnIndex*619,8192);
            }
            compare(x,z);
            columnIndex++;
            if(columnIndex==128){seedIndex++;columnIndex=0;}
            helper.runAfterDelay(1,this::next);
        }
        private void compare(int x,int z) {
            var nativeColumn=generator.getBaseColumn(x,z,height,state); // Independent test reference only.
            var noise=settings.noiseSettings().clampToHeightAccessor(height);
            int width=noise.getCellWidth(),cellHeight=noise.getCellHeight();
            int bx=Math.floorDiv(x,width)*width,bz=Math.floorDiv(z,width)*width;
            long started=System.nanoTime();
            var chunk=new NoiseChunk(1,state,bx,bz,noise,DensityFunctions.BeardifierMarker.INSTANCE,settings,
                    ((NoiseGeneratorAccess)(Object)generator).greatwall$fluidPicker().get(),Blender.empty());
            maxConstructNanos=Math.max(maxConstructNanos,System.nanoTime()-started);
            var bridge=(NoiseCursorBridge)(Object)chunk;
            bridge.greatwall$beginSlices();
            try {
                int call=0;
                while(true) {
                    int limit=LIMITS.get(call++%4);
                    started=System.nanoTime();boolean done=bridge.greatwall$advanceSlices(limit);
                    if(limit==1)maxSlicePointNanos=Math.max(maxSlicePointNanos,System.nanoTime()-started);
                    if(done)break;
                }
                for(int cy=noise.height()/cellHeight-1;cy>=0;cy--) {
                    started=System.nanoTime();chunk.selectCellYZ(cy,0);
                    maxCellNanos=Math.max(maxCellNanos,System.nanoTime()-started);
                    for(int inner=cellHeight-1;inner>=0;inner--) {
                        int y=(Math.floorDiv(noise.minY(),cellHeight)+cy)*cellHeight+inner;
                        chunk.updateForY(y,(double)inner/cellHeight);
                        chunk.updateForX(x,(double)Math.floorMod(x,width)/width);
                        chunk.updateForZ(z,(double)Math.floorMod(z,width)/width);
                        var actual=bridge.greatwall$state();if(actual==null)actual=settings.defaultBlock();
                        var expected=nativeColumn.getBlock(y);
                        if(!expected.equals(actual))helper.fail("sliced state mismatch seed="+SEEDS.get(seedIndex)
                                +" column="+columnIndex+" xyz="+x+","+y+","+z+" native="+expected+" sliced="+actual);
                        reference.update(token(expected));sliced.update(token(actual));blocks++;
                    }
                }
            } finally {chunk.stopInterpolation();}
        }
        private byte[] token(BlockState state) {
            return tokens.computeIfAbsent(state,s->(BlockCatalog.token(s,false)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
