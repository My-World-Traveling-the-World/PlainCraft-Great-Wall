package local.plaincraft.greatwall.terrain;

import java.util.List;
import local.plaincraft.greatwall.component.ImportedRibbonProjection;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 只保留最近一个冻结窗口；版本由计划对象身份确定，避免旧窗积累或跨重规划串用。 */
public final class ImportedProjectionMemo {
    public static final long MAX_BYTES = 4L * 1024 * 1024;
    public record Stats(long builds, long hits, int entries, long bytes) {}
    private List<SegmentPlan> plans;
    private ImportedRibbonProjection projection;
    private long builds, hits, bytes;

    synchronized ImportedRibbonProjection get(PlanWindow window, List<Station> stations, List<SiteRecord> sites) {
        if (samePlans(window.plans())) { hits++; return projection; }
        var result = new ImportedRibbonProjection(stations, sites);
        builds++;
        // 不为超大窗口长期占用额外堆；仍使用同一精确算法，不降精度或缩小路线。
        clear();
        long weight = result.estimatedBytes();
        if (weight <= MAX_BYTES) {
            plans = window.plans(); projection = result; bytes = weight;
        }
        return result;
    }
    private boolean samePlans(List<SegmentPlan> candidate) {
        if (plans == null || plans.size() != candidate.size()) return false;
        for (int i=0;i<plans.size();i++) if (plans.get(i)!=candidate.get(i)) return false;
        return true;
    }
    public synchronized Stats stats() { return new Stats(builds,hits,projection==null?0:1,bytes); }
    public synchronized void clear() { plans=null;projection=null;bytes=0; }
}
