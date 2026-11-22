package local.plaincraft.greatwall.component;

import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImportedLanternTemplateTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void fourRealTemplateCellsAcceptRuntimeLanternEntitiesWithoutChangingLayout() {
        var sites = Map.of("chenghai_tower", 208111, "enemy_tower", 38592, "beacon", 10939, "gate", 29207);
        var runtimeEntity = new ImportedWallTemplate.Entity("test:swinging_lantern", "{}");
        for (var site : sites.entrySet()) {
            // 离线只替换整合包的实体工厂，模板和原版状态仍走生产读取链。
            var added = ImportedWallTemplate.read(site.getKey(), state -> state.getValue(LanternBlock.HANGING)
                    ? runtimeEntity : null);
            var cell = added.cells().stream().filter(c -> c.localId() == site.getValue()).findFirst().orElseThrow();
            assertEquals("minecraft:lantern", added.state(cell, Rotation.NONE).id());
            assertEquals("true", added.state(cell, Rotation.CLOCKWISE_90).properties().get("hanging"));
            assertEquals(runtimeEntity, cell.entity());
            var vanilla = ImportedWallTemplate.read(site.getKey(), state -> null);
            assertEquals(vanilla.cells().size(), added.cells().size());
            assertNull(vanilla.cells().stream().filter(c -> c.localId() == site.getValue()).findFirst().orElseThrow().entity());
        }
    }
}
