package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class FortFootprintCoordinatesTest {
    private static final Bounds LOCAL=new Bounds(-24,-32,-24,24,8,24);

    @Test void translatedAndRotatedFootprintUsesWorldKeysRatherThanLocalCoordinates() throws Exception {
        for(int angle:new int[]{0,15,30,45,60,90}){
            var input=TemplateFixtures.input(SiteKind.FORT,LOCAL,206,953,angle,
                    PaletteFamily.STONE,DecayState.INTACT,0);
            assertTrue(input.supports().keySet().stream().noneMatch(p->p.x()>=-24&&p.x()<24&&p.z()>=-24&&p.z()<24),
                    "retained old local/world-key counterexample");
            assertTrue(check(input),"complete translated world footprint, angle="+angle);
        }
    }

    @Test void everyActualFootprintColumnRemainsMandatoryAndProtectionCannotBeSkipped() throws Exception {
        var base=TemplateFixtures.input(SiteKind.FORT,LOCAL,-401,733,45,
                PaletteFamily.STONE,DecayState.INTACT,0);
        var inside=base.supports().keySet().stream().filter(p->inside(base,p)).findFirst().orElseThrow();
        var missing=new HashMap<>(base.supports());missing.remove(inside);
        assertFalse(check(with(base,missing)),"a missing actual world column must reject");
        for(var kind:new ProbeKind[]{ProbeKind.PROTECTED,ProbeKind.NO_SUPPORT,ProbeKind.UPPER_CENSORED}){
            var changed=new HashMap<>(base.supports());
            changed.put(inside,new ProbeResult(kind,64,OptionalInt.empty(),OptionalInt.empty(),0,TemplateFixtures.HASH));
            assertFalse(check(with(base,changed)),"unsafe actual world column: "+kind);
        }
        for(var probe:new ProbeResult[]{
                new ProbeResult(ProbeKind.SUPPORTED,51,OptionalInt.empty(),OptionalInt.empty(),13,TemplateFixtures.HASH),
                new ProbeResult(ProbeKind.SUPPORTED,84,OptionalInt.empty(),OptionalInt.empty(),0,TemplateFixtures.HASH)}){
            var changed=new HashMap<>(base.supports());changed.put(inside,probe);
            assertFalse(check(with(base,changed)),"drop13 or terrain at walk height is not supported");
        }
    }

    @Test void boundingBoxGuardColumnsAreNotMistakenForTheRotatedFortFootprint() throws Exception {
        var base=TemplateFixtures.input(SiteKind.FORT,LOCAL,206,953,30,
                PaletteFamily.STONE,DecayState.INTACT,0);
        var changed=new HashMap<>(base.supports());
        changed.keySet().removeIf(p->!inside(base,p));
        assertTrue(changed.size()<base.supports().size(),"rotation must leave unused read-envelope columns");
        assertTrue(check(with(base,changed)),"retain the full fort check without requiring exterior guards");
    }

    private static boolean inside(ComponentInput input,Point3i p){
        var local=Rasterizer.localCell(input.site().frame(),p);
        return local.x()>=-24&&local.x()<24&&local.z()>=-24&&local.z()<24;
    }
    private static ComponentInput with(ComponentInput input,Map<Point3i,ProbeResult> supports){
        return new ComponentInput(input.site(),input.stations(),supports,input.decay(),input.family(),
                input.redSandstone(),input.waters(),input.materials());
    }
    private static boolean check(ComponentInput input)throws Exception{
        Method method=Fort.class.getDeclaredMethod("footprintSupported",ComponentInput.class,int.class,int.class);
        method.setAccessible(true);
        return (boolean)method.invoke(null,input,input.site().frame().walkY(),input.site().frame().walkY()-20);
    }
}
