package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 先按原始长整型站号分组，再只排序站组，避免每一列都装箱并查有序树。 */
final class FoundationGroups {
    private record Group(long key, List<WallFinePlanner.FineColumn> columns) {}

    private FoundationGroups() {}

    static List<List<WallFinePlanner.FineColumn>> ordered(List<WallFinePlanner.FineColumn> samples) {
        Group[] table = new Group[16];
        var groups = new ArrayList<Group>();
        Group previous = null;
        for (var sample : samples) {
            // 兼容夹具原来的有符号加法及溢出行为，不能用坐标散列替换站身份。
            long key = sample.hasSectionIdentity() ? sample.globalSQ8() : Long.MIN_VALUE + sample.centreX();
            if (previous == null || previous.key() != key) {
                int slot = find(table, key);
                Group group = table[slot];
                if (group == null) {
                    // 按站数扩容而不是按列数预分配，普通33列和端点105列都不浪费表空间。
                    if (groups.size() >= table.length / 2) {
                        table = grow(table);
                        slot = find(table, key);
                    }
                    group = new Group(key, new ArrayList<>());
                    table[slot] = group;
                    groups.add(group);
                }
                previous = group;
            }
            // 保持站内输入顺序，重复中心仍选第一个，同横向偏移的填充仍稳定排序。
            previous.columns().add(sample);
        }
        Group[] sorted = groups.toArray(Group[]::new);
        Arrays.sort(sorted, Comparator.comparingLong(Group::key));
        var result = new ArrayList<List<WallFinePlanner.FineColumn>>(sorted.length);
        for (Group group : sorted) result.add(group.columns());
        return result;
    }

    private static Group[] grow(Group[] old) {
        Group[] next = new Group[Math.multiplyExact(old.length, 2)];
        for (Group group : old) if (group != null) next[find(next, group.key())] = group;
        return next;
    }

    private static int find(Group[] table, long key) {
        // 混合高低位以处理Q8站号和负坐标；比较完整key，哈希碰撞不合并站组。
        long mixed = key ^ (key >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        int slot = (int) mixed & (table.length - 1);
        while (table[slot] != null && table[slot].key() != key) slot = (slot + 1) & (table.length - 1);
        return slot;
    }
}
