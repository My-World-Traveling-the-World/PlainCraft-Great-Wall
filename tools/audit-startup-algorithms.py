# 文件职责：提取当前源码中的二分与缓存键，独立检验算法反例和替代内核成本。
# 为什么不用游戏耗时冒充结论：这些夹具只有单调密度、模拟缓存键及合成坐标，不运行NoiseChunk。
# 不修改生产源码，不构建/部署模组；报告明确区分原型、源码行为及历史游戏日志。
from pathlib import Path
import subprocess
import argparse,tempfile
parser=argparse.ArgumentParser(description='仅执行源码提取的Java算法夹具，不构建或启动模组。')
parser.add_argument('--java-home',required=True)
parser.add_argument('--output',required=True)
args=parser.parse_args()
java_home=Path(args.java_home)
output=Path(args.output).resolve();output.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parents[1]
scratch=tempfile.TemporaryDirectory(prefix='wall-algorithm-audit-')
work=Path(scratch.name)
try_javac=java_home/'bin/javac.exe';try_java=java_home/'bin/java.exe'
if not try_javac.is_file():try_javac=java_home/'bin/javac';try_java=java_home/'bin/java'
assert try_javac.is_file() and try_java.is_file()
source=(root/'src/main/java/local/plaincraft/greatwall/terrain/NoiseSurfaceSampler.java').read_text(encoding='utf-8')
start=source.index('    private int preliminarySurface(int blockX, int blockZ) {')
end=source.index(chr(10)+'    }',start)+6
method=source[start:end]
assert method.count('int mid = (lo + hi) >>> 1;')==1
method=method.replace('private int preliminarySurface(int blockX, int blockZ)', 'private static int actualSearch(int blockX, int blockZ)')
fixed=method.replace('actualSearch(', 'fixedSearch(').replace('(lo + hi) >>> 1','lo + ((hi - lo) >>> 1)')
assert method.count('while (hi - lo > 1) {')==1
# 旧中点遇到异常边界可能失去收敛性，探针设步数限额，避免审计进程无限等待。
method=method.replace('while (hi - lo > 1) {','int auditSteps=0; while (hi - lo > 1) { if(++auditSteps>128)return Integer.MIN_VALUE;')
java='''import java.util.*;
public class WallAlgorithmProbe {
 static int threshold;
 static final class NoiseSettings {int minY(){return -64;}int height(){return 384;}}
 static final NoiseSettings noiseSettings=new NoiseSettings();
 static final class RandomState {Router router(){return new Router();}}
 static final class Router {DensityFunction initialDensityWithoutJaggedness(){return new DensityFunction();}}
 static final RandomState randomState=new RandomState();
 static final class Mth {static int floorDiv(int a,int b){return Math.floorDiv(a,b);}}
 static final class DensityFunction {
  record SinglePointContext(int x,int y,int z){}
  double compute(SinglePointContext p){return p.y()<=threshold?1:0;}
 }
''' + method + chr(10)+fixed+'''
 record Key(int x,int z,int ordinal){}
 record Ranked(Key key,double distance){}
 static List<Key> full(List<Key> input,double cx,double cz) {
  var copy=new ArrayList<>(input);
  copy.sort(Comparator.comparingDouble(k->distance(k,cx,cz)));return copy.subList(0,Math.min(64,copy.size()));
 }
 static double distance(Key k,double cx,double cz){double dx=(k.x()<<4)+8-cx,dz=(k.z()<<4)+8-cz;return dx*dx+dz*dz;}
 static final Comparator<Ranked> BEST=Comparator.comparingDouble(Ranked::distance).thenComparingInt(r->r.key.ordinal);
 static List<Key> heap(List<Key> input,double cx,double cz){
  var q=new PriorityQueue<Ranked>(65,BEST.reversed());
  for(var key:input){var r=new Ranked(key,distance(key,cx,cz));if(q.size()<64)q.add(r);else if(BEST.compare(r,q.peek())<0){q.poll();q.add(r);}}
  var sorted=new ArrayList<>(q);sorted.sort(BEST);return sorted.stream().map(Ranked::key).toList();
 }
 static volatile Object blackhole;
 static long median(long[] xs){Arrays.sort(xs);return xs[xs.length/2];}
 public static void main(String[] args){
  int incorrect=0;
  for(threshold=-64;threshold<320;threshold++) {int old=actualSearch(0,0),now=fixedSearch(0,0);if(now!=threshold)throw new AssertionError("fixed:"+threshold+":"+now);if(old!=threshold)incorrect++;}
  System.out.println("SIGNED_SEARCH thresholds=384 oldWrong="+incorrect+" fixedWrong=0");
  for(int h:new int[]{-40,0,20,64,128}){threshold=h;System.out.println("SEARCH threshold="+h+" old="+actualSearch(0,0)+" fixed="+fixedSearch(0,0));}
  var random=new Random(17391);
  for(int n:new int[]{64,512,4096,65536}){
   var keys=new ArrayList<Key>();for(int i=0;i<n;i++)keys.add(new Key(random.nextInt(20000)-10000,random.nextInt(20000)-10000,i));
   for(int t=0;t<80;t++){double cx=random.nextInt(10000),cz=random.nextInt(10000);if(!full(keys,cx,cz).equals(heap(keys,cx,cz)))throw new AssertionError("parity");}
   var ties=new ArrayList<Key>();for(int i=0;i<n;i++)ties.add(new Key(i%2==0?-1:0,i%2==0?0:-1,i));
   if(!full(ties,0,0).equals(heap(ties,0,0)))throw new AssertionError("ties");
   for(int i=0;i<6;i++){blackhole=full(keys,134,926);blackhole=heap(keys,134,926);}
   long[] a=new long[9],b=new long[9];
   for(int i=0;i<9;i++){long t=System.nanoTime();blackhole=full(keys,134,926);a[i]=System.nanoTime()-t;t=System.nanoTime();blackhole=heap(keys,134,926);b[i]=System.nanoTime()-t;}
   System.out.printf("TOP64 n=%d oldP50Us=%.3f heapP50Us=%.3f parity=80+ties%n",n,median(a)/1000d,median(b)/1000d);
  }
  for(int n:new int[]{4096,16384,32768}){
   var values=new HashMap<Long,Integer>();for(int x=0;x<256&&values.size()<n;x++)for(int z=0;z<256&&values.size()<n;z++)values.put(((long)x<<32)^(z&0xffffffffL),x+z);
   long[] a=new long[7],b=new long[7];for(int i=0;i<10;i++){long t=System.nanoTime();blackhole=Map.copyOf(values);long costA=System.nanoTime()-t;t=System.nanoTime();blackhole=Collections.unmodifiableMap(new HashMap<>(values));long costB=System.nanoTime()-t;if(i>=3){a[i-3]=costA;b[i-3]=costB;}}
   System.out.printf("PACKED_MAP n=%d immutableP50Ms=%.3f privateHashP50Ms=%.3f%n",values.size(),median(a)/1e6,median(b)/1e6);
  }
 }
}
'''
dest=(work/'WallAlgorithmProbe.java');dest.write_text(java,encoding='utf-8')
subprocess.run([str(try_javac),'-encoding','UTF-8',str(dest)],check=True)
run=subprocess.run([str(try_java),'-Xmx512m','-cp',str(work),'WallAlgorithmProbe'],check=True,stdout=subprocess.PIPE)
(output/'algorithm-probes.log').write_bytes(run.stdout)
print(run.stdout.decode())

root=Path(__file__).resolve().parents[1]/'src/main/java/local/plaincraft/greatwall'
source=(root/'terrain/NoiseSurfaceSampler.java').read_text(encoding='utf-8')
cache=source[source.index('this.cache = SHARED.computeIfAbsent'):source.index('ignored -> new java.util.concurrent.ConcurrentHashMap<>());')+len('ignored -> new java.util.concurrent.ConcurrentHashMap<>());')]
java='''import java.util.*;import java.util.concurrent.*;
public class WallCacheProbe {
 static final ConcurrentHashMap<String,ConcurrentHashMap<Long,String>> SHARED=new ConcurrentHashMap<>();
 record Settings(int seaLevel){} record Noise(int minY,int height){}
 final ConcurrentHashMap<Long,String> cache;
 WallCacheProbe(Object randomState,Settings generatorSettings,Noise noiseSettings){
''' + cache + '''
 }
 public static void main(String[] args){
  var settings=new Settings(63);var noise=new Noise(-64,384);
  var lanes=new ArrayList<WallCacheProbe>();
  for(int i=0;i<16;i++)lanes.add(new WallCacheProbe(new Object(),settings,noise));
  lanes.getFirst().cache.put(123L,"same terrain cell");
  System.out.println("EXTRACTED_CACHE_KEY lanes=16 maps="+SHARED.size()+" lane1SeesLane0="+lanes.get(1).cache.containsKey(123L));
  lanes.clear();System.out.println("STATIC_CACHE_AFTER_DROPPING_LANES maps="+SHARED.size());
 }
}'''
p=(work/'WallCacheProbe.java');p.write_text(java,encoding='utf-8')
subprocess.run([str(try_javac),'-encoding','UTF-8',str(p)],check=True)
run=subprocess.run([str(try_java),'-cp',str(work),'WallCacheProbe'],check=True,stdout=subprocess.PIPE)
print(run.stdout.decode());(output/'cache-key-probe.log').write_bytes(run.stdout)

scratch.cleanup()
