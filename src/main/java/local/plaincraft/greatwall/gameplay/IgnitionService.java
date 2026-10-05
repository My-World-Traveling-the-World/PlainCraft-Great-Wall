package local.plaincraft.greatwall.gameplay;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.persistence.HistoryTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Observes campfire ignition on wall-owned beacon fire pits. {@code beforeUse}
 * captures an immutable snapshot before the vanilla interaction mutates the
 * world; {@code afterUse} promotes that snapshot to a durable beacon credit
 * only when the pit actually transitioned from unlit to lit and the action was
 * consumed. The torch path is a v2.0-preserved custom behaviour surfaced from
 * inside {@code ItemStack.useOn} after the adventure CanPlaceOn check already
 * passed; it never claims to be vanilla torch behaviour.
 */
public final class IgnitionService {
    private final UUID session;
    private final SiteRuntimeIndex sites;
    private final BeaconLedger beacons;

    public IgnitionService(UUID session, SiteRuntimeIndex sites, BeaconLedger beacons) {
        this.session = Objects.requireNonNull(session, "session");
        this.sites = Objects.requireNonNull(sites, "sites");
        this.beacons = Objects.requireNonNull(beacons, "beacons");
    }

    /**
     * Snapshot-only observation. Runs on the server owner before the vanilla
     * {@code useItemOn} mutates anything; never consumes, ignites, or counts.
     */
    public Optional<GameplayTypes.BeforeUse> beforeUse(ServerPlayer player, Level level, ItemStack stack,
            InteractionHand hand, BlockHitResult hit) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(hit, "hit");
        if (level.isClientSide() || !level.getServer().isSameThread()) return Optional.empty();
        if (!level.dimension().equals(Level.OVERWORLD)) return Optional.empty();
        if (player.isSpectator()) return Optional.empty();
        BlockPos pos = hit.getBlockPos();
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return Optional.empty();
        GameplayTypes.Firepit pit = sites.firepit(new Point3i(pos.getX(), pos.getY(), pos.getZ())).orElse(null);
        if (pit == null) return Optional.empty();
        var state = level.getBlockState(pos);
        if (!state.is(Blocks.CAMPFIRE)) return Optional.empty();
        if (state.getValue(CampfireBlock.LIT) || state.getValue(CampfireBlock.WATERLOGGED)) return Optional.empty();
        String tool = toolOf(stack);
        if (tool == null) return Optional.empty();
        return Optional.of(new GameplayTypes.BeforeUse(pit.beacon(), player.getUUID(), level.getGameTime(),
                pit.feet(), tool, level.getServer().getTickCount()));
    }

    /** Promotes a valid consumed ignition to a durable credit; fire-and-forget on the owner. */
    public void afterUse(GameplayTypes.BeforeUse token, ServerPlayer player, InteractionResult result) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(result, "result");
        if (!token.player().equals(player.getUUID())) return;
        if (!result.consumesAction()) return;
        Point3i pit = token.pitFeet();
        BlockPos pos = new BlockPos(pit.x(), pit.y(), pit.z());
        ServerLevel level = player.serverLevel();
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return;
        var state = level.getBlockState(pos);
        if (!state.is(Blocks.CAMPFIRE)) return;
        if (!state.getValue(CampfireBlock.LIT) || state.getValue(CampfireBlock.WATERLOGGED)) return;
        GameplayTypes.Firepit current = sites.firepit(pit).orElse(null);
        if (current == null || !current.beacon().equals(token.beacon())) return;
        beacons.credit(new GameplayTypes.VerifiedIgnition(token.beacon(), player.getUUID(), token.gameTime(),
                pit, true, false, true, token.tool()));
    }

    /**
     * Custom torch ignition from inside {@code ItemStack.useOn}, after the
     * CanPlaceOn adventure check. Returns a result to replace the vanilla call
     * only when the target is a legal unlit wall fire pit; never consumes the
     * torch and never places a torch.
     */
    public Optional<InteractionResult> useTorchAfterPermission(UseOnContext context) {
        Objects.requireNonNull(context, "context");
        if (!(context.getPlayer() instanceof ServerPlayer player)) return Optional.empty();
        if (!(context.getLevel() instanceof ServerLevel level)) return Optional.empty();
        if (!context.getItemInHand().is(Items.TORCH)) return Optional.empty();
        BlockPos pos = context.getClickedPos();
        if (sites.firepit(new Point3i(pos.getX(), pos.getY(), pos.getZ())).isEmpty()) return Optional.empty();
        if (player.isSpectator()) return Optional.empty();
        if (!level.mayInteract(player, pos)) return Optional.empty();
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return Optional.empty();
        var state = level.getBlockState(pos);
        if (!state.is(Blocks.CAMPFIRE)) return Optional.empty();
        if (state.getValue(CampfireBlock.LIT) || state.getValue(CampfireBlock.WATERLOGGED)) return Optional.empty();
        var lit = state.setValue(CampfireBlock.LIT, true);
        if (!level.setBlock(pos, lit, 3)) return Optional.empty();
        level.playSound(null, pos, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(lit));
        return Optional.of(InteractionResult.SUCCESS);
    }

    private static String toolOf(ItemStack stack) {
        if (stack.is(Items.FLINT_AND_STEEL)) return HistoryTypes.Tool.FLINT_AND_STEEL.name();
        if (stack.is(Items.FIRE_CHARGE)) return HistoryTypes.Tool.FIRE_CHARGE.name();
        if (stack.is(Items.TORCH)) return HistoryTypes.Tool.TORCH.name();
        return null;
    }
}
