package local.plaincraft.greatwall.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 累计量按窗口归还，不能结束一个窗口就把其他窗口的驻留量一并清零。 */
class CompileWindowMemoryTest {
    @Test void overlappingWindowsAddAndCloseIndependently() {
        var memory=new CompileWindowMemory();var a=memory.open();var b=memory.open();
        a.sample(new CompileWindowMemory.Retained(100,20,7));
        b.sample(new CompileWindowMemory.Retained(200,30,11));
        assertEquals(368,memory.snapshot().totalBytes());assertEquals(368,memory.snapshot().peakBytes());
        assertEquals(2,memory.snapshot().activeWindows());assertEquals(2,memory.snapshot().peakWindows());
        a.sample(new CompileWindowMemory.Retained(10,2,1));
        assertEquals(254,memory.snapshot().totalBytes(),"采样替换本窗口，而非重复累加历史");
        a.close();a.close();
        assertEquals(241,memory.snapshot().totalBytes());assertEquals(1,memory.snapshot().activeWindows());
        assertEquals(368,memory.snapshot().peakBytes());
        assertThrows(IllegalStateException.class,()->a.sample(new CompileWindowMemory.Retained(1,1,1)));
        b.close();assertEquals(0,memory.snapshot().totalBytes());assertEquals(0,memory.snapshot().activeWindows());
        assertEquals(2,memory.snapshot().closedWindows());
    }

    @Test void repeatedFailedScopesLeaveNoHistoricalResidencyOrCrossSessionStats() {
        var memory=new CompileWindowMemory();
        for(int i=0;i<1000;i++) {
            assertThrows(IllegalStateException.class,()->{
                try(var window=memory.open()) {
                    window.sample(new CompileWindowMemory.Retained(1024,64,128));
                    throw new IllegalStateException("受控窗口失败");
                }
            });
        }
        var stats=memory.snapshot();
        assertEquals(0,stats.activeWindows());assertEquals(0,stats.totalBytes());
        assertEquals(1216,stats.peakBytes());assertEquals(1000,stats.openedWindows());assertEquals(1000,stats.closedWindows());
        var nextSession=new CompileWindowMemory();
        assertEquals(0,nextSession.snapshot().openedWindows());assertEquals(0,nextSession.snapshot().peakBytes());
    }
}
