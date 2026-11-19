package local.plaincraft.greatwall.component;

import java.util.List;
import java.util.function.LongFunction;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 墙体按最终可施工弧段编译，避免生产分派依赖某个旧实现的强制类型转换。 */
public interface IntervalWallTemplate extends ComponentTemplate {
    ComponentVoxels compileIntervals(ComponentInput input, List<ArcInterval> intervals, List<ArcInterval> walkable,
            LongFunction<DecaySample> samples, List<Frame> gates, List<SiteRecord> sites);
}
