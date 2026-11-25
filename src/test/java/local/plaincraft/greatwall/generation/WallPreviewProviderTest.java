package local.plaincraft.greatwall.generation;

import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.shiroha233.roadweaver.api.outgoing.WorldgenPreviewApi;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 权威冻结体素包含显式空气和负坐标，查询不得偷偷恢复磁盘计划。 */
class WallPreviewProviderTest {
    private static final String HASH="a".repeat(64);
    private static final ChunkKey KEY=new ChunkKey("minecraft:overworld",-1,-2);
    private static ChunkWritePlan plan(boolean air) {
        var state=air?new local.plaincraft.greatwall.model.WallTypes.BlockSpec("minecraft:air",Map.of()):new local.plaincraft.greatwall.model.WallTypes.BlockSpec("minecraft:stone_brick_stairs",Map.of("half","bottom","facing","north"));
        var voxel=new VoxelIntent(new Point3i(-1,-60,-17),air?IntentKind.OWNED_VOID:IntentKind.SOLID,Part.CORE,state,new SiteId("w",SiteKind.WALL,0),0,false,true);
        return new ChunkWritePlan(KEY,HASH,HASH,List.of(voxel),List.of(),List.of(),List.of(),List.of(),List.of(),HASH);
    }
    @Test void projectionKeepsPositionPropertiesAndExplicitAir() {
        var solid=WallPreviewProvider.convert(plan(false));
        assertEquals(WorldgenPreviewApi.Fidelity.BLOCKS,solid.fidelity());
        assertEquals(List.of(new WorldgenPreviewApi.Run(15,15,-60,-59,"minecraft:stone_brick_stairs[facing=north,half=bottom]")),solid.runs());
        assertEquals("minecraft:air",WallPreviewProvider.convert(plan(true)).runs().getFirst().blockState());
    }
    @Test void sessionReusesProjectionButPublicationInvalidatesIt() {
        var session=new WallWorldgenPlanCache.Session();
        assertEquals(WorldgenPreviewApi.State.UNAVAILABLE,session.preview(KEY).state());
        var solid=plan(false);session.put(KEY,solid);
        var first=session.preview(KEY);assertEquals(1,first.runs().size());assertSame(first,session.preview(KEY));
        session.put(KEY,plan(true));var next=session.preview(KEY);
        assertNotSame(first,next);assertEquals("minecraft:air",next.runs().getFirst().blockState());
    }
}
