package local.plaincraft.greatwall.component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.gameplay.TemplateLoot;
import local.plaincraft.greatwall.model.WallTypes.BlockEntityIntent;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Rotation;

/** 冻结已核验的实心模板和装饰；净空、地基及接墙规则由构件规划单独决定。 */
public final class ImportedWallTemplate {
    public static final String DECORATION = "templateDecoration";
    private static final String DIRECTORY = "/data/plaincraft_great_wall/wall_templates/v2/";
    private final Point3i size;
    private final List<Cell> cells;
    private final List<List<BlockSpec>> palettes;

    public enum Asset {
        GATE("gate"), ENEMY_TOWER("enemy_tower"), BEACON("beacon"),
        CORNER_TOWER("corner_tower"), CHENGHAI_TOWER("chenghai_tower");

        private final String name;
        private volatile ImportedWallTemplate loaded;

        Asset(String name) { this.name = name; }

        public ImportedWallTemplate template() {
            var current = loaded;
            if (current != null) return current;
            synchronized (this) {
                // 只首次解压；跨区块与多个站点共用不可变体素和调色板。
                if (loaded == null) loaded = read(name);
                return loaded;
            }
        }
    }

    public record Entity(String type, String decoration) {}
    public record Cell(Point3i local, int state, int localId, Entity entity) {}

    private ImportedWallTemplate(Point3i size, List<Cell> cells, List<List<BlockSpec>> palettes) {
        this.size = size;
        this.cells = List.copyOf(cells);
        this.palettes = List.copyOf(palettes);
    }

    public Point3i size() { return size; }
    public List<Cell> cells() { return cells; }
    public BlockSpec state(Cell cell, Rotation rotation) { return palettes.get(rotation.ordinal()).get(cell.state()); }

    public BlockEntityIntent entityIntent(Cell cell, Point3i world, long worldSeed, SiteId site) {
        if (cell.entity() == null) throw new IllegalArgumentException("模板格没有方块实体: " + cell.local());
        var fields = new HashMap<>(TemplateLoot.fields(state(cell, Rotation.NONE).id(), cell.local().y(),
                worldSeed, site, cell.localId()));
        // 即使实体没有可复制的装饰也标记其来源，不能退回样板库存或程序化规则。
        fields.put(DECORATION, cell.entity().decoration());
        return new BlockEntityIntent(world, cell.entity().type(), site, cell.localId(), fields);
    }

    public static Point3i rotateOffset(Point3i p, Rotation rotation) {
        // 正交整数变换与原版方块朝向共用同一个旋转，避免角楼楼梯、门与墙端错位。
        return switch (rotation) {
            case NONE -> p;
            case CLOCKWISE_90 -> new Point3i(-p.z(), p.y(), p.x());
            case CLOCKWISE_180 -> new Point3i(-p.x(), p.y(), -p.z());
            case COUNTERCLOCKWISE_90 -> new Point3i(p.z(), p.y(), -p.x());
        };
    }

    private static ImportedWallTemplate read(String name) {
        try (var in = ImportedWallTemplate.class.getResourceAsStream(DIRECTORY + name + ".nbt")) {
            if (in == null) throw new IOException("缺少内置模板资源");
            var root = NbtIo.readCompressed(in, NbtAccounter.create(96L * 1024 * 1024));
            var dimensions = root.getList("size", Tag.TAG_INT);
            if (root.getInt("DataVersion") != 3955 || dimensions.size() != 3
                    || !root.getList("entities", Tag.TAG_COMPOUND).isEmpty()) {
                throw new IOException("不支持的模板版本或实体格式");
            }
            var size = new Point3i(dimensions.getInt(0), dimensions.getInt(1), dimensions.getInt(2));
            long volume = (long) size.x() * size.y() * size.z();
            if (size.x() <= 0 || size.y() <= 0 || size.z() <= 0 || volume > 500_000) {
                throw new IOException("模板尺寸超出单件预算: " + size);
            }
            var palette = root.getList("palette", Tag.TAG_COMPOUND);
            var nativeStates = new ArrayList<net.minecraft.world.level.block.state.BlockState>(palette.size());
            for (int i = 0; i < palette.size(); i++) {
                var entry = palette.getCompound(i);
                var properties = entry.getCompound("Properties");
                var spec = new HashMap<String, String>();
                for (String key : properties.getAllKeys()) spec.put(key, properties.getString(key));
                nativeStates.add(BlockCatalog.resolveState(new BlockSpec(entry.getString("Name"), spec)));
            }
            var rotations = new ArrayList<List<BlockSpec>>();
            for (Rotation rotation : Rotation.values()) {
                rotations.add(nativeStates.stream().map(state -> BlockCatalog.specification(state.rotate(rotation))).toList());
            }
            var blocks = root.getList("blocks", Tag.TAG_COMPOUND);
            if (blocks.isEmpty() || blocks.size() > volume) throw new IOException("模板未包含有效体素");
            var used = new BitSet((int) volume);
            var cells = new ArrayList<Cell>(blocks.size());
            for (int i = 0; i < blocks.size(); i++) {
                var block = blocks.getCompound(i);
                var pos = block.getList("pos", Tag.TAG_INT);
                int x = pos.getInt(0), y = pos.getInt(1), z = pos.getInt(2);
                int index = block.getInt("state");
                if (pos.size() != 3 || x < 0 || x >= size.x() || y < 0 || y >= size.y() || z < 0 || z >= size.z()
                        || !block.contains("state", Tag.TAG_INT) || index < 0 || index >= nativeStates.size()) {
                    throw new IOException("模板体素越界: " + i);
                }
                int localId = (y * size.z() + z) * size.x() + x;
                var state = nativeStates.get(index);
                if (used.get(localId) || state.isAir() || state.hasBlockEntity() != block.contains("nbt", Tag.TAG_COMPOUND)) {
                    throw new IOException("模板含重复格、非稀疏空气或缺失实体: " + localId);
                }
                used.set(localId);
                Entity entity = null;
                if (state.hasBlockEntity()) {
                    var payload = block.getCompound("nbt").copy();
                    String type = payload.getString("id");
                    var registryType = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                            .getOptional(net.minecraft.resources.ResourceLocation.parse(type));
                    if (registryType.isEmpty() || !registryType.get().isValid(state)) {
                        throw new IOException("模板方块与实体类型不匹配: " + localId + " " + type);
                    }
                    payload.remove("id");
                    entity = new Entity(type, payload.toString());
                }
                cells.add(new Cell(new Point3i(x, y, z), index, localId, entity));
            }
            return new ImportedWallTemplate(size, cells, rotations);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("无法装载正式长城模板: " + name, failure);
        }
    }
}
