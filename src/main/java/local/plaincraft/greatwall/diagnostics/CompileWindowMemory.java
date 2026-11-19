package local.plaincraft.greatwall.diagnostics;

/** 会话拥有的窗口保留量观测；不持有世界/计划引用，也不把缓存估算冒充JVM实际堆占用。 */
public final class CompileWindowMemory {
    public record Retained(long buildingBytes,long projectionBytes,long derivedBytes) {
        public Retained {
            if(buildingBytes<0||projectionBytes<0||derivedBytes<0)throw new IllegalArgumentException("窗口保留量不能为负");
        }
        public long total(){return Math.addExact(buildingBytes,Math.addExact(projectionBytes,derivedBytes));}
    }
    public record Snapshot(int activeWindows,int peakWindows,long buildingBytes,long projectionBytes,
            long derivedBytes,long totalBytes,long peakBytes,long openedWindows,long closedWindows,long samples) {}
    private int active,peakWindows;
    private long building,projection,derived,peak,opened,closed,samples;

    public synchronized Window open() {
        active++;opened++;peakWindows=Math.max(peakWindows,active);
        return new Window();
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(active,peakWindows,building,projection,derived,building+projection+derived,peak,opened,closed,samples);
    }
    /** 每个编译窗口独占令牌；finally关闭只归还自己，不能把其他仍运行窗口的统计清零。 */
    public final class Window implements AutoCloseable {
        private Retained retained=new Retained(0,0,0);
        private boolean disposed;
        private Window() {}
        public void sample(Retained next) {
            synchronized(CompileWindowMemory.this) {
                if(disposed)throw new IllegalStateException("已结束的编译窗口不能重新登记保留量");
                long nextBuilding=Math.addExact(building-retained.buildingBytes(),next.buildingBytes());
                long nextProjection=Math.addExact(projection-retained.projectionBytes(),next.projectionBytes());
                long nextDerived=Math.addExact(derived-retained.derivedBytes(),next.derivedBytes());
                long total=Math.addExact(nextBuilding,Math.addExact(nextProjection,nextDerived));
                building=nextBuilding;projection=nextProjection;derived=nextDerived;
                retained=next;peak=Math.max(peak,total);samples++;
            }
        }
        @Override public void close() {
            synchronized(CompileWindowMemory.this) {
                if(disposed)return;
                disposed=true;
                building-=retained.buildingBytes();projection-=retained.projectionBytes();derived-=retained.derivedBytes();
                active--;closed++;
            }
        }
    }
}
