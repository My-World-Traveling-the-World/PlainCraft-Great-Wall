package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 五件实物四向的全部取证列对拍，不用少量中心点代替最终施工占地。 */
class ImportedProjectionParityTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @ParameterizedTest @EnumSource(Asset.class)
    void everyActualEvidenceColumnMatchesOriginal(Asset asset) {
        for(int orientation=0;orientation<4;orientation++) {
            var f=ImportedProductionPlanTest.fixture(asset,orientation);
            var reference=new FrozenImportedRibbonProjection(f.plan().stations().stations(),List.of(f.site()));
            var indexed=new ImportedRibbonProjection(f.plan().stations().stations(),List.of(f.site()));
            assertFalse(f.columns().isEmpty());
            for(var p:f.columns().keySet()) assertEquals(reference.contract(p.x(),p.z()), indexed.contract(p.x(),p.z()),
                    "asset="+asset+" orientation="+orientation+" column="+p);
        }
    }
}
