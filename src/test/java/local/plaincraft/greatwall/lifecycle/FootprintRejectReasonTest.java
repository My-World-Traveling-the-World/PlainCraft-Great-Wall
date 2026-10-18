package local.plaincraft.greatwall.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.8.435：被拒基座必须自报原因。
 *
 * <p>真机那局所有拒绝行都是 `water=false support=false relief=false` —— 也就是**什么都没说**，
 * 因为真正的原因是提前退出路径（一根 censored 列），而那条路径把三个标志都留成了 false。
 * 这个顺序就是修复：censored 最先命名，避免把「因为一列测不准而水位/支撑越界」误报成水或支撑问题。
 */
final class FootprintRejectReasonTest {

    @Test
    void anUncertifiedColumnIsNamedBeforeAnyBoundBreach() {
        assertEquals("censored", RootSearch.rejectReason(true, true, true, true));
        assertEquals("censored", RootSearch.rejectReason(true, false, false, false));
    }

    @Test
    void theBoundsKeepTheirOwnNames() {
        assertEquals("support", RootSearch.rejectReason(false, true, false, false));
        assertEquals("water", RootSearch.rejectReason(false, false, true, false));
        assertEquals("relief", RootSearch.rejectReason(false, false, false, true));
    }

    @Test
    void aRefusalWithNoFlagStillHasAReason() {
        assertEquals("other", RootSearch.rejectReason(false, false, false, false));
    }
}
