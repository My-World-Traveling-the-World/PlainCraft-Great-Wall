package local.plaincraft.greatwall.generation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;

/** Pure whole-component resolution followed by owner clipping; no live-world reads. */
public final class ChunkPlanCompiler {
    /** How many required air clearances lost to a neighbouring component's body. */
    private static final java.util.concurrent.atomic.AtomicInteger VOID_OVERLAPS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final Comparator<Point3i> POS=Comparator.comparingInt(Point3i::y).thenComparingInt(Point3i::z).thenComparingInt(Point3i::x);
    private static final Comparator<SiteId> SITE=Comparator.comparing(SiteId::wallId).thenComparing(s->s.kind().name()).thenComparingLong(SiteId::ordinal);
    public ChunkPlanCompiler() {}
    public ChunkWritePlan compile(ChunkKey owner,String recipeHash,List<RealizationCell> cells,Map<Point3i,String> beforeStates) {
        Objects.requireNonNull(owner);Objects.requireNonNull(beforeStates);
        long candidates=0,owned=0;
        for(var cell:cells)for(var c:cell.components())for(var voxel:c.voxels()) {
            candidates++;
            if(owns(owner,voxel.pos())&&voxel.kind()!=IntentKind.KEEP)owned++;
        }
        CompiledAllocation.charge(Math.addExact(4096L,Math.addExact(Math.multiplyExact(candidates,128L),Math.multiplyExact(owned,512L))));
        return compileOwner(owner, recipeHash, beforeStates, resolve(owner.dimension(), recipeHash, cells, false));
    }

    /** 仅供启动窗口内复用；常规入口仍为每次调用独立记账。 */
    public Window window() { return new Window(); }

    public final class Window {
        // 同一批中邻接 owner 先后使用同组，只保留最近一组；旧组只在仍执行的 worker 手里。
        private Entry recent;
        private long resolutions;

        /** 对比实际解析次数和 owner 数，确认窗口确实省下重复工作。 */
        public synchronized long resolutions() { return resolutions; }

        public ChunkWritePlan compile(ChunkKey owner, String recipeHash, List<RealizationCell> cells,
                Map<Point3i,String> beforeStates) {
            Entry entry;
            boolean prepare;
            synchronized (this) {
                prepare = recent == null || !recent.matches(owner.dimension(), recipeHash, cells);
                if (prepare) {
                    recent = new Entry(owner.dimension(), recipeHash, List.copyOf(cells));
                    resolutions++;
                }
                entry = recent;
            }
            // 在锁外解析，避免一组的大墙体阻塞其他组。等待者只复用同一组的结果。
            if (prepare) {
                try { entry.result.complete(resolve(entry.dimension, entry.recipeHash, entry.cells, true)); }
                catch (Throwable failure) { entry.result.completeExceptionally(failure); }
            }
            Resolved resolved;
            try { resolved = entry.result.join(); }
            catch (java.util.concurrent.CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw failure;
            }
            return compileOwner(owner, recipeHash, beforeStates, resolved);
        }

        private final class Entry {
            final String dimension, recipeHash;
            final List<RealizationCell> cells;
            final java.util.concurrent.CompletableFuture<Resolved> result = new java.util.concurrent.CompletableFuture<>();

            Entry(String dimension, String recipeHash, List<RealizationCell> cells) {
                this.dimension = dimension; this.recipeHash = recipeHash; this.cells = cells;
            }

            boolean matches(String dimension, String recipeHash, List<RealizationCell> cells) {
                if (!this.dimension.equals(dimension) || !this.recipeHash.equals(recipeHash)
                        || this.cells.size() != cells.size()) return false;
                // 不能信任输入宣称的哈希；不同实例仍要独立做完整内容校验。
                for (int i = 0; i < cells.size(); i++) if (this.cells.get(i) != cells.get(i)) return false;
                return true;
            }
        }
    }

    private record Resolved(Map<SiteId, ComponentVoxels> components, Map<Point3i, VoxelIntent> voxels,
            Map<ChunkKey, List<VoxelIntent>> byOwner, Map<Point3i, BlockEntityIntent> entities,
            TreeSet<String> inputs, TreeSet<String> t0, long revision) { }

    private static Resolved resolve(String dimension, String recipeHash, List<RealizationCell> cells, boolean indexOwners) {
        var components=new TreeMap<SiteId,ComponentVoxels>(SITE);
        var inputs=new TreeSet<String>();var t0=new TreeSet<String>();long revision=0;
        for(var cell:cells) {
            if(!cell.key().dimension().equals(dimension)||!cell.key().recipeHash().equals(recipeHash))
                throw failure("foreign realization cell");
            inputs.add(cell.hash()+":"+cell.gateRevision()+":"+cell.protectionHash());
            t0.add(cell.t0Hash()); revision=Math.max(revision,cell.gateRevision());
            for(var c:cell.components()) {
                if(!c.hash().equals(CanonicalCodec.componentHash(c))) throw failure("component hash mismatch");
                var old=components.putIfAbsent(c.id(),c);
                if(old!=null&&!old.hash().equals(c.hash())) throw failure("same component has two realizations");
            }
        }
        var logical=new TreeMap<Point3i,VoxelIntent>(POS);
        for(var c:components.values()) for(var v:c.voxels()) logical.merge(v.pos(),v,ChunkPlanCompiler::winner);
        var authored=new java.util.HashSet<SiteId>();
        for(var c:components.values())if(c.blockEntities().stream().anyMatch(e->e.fields().containsKey(PlannedBlockEntityNbt.DECORATION)))
            authored.add(c.id());
        var resolved=new TreeMap<Point3i,VoxelIntent>(POS);
        // 实物楼梯的内外拐角来自作者存档，不能按裁切后的邻居重新塑形。
        for(var v:logical.values()) resolved.put(v.pos(),authored.contains(v.component())?v:connections(v,logical));
        // 归属裁决后只配对一次，所有 owner 共用结果，证明和写入不再各判一遍。
        var entities=new TreeMap<Point3i,BlockEntityIntent>(POS);
        for(var c:components.values()) for(var e:c.blockEntities()) {
            var v=resolved.get(e.pos());
            if(matchesEntity(v,e)) {
                var old=entities.putIfAbsent(e.pos(),e);
                if(old!=null&&!old.equals(e))throw failure("conflicting block entity intent");
            }
        }
        // 围栏、台阶、脚点仍看完整构件，只对最终遍历分桶，并保留原坐标顺序。
        Map<ChunkKey, List<VoxelIntent>> byOwner = indexOwners ? new HashMap<>() : null;
        if (indexOwners) for (var v : resolved.values()) {
            if (v.kind() == IntentKind.KEEP) continue;
            var key = new ChunkKey(dimension, Math.floorDiv(v.pos().x(), 16), Math.floorDiv(v.pos().z(), 16));
            byOwner.computeIfAbsent(key, unused -> new ArrayList<>()).add(v);
        }
        return new Resolved(components, resolved, byOwner, entities, inputs, t0, revision);
    }

    private static ChunkWritePlan compileOwner(ChunkKey owner, String recipeHash, Map<Point3i, String> beforeStates,
            Resolved input) {
        var components = input.components();
        var resolved = input.voxels();
        var voxels=new ArrayList<VoxelIntent>();var proofs=new ArrayList<BlockProof>();
        var entities=new ArrayList<BlockEntityIntent>();
        var post=new TreeSet<Point3i>(POS);var fluid=new TreeSet<Point3i>(POS);var feet=new TreeSet<Point3i>(POS);
        var ownerVoxels = input.byOwner() == null ? resolved.values() : input.byOwner().getOrDefault(owner, List.of());
        for(var v:ownerVoxels) {
            if(!owns(owner,v.pos())||v.kind()==IntentKind.KEEP) continue;
            String before=beforeStates.get(v.pos());
            if(before==null) throw new WallFailure(FailureCode.T0_MISSING,"missing before-state "+v.pos());
            boolean be=before.endsWith(":be=true");
            if(be) throw failure("foreign block entity in write set");
            boolean thaw=v.component().kind()==SiteKind.WATER_GATE&&v.part()==Part.WATER&&v.kind()==IntentKind.FLUID
                    &&before.startsWith("minecraft:ice{")&&!be;
            if((v.kind()==IntentKind.OWNED_VOID||v.kind()==IntentKind.FLUID)&&!replaceableVoid(before)&&!thaw)
                // Which component wants this air, which of its parts, and what it would have to carve: a
                // bare position cannot tell "the arch ran into a mountain" from "a channel hit another
                // mod's block", and those two call for opposite handling. The code stays
                // OWNERSHIP_CONFLICT because it is one of eighty call sites' shared vocabulary; the parts
                // below are what makes this instance decidable.
                throw failure("negative space cannot carve natural/foreign solid: "+v.pos()
                        +" kind="+v.kind()+" part="+v.part()+" component="+v.component()+" before="+before);
            // 跟随 owner 的体素顺序收集实体，既不扫其他区块，也保持原有坐标排序。
            var entity=input.entities().get(v.pos());
            if(entity!=null)entities.add(entity);
            String after=token(v.state(),entity!=null);
            voxels.add(v);proofs.add(new BlockProof(v.pos(),before,after,be,v.component()));
            if(v.state().id().endsWith("_stairs")||v.state().id().endsWith("_slab")||v.part()==Part.FENCE) post.add(v.pos());
            if(v.kind()==IntentKind.FLUID||"true".equals(v.state().properties().get("waterlogged"))) fluid.add(v.pos());
            Point3i f=new Point3i(v.pos().x(),Math.addExact(v.pos().y(),1),v.pos().z());
            if(v.walkable()&&!solid(resolved.get(f))&&!solid(resolved.get(new Point3i(f.x(),f.y()+1,f.z())))) feet.add(f);
        }
        var slots=new ArrayList<SpawnSlot>();var states=new TreeMap<SiteId,DecayState>(SITE);
        for(var c:components.values()) {
            boolean intersects=c.voxels().stream().anyMatch(v->owns(owner,v.pos()));
            if(intersects) states.put(c.id(),c.realizedState());
            for(var s:c.spawnSlots()) if(owns(owner,s.feet())&&feet.contains(s.feet())) slots.add(s);
        }
        slots.sort(Comparator.comparing(SpawnSlot::site,SITE).thenComparingInt(SpawnSlot::slot));
        String inputHash=hash(recipeHash+"\n"+owner+"\n"+String.join("\n",input.inputs()));
        var metadata=new WriteMetadata(hash(String.join("\n",input.t0())),input.revision(),slots,states);
        var plan=new ChunkWritePlan(owner,recipeHash,inputHash,voxels,proofs,entities,
                List.copyOf(post),List.copyOf(fluid),List.copyOf(feet),metadata,"0".repeat(64));
        return rehash(plan);
    }
    private static boolean matchesEntity(VoxelIntent voxel, BlockEntityIntent entity) {
        if (voxel == null || !voxel.component().equals(entity.site())) return false;
        if (!entity.fields().containsKey(PlannedBlockEntityNbt.DECORATION)) {
            // 保留已冻结的旧构件配对规则，不能因导入新模板改变旧计划摘要。
            return voxel.state().id().equals(entity.type());
        }
        // 方块名与实体类型并非同一个注册表；例如红色旗帜的实体类型是 banner。
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE;
        var type = registry.getOptional(net.minecraft.resources.ResourceLocation.parse(entity.type()));
        if (voxel.kind()!=IntentKind.SOLID || type.isEmpty()
                || !type.get().isValid(local.plaincraft.greatwall.terrain.BlockCatalog.resolveState(voxel.state()))) {
            throw failure("模板方块实体与胜出的方块不兼容: " + entity.pos() + " " + entity.type());
        }
        return true;
    }
    /** Coordinator replaces the cell-input digest with this owner's durable coverage digest. */
    public ChunkWritePlan bindCoverage(ChunkWritePlan plan,String coverageHash) {
        var m=plan.metadata();
        return rehash(new ChunkWritePlan(plan.owner(),plan.recipeHash(),plan.inputHash(),plan.voxels(),plan.proofs(),plan.blockEntities(),
                plan.postprocess(),plan.fluidTicks(),plan.walkwayFeet(),new WriteMetadata(coverageHash,m.gateRevision(),m.spawnSlots(),m.realizedStates()),"0".repeat(64)));
    }
    public static String token(local.plaincraft.greatwall.model.WallTypes.BlockSpec state) {
        return token(state,false);
    }
    public static String token(local.plaincraft.greatwall.model.WallTypes.BlockSpec state,boolean entity) {
        String fluid=state.id().equals("minecraft:water")||"true".equals(state.properties().get("waterlogged"))?"minecraft:water":"minecraft:empty";
        return state.id()+new TreeMap<>(state.properties()).toString()+":fluid="+fluid+":be="+entity;
    }
    private static ChunkWritePlan rehash(ChunkWritePlan p) {
        return new ChunkWritePlan(p.owner(),p.recipeHash(),p.inputHash(),p.voxels(),p.proofs(),p.blockEntities(),p.postprocess(),
                p.fluidTicks(),p.walkwayFeet(),p.metadata(),CanonicalCodec.writePlanHash(p));
    }
    private static VoxelIntent winner(VoxelIntent a,VoxelIntent b) {
        if(a.equals(b))return a;
        if(a.kind()==IntentKind.OWNED_VOID&&b.kind()==IntentKind.OWNED_VOID)
            return a.priority()!=b.priority()?(a.priority()>b.priority()?a:b):(SITE.compare(a.component(),b.component())<=0?a:b);
        if(a.kind()==IntentKind.OWNED_VOID||b.kind()==IntentKind.OWNED_VOID) {
            var empty=a.kind()==IntentKind.OWNED_VOID?a:b;var other=empty==a?b:a;
            if(!empty.component().equals(other.component()) && !(empty.component().wallId().equals(other.component().wallId())
                    && other.component().kind()==SiteKind.WALL)) {
                // Two sites' footprints overlapping used to kill the whole owner: the corridor's
                // air clearance of one met the body of the other and the conflict was a hard error.
                // It is a boundary between neighbouring components, not a corruption: the solid
                // keeps the voxel, the air clearance is dropped, and the collision is logged.
                if(empty.required()){
                    int n=VOID_OVERLAPS.incrementAndGet();
                    if(n<=4||n%512==0){
                        local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                                "[GreatWall:S1C] VOID_OVERLAP_DROPPED pos={} empty={} other={} count={}",
                                empty.pos(),empty.component(),other.component(),n);
                    }
                }
                return other;
            }
        }
        if(a.priority()!=b.priority())return a.priority()>b.priority()?a:b;
        int id=SITE.compare(a.component(),b.component());
        if(id!=0)return id<0?a:b;
        if(!a.equals(b))throw failure("ambiguous equal-priority same-site voxel");
        return a;
    }
    private static VoxelIntent connections(VoxelIntent v,Map<Point3i,VoxelIntent> logical) {
        if(v.state().id().endsWith("_stairs"))return stairShape(v,logical);
        if(v.part()!=Part.FENCE)return v;
        var properties=new TreeMap<>(v.state().properties());properties.putIfAbsent("waterlogged","false");
        String[] names={"north","east","south","west"};int[][] sides={{0,-1},{1,0},{0,1},{-1,0}};
        for(int i=0;i<4;i++) {
            var n=logical.get(new Point3i(v.pos().x()+sides[i][0],v.pos().y(),v.pos().z()+sides[i][1]));
            boolean connected=solid(n)&&(n.part()==Part.FENCE||switch(n.part()){case FOUNDATION,CORE,SKIN,ARCH,ROOF,HAY->true;default->false;});
            properties.put(names[i],Boolean.toString(connected));
        }
        return new VoxelIntent(v.pos(),v.kind(),v.part(),new local.plaincraft.greatwall.model.WallTypes.BlockSpec(v.state().id(),properties),v.component(),v.priority(),v.required(),v.walkable());
    }
    private static VoxelIntent stairShape(VoxelIntent v,Map<Point3i,VoxelIntent> logical) {
        String[] directions={"north","east","south","west"};int[][] offsets={{0,-1},{1,0},{0,1},{-1,0}};
        String facing=v.state().properties().get("facing"),half=v.state().properties().get("half");
        int index=List.of(directions).indexOf(facing);
        if(index<0||half==null)throw failure("stairs lack frozen facing/half");
        String shape="straight";
        for(int pass=0;pass<2;pass++){
            int side=pass==0?index:(index+2)%4;
            var neighbor=logical.get(new Point3i(v.pos().x()+offsets[side][0],v.pos().y(),v.pos().z()+offsets[side][1]));
            if(neighbor==null||!neighbor.state().id().endsWith("_stairs")||!half.equals(neighbor.state().properties().get("half")))continue;
            int next=List.of(directions).indexOf(neighbor.state().properties().get("facing"));
            if(next<0||next%2==index%2)continue;
            int check=pass==0?(next+2)%4:next;
            var corner=logical.get(new Point3i(v.pos().x()+offsets[check][0],v.pos().y(),v.pos().z()+offsets[check][1]));
            if(corner!=null&&corner.state().id().endsWith("_stairs")&&facing.equals(corner.state().properties().get("facing"))
                    &&half.equals(corner.state().properties().get("half")))continue;
            shape=(pass==0?"outer_":"inner_")+(next==(index+3)%4?"left":"right");break;
        }
        var properties=new TreeMap<>(v.state().properties());properties.put("shape",shape);
        return new VoxelIntent(v.pos(),v.kind(),v.part(),new local.plaincraft.greatwall.model.WallTypes.BlockSpec(v.state().id(),properties),v.component(),v.priority(),v.required(),v.walkable());
    }
    private static boolean solid(VoxelIntent v){return v!=null&&v.kind()==IntentKind.SOLID;}
    private static boolean owns(ChunkKey key,Point3i p){return Math.floorDiv(p.x(),16)==key.x()&&Math.floorDiv(p.z(),16)==key.z();}
    private static boolean replaceableVoid(String token) {
        int end=token.indexOf('{');String id=end<0?token:token.substring(0,end);
        return switch(id){case "minecraft:air","minecraft:cave_air","minecraft:void_air","minecraft:water","minecraft:short_grass",
                "minecraft:tall_grass","minecraft:fern","minecraft:large_fern","minecraft:dead_bush","minecraft:snow"->true;default->false;};
    }
    private static String hash(String value){return DeterministicHash.sha256(value.getBytes(StandardCharsets.UTF_8));}
    private static WallFailure failure(String message){return new WallFailure(FailureCode.OWNERSHIP_CONFLICT,message);}
}
