package local.plaincraft.greatwall.compat.c2me;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.mixin.ChunkMapAccess;
import net.minecraft.world.level.ChunkPos;

/** Only C2ME's un-obfuscated adapter API is reflective; no Minecraft names are strings. */
public final class C2meProbeAdapter {
    private record Request(WorldServices service, String dimension, int x, int z) {}
    private static final ThreadLocal<Request> SUBSCRIBING = new ThreadLocal<>();

    private C2meProbeAdapter() {}

    public static Completable gate(Object context, Object delegate, Completable original) {
        if (!delegate.toString().equals("minecraft:features")) return original;
        var access = (ChunkMapAccess) invoke(context, "tacs");
        var level = access.greatwall$level();
        var service = WorldServices.find(level.getServer());
        if (service == null) return original;
        var holder = invoke(context, "holder");
        ChunkPos pos = (ChunkPos) invoke(holder, "getKey");
        Object manager = invoke(context, "schedulingManager");
        Executor executor;
        try {
            executor = (Executor) manager.getClass().getMethod("positionedExecutor", long.class)
                    .invoke(manager, pos.toLong());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Pinned C2ME positionedExecutor is unavailable", failure);
        }
        String dimension = level.dimension().location().toString();
        var lease = service.require(dimension, pos.x, pos.z, "c2me:features");
        return Completable.fromCompletionStage(lease).observeOn(Schedulers.from(executor))
                .andThen(Completable.create(emitter -> {
                    service.assertActiveSession();
                    // Set context before subscribing: defer's supplier precedes doOnSubscribe.
                    SUBSCRIBING.set(new Request(service, dimension, pos.x, pos.z));
                    try {
                        emitter.setDisposable(original.subscribe(emitter::onComplete,
                                failure -> emitter.tryOnError(failure)));
                    } finally {
                        SUBSCRIBING.remove();
                    }
                })).doOnError(service::failRequiredPlan)
                .doOnDispose(() -> lease.cancel(false));
    }

    public static <T> Supplier<CompletableFuture<T>> instrument(Supplier<CompletableFuture<T>> action,
                                                               String token) {
        Request request = SUBSCRIBING.get();
        if (request == null) return action;
        // The original C2ME ScheduledTask invokes this supplier only after acquiring its locks.
        SUBSCRIBING.remove();
        return () -> {
            var service = request.service();
            service.assertActiveSession();
            service.trace("WRITE_LOCK_ACQUIRED", request.dimension(), request.x(), request.z(), "c2me:features", token);
            StageTrace.enterLockAction();
            try {
                service.trace("STAGE_ENTER", request.dimension(), request.x(), request.z(), "c2me:features", token);
                return action.get().whenComplete((value, failure) -> service.trace("STAGE_EXIT",
                        request.dimension(), request.x(), request.z(), "c2me:features", token));
            } finally {
                StageTrace.leaveLockAction();
            }
        };
    }

    private static Object invoke(Object receiver, String method) {
        try { return receiver.getClass().getMethod(method).invoke(receiver); }
        catch (InvocationTargetException failure) { throw new IllegalStateException(method, failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Pinned C2ME API missing: " + method, failure); }
    }
}
