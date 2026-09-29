package local.plaincraft.greatwall.terrain;

import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.SampleKind;
import local.plaincraft.greatwall.model.WallTypes.WorkState;

/** Owner-confined natural-terrain input. No chunks, registries or futures cross this boundary. */
public interface NaturalTerrainOracle extends AutoCloseable {
    interface ColumnCursor { boolean done(); }
    ColumnCursor begin(int x, int z, SampleKind kind);
    WorkState advance(ColumnCursor cursor, int primitiveLimit);
    ColumnSample finish(ColumnCursor cursor);
    String fingerprint();
    @Override void close();
}
