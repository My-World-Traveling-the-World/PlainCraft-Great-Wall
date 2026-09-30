package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.LongFunction;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class CurvedGateBoundaryTest {
    private static final Point3i WITNESS=new Point3i(223,84,-3);
    // Recorded from original native CURVED_SITES index 58, before the production repair.
    private static final Frame GATE=new Frame(new Point2q(64923,-120),1053829736,205826120,84,false);
    private static final ArcInterval CELL=new ArcInterval(192L*256,256L*256);
    private static final ArcInterval OLD_LEFT=new ArcInterval(192L*256,226L*256);
    private final WallSection wall=new WallSection(new DecayField(8),TemplateFixtures.PALETTE);

    @Test void legacyScalarCutReallyPublishesClearanceInsideTheFirstGateParapetColumn() {
        var input=curvedInput();
        assertEquals(new Point3i(-30,0,3),Rasterizer.localCell(GATE,WITNESS));
        var projected=WallSection.contract(input.stations(),223,-3);
        assertEquals(57754,projected.globalSQ8());
        assertTrue(projected.globalSQ8()<57856,"old scalar gate cut does not remove this column");
        var old=wall.compileIntervals(input,List.of(OLD_LEFT));
        var v=old.voxels().stream().filter(p->p.pos().equals(WITNESS)).findFirst().orElseThrow();
        assertEquals(IntentKind.OWNED_VOID,v.kind());assertTrue(v.required());assertEquals(900,v.priority());
        var gateSite=new SiteRecord(new SiteId("adjacency",SiteKind.GATE,200),256L*256,GATE,
                new Bounds(200,51,-40,320,88,40),SiteState.PLANNED,Optional.empty(),Optional.empty());
        var gate=new Gate(new DecayField(8),TemplateFixtures.PALETTE).compile(new ComponentInput(
                gateSite,input.stations(),input.supports(),input.decay(),input.family(),false,List.of(),List.of()));
        var solid=gate.voxels().stream().filter(p->p.pos().equals(WITNESS)).findFirst().orElseThrow();
        assertEquals(Part.PARAPET,solid.part());assertEquals(IntentKind.SOLID,solid.kind());
        assertEquals(700,solid.priority());assertNotEquals(v.component(),solid.component());
    }

    @Test void replacementUsesCompleteEndPlanesNotAConflictCoordinateWhitelist() {
        var input=curvedInput();
        var full=wall.compileIntervals(input,List.of(CELL));
        var fixed=wall.compileIntervals(input,List.of(CELL),List.of(OLD_LEFT),s->input.decay(),List.of(GATE));
        assertFalse(fixed.voxels().isEmpty());assertFalse(fixed.requiredRoutes().isEmpty());
        var expected=full.voxels().stream().filter(v->{
            int x=Rasterizer.localCell(GATE,v.pos()).x();return x< -30||x>=30;
        }).toList();
        assertEquals(expected,fixed.voxels(),"keep all original parts outside the rigid span, not just a clear path");
        assertTrue(full.voxels().size()-fixed.voxels().size()>100,"this is a whole-column replacement, not one erased conflict");
        assertFalse(fixed.voxels().stream().anyMatch(v->v.pos().equals(WITNESS)));
        assertEquals(full.id(),fixed.id());assertNotEquals(full.hash(),fixed.hash());
    }

    @Test void emptyReplacementInputAndStraightGatePreserveCanonicalWallBytes() {
        var base=TemplateFixtures.input(SiteKind.WALL,new Bounds(-32,-32,-5,32,3,5));
        long center=base.site().globalSQ8();
        var all=List.of(new ArcInterval(center-32L*256,center+32L*256));
        var outside=List.of(new ArcInterval(center-32L*256,center-30L*256),
                new ArcInterval(center+30L*256,center+32L*256));
        LongFunction<DecaySample> sample=s->base.decay();
        assertArrayEquals(CanonicalCodec.component(wall.compileIntervals(base,all,all,sample)),
                CanonicalCodec.component(wall.compileIntervals(base,all,all,sample,List.of())));
        assertArrayEquals(CanonicalCodec.component(wall.compileIntervals(base,outside,outside,sample)),
                CanonicalCodec.component(wall.compileIntervals(base,all,outside,sample,List.of(base.site().frame()))));
    }

    @Test void gateOrderCannotChangeCanonicalWallBytes() {
        var input=curvedInput();
        var far=new Frame(new Point2q(128L*256,0),1<<30,0,84,false);
        var a=wall.compileIntervals(input,List.of(CELL),List.of(OLD_LEFT),s->input.decay(),List.of(GATE,far));
        var b=wall.compileIntervals(input,List.of(CELL),List.of(OLD_LEFT),s->input.decay(),List.of(far,GATE));
        assertArrayEquals(CanonicalCodec.component(a),CanonicalCodec.component(b));
    }

    private static ComponentInput curvedInput() {
        var points=new ArrayList<Point2q>();
        for(int n=0;n<=128;n++)points.add(new Point2q(n*1024L,StrictMath.round(4*StrictMath.sin(n*StrictMath.PI/16)*256)));
        var lengths=ArcLength.accumulate(points);var stations=new ArrayList<Station>();
        for(int n=0;n<points.size();n++)stations.add(new Station(lengths.get(n),points.get(n),84,0));
        var frame=new Frame(new Point2q(224L*256,0),1<<30,0,84,false);
        var site=new SiteRecord(new SiteId("adjacency",SiteKind.WALL,3),224L*256,frame,
                new Bounds(150,51,-40,330,88,40),SiteState.PLANNED,Optional.empty(),Optional.empty());
        var supports=new HashMap<Point3i,ProbeResult>();
        for(int z=-40;z<40;z++)for(int x=150;x<330;x++)supports.put(new Point3i(x,84,z),
                new ProbeResult(ProbeKind.SUPPORTED,60,OptionalInt.empty(),OptionalInt.empty(),4,TemplateFixtures.HASH));
        return new ComponentInput(site,stations,supports,new DecaySample(DecayState.INTACT,0,20),
                PaletteFamily.STONE,false,List.of(),List.of());
    }
}
