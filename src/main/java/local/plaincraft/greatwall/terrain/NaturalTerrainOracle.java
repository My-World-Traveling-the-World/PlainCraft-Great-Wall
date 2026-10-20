package local.plaincraft.greatwall.terrain;

import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.SampleKind;
import local.plaincraft.greatwall.model.WallTypes.WorkState;

/** Owner-confined natural-terrain input. No chunks, registries or futures cross this boundary. */
public interface NaturalTerrainOracle extends AutoCloseable {
    /**
     * The biome at one column, or {@code null} when this oracle cannot answer.
     *
     * <p>Deliberately a default: a biome lookup is an optimisation for the siting and hydro
     * stages, not a contract every oracle owes. An oracle that returns null simply leaves the
     * caller on its old water-based decision.
     */
    default String biomeKeyAt(int x, int z) { return null; }
    interface ColumnCursor { boolean done(); }
    ColumnCursor begin(int x, int z, SampleKind kind);
    WorkState advance(ColumnCursor cursor, int primitiveLimit);
    ColumnSample finish(ColumnCursor cursor);
    /**
     * Releases a cursor this caller will not finish.
     *
     * <p>A begun cursor stays the oracle's active column until it is finished, and the next
     * {@link #begin} on the same oracle fails rather than sampling a different column. A caller that
     * gives up on a column - a step budget, an aborted slice - must therefore hand the cursor back,
     * or the oracle stays poisoned for every later column. Oracles that keep no active state inherit
     * the no-op default.
     */
    default void abandon(ColumnCursor cursor) {}
    String fingerprint();
    /** Only analytic reference fixtures opt in; native production inputs must use asynchronous lanes. */
    default boolean supportsSynchronousDiagnostics() { return false; }
    @Override void close();
}
