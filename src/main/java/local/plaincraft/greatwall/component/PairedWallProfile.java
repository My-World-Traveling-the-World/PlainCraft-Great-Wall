package local.plaincraft.greatwall.component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** 读取实物配套墙；加载时校验全部 48 列，施工时只保留一个四列循环。 */
final class PairedWallProfile {
    static final int HALF = 5;
    static final int HEIGHT = 18;
    static final int FEET = 15;
    static final int PERIOD = 4;
    private static final int FILE_LENGTH = 48;
    private static final String RESOURCE = "/assets/plaincraft-great-wall/templates/wall_module.csv";

    private PairedWallProfile() {}

    private static final class Holder {
        // 类初始化保证并发施工共享同一份已校验剖面。
        static final BlockSpec[][][][] STATES = load();
    }

    static BlockSpec at(int phase, int lateral, int bottomY, Direction merlonSide) {
        // 模块原本的垛口在 +Z；旋转方块属性时只看这条横轴。
        var rotation = switch (merlonSide) {
            case SOUTH -> Rotation.NONE;
            case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException("垛口侧必须为水平轴");
        };
        return Holder.STATES[rotation.ordinal()][phase][bottomY][lateral + HALF];
    }

    private static BlockSpec[][][][] load() {
        try (var in = PairedWallProfile.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("找不到资源");
            var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            var table = new BlockState[FILE_LENGTH][HEIGHT][HALF * 2 + 1];
            var parsed = new HashMap<String, BlockState>();
            String line;
            int row = 0;
            while ((line = reader.readLine()) != null) {
                row++;
                var fields = line.split(",", 4);
                if (fields.length != 4) throw new IOException("无效墙模块行: " + row);
                int phase = Integer.parseInt(fields[0]);
                int across = Integer.parseInt(fields[1]) + HALF;
                int y = HEIGHT - 1 - Integer.parseInt(fields[2]);
                if (phase < 0 || phase >= FILE_LENGTH || y < 0 || y >= HEIGHT || across < 0 || across > 2 * HALF
                        || table[phase][y][across] != null) throw new IOException("重复或越界的墙模块格: " + row);
                table[phase][y][across] = parsed.computeIfAbsent(fields[3], PairedWallProfile::parse);
            }
            // 9504 是含空气的断面格数；CSV 只记录 8136 个实心方块。
            if (row != 8_136) throw new IOException("墙模块有效方块数量改变: " + row);
            return freeze(table);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("无法加载正式接墙剖面: " + RESOURCE, failure);
        }
    }

    private static BlockSpec[][][][] freeze(BlockState[][][] table) throws IOException {
        var states = new BlockSpec[Rotation.values().length][PERIOD][HEIGHT][HALF * 2 + 1];
        for (int p = 0; p < FILE_LENGTH; p++) for (int y = 0; y < HEIGHT; y++) for (int x = 0; x <= 2 * HALF; x++) {
            var state = table[p][y][x];
            // 连空气一起比，并拒绝空的首列，避免资源读空后循环仍自洽。
            if (state != table[p % PERIOD][y][x] || (state == null && y < FEET)) {
                throw new IOException("墙模块四列循环或承重层损坏: " + p + "/" + y + "/" + x);
            }
            if (p < PERIOD && state != null) for (var rotation : Rotation.values()) {
                states[rotation.ordinal()][p][y][x] = BlockCatalog.specification(state.rotate(rotation));
            }
        }
        return states;
    }

    private static BlockState parse(String text) {
        int open = text.indexOf('[');
        String id = open < 0 ? text : text.substring(0, open);
        var properties = new HashMap<String, String>();
        if (open >= 0) {
            if (!text.endsWith("]")) throw new IllegalArgumentException("不完整的墙模块方块属性: " + text);
            for (var entry : text.substring(open + 1, text.length() - 1).split(",")) {
                var pair = entry.split("=", -1);
                if (pair.length != 2 || properties.putIfAbsent(pair[0], pair[1]) != null) {
                    throw new IllegalArgumentException("无效或重复的墙模块方块属性: " + text);
                }
            }
        }
        // 正式接线必须拒绝错误的 ID、属性或空气，不能用展台的默认材质静默掩盖。
        var state = BlockCatalog.resolveState(new BlockSpec(id.contains(":") ? id : "minecraft:" + id, properties));
        if (state.isAir() || state.hasBlockEntity()) throw new IllegalArgumentException("无效墙模块方块: " + text);
        return state;
    }
}
