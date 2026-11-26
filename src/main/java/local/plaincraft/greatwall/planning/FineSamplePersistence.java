package local.plaincraft.greatwall.planning;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.planning.WallFinePlanner.FineColumn;
import local.plaincraft.greatwall.terrain.FineSampleCache;

/** 初采和补采共用一次提交，避免用旧磁盘快照单写补列时覆盖本轮初采。 */
final class FineSamplePersistence implements AutoCloseable {
    private final FineSampleCache cache;
    private final Executor io;
    private final Predicate<Collection<FineSampleCache.Entry>> store;
    private List<FineColumn> pending=new ArrayList<>();
    private CompletableFuture<Boolean> flushing;
    private boolean sealed,closed;

    FineSamplePersistence(FineSampleCache cache,Executor io) {this(cache,io,cache::tryStore);}
    FineSamplePersistence(FineSampleCache cache,Executor io,Predicate<Collection<FineSampleCache.Entry>> store) {
        this.cache=cache;this.io=io;this.store=store;
    }

    synchronized boolean accept(List<FineColumn> probed) {
        if(sealed)return false;
        if(cache.persistent())pending.addAll(probed);
        return true;
    }

    CompletableFuture<Boolean> finish() {
        CompletableFuture<Boolean> result;
        synchronized(this) {
            if(flushing!=null)return flushing;
            sealed=true;
            // 汇总一次会话命中和新探测量，重开仍能区分“全命中”和“重新走过地形”。
            GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_CACHE 会话合并 hits={} misses={} cached={} probed={} persistent={}",
                    cache.hits(),cache.misses(),cache.size(),pending.size(),cache.persistent());
            if(pending.isEmpty())return flushing=CompletableFuture.completedFuture(true);
            result=flushing=new CompletableFuture<>();
        }
        // 直接使用已有IO线程；关闭不能依赖可能已被停掉的planner pump来完成回调。
        try {io.execute(()->{
            try {result.complete(write());}
            catch(Throwable failure){result.completeExceptionally(failure);}
        });}
        catch(RejectedExecutionException failure) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] 精采缓存提交被拒绝，停服时重试 reason={}",failure.toString());
            result.complete(false);
        }
        return result;
    }

    private boolean write() {
        List<FineColumn> batch;
        synchronized(this) {if(pending.isEmpty())return true;batch=pending;}
        var entries=new ArrayList<FineSampleCache.Entry>(batch.size());
        for(var column:batch)entries.add(new FineSampleCache.Entry(column.x(),column.z(),column.sample()));
        boolean success;
        try {success=store.test(entries);}
        catch(RuntimeException failure) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] 精采缓存写入失败，保留完整批次供停服重试 reason={}",failure.toString());
            return false;
        }
        // 封口后不会再追加；只有确实成功才能释放样本，否则关闭时仍需完整的初采+补采。
        if(success)synchronized(this){pending=List.of();}
        return success;
    }

    @Override public void close() {
        CompletableFuture<Boolean> current;boolean own;
        synchronized(this) {
            if(closed)return;
            closed=true;sealed=true;own=flushing==null;
            // 关闭先到也占用同一提交凭据，防止预检结案随后派发第二个.tmp写入者。
            if(own)flushing=new CompletableFuture<>();
            current=flushing;
        }
        // 等已派发的写入结束再决定是否重试，同一个.tmp不能有并发写入者。
        if(own) {
            try {current.complete(write());}
            catch(Throwable failure){current.completeExceptionally(failure);throw failure;}
        } else if(!current.join())write();
    }
}
