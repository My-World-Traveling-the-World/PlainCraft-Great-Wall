package local.plaincraft.greatwall.generation;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Permanent loss of ownership, observed only on already loaded server chunks. */
public final class OwnershipMutations {
    private record Authorization(ChunkKey owner,long revision) {}
    private static final ThreadLocal<ArrayDeque<Authorization>> WRITES=new ThreadLocal<>();
    private static final ConcurrentHashMap<Thread,Boolean> SERVER_THREADS=new ConcurrentHashMap<>();
    private OwnershipMutations() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server->SERVER_THREADS.put(Thread.currentThread(),true));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{
            SERVER_THREADS.remove(Thread.currentThread());WRITES.remove();
        });
    }

    public static AutoCloseable beginOwnedWrite(ChunkKey owner,long revision) {
        Objects.requireNonNull(owner);
        if(revision<0)throw new IllegalArgumentException("negative write revision");
        if(!SERVER_THREADS.containsKey(Thread.currentThread()))throw new IllegalStateException("owned write requires server owner");
        var stack=WRITES.get();
        if(stack==null){stack=new ArrayDeque<>();WRITES.set(stack);}
        var token=new Authorization(owner,revision);stack.push(token);
        var thread=Thread.currentThread();var scope=stack;
        return new AutoCloseable() {
            private boolean closed;
            @Override public void close() {
                if(closed)return;
                if(Thread.currentThread()!=thread||scope.peek()!=token)
                    throw new IllegalStateException("owned write must close on owner thread in stack order");
                closed=true;scope.pop();if(scope.isEmpty())WRITES.remove();
            }
        };
    }

    public static void changed(LevelChunk chunk,BlockPos pos,BlockState before,BlockState after) {
        Objects.requireNonNull(chunk);Objects.requireNonNull(pos);Objects.requireNonNull(before);Objects.requireNonNull(after);
        if(before.equals(after)||!(chunk.getLevel() instanceof ServerLevel level))return;
        if(!level.getServer().isSameThread())throw new IllegalStateException("ownership mutation requires server owner");
        var p=new Point3i(pos.getX(),pos.getY(),pos.getZ());
        if(Math.floorDiv(p.x(),16)!=chunk.getPos().x||Math.floorDiv(p.z(),16)!=chunk.getPos().z)
            throw new IllegalArgumentException("foreign mutation coordinate");
        var mask=chunk.getAttached(TerrainMarkers.OWNERSHIP);
        if(mask==null||!mask.mask().contains(p))return;
        if(mask.firepits().contains(p)&&firepitToggle(before,after))return;
        var stack=WRITES.get();var auth=stack==null?null:stack.peek();
        var key=new ChunkKey(level.dimension().location().toString(),chunk.getPos().x,chunk.getPos().z);
        if(auth!=null&&auth.owner().equals(key)&&auth.revision()==mask.revision())return;
        var existing=chunk.getAttached(TerrainMarkers.TOMBSTONES);
        if(existing!=null&&existing.contains(p))return;
        var points=new HashSet<Point3i>();
        if(existing!=null)points.addAll(existing);
        points.add(p);chunk.setAttached(TerrainMarkers.TOMBSTONES,java.util.Set.copyOf(points));chunk.setUnsaved(true);
    }

    public static boolean wasChanged(LevelChunk chunk,Point3i pos) {
        Objects.requireNonNull(chunk);Objects.requireNonNull(pos);
        if(!(chunk.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread())
            throw new IllegalStateException("tombstone query requires server owner");
        var points=chunk.getAttached(TerrainMarkers.TOMBSTONES);
        return points!=null&&points.contains(pos);
    }

    private static boolean firepitToggle(BlockState before,BlockState after) {
        if(!(before.getBlock() instanceof CampfireBlock)||before.getBlock()!=after.getBlock())return false;
        for(var property:before.getProperties()) {
            if(property==CampfireBlock.LIT||property==CampfireBlock.WATERLOGGED)continue;
            if(!Objects.equals(before.getValue(property),after.getValue(property)))return false;
        }
        return true;
    }
}
