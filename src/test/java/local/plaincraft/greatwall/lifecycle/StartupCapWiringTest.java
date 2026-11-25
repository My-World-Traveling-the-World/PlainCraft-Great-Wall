package local.plaincraft.greatwall.lifecycle;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 行为夹具验证补采产物，另守住正式会话必须消费该产物，防止只给测试接线。 */
class StartupCapWiringTest {
    @Test void productionPublishesWithPreparedTerrainAndCoverage() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.exists(root.resolve("src/main/java/local/plaincraft/greatwall/lifecycle/ProductionSession.java")))root=root.getParent();
        assertNotNull(root,"缺源码时不能跳过正式接线检查");
        var compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler);
        try(var manager=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8)) {
            var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,manager.getJavaFileObjects(
                    root.resolve("src/main/java/local/plaincraft/greatwall/lifecycle/ProductionSession.java").toFile()));
            var methods=new ArrayList<MethodTree>();
            new TreeScanner<Void,Void>() {
                @Override public Void visitMethod(MethodTree m,Void p){if(m.getName().contentEquals("publishOrFail"))methods.add(m);return super.visitMethod(m,p);}
            }.scan(task.parse().iterator().next(),null);
            assertEquals(1,methods.size());var calls=new ArrayList<MethodInvocationTree>();
            new TreeScanner<Void,Void>() {
                @Override public Void visitMethodInvocation(MethodInvocationTree call,Void p){calls.add(call);return super.visitMethodInvocation(call,p);}
            }.scan(methods.getFirst().getBody(),null);
            assertEquals(1,calls.stream().filter(c->c.getMethodSelect().toString().endsWith("StartupCapEvidence.prepare")).count());
            var publish=calls.stream().filter(c->c.getMethodSelect().toString().equals("publishPlanningWindow")).toList();
            assertEquals(1,publish.size());
            var prepared=assertInstanceOf(MethodInvocationTree.class,publish.getFirst().getArguments().get(2));
            assertEquals("prepared.compiler",prepared.getMethodSelect().toString(),"正式入口不能继续使用补列前的provider或coverage");
        }
    }
}
