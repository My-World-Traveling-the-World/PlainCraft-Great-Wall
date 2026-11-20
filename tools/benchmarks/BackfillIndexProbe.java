package local.plaincraft.greatwall.generation;

import com.google.gson.GsonBuilder;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;

/** 固定快照原型的建树和查询分开计价；不把动态队列维护成本写成零。 */
public final class BackfillIndexProbe {
    private static final ThreadMXBean MEMORY=(ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static volatile Object retained;
    public static void main(String[] args) throws Exception {
        var cases=new ArrayList<Map<String,Object>>();
        for(String name:List.of("sparse","dense","small")) {
            var keys=new ArrayList<ChunkKey>();int size=name.equals("small")?64:16384;
            for(int i=0;i<size;i++)keys.add(new ChunkKey("minecraft:overworld",
                    name.equals("dense")?i%2-1:(i%128)*32,name.equals("dense")?i%2-1:(i/128)*32));
            var index=new BackfillCandidateIndex(keys);
            int examined=0;
            for(int i=0;i<16;i++) {
                var query=index.select(64,true,i*13,i*7);
                if(!BackfillCandidateSelection.select(keys,64,true,i*13,i*7).equals(query.keys()))
                    throw new AssertionError("完整64项及同距次序不等价: "+name);
                examined+=query.examined();
            }
            for(int i=0;i<3;i++){measure(keys,index,false);measure(keys,index,true);new BackfillCandidateIndex(keys);}
            double[][] old=new double[9][],now=new double[9][],build=new double[9][];
            for(int i=0;i<9;i++) {
                long bytes=MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();
                retained=new BackfillCandidateIndex(keys);
                build[i]=new double[]{(System.nanoTime()-start)/1e6,
                        MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytes};
                if((i&1)==0){old[i]=measure(keys,index,false);now[i]=measure(keys,index,true);}
                else{now[i]=measure(keys,index,true);old[i]=measure(keys,index,false);}
            }
            var row=new LinkedHashMap<String,Object>();
            row.put("name",name);row.put("pending",size);row.put("queries_per_sample",16);
            row.put("old_query_ms_p50",median(old,0));row.put("index_query_ms_p50",median(now,0));
            row.put("old_bytes_per_query",median(old,1));row.put("index_bytes_per_query",median(now,1));
            row.put("build_ms_p50",median(build,0));row.put("build_bytes_p50",median(build,1));
            row.put("mean_examined",examined/16.0);cases.add(row);
        }
        var evidence=Map.of("scope","immutable snapshot prototype, excludes dynamic queue maintenance",
                "warmups",3,"samples",9,"cases",cases);
        String json=new GsonBuilder().setPrettyPrinting().create().toJson(evidence)+"\n";
        Files.writeString(Path.of(args[0]),json);System.out.print(json);
    }
    private static double[] measure(List<ChunkKey> keys,BackfillCandidateIndex index,boolean indexed) {
        long bytes=MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();
        for(int i=0;i<16;i++)retained=indexed?index.select(64,true,i*13,i*7)
                :BackfillCandidateSelection.select(keys,64,true,i*13,i*7);
        return new double[]{(System.nanoTime()-start)/1e6,
                (MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytes)/16.0};
    }
    private static double median(double[][] rows,int column) {
        double[] values=Arrays.stream(rows).mapToDouble(row->row[column]).sorted().toArray();
        return values[values.length/2];
    }
}
