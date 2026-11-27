package local.plaincraft.greatwall.lifecycle;

import local.plaincraft.greatwall.config.GreatWallConfig;

/** 启动时取一次长度：同一窗口负责候选评分、轴缓存读写和配方端口平移。 */
record RootAxisWindow(long wallLength, int forwardBlocks) {
    static RootAxisWindow of(long length, int requested) {
        // 有限墙不使用无限预热设置；无限按正式施工一样向上对齐到完整分段。
        return new RootAxisWindow(length, length > 0 ? 0 : Math.toIntExact(
                GreatWallConfig.startupSegments(0, requested) * GreatWallConfig.SEGMENT_BLOCKS));
    }

    static RootAxisWindow current() {
        var settings = GreatWallConfig.get();
        return of(settings.wallLengthBlocks(), settings.unlimitedPrewarmBlocks());
    }
}
