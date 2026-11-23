package local.plaincraft.greatwall.lifecycle;

import java.util.Map;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 生产默认与历史读取分开验证，不能靠手工创建 v2 夹具冒充真正启用。 */
class ProductionRecipeVersionsTest {
    @Test void newWorldUsesRealTemplatesAndLegacyDigestRemainsIdentical() throws Exception {
        assertEquals(3, ProductionRecipeVersions.newWorld().templateVersion());
        assertEquals(1, WorldBootstrap.defaultConfig().templateVersion());
        assertEquals(WorldRecipeGuard.fingerprint(Map.of("templateVersion", new JsonPrimitive(1))),
                ProductionRecipeVersions.templatesHash(1));
        assertNotEquals(ProductionRecipeVersions.templatesHash(1), ProductionRecipeVersions.templatesHash(2));
        assertEquals(64, ProductionRecipeVersions.templatesHash(2).length());
        assertNotEquals(ProductionRecipeVersions.templatesHash(2), ProductionRecipeVersions.templatesHash(3));
    }
}
