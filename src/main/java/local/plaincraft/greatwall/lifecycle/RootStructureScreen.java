package local.plaincraft.greatwall.lifecycle;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Immutable, lock-external root votes. This port must never request FEATURES or await a recipe. */
public interface RootStructureScreen extends AutoCloseable {
    record Candidate(Point3i origin, int radiusIndex, int angleIndex, List<Axis> axes,
                     int endpointAxis, Bounds endpointBounds) {
        public Candidate(Point3i origin,int radiusIndex,int angleIndex,List<Axis> axes) {
            this(origin,radiusIndex,angleIndex,axes,-1,null);
        }
        public Candidate {
            Objects.requireNonNull(origin);
            axes=List.copyOf(axes);
            if (endpointBounds==null ? endpointAxis!=-1 : endpointAxis<0 || endpointAxis>=axes.size())
                throw new IllegalArgumentException("端楼保护证据必须明确对应一个走向");
            if(radiusIndex<0||radiusIndex>=RootSearch.RADIUS_COUNT||angleIndex<0||angleIndex>=36||axes.size()!=5)
                throw new IllegalArgumentException("frozen root candidate indices required");
        }
    }
    record Vote(int radiusIndex, int angleIndex, List<Integer> allowedAxes, List<String> reasons) {
        public Vote {
            allowedAxes=List.copyOf(allowedAxes); reasons=List.copyOf(reasons);
            if(radiusIndex<0||radiusIndex>=RootSearch.RADIUS_COUNT||angleIndex<0||angleIndex>=36||reasons.size()!=5
                    ||allowedAxes.stream().anyMatch(i->i<0||i>=5)
                    ||allowedAxes.stream().distinct().count()!=allowedAxes.size())
                throw new IllegalArgumentException("complete five-axis structure vote required");
            for(int axis=0;axis<5;axis++)if(allowedAxes.contains(axis)!=reasons.get(axis).equals("CLEAR"))
                throw new IllegalArgumentException("allowed axes require an explicit clear proof");
        }
    }
    CompletableFuture<List<Vote>> inspect(List<Candidate> candidates);
    CompletableFuture<Void> drained();
    void close();
}
