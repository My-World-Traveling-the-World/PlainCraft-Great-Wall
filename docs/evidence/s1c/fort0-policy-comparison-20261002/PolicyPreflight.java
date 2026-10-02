import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.TerrainTile;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/** Counterfactual analysis only; never changes tags, T0, recipe, or a world. */
public final class PolicyPreflight {
    private static final Set<String> ADDED_PLANTS=Set.of("minecraft:sweet_berry_bush",
            "minecraft:brown_mushroom","minecraft:pumpkin");
    private static final Set<String> ADDED=Set.of("minecraft:mossy_cobblestone",
            "minecraft:sweet_berry_bush","minecraft:brown_mushroom","minecraft:pumpkin");
    private static final Map<String,Set<String>> TAGS=new HashMap<>();
    private static final Map<String,BlockState> STATES=new HashMap<>();
    private static Set<String> support,plants,replaceable,protectedIds,logs,leaves;
    private static boolean mossySupports=true;

    private static Set<String> tag(String id)throws IOException {
        if(TAGS.containsKey(id))return TAGS.get(id);
        int colon=id.indexOf(':');
        String path="data/"+id.substring(0,colon)+"/tags/block/"+id.substring(colon+1)+".json";
        var result=new TreeSet<String>();
        try(var in=PolicyPreflight.class.getClassLoader().getResourceAsStream(path)){
            if(in==null)throw new IOException("missing tag resource "+path);
            var json=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var entry:json.getAsJsonArray("values")){
                String name=entry.isJsonPrimitive()?entry.getAsString():entry.getAsJsonObject().get("id").getAsString();
                if(name.startsWith("#"))result.addAll(tag(name.substring(1)));else result.add(name);
            }
        }
        TAGS.put(id,Set.copyOf(result));return TAGS.get(id);
    }
    private static String id(String token){return token.substring(0,token.indexOf('{'));}
    private static BlockState state(String token){
        return STATES.computeIfAbsent(token,t->{
            var properties=new TreeMap<String,String>();
            String values=t.substring(t.indexOf('{')+1,t.indexOf('}'));
            if(!values.isEmpty())for(String pair:values.split(", ")){
                int eq=pair.indexOf('=');properties.put(pair.substring(0,eq),pair.substring(eq+1));
            }
            var value=BlockCatalog.resolveState(new BlockSpec(id(t),properties));
            if(!BlockCatalog.token(value,t.endsWith(":be=true")).equals(t))
                throw new IllegalArgumentException("state did not roundtrip: "+t);
            return value;
        });
    }
    private static BlockCatalog.Flags flags(String token,boolean proposed){
        var s=state(token);String name=id(token);
        boolean gravity=s.getBlock() instanceof FallingBlock;
        boolean explicit=protectedIds.contains(name)||s.hasBlockEntity()||token.endsWith(":be=true")
                ||!name.startsWith("minecraft:")||s.getFluidState().getType()==Fluids.LAVA
                ||s.getFluidState().getType()==Fluids.FLOWING_LAVA;
        boolean plant=!explicit&&(s.isAir()||(plants.contains(name)||proposed&&ADDED_PLANTS.contains(name))
                &&s.getFluidState().isEmpty()&&!gravity&&!s.is(Blocks.POWDER_SNOW)
                &&!logs.contains(name)&&!leaves.contains(name)
                &&(!s.is(Blocks.SNOW)||s.getValue(SnowLayerBlock.LAYERS)<8));
        boolean natural=support.contains(name)||proposed&&mossySupports&&name.equals("minecraft:mossy_cobblestone");
        boolean naturalReplaceable=replaceable.contains(name)
                ||proposed&&!mossySupports&&name.equals("minecraft:mossy_cobblestone");
        boolean load=natural&&!explicit&&!gravity&&s.getFluidState().isEmpty()
                &&!s.is(Blocks.POWDER_SNOW)&&!logs.contains(name)&&!leaves.contains(name)
                &&s.isFaceSturdy(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,Direction.UP);
        // Bootstrap alone does not bind fluid tags; this vanilla-only model uses exact native fluid IDs.
        boolean water=s.getFluidState().getType()==Fluids.WATER||s.getFluidState().getType()==Fluids.FLOWING_WATER;
        return new BlockCatalog.Flags(load,plant,water,gravity,s.hasBlockEntity()||token.endsWith(":be=true"),
                explicit||!natural&&!naturalReplaceable&&!plant&&!water&&!s.is(Blocks.ICE));
    }
    private static Map<String,Object> summary(ProbeResult p){
        return Map.of("kind",p.kind(),"groundTopY",p.groundTopY(),"drop",p.drop(),
                "waterTopY",p.waterTopY().isPresent()?p.waterTopY().getAsInt():"absent",
                "thinIceY",p.thinIceY().isPresent()?p.thinIceY().getAsInt():"absent");
    }
    private static final class View implements SurfaceProbe.ColumnView {
        final int x,z;final CaptureMetadata metadata;final Map<Integer,TerrainTile> pages;
        final ColumnEvidence summary;final boolean proposed;
        View(int x,int z,CaptureMetadata m,Map<Integer,TerrainTile> pages,boolean proposed){
            this.x=x;this.z=z;metadata=m;this.pages=pages;this.proposed=proposed;
            summary=m.columns().stream().filter(c->c.x()==x&&c.z()==z).findFirst().orElseThrow();
        }
        public String state(int y){
            var page=Objects.requireNonNull(pages.get(Math.floorDiv(y,128)*128),"missing T0 page");
            return page.state(Math.floorMod(x,16),y,Math.floorMod(z,16));
        }
        public boolean solidSupport(int y){return flags(state(y),proposed).support();}
        public boolean water(int y){return flags(state(y),proposed).water();}
        public boolean airOrReplaceablePlant(int y){return flags(state(y),proposed).replaceablePlant();}
        public int highestNonPlantFeet(){return summary.upperCensored()?Integer.MAX_VALUE:summary.highestNonPlantFeet();}
        public String hash(){return metadata.hash();}
        public boolean protectedAt(int y){
            var p=new Point3i(x,y,z);String token=state(y);
            // Hypothetical fresh capture removes only old catalog marks on the four proposed IDs.
            boolean immutable=metadata.immutableBlocks().contains(p)
                    &&!(proposed&&ADDED.contains(id(token))&&!flags(token,true).protectedBlock());
            return flags(token,proposed).protectedBlock()||token.startsWith("minecraft:ice{")&&!naturalThinIce(y)
                    ||metadata.blockEntities().contains(p)||immutable
                    ||metadata.protectedStructures().stream().anyMatch(b->x>=b.minX()&&x<b.maxXExclusive()
                            &&z>=b.minZ()&&z<b.maxZExclusive()&&y>=b.minY()&&y<b.maxYExclusive());
        }
        public boolean naturalThinIce(int y){
            if(y<=metadata.coverage().readBounds().minY()||y+1>=metadata.coverage().readBounds().maxYExclusive())return false;
            var p=new Point3i(x,y,z);
            return state(y).startsWith("minecraft:ice{")&&flags(state(y-1),proposed).water()
                    &&flags(state(y+1),proposed).replaceablePlant()&&!metadata.blockEntities().contains(p)
                    &&!metadata.immutableBlocks().contains(p)&&metadata.protectedStructures().stream().noneMatch(b->
                    x>=b.minX()&&x<b.maxXExclusive()&&z>=b.minZ()&&z<b.maxZExclusive()&&y>=b.minY()&&y<b.maxYExclusive());
        }
    }
    public static void main(String[] args)throws Exception{
        if(args.length<2||args.length>3)throw new IllegalArgumentException("isolatedRun outputReport [replace-only]");
        if(args.length==3){
            if(!args[2].equals("replace-only"))throw new IllegalArgumentException("unknown counterfactual");
            mossySupports=false;
        }
        Path allowed=Path.of("build/run/serverSmoke").toRealPath(),run=Path.of(args[0]).toRealPath();
        if(!run.startsWith(allowed)||!Files.isRegularFile(run.resolve("greatwall_test_world"))
                ||!Files.isRegularFile(run.resolve("greatwall_s1c_fixture")))throw new IllegalArgumentException("unmarked fixture");
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        support=tag("plaincraft_great_wall:foundation_support");
        plants=tag("plaincraft_great_wall:replaceable_vegetation");
        replaceable=tag("plaincraft_great_wall:natural_replaceable");
        protectedIds=tag("plaincraft_great_wall:protected");
        logs=tag("minecraft:logs");leaves=tag("minecraft:leaves");
        var properties=new TreeMap<String,Object>();
        for(String name:ADDED){
            String token=BlockCatalog.token(BlockCatalog.resolveState(new BlockSpec(name,Map.of())),false);
            var s=state(token);
            properties.put(name,Map.of("current",flags(token,false),"proposed",flags(token,true),
                    "nativeBlockClass",s.getBlock().getClass().getName(),
                    "nativeTopFaceSturdy",s.isFaceSturdy(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,Direction.UP),
                    "nativeCollisionFullBlock",s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO)));
        }
        properties.put("minecraft:magma_block",Map.of("current",flags(BlockCatalog.token(Blocks.MAGMA_BLOCK.defaultBlockState(),false),false),
                "proposed",flags(BlockCatalog.token(Blocks.MAGMA_BLOCK.defaultBlockState(),false),true)));
        Path wall=run.resolve("world/greatwall");
        var recipe=new WorldManifest(wall.resolve("manifest")).read().orElseThrow();
        var plan=new SegmentCacheStore(wall.resolve("cache"),recipe).readPlan(
                new SegmentKey(recipe.recipeHash(),recipe.dimension(),0)).orElseThrow();
        var fort=plan.sites().stream().filter(s->s.id().kind()==SiteKind.FORT&&s.id().ordinal()==0).findFirst().orElseThrow();
        var frame=fort.frame();
        var bounds=Rasterizer.worldBounds(frame,new Bounds(-24,-32,-24,24,8,24));
        var store=ChunkEvidenceStore.production(wall.resolve("evidence"));
        var kinds=new TreeMap<String,Integer>();var entranceKinds=new TreeMap<String,Integer>();
        var rows=new ArrayList<Map<String,Object>>();var feet=new TreeMap<Integer,Integer>();
        var residualTokens=new TreeMap<String,Integer>();var currentKinds=new TreeMap<String,Integer>();
        var residualPositions=new ArrayList<Map<String,Object>>();
        var mossyTopTriples=new ArrayList<Point3i>();int entranceColumns=0;
        for(var owner:Rasterizer.owners(recipe.dimension(),bounds)){
            var m=store.readCoverage(owner).orElseThrow();var pages=new HashMap<Integer,TerrainTile>();
            for(int base:m.coverage().tileBases()){
                var key=new TerrainTile.Key(recipe.dimension(),owner.x(),owner.z(),Math.max(base,recipe.bounds().minY()),
                        Math.min(base+127,recipe.bounds().maxYExclusive()-1));
                pages.put(base,store.read(key,recipe.recipeHash()).orElseThrow());
            }
            for(int z=Math.max(bounds.minZ(),owner.z()*16);z<Math.min(bounds.maxZExclusive(),owner.z()*16+16);z++)
                for(int x=Math.max(bounds.minX(),owner.x()*16);x<Math.min(bounds.maxXExclusive(),owner.x()*16+16);x++){
                    var old=new View(x,z,m,pages,false);var view=new View(x,z,m,pages,true);
                    var actual=new SurfaceProbe().probe(old,frame.walkY(),recipe.bounds());
                    var result=new SurfaceProbe().probe(view,frame.walkY(),recipe.bounds());
                    currentKinds.merge(actual.kind().name(),1,Integer::sum);kinds.merge(result.kind().name(),1,Integer::sum);
                    if(result.kind()==ProbeKind.PROTECTED){
                        for(int y=frame.walkY()+6;y>=frame.walkY()-35;y--)if(view.protectedAt(y)){
                            residualTokens.merge(view.state(y),1,Integer::sum);
                            var local=Rasterizer.localCell(frame,new Point3i(x,y,z));
                            residualPositions.add(Map.of("pos",new Point3i(x,y,z),"local",local,
                                    "insideFortLocalFootprint",local.x()>=-24&&local.x()<24&&local.z()>=-24&&local.z()<24,
                                    "token",view.state(y)));
                            break;
                        }
                    }
                    var local=Rasterizer.localCell(frame,new Point3i(x,frame.walkY(),z));
                    if(local.x()>=-2&&local.x()<2&&local.z()>=-24&&local.z()<24){
                        entranceColumns++;entranceKinds.merge(result.kind().name(),1,Integer::sum);
                        var triple=List.of(view.state(result.groundTopY()-1),view.state(result.groundTopY()-2),
                                view.state(result.groundTopY()-3));
                        rows.add(Map.of("x",x,"z",z,"local",local,"current",summary(actual),"proposed",summary(result),"threeLayers",triple));
                        if(result.kind()==ProbeKind.SUPPORTED){
                            feet.merge(result.groundTopY(),1,Integer::sum);
                            if(triple.stream().anyMatch(t->id(t).equals("minecraft:mossy_cobblestone")))
                                mossyTopTriples.add(new Point3i(x,result.groundTopY(),z));
                        }
                    }
                }
        }
        int min=feet.firstKey(),max=feet.lastKey();
        var report=new TreeMap<String,Object>();
        report.put("scope","COUNTERFACTUAL diagnostic using unchanged SurfaceProbe, original T0 and modeled fresh tag classification; NOT real run or APPLY acceptance");
        report.put("proposedMossyPolicy",mossySupports?"foundation_support":"natural_replaceable_only");
        report.put("modelCaveats",List.of("native tag sets expanded from current local resources, not a live world's loaded tag binding",
                "old immutable marks removed only in an in-memory hypothetical new capture; old durable T0 remains unchanged",
                "original height upper-censor summary retained; existing captured-column thin-ice formula retained",
                "bootstrap fluid tags are unbound: vanilla water/lava use exact native fluid identities",
                "mossy collision property does not prove feature origin or player provenance"));
        report.put("recipe",recipe.recipeHash());report.put("frame",frame);report.put("bounds",bounds);
        report.put("nativeProperties",properties);report.put("currentKinds",currentKinds);report.put("proposedKinds",kinds);
        report.put("residualProtectedFirstTokens",residualTokens);report.put("entranceColumns",entranceColumns);
        report.put("residualProtectedPositions",residualPositions);
        report.put("entranceKinds",entranceKinds);report.put("groundTopYDistribution",feet);
        report.put("minGroundTopY",min);report.put("maxGroundTopY",max);report.put("groundSpread",max-min);
        report.put("rampGroundContractAccepts",entranceKinds.size()==1&&entranceKinds.containsKey("SUPPORTED")
                &&max-min<=1&&max>=frame.walkY()-32&&max<frame.walkY());
        report.put("mossyInThreeLayerEvidence",mossyTopTriples);report.put("entranceRows",rows);
        try(var out=Files.newBufferedWriter(Path.of(args[1]),StandardCharsets.UTF_8)){
            new GsonBuilder().setPrettyPrinting().create().toJson(report,out);
        }
        System.out.println("POLICY_PREFLIGHT entrance="+entranceKinds+" ground="+feet+" residual="+residualTokens+" spread="+(max-min));
        if(!currentKinds.equals(Map.of("SUPPORTED",3344,"PROTECTED",256))||entranceColumns!=190)
            throw new IllegalStateException("baseline failed to reproduce archived original facts: "+currentKinds);
    }
}
