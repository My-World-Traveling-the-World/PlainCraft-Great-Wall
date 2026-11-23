package local.plaincraft.greatwall.model;

import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.Axis;
import local.plaincraft.greatwall.model.WallTypes.Point3i;

/** 冻结在世界配方里的分界；续建不能用新版的方块结果覆盖旧凭证。 */
public final class WallRecipeRules {
    public static final int AUTHORED = 2;
    public static final int INTACT = 3;

    private WallRecipeRules() {}

    public static boolean usesAuthoredTemplates(int version) {
        return version == AUTHORED || version == INTACT;
    }

    public static boolean weathers(int version) {
        return version < INTACT;
    }

    /** 把出生点附近的安全选址当作有限墙中点；沿用非负段号与原施工凭证格式。 */
    public static Axis centerFiniteAxis(Axis selected, long length, int version) {
        if (version < INTACT || length == 0) return selected;
        long back = length / 2;
        var root = selected.origin();
        return new Axis(new Point3i(Math.toIntExact(root.x() - FixedMath.mulDiv(back, selected.exQ30(), 1L << 30)),
                root.y(), Math.toIntExact(root.z() - FixedMath.mulDiv(back, selected.ezQ30(), 1L << 30))),
                selected.exQ30(), selected.ezQ30());
    }
}
