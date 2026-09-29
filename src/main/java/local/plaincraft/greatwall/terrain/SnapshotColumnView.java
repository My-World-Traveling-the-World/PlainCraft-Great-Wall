package local.plaincraft.greatwall.terrain;

import java.nio.charset.StandardCharsets;
import java.util.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Immutable T0-backed column. Uncaptured heights never fall through to the live world. */
public final class SnapshotColumnView implements SurfaceProbe.ColumnView {
    private final int x,z;
    private final CaptureMetadata metadata;
    private final ColumnEvidence column;
    private final Map<Integer,TerrainTile> pages;
    private final BlockCatalog catalog;
    private final String hash;
    public SnapshotColumnView(int x,int z,CaptureMetadata metadata,Map<TerrainTile.Key,TerrainTile> tiles,BlockCatalog catalog) {
        this.x=x;this.z=z;this.metadata=Objects.requireNonNull(metadata);this.catalog=Objects.requireNonNull(catalog);
        this.column=metadata.columns().stream().filter(c->c.x()==x&&c.z()==z).findFirst()
                .orElseThrow(()->new WallFailure(FailureCode.T0_MISSING,"missing column summary"));
        var pages=new TreeMap<Integer,TerrainTile>();
        tiles.values().stream().filter(t->t.key().dimension().equals(metadata.coverage().chunk().dimension())
                &&t.key().chunkX()==Math.floorDiv(x,16)&&t.key().chunkZ()==Math.floorDiv(z,16))
                .forEach(t->{if(pages.put(Math.floorDiv(t.key().minY(),128)*128,t)!=null)throw new IllegalArgumentException("duplicate page");});
        this.pages=Map.copyOf(pages);
        this.hash=DeterministicHash.sha256((metadata.hash()+"\n"+x+":"+z+"\n"+pages.values().stream().map(TerrainTile::hash).toList()).getBytes(StandardCharsets.UTF_8));
    }
    @Override public String state(int y){
        var tile=pages.get(Math.floorDiv(y,128)*128);
        if(tile==null||y<tile.key().minY()||y>tile.key().maxY())throw new WallFailure(FailureCode.T0_MISSING,"uncaptured column y="+y);
        return tile.state(Math.floorMod(x,16),y,Math.floorMod(z,16));
    }
    @Override public boolean solidSupport(int y){return catalog.flags(state(y)).support();}
    @Override public boolean protectedAt(int y){
        var p=new Point3i(x,y,z);
        return catalog.flags(state(y)).protectedBlock()||metadata.blockEntities().contains(p)||metadata.immutableBlocks().contains(p)
                ||metadata.protectedStructures().stream().anyMatch(b->x>=b.minX()&&x<b.maxXExclusive()&&z>=b.minZ()&&z<b.maxZExclusive()&&y>=b.minY()&&y<b.maxYExclusive());
    }
    @Override public boolean water(int y){return catalog.flags(state(y)).water();}
    @Override public boolean airOrReplaceablePlant(int y){return catalog.flags(state(y)).replaceablePlant();}
    @Override public int highestNonPlantFeet(){return column.upperCensored()?Integer.MAX_VALUE:column.highestNonPlantFeet();}
    @Override public String hash(){return hash;}
}
