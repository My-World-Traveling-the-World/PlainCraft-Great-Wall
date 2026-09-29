package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class ComponentAssemblyTest {
    private static final Bounds BOUNDS = new Bounds(-2,-32,-2,3,4,3);
    private static ComponentVoxels compile(ComponentInput input) {
        var a = new ComponentAssembly(input,BOUNDS,new DecayField(0),TemplateFixtures.PALETTE);
        a.foundation(-2,-2,3,3,-1);
        a.floor(-2,-2,3,3,0,Part.FLOOR,"east");
        a.route(new Point3i(-1,0,0),new Point3i(1,0,0));
        return a.finish();
    }
    @Test void wholeComponentNeedsEverySupportBeforeClipping() {
        var input = TemplateFixtures.input(SiteKind.ENEMY_TOWER,BOUNDS);
        var result = compile(input);
        assertFalse(result.voxels().isEmpty());
        var supports = new HashMap<>(input.supports()); supports.remove(new Point3i(2,84,2));
        assertTrue(compile(new ComponentInput(input.site(),input.stations(),supports,input.decay(),input.family(),
                false,input.waters(),input.materials())).voxels().isEmpty());
    }
    @Test void candidatesAndInputAreNotMutatedByRepeatedCompilation() {
        var input = TemplateFixtures.input(SiteKind.ENEMY_TOWER,BOUNDS);
        assertEquals(compile(input),compile(input));
        assertThrows(UnsupportedOperationException.class, () -> compile(input).voxels().clear());
        assertTrue(compile(input).voxels().stream().anyMatch(v -> v.kind() == IntentKind.OWNED_VOID));
    }
}
