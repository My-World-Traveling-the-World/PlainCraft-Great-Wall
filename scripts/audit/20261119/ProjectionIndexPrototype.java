package local.plaincraft.greatwall.planning;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import local.plaincraft.greatwall.component.ImportedRibbonProjection;
import local.plaincraft.greatwall.component.WallSection;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 仅供审计对拍的空间索引原型；正式生成仍调用原来的全扫实现。 */
final class ProjectionIndexPrototype {
    private record Edge(Station a,Station b,long dx,long dz,long denominator,long length,int order) {}
    private record Node(long x0,long z0,long x1,long z1,int from,int to,Node left,Node right) {}
    private static final class Query {
        final long x,z;
        long best=Long.MAX_VALUE,numerator,visits;
        Edge chosen;
        Query(int x,int z){this.x=x*256L+128;this.z=z*256L+128;}
    }
    private final Edge[] edges;
    private final Node root;
    private final long first,last;
    long totalVisits;

    ProjectionIndexPrototype(ImportedRibbonProjection original)throws Exception{
        var field=ImportedRibbonProjection.class.getDeclaredField("edges");field.setAccessible(true);
        var source=(List<?>)field.get(original);
        edges=new Edge[source.size()];
        for(int i=0;i<edges.length;i++){
            Object value=source.get(i);
            edges[i]=new Edge((Station)read(value,"a"),(Station)read(value,"b"),
                    (Long)read(value,"dx"),(Long)read(value,"dz"),(Long)read(value,"denominator"),
                    (Long)read(value,"length"),i);
        }
        first=edges[0].a().globalSQ8();last=edges[edges.length-1].b().globalSQ8();
        root=build(0,edges.length);
    }
    private static Object read(Object value,String name)throws Exception{
        Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);
    }
    private Node build(int from,int to){
        long x0=Long.MAX_VALUE,z0=Long.MAX_VALUE,x1=Long.MIN_VALUE,z1=Long.MIN_VALUE;
        for(int i=from;i<to;i++){
            var e=edges[i];var a=e.a().point();var b=e.b().point();
            x0=Math.min(x0,Math.min(a.xQ8(),b.xQ8()));x1=Math.max(x1,Math.max(a.xQ8(),b.xQ8()));
            z0=Math.min(z0,Math.min(a.zQ8(),b.zQ8()));z1=Math.max(z1,Math.max(a.zQ8(),b.zQ8()));
        }
        if(to-from<=4)return new Node(x0,z0,x1,z1,from,to,null,null);
        boolean x=x1-x0>=z1-z0;
        Arrays.sort(edges,from,to,Comparator.<Edge>comparingLong(e->x
                ?e.a().point().xQ8()+e.b().point().xQ8():e.a().point().zQ8()+e.b().point().zQ8())
                .thenComparingInt(Edge::order));
        int mid=(from+to)>>>1;
        return new Node(x0,z0,x1,z1,from,to,build(from,mid),build(mid,to));
    }
    WallSection.ColumnContract contract(int x,int z){
        var q=new Query(x,z);visit(root,q);totalVisits+=q.visits;
        var e=q.chosen;if(e==null)return null;
        long s=e.a().globalSQ8()+FixedMath.mulDiv(e.b().globalSQ8()-e.a().globalSQ8(),q.numerator,e.denominator());
        long lateral=FixedMath.mulDiv(e.dx(),q.z-e.a().point().zQ8(),e.length())
                -FixedMath.mulDiv(e.dz(),q.x-e.a().point().xQ8(),e.length());
        int y=e.a().walkY()+(int)FixedMath.roundDiv((long)(e.b().walkY()-e.a().walkY())*q.numerator,e.denominator());
        return new WallSection.ColumnContract(s,FixedMath.floorBlock(lateral),y);
    }
    private void visit(Node node,Query q){
        if(distance(node,q)>q.best)return;
        if(node.left()!=null){
            var a=node.left();var b=node.right();
            if(distance(a,q)>distance(b,q)){var swap=a;a=b;b=swap;}
            visit(a,q);visit(b,q);return;
        }
        for(int i=node.from();i<node.to();i++){
            q.visits++;
            var e=edges[i];var a=e.a().point();var b=e.b().point();
            long boxX=Math.max(0,Math.max(Math.min(a.xQ8(),b.xQ8())-q.x,q.x-Math.max(a.xQ8(),b.xQ8())));
            long boxZ=Math.max(0,Math.max(Math.min(a.zQ8(),b.zQ8())-q.z,q.z-Math.max(a.zQ8(),b.zQ8())));
            if(boxX*boxX+boxZ*boxZ>q.best)continue;
            long raw=(q.x-a.xQ8())*e.dx()+(q.z-a.zQ8())*e.dz();
            long n=Math.clamp(raw,0,e.denominator());
            long dx=q.x-a.xQ8()-FixedMath.mulDiv(e.dx(),n,e.denominator());
            long dz=q.z-a.zQ8()-FixedMath.mulDiv(e.dz(),n,e.denominator());
            long d=dx*dx+dz*dz;
            // 任意遍历顺序下，相等距离仍选原始站序最靠前的一条边。
            if(d>q.best||d==q.best&&q.chosen!=null&&e.order()>=q.chosen.order())continue;
            q.best=d;q.chosen=e;
            q.numerator=e.a().globalSQ8()==first&&raw<0||e.b().globalSQ8()==last&&raw>e.denominator()?raw:n;
        }
    }
    private static long distance(Node n,Query q){
        long x=Math.max(0,Math.max(n.x0()-q.x,q.x-n.x1()));
        long z=Math.max(0,Math.max(n.z0()-q.z,q.z-n.z1()));
        return x*x+z*z;
    }
}
