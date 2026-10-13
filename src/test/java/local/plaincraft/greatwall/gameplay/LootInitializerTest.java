package local.plaincraft.greatwall.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import local.plaincraft.greatwall.gameplay.GameplayTypes.LootAssignment;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link LootInitializer#assignment}, which had no test.
 *
 * <h2>Why this exists</h2>
 * The class makes two promises in its own documentation that are exactly the kind a unit test can
 * hold it to: the choice is "pure", and the seed is derived from recipe and site identity "never a
 * random UUID, so a freshly generated chest is reproducible and a looted chest never refills on
 * reload". Both fail silently if broken - a non-deterministic seed refills looted chests on every
 * reload, and a wrong table choice quietly changes what a fort or tower contains - and neither shows
 * up in a crash.
 *
 * <p>Pure logic: no world, no chest, no registries. The NBT and chest paths are covered elsewhere by
 * {@code LootResourceGameTests}; this file covers the table decision and the seed.
 */
class LootInitializerTest {
    private static final Point3i POS = new Point3i(10, 64, 20);
    /**
     * Beyond {@code NEAR_FORT_Q8}, which is 600 blocks in Q8 = 600 * 256 = 153600. An earlier version
     * of this file used 100000 and called it "far", but 100000 Q8 is about 390 blocks - inside the
     * threshold - so a tower expected to be "ordinary" was correctly given supplies. The constant is
     * now derived from the same unit the production class documents.
     */
    private static final long NEAR_FORT_Q8 = 600L * 256L;
    private static final long FAR_FROM_FORT = NEAR_FORT_Q8 + 1L;

    private static WorldRecipe recipe(long seed) {
        return new WorldRecipe(new UUID(0, 1), "fixture", seed, "minecraft:overworld", new Point3i(0, 64, 0),
                new Axis(new Point3i(0, 64, 0), 1 << 30, 0), new DimensionBounds(-64, 320, 29_999_000),
                new GenerationConfig(1, 1, 2048, 4, 150000, 256, 512, 8, 64L << 20, 64L << 20, 32L << 20,
                        "a".repeat(64)),
                "a".repeat(64), "a".repeat(64), "a".repeat(64), "a".repeat(64));
    }

    private static SiteRecord site(SiteKind kind, long ordinal) {
        return new SiteRecord(new SiteId("fixture", kind, ordinal), 0L,
                new Frame(new Point2q(0L, 0L), 1 << 30, 0, 84, false),
                new Bounds(-8, 60, -8, 8, 92, 8), SiteState.PLANNED, Optional.empty(), Optional.empty());
    }

    private static Optional<LootAssignment> assign(SiteKind kind, long ordinal, DecayState decay,
            int containerId) {
        return new LootInitializer().assignment(recipe(3), site(kind, ordinal), decay, containerId, POS,
                FAR_FROM_FORT);
    }

    @Test void ruinedAndBuriedSitesCarryNoLoot() {
        // A ruined or buried site must not hand out a chest; that is the difference between a wall the
        // player can loot and one they cannot.
        for (SiteKind kind : new SiteKind[] { SiteKind.FORT, SiteKind.ENEMY_TOWER }) {
            assertTrue(assign(kind, 1, DecayState.RUIN, 0).isEmpty(), kind + " ruined must be empty");
            assertTrue(assign(kind, 1, DecayState.BURIED, 0).isEmpty(), kind + " buried must be empty");
        }
    }

    @Test void aFortUsesItsThreeContainerSlotsInOrder() {
        assertEquals("plaincraft_great_wall:chests/armory", assign(SiteKind.FORT, 1, DecayState.INTACT, 0)
                .orElseThrow().table(), "container 0 of a fort is the armory");
        assertEquals("plaincraft_great_wall:chests/supplies", assign(SiteKind.FORT, 1, DecayState.INTACT, 1)
                .orElseThrow().table(), "container 1 of a fort is the supplies chest");
        assertEquals("plaincraft_great_wall:chests/ordinary", assign(SiteKind.FORT, 1, DecayState.INTACT, 2)
                .orElseThrow().table(), "any further container of a fort is ordinary");
    }

    @Test void theRecipeSeedDecidesWhetherATowerHasALootTableAtAll() {
        // The class promises purity: two calls with identical inputs must agree.
        var a = assign(SiteKind.ENEMY_TOWER, 7, DecayState.INTACT, 0);
        var b = assign(SiteKind.ENEMY_TOWER, 7, DecayState.INTACT, 0);
        assertEquals(a, b, "identical inputs must produce an identical assignment");

        // Which table a tower uses is decided by decay and distance, NOT by the seed; the seed decides
        // only whether a chest exists at all. An earlier version of this test asserted that the table
        // varies with the seed and failed for that reason. What the seed must influence is presence, so
        // that is what is asserted: over many seeds some towers carry loot and some do not.
        int withLoot = 0;
        for (long seed = 0; seed < 64; seed++) {
            if (new LootInitializer().assignment(recipe(seed), site(SiteKind.ENEMY_TOWER, 7),
                    DecayState.INTACT, 0, POS, FAR_FROM_FORT).isPresent()) withLoot++;
        }
        assertTrue(withLoot > 0 && withLoot < 64,
                "the recipe seed must influence whether a chest exists, saw " + withLoot + " of 64");
    }

    @Test void mostEnemyTowersAreDeliberatelyEmpty() {
        // The comment states a stable 35% of towers carry a chest. Asserting the actual rate over many
        // ordinals pins the "most towers are empty" design rather than a single sample.
        int withLoot = 0;
        int total = 400;
        for (long ordinal = 1; ordinal <= total; ordinal++) {
            if (assign(SiteKind.ENEMY_TOWER, ordinal, DecayState.INTACT, 0).isPresent()) withLoot++;
        }
        assertTrue(withLoot > 0, "some towers must carry loot, otherwise 35% is not being applied");
        assertTrue(withLoot < total, "some towers must be empty, otherwise 35% is not being applied");
        double rate = withLoot / (double) total;
        assertTrue(rate > 0.20 && rate < 0.50,
                "the intended rate is 35%; observed " + withLoot + "/" + total + " = " + rate);
    }

    @Test
    void anIntactTowerNearAFortGetsSuppliesButAFarOneDoesNot() {
        // Distance decides supplies versus ordinary for an intact tower: one beside a fort is
        // provisioned, a remote one is not. This is the only branch that depends on world layout, and
        // the boundary is inclusive at NEAR_FORT_Q8 (600 blocks).
        long ordinal = towerWithLoot();
        var near = new LootInitializer().assignment(recipe(3), site(SiteKind.ENEMY_TOWER, ordinal),
                DecayState.INTACT, 0, POS, NEAR_FORT_Q8);
        var justOutside = new LootInitializer().assignment(recipe(3), site(SiteKind.ENEMY_TOWER, ordinal),
                DecayState.INTACT, 0, POS, FAR_FROM_FORT);

        // Presence is seed-decided and independent of distance, so both must agree on that half.
        assertEquals(near.isPresent(), justOutside.isPresent(),
                "distance must not decide whether a chest exists, only which table it uses");
        if (near.isEmpty()) return;

        assertEquals("plaincraft_great_wall:chests/supplies", near.orElseThrow().table(),
                "an intact tower exactly at the 600-block boundary counts as near");
        assertEquals("plaincraft_great_wall:chests/ordinary", justOutside.orElseThrow().table(),
                "one Q8 unit beyond the boundary is ordinary");
    }

    @Test void aPartialTowerUsesTheWeatheredTable() {
        long ordinal = towerWithLoot();
        var assignment = assign(SiteKind.ENEMY_TOWER, ordinal, DecayState.PARTIAL, 0);
        if (assignment.isEmpty()) return; // presence is seed-decided; the rate test covers that
        assertEquals("plaincraft_great_wall:chests/weathered", assignment.orElseThrow().table(),
                "a partially decayed tower uses the weathered table, not supplies and not ordinary");
    }

    @Test void otherSiteKindsNeverCarryLoot() {
        // Only forts and enemy towers are loot sites; anything else must be refused rather than given
        // a default table, or a gate or beacon would start dropping chests.
        for (SiteKind kind : SiteKind.values()) {
            if (kind == SiteKind.FORT || kind == SiteKind.ENEMY_TOWER) continue;
            assertTrue(assign(kind, 1, DecayState.INTACT, 0).isEmpty(),
                    kind + " must not carry loot");
        }
    }

    @Test void theSeedAndInputHashAreStableAndNeverRandom() {
        // "never a random UUID" is the promise that stops a looted chest refilling on reload: the seed
        // must be a function of identity alone, and the input hash must be the recipe hash.
        var first = assign(SiteKind.FORT, 4, DecayState.INTACT, 0).orElseThrow();
        var second = assign(SiteKind.FORT, 4, DecayState.INTACT, 0).orElseThrow();
        assertEquals(first.seed(), second.seed(), "the loot seed must be stable across calls");
        assertEquals(recipe(3).recipeHash(), first.inputHash(),
                "the input hash must be the recipe identity, not a per-call value");

        long otherOrdinal = assign(SiteKind.FORT, 5, DecayState.INTACT, 0).orElseThrow().seed();
        assertFalse(first.seed() == otherOrdinal,
                "a different site identity must produce a different seed, otherwise two chests share loot");
        assertFalse(first.seed() == 0L, "a zero seed would mean the hash never ran");
    }

    @Test void malformedInputIsRejectedRatherThanGuessed(  ) {
        var initializer = new LootInitializer();
        var recipe = recipe(3);
        var site = site(SiteKind.FORT, 1);
        assertThrows(NullPointerException.class,
                () -> initializer.assignment(null, site, DecayState.INTACT, 0, POS, 0L));
        assertThrows(NullPointerException.class,
                () -> initializer.assignment(recipe, null, DecayState.INTACT, 0, POS, 0L));
        assertThrows(NullPointerException.class,
                () -> initializer.assignment(recipe, site, null, 0, POS, 0L));
        assertThrows(NullPointerException.class,
                () -> initializer.assignment(recipe, site, DecayState.INTACT, 0, null, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> initializer.assignment(recipe, site, DecayState.INTACT, -1, POS, 0L));
    }

    /** The first ordinal (from 1) whose tower actually carries a chest, so branch tests can reach them. */
    private static long towerWithLoot() {
        for (long ordinal = 1; ordinal <= 500; ordinal++) {
            if (assign(SiteKind.ENEMY_TOWER, ordinal, DecayState.INTACT, 0).isPresent()) return ordinal;
        }
        throw new IllegalStateException("no tower in the first 500 ordinals carries loot");
    }
}
