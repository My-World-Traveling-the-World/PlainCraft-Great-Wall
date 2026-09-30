package local.plaincraft.greatwall.gametest;

import java.lang.reflect.Proxy;
import java.util.*;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.generation.*;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Analytic fixtures only. The native view never reads or loads a real neighboring chunk. */
final class ComponentFixtures {
    static final String H="a".repeat(64);
    static final PaletteBinding PALETTE=(i,p,l,w,s,h)->BlockCatalog.specification(switch(p){
        case FLOOR,SLAB->Blocks.STONE_BRICK_SLAB.defaultBlockState();
        case STAIR->Blocks.STONE_BRICK_STAIRS.defaultBlockState();
        case CAMPFIRE->Blocks.CAMPFIRE.defaultBlockState();case HAY->Blocks.HAY_BLOCK.defaultBlockState();
        case FENCE->Blocks.IRON_BARS.defaultBlockState();case LAMP->Blocks.LANTERN.defaultBlockState();
        case CHEST->Blocks.CHEST.defaultBlockState();case WATER->Blocks.WATER.defaultBlockState();
        case AIR->Blocks.AIR.defaultBlockState();default->Blocks.STONE_BRICKS.defaultBlockState();});
    static ComponentInput input(SiteKind kind,Bounds bounds,int x,int z,int angle,DecayState state){
        double r=angle*StrictMath.PI/180;
        var frame=new Frame(new Point2q(x*256L,z*256L),(int)StrictMath.round(StrictMath.cos(r)*(1L<<30)),
                (int)StrictMath.round(StrictMath.sin(r)*(1L<<30)),84,false);
        var b=Rasterizer.worldBounds(frame,bounds);
        var site=new SiteRecord(new SiteId("fixture",kind,1),1024*256,frame,b,SiteState.PLANNED,Optional.empty(),Optional.empty());
        var supports=new HashMap<Point3i,ProbeResult>();
        for(int zz=b.minZ();zz<b.maxZExclusive();zz++)for(int xx=b.minX();xx<b.maxXExclusive();xx++)
            supports.put(new Point3i(xx,84,zz),new ProbeResult(ProbeKind.SUPPORTED,64,OptionalInt.empty(),OptionalInt.empty(),0,H));
        return new ComponentInput(site,List.of(new Station(0,Projection.local(frame,-1024*256L,0),84,0),
                new Station(2048*256L,Projection.local(frame,1024*256L,0),84,0)),supports,
                new DecaySample(state,state==DecayState.INTACT?0:state==DecayState.PARTIAL?24000:54000,
                        state==DecayState.INTACT?20:state==DecayState.PARTIAL?15:2),PaletteFamily.STONE,false,List.of(),List.of());
    }
    static Map<ChunkKey,ChunkWritePlan> plans(ComponentVoxels c){
        return plans(c,64);
    }
    static Map<ChunkKey,ChunkWritePlan> plans(ComponentVoxels c,int ground){
        var cell=new RealizationCell(new CellKey(H,"minecraft:overworld",16),List.of(),List.of(c),H,0,H,H);
        var owners=new HashSet<ChunkKey>();var before=new HashMap<Point3i,String>();
        for(var v:c.voxels()){
            owners.add(new ChunkKey("minecraft:overworld",Math.floorDiv(v.pos().x(),16),Math.floorDiv(v.pos().z(),16)));
            before.put(v.pos(),BlockCatalog.token(v.pos().y()<ground?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),false));
        }
        var result=new HashMap<ChunkKey,ChunkWritePlan>();var compiler=new ChunkPlanCompiler();
        for(var key:owners)result.put(key,compiler.bindCoverage(compiler.compile(key,H,List.of(cell),before),H));
        return Map.copyOf(result);
    }
    static Map<Point3i,BlockState> states(ComponentVoxels c){
        return states(c,64);
    }
    static Map<Point3i,BlockState> states(ComponentVoxels c,int ground){
        var result=new HashMap<Point3i,BlockState>();plans(c,ground).values().forEach(p->p.voxels().forEach(v->result.put(v.pos(),BlockCatalog.resolveState(v.state()))));
        return Map.copyOf(result);
    }
    static LevelAccessor view(Map<Point3i,BlockState> states){
        return (LevelAccessor)Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(),new Class<?>[]{LevelAccessor.class},(proxy,method,args)->{
            return switch(method.getName()){
                case "getBlockState"->state(states,(BlockPos)args[0]);
                case "getFluidState"->state(states,(BlockPos)args[0]).getFluidState();
                case "getBlockEntity"->null;
                case "getMinBuildHeight"->-64;case "getMaxBuildHeight"->320;case "getHeight"->384;
                case "isClientSide"->false;case "isOutsideBuildHeight"->false;case "hasChunk","hasChunkAt"->true;
                case "scheduleTick"->null;
                case "toString"->"immutable-component-fixture";
                default->throw new UnsupportedOperationException("uncertified fixture world access: "+method.getName());
            };
        });
    }
    static BlockState state(Map<Point3i,BlockState> states,BlockPos p){
        return states.getOrDefault(new Point3i(p.getX(),p.getY(),p.getZ()),Blocks.AIR.defaultBlockState());
    }
    static final class CollisionView {
        private final Map<Point3i,List<AABB>> boxes;
        CollisionView(Map<Point3i,BlockState> states){
            var view=view(states);var map=new HashMap<Point3i,List<AABB>>();
            states.forEach((p,s)->{var pos=new BlockPos(p.x(),p.y(),p.z());
                var shape=s.getCollisionShape(view,pos);
                if(!shape.isEmpty())map.put(p,shape.toAabbs().stream().map(b->b.move(p.x(),p.y(),p.z())).toList());
            });boxes=Collections.unmodifiableMap(map);
        }
        boolean collision(AABB box){
            for(int y=(int)Math.floor(box.minY);y<=Math.floor(box.maxY);y++)
                for(int z=(int)Math.floor(box.minZ);z<=Math.floor(box.maxZ);z++)
                    for(int x=(int)Math.floor(box.minX);x<=Math.floor(box.maxX);x++)
                        for(var b:boxes.getOrDefault(new Point3i(x,y,z),List.of()))if(b.intersects(box))return true;
            return false;
        }
        private AABB body(Foot f){double x=f.x2()/2.0+.5,y=f.y2()/2.0,z=f.z2()/2.0+.5;
            return new AABB(x-.299,y+.0001,z-.299,x+.299,y+1.7999,z+.299);}
        private boolean standable(Foot f){var b=body(f);return !collision(b)&&collision(new AABB(b.minX,f.y2()/2.0-.01,b.minZ,b.maxX,f.y2()/2.0,b.maxZ));}
        Set<Foot> reachable(Point3i start,Bounds bounds){
            var origin=new Foot(start.x()*2,start.y()*2,start.z()*2);
            if(!standable(origin))return Set.of();
            var queue=new ArrayDeque<Foot>();var seen=new HashSet<Foot>();queue.add(origin);seen.add(origin);
            while(!queue.isEmpty()){
                var f=queue.removeFirst();
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(int dy=-2;dy<=2;dy++){
                    var n=new Foot(f.x2()+d[0],f.y2()+dy,f.z2()+d[1]);
                    if(n.x2()<bounds.minX()*2||n.x2()>=bounds.maxXExclusive()*2||n.z2()<bounds.minZ()*2||n.z2()>=bounds.maxZExclusive()*2
                            ||n.y2()<bounds.minY()*2||n.y2()>=bounds.maxYExclusive()*2||seen.contains(n)||!standable(n))continue;
                    if(collision(body(new Foot(f.x2(),Math.max(f.y2(),n.y2()),f.z2()))))continue;
                    seen.add(n);queue.addLast(n);
                }
            }
            return Set.copyOf(seen);
        }
    }
    record Foot(int x2,int y2,int z2){}
    private ComponentFixtures(){}
}
