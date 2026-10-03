import com.google.gson.GsonBuilder;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import local.plaincraft.greatwall.diagnostics.WalkwayCollisionCheck;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/** Read-only saved-column diagnosis, never a world loader or an acceptance runner. */
public final class ReadIsolatedWallColumn {
    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("workspace runId x z minY maxYInclusive");
        }
        Path workspace = Path.of(args[0]).toRealPath();
        Path smoke = workspace.resolve("build/run/serverSmoke/vanilla").toRealPath();
        if (!args[1].matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("invalid isolated runId");
        }
        Path run = smoke.resolve(args[1]).toRealPath();
        Path world = run.resolve("world").toRealPath();
        if (!world.startsWith(smoke) || !Files.isRegularFile(run.resolve("greatwall_test_world"))
                || !Files.isRegularFile(run.resolve("greatwall_s1c_fixture"))) {
            throw new IllegalArgumentException("isolated smoke world marker required");
        }
        int x = Integer.parseInt(args[2]), z = Integer.parseInt(args[3]);
        int minY = Integer.parseInt(args[4]), maxY = Integer.parseInt(args[5]);
        if (minY > maxY || (long) maxY - minY > 128) {
            throw new IllegalArgumentException("diagnostic column limited to 129 rows");
        }
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        Path region = world.resolve("region/r." + Math.floorDiv(cx, 32)
                + "." + Math.floorDiv(cz, 32) + ".mca").toRealPath();
        if (!region.startsWith(world)) throw new IllegalArgumentException("region escapes isolated world");
        String before = sha(region);
        CompoundTag chunk = readChunk(region, cx, cz);
        if (chunk.getInt("xPos") != cx || chunk.getInt("zPos") != cz) {
            throw new IOException("chunk identity mismatch");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var rows = new ArrayList<Map<String, Object>>();
        for (int y = minY; y <= maxY; y++) {
            CompoundTag state = paletteState(chunk, x, y, z);
            var properties = new TreeMap<String, String>();
            CompoundTag props = state.getCompound("Properties");
            for (String key : props.getAllKeys()) properties.put(key, props.getString(key));
            var row = new TreeMap<String, Object>();
            row.putAll(Map.of("x", x, "y", y, "z", z, "id", state.getString("Name"),
                    "properties", properties));
            row.putAll(shapeFacts(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), state)));
            rows.add(row);
        }
        var attachments = new TreeMap<String, Object>();
        var nativeAttachments = chunk.getCompound("fabric:attachments");
        var marker = nativeAttachments.getCompound("plaincraft_great_wall:production_v1");
        attachments.put("complete", marker.getBoolean("complete"));
        attachments.put("recipe", marker.getString("recipe"));
        attachments.put("coverage", marker.getString("coverage"));
        attachments.put("plan", marker.getString("plan"));
        var feet = marker.getList("feet", Tag.TAG_COMPOUND);
        var columnFeet = new ArrayList<Integer>();
        for (int i = 0; i < feet.size(); i++) {
            var foot = feet.getCompound(i);
            if (foot.getInt("x") == x && foot.getInt("z") == z) columnFeet.add(foot.getInt("y"));
        }
        attachments.put("totalFeet", feet.size());
        attachments.put("columnFeet", columnFeet);
        var store = ChunkEvidenceStore.production(world.resolve("greatwall/evidence"));
        var plan = store.readPlan(new ChunkKey("minecraft:overworld", cx, cz))
                .orElseThrow(() -> new IOException("missing durable owner plan"));
        if (!plan.hash().equals(marker.getString("plan"))
                || !plan.recipeHash().equals(marker.getString("recipe"))
                || !plan.metadata().coverageHash().equals(marker.getString("coverage"))) {
            throw new IOException("saved marker and durable proof identity differ");
        }
        var planColumn = plan.voxels().stream().filter(v -> v.pos().x() == x && v.pos().z() == z
                && v.pos().y() >= minY && v.pos().y() <= maxY).map(v -> Map.of(
                        "y", v.pos().y(), "id", v.state().id(), "properties", v.state().properties(),
                        "part", v.part().name(), "kind", v.kind().name(), "component", v.component().toString())).toList();
        var checks = new ArrayList<Map<String, Object>>();
        for (int footY : columnFeet) {
            var feetState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteState(chunk, x, footY, z));
            var headState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteState(chunk, x, footY + 1, z));
            var belowState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteState(chunk, x, footY - 1, z));
            var view = EmptyBlockGetter.INSTANCE;
            var pos = BlockPos.ZERO;
            var result = WalkwayCollisionCheck.inspect(feetState.getCollisionShape(view, pos),
                    headState.getCollisionShape(view, pos), belowState.getCollisionShape(view, pos));
            boolean oldValid = feetState.getCollisionShape(view, pos).isEmpty()
                    && headState.getCollisionShape(view, pos).isEmpty()
                    && belowState.isFaceSturdy(view, pos, Direction.UP);
            checks.add(Map.of("feetY", footY, "oldFullFaceValid", oldValid, "playerCollisionValid", result.valid(),
                    "feetClear", result.feetClear(), "headClear", result.headClear(),
                    "supported", result.supported(), "baseBodyClear", result.baseBodyClear()));
        }
        int oldRejected = 0;
        var ownerRejected = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < feet.size(); i++) {
            var foot = feet.getCompound(i);
            int fx = foot.getInt("x"), fy = foot.getInt("y"), fz = foot.getInt("z");
            if (Math.floorDiv(fx, 16) != cx || Math.floorDiv(fz, 16) != cz) {
                throw new IOException("walkway marker references another owner");
            }
            var feetState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteState(chunk, fx, fy, fz));
            var headState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteState(chunk, fx, fy + 1, fz));
            var belowState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteState(chunk, fx, fy - 1, fz));
            var view = EmptyBlockGetter.INSTANCE;
            var pos = new BlockPos(fx, fy, fz);
            var result = WalkwayCollisionCheck.inspect(feetState.getCollisionShape(view, pos),
                    headState.getCollisionShape(view, pos.above()), belowState.getCollisionShape(view, pos.below()));
            if (!feetState.getCollisionShape(view, pos).isEmpty()
                    || !headState.getCollisionShape(view, pos.above()).isEmpty()
                    || !belowState.isFaceSturdy(view, pos.below(), Direction.UP)) oldRejected++;
            if (!result.valid()) {
                ownerRejected.add(Map.of("x", fx, "y", fy, "z", fz, "result", result.toString(),
                        "below", belowState.toString(), "feet", feetState.toString(), "head", headState.toString()));
            }
        }
        var controls = new TreeMap<String, Object>();
        controls.put("air", shapeFacts(Blocks.AIR.defaultBlockState()));
        controls.put("fullStone", shapeFacts(Blocks.STONE.defaultBlockState()));
        controls.put("bottomSlab", shapeFacts(Blocks.STONE_BRICK_SLAB.defaultBlockState()));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var stair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            var facts = shapeFacts(stair);
            if ((boolean) facts.get("upFaceSturdy") || !(boolean) facts.get("playerFootprintHasTopSupport")) {
                throw new IllegalStateException("stair counterexample missing at " + facing);
            }
            controls.put("bottomStair_" + facing.getName(), facts);
        }
        if ((boolean) controlsFact(controls, "air", "playerFootprintHasTopSupport")
                || (boolean) controlsFact(controls, "bottomSlab", "playerFootprintHasTopSupport")
                || !(boolean) controlsFact(controls, "fullStone", "playerFootprintHasTopSupport")) {
            throw new IllegalStateException("support controls failed");
        }
        String after = sha(region);
        if (!before.equals(after)) throw new IOException("input region changed during diagnosis");
        var report = new TreeMap<String, Object>();
        report.put("purpose", "READ_ONLY_SAVED_NBT_NOT_GRADLE_OR_RUNTIME_ACCEPTANCE");
        report.put("world", world.toString());
        report.put("region", region.toString());
        report.put("regionSha256Before", before);
        report.put("regionSha256After", after);
        report.put("owner", Map.of("x", cx, "z", cz));
        report.put("status", chunk.getString("Status"));
        report.put("dataVersion", chunk.getInt("DataVersion"));
        report.put("lastUpdate", chunk.getLong("LastUpdate"));
        report.put("rows", rows);
        report.put("attachments", attachments);
        report.put("durablePlanVoxelCount", plan.voxels().size());
        report.put("durablePlanColumn", planColumn);
        report.put("columnChecks", checks);
        report.put("savedOwnerWalkwayChecked", feet.size());
        report.put("oldFullFaceRejected", oldRejected);
        report.put("playerCollisionRejected", ownerRejected);
        report.put("vanillaShapeControls", controls);
        report.put("limits", "Saved snapshot plus vanilla collision APIs with EmptyBlockGetter; "
                + "no live Level or mod hooks, no traversal/reopen certification.");
        Bootstrap.STDOUT.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private static Object controlsFact(Map<String, Object> controls, String block, String key) {
        return ((Map<?, ?>) controls.get(block)).get(key);
    }

    private static Map<String, Object> shapeFacts(BlockState state) {
        var view = EmptyBlockGetter.INSTANCE;
        var pos = BlockPos.ZERO;
        var shape = state.getCollisionShape(view, pos);
        var boxes = shape.toAabbs();
        // Same 0.6 x 1.8 body and narrow support band as ComponentFixtures.CollisionView.
        var supportBand = new AABB(.201, .99, .201, .799, 1, .799);
        var body = new AABB(.201, 1.0001, .201, .799, 2.7999, .799);
        var encodedBoxes = boxes.stream().map(b -> Map.of("minX", b.minX, "minY", b.minY,
                "minZ", b.minZ, "maxX", b.maxX, "maxY", b.maxY, "maxZ", b.maxZ)).toList();
        return Map.of("collisionEmpty", shape.isEmpty(),
                "upFaceSturdy", state.isFaceSturdy(view, pos, Direction.UP),
                "playerFootprintHasTopSupport", boxes.stream().anyMatch(b -> b.intersects(supportBand)),
                "playerBodyAboveIsClear", boxes.stream().noneMatch(b -> b.intersects(body)),
                "boxes", encodedBoxes);
    }

    private static CompoundTag readChunk(Path region, int cx, int cz) throws IOException {
        try (var channel = FileChannel.open(region, StandardOpenOption.READ)) {
            int slot = Math.floorMod(cx, 32) + 32 * Math.floorMod(cz, 32);
            int location = read(channel, slot * 4L, 4).getInt();
            int sector = location >>> 8, count = location & 255;
            if (sector < 2 || count == 0) throw new IOException("saved owner chunk unavailable");
            ByteBuffer header = read(channel, sector * 4096L, 5);
            int length = header.getInt(), compression = Byte.toUnsignedInt(header.get());
            if (length < 2 || length > count * 4096 - 4 || length > 16 * 1024 * 1024) {
                throw new IOException("invalid chunk record length");
            }
            if ((compression & 128) != 0) throw new IOException("external chunk record unsupported");
            byte[] payload = read(channel, sector * 4096L + 5, length - 1).array();
            InputStream input = new ByteArrayInputStream(payload);
            input = switch (compression) {
                case 1 -> new GZIPInputStream(input);
                case 2 -> new InflaterInputStream(input);
                case 3 -> input;
                default -> throw new IOException("unsupported compression " + compression);
            };
            try (var data = new DataInputStream(input)) {
                return NbtIo.read(data, NbtAccounter.create(64L * 1024 * 1024));
            }
        }
    }

    private static CompoundTag paletteState(CompoundTag chunk, int x, int y, int z) throws IOException {
        var sections = chunk.getList("sections", Tag.TAG_COMPOUND);
        int sectionY = Math.floorDiv(y, 16);
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.getCompound(i);
            if (section.getByte("Y") != sectionY) continue;
            var states = section.getCompound("block_states");
            var palette = states.getList("palette", Tag.TAG_COMPOUND);
            if (palette.isEmpty()) throw new IOException("missing section palette");
            int index = 0;
            if (palette.size() > 1) {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                var storage = new SimpleBitStorage(bits, 4096, states.getLongArray("data"));
                int block = Math.floorMod(y, 16) * 256 + Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16);
                index = storage.get(block);
            }
            if (index >= palette.size()) throw new IOException("invalid palette index");
            return palette.getCompound(index);
        }
        throw new IOException("saved section unavailable at Y=" + sectionY);
    }

    private static ByteBuffer read(FileChannel channel, long offset, int size) throws IOException {
        ByteBuffer result = ByteBuffer.allocate(size);
        while (result.hasRemaining()) {
            int count = channel.read(result, offset + result.position());
            if (count < 0) throw new IOException("truncated region record");
            if (count == 0) throw new IOException("region read made no progress");
        }
        return result.flip();
    }

    private static String sha(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = stream.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
