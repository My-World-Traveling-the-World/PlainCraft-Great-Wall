package local.plaincraft.greatwall.lifecycle;

/** 把不同单位的完成量折算到同一进度条。权重表示工序份额，耗时由阶段时钟单独记录。 */
final class WallProgressWeights {
    private WallProgressWeights() {}

    static double fraction(int done, int total) {
        return total <= 0 ? 0 : Math.clamp((double) done / total, 0, 1);
    }

    static double root(int phase, int done, int total, int structures, int structureTotal) {
        double step = fraction(done, total);
        return switch (phase) {
            case PrewarmProgress.ROOT_MACRO_SURVEY -> 5 * step;
            case 0 -> (total == 0 ? 0 : 5) + 10 * step;
            case PrewarmProgress.ROOT_ENDPOINT_GROUND -> 15 + 12 * step;
            case PrewarmProgress.ROOT_ENDPOINT_STRUCTURES -> 27;
            case 4 -> 15 + 5 * fraction(structures, structureTotal);
            case 1, 2, 5 -> 20 + 10 * step;
            case 3 -> 30;
            default -> 0;
        };
    }

    static int overall(PrewarmProgress.Snapshot s) {
        // 分段与精采并行，共享原来的 37 分；任一方先到终点都不会替另一方计分。
        double score = switch (s.stage()) {
            case ROOT_SEARCH -> root(s.rootPhase(), s.rootCandidate(), s.rootTotal(),
                    s.structureCandidate(), s.structureTotal());
            case STRUCTURE_SCAN, FINE_SAMPLE, PREWARM -> {
                var scan = s.subFor(PrewarmProgress.Stage.STRUCTURE_SCAN);
                var fine = s.subFor(PrewarmProgress.Stage.FINE_SAMPLE);
                double scanned = s.stage() == PrewarmProgress.Stage.STRUCTURE_SCAN ? fraction(scan[0], scan[1]) : 1;
                // 初始估计 1 列而缓存已命中多列时，分母尚未建立，不能把整个精采份额算满。
                double sampled = fine[1] <= 1 && fine[0] > 1 ? 0 : fraction(fine[0], fine[1]);
                // 其他段未定线时无法知道有多少列；少量份额来自已完成切片，不拿局部分母推算整窗。
                double sliceCredit = 6 * fraction(s.fineSlicesDone(), s.fineSliceLimit());
                yield 30 + 25 * scanned + Math.max(31 * sampled, sliceCredit) + 6 * fraction(s.completed(), s.total());
            }
            case COMPILE -> switch (s.compileStep()) {
                case ASSEMBLE_WINDOW -> 92;
                case HEIGHT_ENVELOPE -> 93;
                case CAP_EVIDENCE -> 93 + 2 * fraction(s.subDone(), s.subTotal());
                case FREEZE_PLANS -> 95;
                case PUBLISH_CHUNKS -> 96 + 2 * fraction(s.subDone(), s.subTotal());
            };
            case DONE -> 98 + 2 * fraction(s.subDone(), s.subTotal());
            case BACKFILL -> 99;
        };
        // 保护清单的最后一项也可能读失败，只有成功终态才能提供最后一分。
        return Math.min(99, (int) Math.floor(score));
    }
}
