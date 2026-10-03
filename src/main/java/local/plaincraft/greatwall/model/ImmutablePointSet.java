package local.plaincraft.greatwall.model;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import local.plaincraft.greatwall.model.WallTypes.Point3i;

/** Detached point snapshots without SetN's linear probing of dense coordinate hashes. */
public final class ImmutablePointSet extends AbstractSet<Point3i> {
    private final Set<Point3i> points;

    private ImmutablePointSet(Set<Point3i> source) {
        var copy = new HashSet<>(source);
        copy.forEach(Objects::requireNonNull);
        points = Collections.unmodifiableSet(copy);
    }

    public static Set<Point3i> copyOf(Set<Point3i> source) {
        Objects.requireNonNull(source, "source");
        if (source.isEmpty()) return Set.of();
        // The decoder already froze this private backing set under its scratch reservation.
        return source instanceof ImmutablePointSet ? source : new ImmutablePointSet(source);
    }

    @Override public Iterator<Point3i> iterator() { return points.iterator(); }
    @Override public int size() { return points.size(); }
    @Override public boolean contains(Object point) {
        return points.contains(Objects.requireNonNull(point, "point"));
    }
    @Override public boolean add(Point3i point) { throw readOnly(); }
    @Override public boolean addAll(Collection<? extends Point3i> values) { throw readOnly(); }
    @Override public boolean remove(Object point) { throw readOnly(); }
    @Override public boolean removeAll(Collection<?> values) { throw readOnly(); }
    @Override public boolean retainAll(Collection<?> values) { throw readOnly(); }
    @Override public boolean removeIf(Predicate<? super Point3i> filter) { throw readOnly(); }
    @Override public void clear() { throw readOnly(); }

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("immutable point snapshot");
    }
}
