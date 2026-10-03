package local.plaincraft.greatwall.client.debug;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import local.plaincraft.greatwall.diagnostics.DebugPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Explicitly initialized, client-owned diagnostics. No production entrypoint calls this class. */
public final class GreatWallDebugClient {
    private static boolean initialized;
    private static ClientPacketListener connection;
    private static ClientLevel world;
    private static PageCache cache;

    private GreatWallDebugClient() {}

    public static void initialize() {
        owner();
        if (initialized) return;
        DebugPayload.registerType();
        if (!ClientPlayNetworking.registerGlobalReceiver(DebugPayload.TYPE, (payload, context) -> {
            var client = context.client();
            var sender = context.responseSender();
            var receivedWorld = client.level;
            client.execute(() -> {
                // Context.player() can refer to a replacement player; the sender identifies the addon.
                var current = client.getConnection();
                if (current != null && current.isAcceptingMessages() && client.level != null
                        && client.level == receivedWorld && ClientPlayNetworking.getSender() == sender) {
                    accept(payload);
                }
            });
        })) {
            throw new IllegalStateException("Great Wall debug receiver already registered");
        }
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            if (connection == handler || client.getConnection() == null) clear();
        }));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            owner();
            if (connected(client) && cache != null) cache.expire(System.nanoTime());
        });
        WorldRenderEvents.AFTER_ENTITIES.register(context -> OverlayRenderer.render(context, pages()));
        initialized = true;
    }

    public static void accept(DebugPayload payload) {
        var client = owner();
        Objects.requireNonNull(payload, "payload");
        if (!connected(client)) return;
        if (cache == null) cache = new PageCache();
        cache.accept(payload, System.nanoTime());
    }

    public static List<DebugPayload> pages() {
        var client = owner();
        if (!connected(client) || cache == null) return List.of();
        return cache.pages(System.nanoTime());
    }

    public static void clear() {
        owner();
        if (cache != null) cache.clear();
        cache = null;
        connection = null;
        world = null;
    }

    private static Minecraft owner() {
        var client = Minecraft.getInstance();
        if (client == null || !client.isSameThread()) {
            throw new IllegalStateException("Great Wall debug requires the actual client owner");
        }
        return client;
    }

    private static boolean connected(Minecraft client) {
        var current = client.getConnection();
        if (current == null || !current.isAcceptingMessages() || client.level == null) {
            clear();
            return false;
        }
        if (connection != current || world != client.level) {
            clear();
            connection = current;
            world = client.level;
        }
        return true;
    }

    /** Pure cache contracts can be tested without substituting a fake Minecraft owner. */
    static final class PageCache {
        static final long BYTE_LIMIT = 2L * 1024 * 1024;
        static final long TTL_NANOS = 30_000_000_000L;
        private static final int PAGE_LIMIT = 256;
        private static final int RETIRED_LIMIT = 256;
        // Reserve bounded map/set nodes, UUIDs, bookkeeping and a returned page-reference array.
        private static final long METADATA_BYTES = 65_536L;
        private final Thread owner = Thread.currentThread();
        private final TreeMap<Integer, DebugPayload> pending = new TreeMap<>();
        private final Set<UUID> retired = new HashSet<>();
        private UUID session;
        private long revision;
        private long startedNanos;
        private long retainedBytes = METADATA_BYTES;
        private int totalPages;
        private boolean hasRevision;
        private boolean rejectedRevision;
        private boolean exhaustedSessions;

        void accept(DebugPayload payload, long now) {
            requireOwner();
            Objects.requireNonNull(payload, "payload");
            expire(now);
            if (exhaustedSessions || retired.contains(payload.session())) return;
            if (!payload.session().equals(session)) {
                if (session != null) {
                    if (retired.size() == RETIRED_LIMIT) {
                        discardRevision();
                        exhaustedSessions = true;
                        return;
                    }
                    retired.add(session);
                }
                discardRevision();
                session = payload.session();
                hasRevision = false;
            }
            if (hasRevision && payload.revision() < revision) return;
            if (!hasRevision || payload.revision() > revision) {
                discardRevision();
                revision = payload.revision();
                hasRevision = true;
                rejectedRevision = false;
                totalPages = payload.totalPages();
                startedNanos = now;
            }
            if (rejectedRevision) return;
            if (payload.totalPages() != totalPages || totalPages > PAGE_LIMIT) {
                discardRevision();
                return;
            }
            var previous = pending.get(payload.page());
            if (previous != null) {
                if (!previous.equals(payload)) discardRevision();
                return;
            }
            long bytes = retainedBytes(payload);
            if (pending.size() == PAGE_LIMIT || bytes > BYTE_LIMIT - retainedBytes) {
                discardRevision();
                return;
            }
            pending.put(payload.page(), payload);
            retainedBytes += bytes;
        }

        List<DebugPayload> pages(long now) {
            requireOwner();
            expire(now);
            return !rejectedRevision && hasRevision && pending.size() == totalPages
                    ? List.copyOf(pending.values()) : List.of();
        }

        void expire(long now) {
            requireOwner();
            if (!pending.isEmpty() && now - startedNanos >= TTL_NANOS) discardRevision();
        }

        void clear() {
            requireOwner();
            discardRevision();
            retired.clear();
            session = null;
            revision = 0;
            startedNanos = 0;
            totalPages = 0;
            hasRevision = false;
            exhaustedSessions = false;
        }

        long retainedBytes() {
            requireOwner();
            return retainedBytes;
        }

        private void discardRevision() {
            pending.clear();
            retainedBytes = METADATA_BYTES;
            totalPages = 0;
            rejectedRevision = true;
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("debug cache accessed outside its owner");
            }
        }

        private static long retainedBytes(DebugPayload payload) {
            // Retained records, point/bounds objects, lists and UTF-16 strings, not wire bytes.
            long bytes = 512L + 176L * payload.lines().size() + 144L * payload.boxes().size();
            for (var label : payload.labels()) bytes += 160L + 2L * label.text().length();
            return bytes;
        }
    }
}
