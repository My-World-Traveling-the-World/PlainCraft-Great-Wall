package local.plaincraft.greatwall.generation;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;

/** Native chunk attachments must survive ProtoChunk promotion and serialization. */
public final class TerrainMarkers {
    public static final AttachmentType<String> T0 = AttachmentRegistry.createPersistent(
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "s0_t0"), Codec.STRING);
    public static final AttachmentType<String> APPLIED = AttachmentRegistry.createPersistent(
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "s0_applied"), Codec.STRING);
    private TerrainMarkers() {}
    public static void register() { /* Initializes the types during mod registration. */ }
}
