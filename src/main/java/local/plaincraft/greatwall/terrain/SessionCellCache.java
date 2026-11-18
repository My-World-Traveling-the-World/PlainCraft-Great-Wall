package local.plaincraft.greatwall.terrain;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** 会话拥有缓存，结果只读共享；烘焙在调用lane执行，不把可变NoiseChunk交给其他线程。 */
final class SessionCellCache<V> implements AutoCloseable {
    record Lookup<V>(V value,boolean built) {}
    private final int capacity;
    private final LinkedHashMap<Long,V> values=new LinkedHashMap<>();
    private final HashMap<Long,CompletableFuture<V>> flights=new HashMap<>();
    private boolean closed;

    SessionCellCache(int capacity) {
        if(capacity<1)throw new IllegalArgumentException("cache capacity must be positive");
        this.capacity=capacity;
    }

    Lookup<V> get(long key,Supplier<V> bake) {
        CompletableFuture<V> flight;
        boolean owner;
        synchronized(this) {
            ensureOpen();
            V value=values.get(key);
            if(value!=null)return new Lookup<>(value,false);
            flight=flights.get(key);
            owner=flight==null;
            if(owner) { flight=new CompletableFuture<>();flights.put(key,flight); }
        }
        if(!owner) {
            try { return new Lookup<>(flight.join(),false); }
            catch(CompletionException failure) { throw rethrow(failure.getCause()); }
        }
        try {
            V value=Objects.requireNonNull(bake.get());
            synchronized(this) {
                ensureOpen();
                if(flights.get(key)!=flight)throw new IllegalStateException("采样认领已失效");
                // 完成结果有限FIFO淘汰；在途任务不进入结果表，不会被驱逐成重复烘焙。
                while(values.size()>=capacity)values.remove(values.keySet().iterator().next());
                values.put(key,value);
                flights.remove(key);
            }
            flight.complete(value);
            return new Lookup<>(value,true);
        } catch(Throwable failure) {
            synchronized(this) { flights.remove(key,flight); }
            flight.completeExceptionally(failure);
            throw rethrow(failure);
        }
    }

    synchronized int size() { return values.size(); }
    synchronized int inFlight() { return flights.size(); }

    @Override public void close() {
        java.util.List<CompletableFuture<V>> waiting;
        synchronized(this) {
            if(closed)return;
            closed=true;values.clear();waiting=java.util.List.copyOf(flights.values());flights.clear();
        }
        // 锁外唤醒等待者，避免它们的完成链持有缓存锁执行；正在烘焙者发布时也会拒绝。
        for(var future:waiting)future.completeExceptionally(new IllegalStateException("会话采样缓存已关闭"));
    }

    private void ensureOpen() { if(closed)throw new IllegalStateException("会话采样缓存已关闭"); }
    private static RuntimeException rethrow(Throwable failure) {
        if(failure instanceof Error error)throw error;
        return failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
    }
}
