package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 玩法身份与美术体素分开组装，换真实楼体后仍保留驻军、烽火和容器收据。 */
final class ImportedBuildingGameplay {
    private ImportedBuildingGameplay() {}

    static ComponentVoxels attach(ComponentVoxels body) {
        var entities = new ArrayList<BlockEntityIntent>();
        var states = new HashMap<Point3i, BlockSpec>();
        for (var v : body.voxels()) states.put(v.pos(), v.state());
        int ordinal = 0;
        for (var entity : body.blockEntities()) {
            if (body.id().kind() == SiteKind.BEACON && entity.type().equals("minecraft:campfire")) {
                var fields = new HashMap<>(entity.fields());
                fields.put("beaconWall", body.id().wallId());
                fields.put("beaconOrdinal", Long.toString(body.id().ordinal()));
                // 装饰的局部 ID 参与战利品收据，不能为了玩法编号而改掉。
                fields.put("firepitOrdinal", Integer.toString(ordinal++));
                var state = states.get(entity.pos());
                fields.put("lit", state.properties().getOrDefault("lit", "false"));
                fields.put("waterlogged", state.properties().getOrDefault("waterlogged", "false"));
                entity = new BlockEntityIntent(entity.pos(), entity.type(), entity.site(), entity.localId(), fields);
            }
            entities.add(entity);
        }
        var slots = new ArrayList<SpawnSlot>();
        if (body.id().kind() == SiteKind.ENEMY_TOWER) {
            var route = body.requiredRoutes().getFirst();
            if (route.size() < 7) throw new IllegalArgumentException("敌楼通路不足以容纳三个独立驻军点");
            // 通路已用原版碰撞形状认证；选楼内三个点，不能照搬旧楼相对坐标刷进墙里。
            for (int i = 0; i < 3; i++)
                slots.add(new SpawnSlot(body.id(), i, route.get(route.size() / 2 + (i - 1) * 2), "minecraft:pillager"));
        }
        return ComponentAssembly.hashed(body.id(), body.bounds(), body.voxels(), entities, slots, body.requiredRoutes());
    }
}
