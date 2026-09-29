package local.plaincraft.greatwall.terrain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import local.plaincraft.greatwall.model.WallTypes;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Immutable, authoritative pre-APPLY agreement. No live chunks or cache-dependent votes. */
public record GroundExitDecision(Key key, Exit left, Optional<Exit> right, long terminalSQ8,
        List<BreakInterval> breaks, List<ArcInterval> exclusions, List<Cap> caps, String t0Hash, String hash) {
    public static final long CELL_Q8 = 64L * 256;
    public GroundExitDecision {
        Objects.requireNonNull(key); Objects.requireNonNull(left); Objects.requireNonNull(right);
        breaks = List.copyOf(breaks); exclusions = List.copyOf(exclusions); caps = List.copyOf(caps);
        WallTypes.requireHash(t0Hash); WallTypes.requireHash(hash);
        if (left.sQ8() != key.range().fromQ8() || terminalSQ8 <= left.sQ8())
            throw new IllegalArgumentException("invalid decision bounds");
        if (right.isPresent() ? right.get().sQ8() != key.range().toQ8() || terminalSQ8 != Long.MAX_VALUE
                : key.range().toQ8() != Long.MAX_VALUE || terminalSQ8 == Long.MAX_VALUE)
            throw new IllegalArgumentException("decision must end at an accepted exit or proven terminal");
    }
    public record Key(String recipeHash, String dimension, ArcInterval range) {
        public Key {
            WallTypes.requireHash(recipeHash); WallTypes.requireResourceKey(dimension); Objects.requireNonNull(range);
            if (range.fromQ8() < 0) throw new IllegalArgumentException("negative decision S");
        }
    }
    public record Exit(SiteId site, long sQ8, ArcInterval footprint, String componentHash, String t0Hash) {
        public Exit {
            Objects.requireNonNull(site); Objects.requireNonNull(footprint);
            WallTypes.requireHash(componentHash); WallTypes.requireHash(t0Hash);
            if (sQ8 < 0 || sQ8 < footprint.fromQ8() || sQ8 >= footprint.toQ8())
                throw new IllegalArgumentException("exit outside footprint");
        }
    }
    public record Edge(long sQ8, boolean reverse, ArcInterval allowed) {
        public Edge {
            Objects.requireNonNull(allowed);
            if (sQ8 < 0 || sQ8 != (reverse ? allowed.fromQ8() : allowed.toQ8()))
                throw new IllegalArgumentException("edge must bound the walk run");
        }
    }
    public record Cap(SiteId site, Edge edge, long actualSQ8, String componentHash, ArcInterval footprint) {
        public Cap(SiteId site,Edge edge,long actualSQ8,String componentHash) {
            this(site,edge,actualSQ8,componentHash,new ArcInterval(footprintStart(edge,actualSQ8),footprintEnd(edge,actualSQ8)));
        }
        public Cap {
            Objects.requireNonNull(site); Objects.requireNonNull(edge); WallTypes.requireHash(componentHash);Objects.requireNonNull(footprint);
            long retreat = edge.reverse() ? actualSQ8 - edge.sQ8() : edge.sQ8() - actualSQ8;
            if (site.kind() != SiteKind.END_CAP || retreat < 0 || retreat > 128L * 256 || retreat % (4 * 256) != 0)
                throw new IllegalArgumentException("cap violates fixed retreat schedule");
            if (footprint.fromQ8() < edge.allowed().fromQ8() || footprint.toQ8() > edge.allowed().toQ8())
                throw new IllegalArgumentException("cap crosses another walk-run boundary");
        }
        private static long footprintStart(Edge e, long s) { return e.reverse() ? s : s - 30L * 256; }
        private static long footprintEnd(Edge e, long s) { return e.reverse() ? s + 30L * 256 : s; }
    }
    public record CapVote(Optional<Cap> cap, String t0Hash) {
        public CapVote { Objects.requireNonNull(cap); WallTypes.requireHash(t0Hash); }
    }
    /** A bounded core slice, not the +/-192 capture halo. Terminal evidence is global. */
    public record Slice(CellKey cell, List<Exit> exits, List<BreakInterval> breaks,
            Optional<BreakInterval> terminal, String t0Hash) {
        public Slice {
            Objects.requireNonNull(cell); exits = List.copyOf(exits); breaks = List.copyOf(breaks);
            Objects.requireNonNull(terminal); WallTypes.requireHash(t0Hash);
            long from = Math.multiplyExact(cell.arcCell(), CELL_Q8), to = Math.addExact(from, CELL_Q8);
            if (from < 0) throw new IllegalArgumentException("negative cell");
            for (var exit : exits) if (exit.sQ8() < from || exit.sQ8() >= to)
                throw new IllegalArgumentException("exit belongs to its center cell only");
            for (var gap : breaks) if (gap.range().fromQ8() < from || gap.range().toQ8() > to)
                throw new IllegalArgumentException("slice break escaped core");
            terminal.ifPresent(t -> { if (t.range().toQ8() != Long.MAX_VALUE) throw new IllegalArgumentException("finite terminal"); });
        }
    }
}
