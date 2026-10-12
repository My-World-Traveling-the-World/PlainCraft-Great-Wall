package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import local.plaincraft.greatwall.diagnostics.CodecIO;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Immutable protection evidence. Native reads require the caller's completed FEATURES/owner lease. */
public record ProtectionSnapshot(List<Bounds> structures,Set<Point3i> blockEntities,
        Set<Point3i> immutableBlocks,String hash) {
    private static final Comparator<Bounds> BOX_ORDER=Comparator.comparingInt(Bounds::minY)
            .thenComparingInt(Bounds::minZ).thenComparingInt(Bounds::minX)
            .thenComparingInt(Bounds::maxYExclusive).thenComparingInt(Bounds::maxZExclusive)
            .thenComparingInt(Bounds::maxXExclusive);
    private static final Comparator<Point3i> POINT_ORDER=Comparator.comparingInt(Point3i::y)
            .thenComparingInt(Point3i::z).thenComparingInt(Point3i::x);

    public ProtectionSnapshot {
        Objects.requireNonNull(structures);Objects.requireNonNull(blockEntities);Objects.requireNonNull(immutableBlocks);
        CompiledAllocation.charge(512L+128L*(structures.size()+blockEntities.size()+immutableBlocks.size()));
        structures=structures.stream().distinct().sorted(BOX_ORDER).toList();
        blockEntities=Set.copyOf(blockEntities);immutableBlocks=Set.copyOf(immutableBlocks);
        if(!semanticHash(structures,blockEntities,immutableBlocks).equals(hash))
            throw new WallFailure(FailureCode.T0_CORRUPT,"protection snapshot hash mismatch");
    }

    public static ProtectionSnapshot freeze(List<Bounds> structures,Set<Point3i> entities,Set<Point3i> immutable) {
        return new ProtectionSnapshot(structures,entities,immutable,semanticHash(structures,entities,immutable));
    }

    public static ProtectionSnapshot capture(ChunkAccess chunk,List<Bounds> predictedStructures,BlockCatalog catalog) {
        requireFeatures(chunk);
        int x=chunk.getPos().getMinBlockX(),z=chunk.getPos().getMinBlockZ();
        Bounds read=new Bounds(x,chunk.getMinBuildHeight(),z,x+16,chunk.getMaxBuildHeight(),z+16);
        var boxes=structureBounds(chunk,predictedStructures,Map.of(chunk.getPos(),chunk),read);
        var entities=new HashSet<Point3i>();
        CompiledAllocation.charge(128L*chunk.getBlockEntitiesPos().size());
        for(var pos:chunk.getBlockEntitiesPos())entities.add(new Point3i(pos.getX(),pos.getY(),pos.getZ()));
        var immutable=new HashSet<Point3i>();
        var pos=new BlockPos.MutableBlockPos();
        for(int y=read.minY();y<read.maxYExclusive();y++)for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
            pos.set(x+dx,y,z+dz);
            if(catalog.flags(BlockCatalog.token(chunk.getBlockState(pos),false)).protectedBlock()) {
                CompiledAllocation.charge(128);
                immutable.add(new Point3i(pos.getX(),pos.getY(),pos.getZ()));
            }
        }
        return freeze(boxes,entities,immutable);
    }

    /**
     * Radius, in chunks, that the FEATURES lease must cover so every structure
     * reference can be resolved from lease content alone.
     *
     * <p>The S1 contract requires a cross-chunk reference to be resolved from the
     * finite STRUCTURE_STARTS/FEATURES lease content that PREPARE already
     * provided, never a synchronous getChunk. A reference points at the chunk
     * where a structure <em>starts</em>, which can be several chunks away from the
     * chunk it reaches into, so the lease must span at least the
     * STRUCTURE_REFERENCES write radius and not merely the FEATURES one. Measured
     * on seed 17: owner [-14,72] held references to [-14,76] (+4) and [-14,70]
     * (-2) while a FEATURES-only halo prepared just the 3x3 neighbourhood, so
     * every reference went unresolved and the whole read window was protected -
     * making the fort0 entrance 188/188 columns protected with zero terrain
     * faults. The design already permits radius 4 (9x9 = the 81-chunk lease cap),
     * so this stays inside the frozen bound.
     */
    public static int leaseRadiusChunks() {
        int features=net.minecraft.world.level.chunk.status.ChunkPyramid.GENERATION_PYRAMID
                .getStepTo(net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES).blockStateWriteRadius();
        int references=net.minecraft.world.level.chunk.status.ChunkPyramid.GENERATION_PYRAMID
                .getStepTo(net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_REFERENCES)
                .blockStateWriteRadius();
        int radius=Math.max(features,references);
        if(radius<0||radius>4)throw new WallFailure(FailureCode.UNSUPPORTED_ADAPTER,
                "structure-reference halo outside the frozen 9x9 lease bound: "+radius);
        return radius;
    }

    /**
     * Structure bounding boxes to protect.
     *
     * <p>Two sources: the planning-resolved {@code predicted} boxes (the corridor scan already ran
     * {@code Structure.generate} over every structure the wall can reach, so no cross-chunk
     * reference needs resolving here) and this owner's own {@code getAllStarts}. The historical
     * cross-chunk {@code getAllReferences} walk — which required the 81-chunk FEATURES lease and
     * was the source of the Chunky deadlock — is gone: an unresolved reference no longer protects
     * the whole read window, because the corridor scan already knew about that structure and put
     * its box in {@code predicted}.
     */
    public static List<Bounds> structureBounds(ChunkAccess owner,List<Bounds> predicted,
            Map<ChunkPos,ChunkAccess> prepared,Bounds conservativeReadBounds) {
        requireFeatures(owner);Objects.requireNonNull(predicted);Objects.requireNonNull(prepared);
        CompiledAllocation.charge(512L+128L*structureBoxUpperBound(owner,predicted.size(),prepared));
        var boxes=new ArrayList<>(predicted);
        for(var entry:owner.getAllStarts().entrySet()) {
            var start=entry.getValue();
            traceOrigin(owner,"OWNER_START",entry.getKey().getClass().getSimpleName(),owner.getPos(),start,conservativeReadBounds);
            append(start,boxes);
        }
        return boxes.stream().distinct().sorted(BOX_ORDER).toList();
    }

    private static void traceOrigin(ChunkAccess owner,String source,String structure,ChunkPos reference,
            StructureStart start,Bounds fallback) {
        if(!Boolean.getBoolean("greatwall.s1c.protectionTrace"))return;
        if(start!=null&&start.isValid())
            GreatWallMod.LOGGER.info("[GreatWall:protection-source] owner={} source={} structure={} reference={} actual={} pieces={}",
                    owner.getPos(),source,structure,reference,bounds(start.getBoundingBox()),start.getPieces().size());
        else if(!source.equals("OWNER_START"))
            GreatWallMod.LOGGER.info("[GreatWall:protection-source] owner={} source={} structure={} reference={} fallback={}",
                    owner.getPos(),source,structure,reference,fallback);
    }

    /** Count native piece views before the T0 allocation reservation; do not construct evidence here. */
    public static long structureBoxUpperBound(ChunkAccess owner,int predictedCount,Map<ChunkPos,ChunkAccess> prepared) {
        if(predictedCount<0)throw new IllegalArgumentException("negative predicted count");
        long count=predictedCount;
        for(var start:owner.getAllStarts().values())count=Math.addExact(count,boxCount(start));
        return count;
    }

    public boolean protectedAt(Point3i pos) {
        Objects.requireNonNull(pos);
        if(blockEntities.contains(pos)||immutableBlocks.contains(pos))return true;
        for(var box:structures)if(pos.x()>=box.minX()&&pos.x()<box.maxXExclusive()
                &&pos.y()>=box.minY()&&pos.y()<box.maxYExclusive()
                &&pos.z()>=box.minZ()&&pos.z()<box.maxZExclusive())return true;
        return false;
    }

    private static long boxCount(StructureStart start) {
        return start==null||!start.isValid()?0:1L+start.getPieces().size();
    }
    private static void append(StructureStart start,List<Bounds> boxes) {
        if(start==null||!start.isValid())return;
        boxes.add(bounds(start.getBoundingBox()));
        for(var piece:start.getPieces())boxes.add(bounds(piece.getBoundingBox()));
    }
    private static Bounds bounds(BoundingBox b) {
        return new Bounds(b.minX(),b.minY(),b.minZ(),Math.addExact(b.maxX(),1),Math.addExact(b.maxY(),1),Math.addExact(b.maxZ(),1));
    }
    private static void requireFeatures(ChunkAccess chunk) {
        Objects.requireNonNull(chunk);
        if(!chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES))
            throw new WallFailure(FailureCode.MISSING_FEATURES_PLAN,"protection captured before FEATURES completed");
    }
    private static String semanticHash(List<Bounds> boxes,Set<Point3i> entities,Set<Point3i> immutable) {
        CompiledAllocation.charge(128L*(boxes.size()+entities.size()+immutable.size())+512);
        var sorted=boxes.stream().distinct().sorted(BOX_ORDER).toList();
        var e=entities.stream().sorted(POINT_ORDER).toList();
        var i=immutable.stream().sorted(POINT_ORDER).toList();
        return CodecIO.hash(32<<20,out->{
            out.writeInt(1);out.writeInt(sorted.size());
            for(var b:sorted){out.writeInt(b.minX());out.writeInt(b.minY());out.writeInt(b.minZ());
                out.writeInt(b.maxXExclusive());out.writeInt(b.maxYExclusive());out.writeInt(b.maxZExclusive());}
            for(var set:List.of(e,i)){out.writeInt(set.size());for(var p:set){out.writeInt(p.x());out.writeInt(p.y());out.writeInt(p.z());}}
        });
    }
}
