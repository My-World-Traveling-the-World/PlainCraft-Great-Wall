package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StartupStructureGateTest {
    @Test
    void successfulEmptyScanInstallsBeforeOpening() {
        var gate = new StartupStructureGate();
        var installed = new AtomicReference<List<String>>();
        assertTrue(gate.complete(List.<String>of(), null, boxes -> {
            assertFalse(gate.ready());
            installed.set(boxes);
        }, failure -> fail(failure)));
        assertEquals(List.of(), installed.get());
        assertTrue(gate.ready());
    }

    @Test
    void failedOrCancelledScanCannotInstallOrReopenWithALateResult() {
        for (var error : List.of(new IllegalStateException("scan"), new CancellationException("close"))) {
            var gate = new StartupStructureGate();
            var reported = new AtomicReference<Throwable>();
            // 传入部分结果，证明有故障时即便扫描已经找到结构也不得发布。
            assertFalse(gate.complete(List.of("partial"), error, boxes -> fail("安装了失败结果"), reported::set));
            assertSame(error, reported.get());
            assertFalse(gate.complete(List.of("late"), null, boxes -> fail("失败后仍安装"), failure -> fail(failure)));
            assertFalse(gate.ready());
        }
    }

    @Test
    void indexInstallationFailureKeepsGateClosed() {
        var gate = new StartupStructureGate();
        var error = new IllegalArgumentException("index");
        var reported = new AtomicReference<Throwable>();
        assertFalse(gate.complete(List.of("house"), null, boxes -> { throw error; }, reported::set));
        assertSame(error, reported.get());
        assertFalse(gate.ready());
    }

    @Test
    void productionBulkCallsShareTheGate() throws Exception {
        var source = Files.readString(Path.of("src/main/java/local/plaincraft/greatwall/lifecycle/ProductionSession.java"));
        // 组件行为测试不启动 Minecraft；另守住两条生命周期通知不得绕回直接调用。
        assertEquals(1, source.lines().filter(line -> line.contains(".afterRoadBulk();")).count());
        assertTrue(source.contains("if (!structureGate.ready() || state.containsKey(\"closed\")"));
        assertTrue(source.contains("boolean installed = structureGate.complete(structures, error,"));
    }
}
