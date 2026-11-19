package local.plaincraft.greatwall.component;

import local.plaincraft.greatwall.model.WallTypes.*;

/** 整数旋转的实物不走任意角度栅格包络；采样与施工共用精确占地。 */
public interface ExactFootprintTemplate extends ComponentTemplate {
    Bounds worldBounds(ComponentInput input);
}
