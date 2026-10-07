package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link BarrierFixtureLayout}, the S0 laboratory's chunk layout, which had no test.
 *
 * <h2>Why this exists</h2>
 * The layout maps a round number to four chunk positions and back again, and the S0 barrier rounds
 * are driven from that mapping. Its failure mode is arithmetic rather than loud: if {@code round()}
 * and {@code owners()} stopped agreeing, a round would operate on chunks another round owns - or on
 * none - and the barrier test would still look like it ran. The round-trip is therefore asserted
 * directly rather than assumed.
 *
 * <p>Pure logic: the only type involved is {@link ChunkPos}. {@code S0-01} is the only caller, and
 * the class header notes the footprint is unrelated to the wall route.
 */
class BarrierFixtureLayoutTest {
    private static final int ORIGIN = 2000;

    @Test void roundsMustBeInRange() {
        assertThrows(IllegalArgumentException.class, () -> new BarrierFixtureLayout(0),
                "zero rounds is rejected rather than producing an empty layout");
        assertThrows(IllegalArgumentException.class, () -> new BarrierFixtureLayout(101),
                "101 rounds exceeds the documented 1..100");
        assertEquals(1, new BarrierFixtureLayout(1).rounds(), "1 is the smallest legal value");
        assertEquals(100, new BarrierFixtureLayout(100).rounds(), "100 is the largest legal value");
    }

    @Test void anOutOfRangeRoundIsRejectedRatherThanReturningWrongChunks() {
        var layout = new BarrierFixtureLayout(5);
        assertThrows(IllegalArgumentException.class, () -> layout.owners(-1));
        assertThrows(IllegalArgumentException.class, () -> layout.owners(5),
                "round == rounds is out of range, not a wrap-around");
    }

    @Test void everyRoundOwnsTheSameNumberOfDistinctChunks() {
        // Four chunks per round, and never the same chunk twice: owners() builds its list by hand, so a
        // copy-paste slip there would show up here.
        var layout = new BarrierFixtureLayout(12);
        for (int round = 0; round < 12; round++) {
            List<ChunkPos> owners = layout.owners(round);
            assertEquals(4, owners.size(), "round " + round + " must own four chunks");
            assertEquals(4, new HashSet<>(owners).size(), "round " + round + " must not repeat a chunk");
        }
    }

    @Test void roundsDoNotShareChunks() {
        // Overlapping rounds would make two rounds fight over the same terrain, which is the one thing
        // a per-round laboratory must not do.
        var layout = new BarrierFixtureLayout(30);
        var seen = new HashSet<ChunkPos>();
        for (int round = 0; round < 30; round++) {
            for (ChunkPos pos : layout.owners(round)) {
                assertTrue(seen.add(pos), "chunk " + pos + " is claimed by more than one round");
            }
        }
    }

    @Test void roundAndOwnersAgreeInBothDirections() {
        // The round trip is the property the layout exists for. An off-by-one in either direction
        // would silently point a round at its neighbour's terrain.
        var layout = new BarrierFixtureLayout(40);
        for (int round = 0; round < 40; round++) {
            for (ChunkPos pos : layout.owners(round)) {
                assertEquals(round, layout.round(pos),
                        "round(" + pos + ") must return the round that owns it");
                assertTrue(layout.owns(pos), "an owned chunk must report owns() true");
            }
        }
    }

    @Test void aChunkOutsideTheFootprintIsNotOwned() {
        var layout = new BarrierFixtureLayout(100);
        assertFalse(layout.owns(new ChunkPos(ORIGIN - 1, ORIGIN)), "west of the origin is outside");
        assertFalse(layout.owns(new ChunkPos(ORIGIN, ORIGIN - 1)), "north of the origin is outside");
        assertFalse(layout.owns(new ChunkPos(ORIGIN + 80, ORIGIN)), "the footprint is 80 chunks wide");
        assertFalse(layout.owns(new ChunkPos(ORIGIN, ORIGIN + 80)), "the footprint is 80 chunks deep");
        assertEquals(-1, layout.round(new ChunkPos(0, 0)), "an unrelated chunk reports round -1");
        assertEquals(-1, layout.round(new ChunkPos(ORIGIN + 2, ORIGIN)),
                "a chunk inside the 8x8 cell but outside the 2x2 owner square is not an owner");
        assertEquals(-1, layout.round(new ChunkPos(ORIGIN, ORIGIN + 2)),
                "the same holds on the other axis");
    }

    @Test void roundsBeyondTheConfiguredCountAreNotOwnedEvenInsideTheFootprint() {
        // The footprint is a fixed 80x80 region, so a short layout must stop claiming chunks past its
        // own round count rather than owning the whole region.
        var short_ = new BarrierFixtureLayout(1);
        assertTrue(short_.owns(new ChunkPos(ORIGIN, ORIGIN)), "round 0 is in range");
        assertFalse(short_.owns(new ChunkPos(ORIGIN + 8, ORIGIN)),
                "round 1 exists in the footprint but not in this layout");
        assertEquals(-1, short_.round(new ChunkPos(ORIGIN + 8, ORIGIN)),
                "and must report -1 rather than a round it does not have");
    }

    @Test void theCellOfAnOwnerAlwaysContainsThatOwner() {
        // cell() groups owners into the barrier's working sets; if a group omitted its own member the
        // caller would mutate chunks it did not pass in.
        var layout = new BarrierFixtureLayout(4);
        for (int round = 0; round < 4; round++) {
            for (ChunkPos owner : layout.owners(round)) {
                List<ChunkPos> cell = layout.cell(owner);
                assertTrue(cell.contains(owner), "cell(" + owner + ") must contain " + owner);
                assertTrue(layout.owners(round).containsAll(cell),
                        "a cell must not reach outside its own round");
                assertEquals(new HashSet<>(cell).size(), cell.size(), "a cell must not repeat a chunk");
            }
        }
    }

    @Test void aNonOwnerHasNoCell() {
        var layout = new BarrierFixtureLayout(4);
        assertThrows(IllegalArgumentException.class, () -> layout.cell(new ChunkPos(0, 0)),
                "asking for the cell of a non-owner must fail rather than return an empty group");
    }

    @Test void theFeatureSourceIsTheEasternNeighbourOfAnOwnedChunk() {
        // featureSource answers "is the chunk east of this one an owner". The direction is asserted
        // explicitly because an earlier version of this test assumed west and failed: the production
        // expression is owns(pos.x + 1, pos.z), and getting the sign wrong would put a feature on the
        // wrong side of its cell while still looking deliberate.
        var layout = new BarrierFixtureLayout(40);
        for (int round = 0; round < 40; round++) {
            for (ChunkPos owner : layout.owners(round)) {
                assertEquals(layout.owns(new ChunkPos(owner.x + 1, owner.z)), layout.featureSource(owner),
                        "featureSource(" + owner + ") must equal owns(east neighbour)");
            }
        }
        // A direction check that does not depend on the owner block's shape. Owners form a 2x2 block,
        // so comparing against the west neighbour does not discriminate (a second version of this test
        // asserted that and failed). The eastern half of the block is owned and the chunk beyond it is
        // not, so the query must return true for the first and false for the second.
        var block = layout.owners(0);
        var westOfBlock = block.stream().min(java.util.Comparator.comparingInt(p -> p.x)).orElseThrow();
        var eastOfBlock = block.stream().max(java.util.Comparator.comparingInt(p -> p.x)).orElseThrow();
        assertTrue(layout.featureSource(westOfBlock),
                "the western half of an owner block has an owner to its east");
        assertFalse(layout.featureSource(eastOfBlock),
                "the eastern half does not, so the query must differ between the two");
    }

    @Test void ownerChunksAreAllDistinctAcrossTheFullFootprint() {
        // Exhaustive over the whole 80x80 region: every chunk the layout claims must map back to
        // exactly one round, and the total claimed must equal four per round.
        int rounds = 100;
        var layout = new BarrierFixtureLayout(rounds);
        var byRound = new ArrayList<Set<ChunkPos>>();
        for (int round = 0; round < rounds; round++) byRound.add(new HashSet<>(layout.owners(round)));

        int claimed = 0;
        for (int dx = 0; dx < 80; dx++) {
            for (int dz = 0; dz < 80; dz++) {
                var pos = new ChunkPos(ORIGIN + dx, ORIGIN + dz);
                int round = layout.round(pos);
                if (round < 0) continue;
                claimed++;
                assertTrue(byRound.get(round).contains(pos),
                        pos + " is reported as round " + round + " but that round does not list it");
            }
        }
        assertEquals(rounds * 4, claimed,
                "the region must claim exactly four chunks per round and nothing else");
    }
}
