package local.plaincraft.greatwall.lifecycle;

import local.plaincraft.greatwall.planning.FineHeightIndex;

/** 启动精采样只交给编译一次；停服或失败后拒绝迟到结果。 */
final class StartupSampleHandoff {
    enum State { NEW, RUNNING, READY, FAILED, TAKEN, CLOSED }

    record Claim(State state, FineHeightIndex index) {
        boolean waiting() { return state == State.NEW || state == State.RUNNING; }
    }

    private Claim pending = new Claim(State.NEW, null);

    synchronized boolean begin() {
        // 扫描与安装入口都能启动采样；只有取得同一会话的启动权才读缓存和排队。
        if (pending.state() != State.NEW) return false;
        pending = new Claim(State.RUNNING, null);
        return true;
    }

    synchronized boolean complete(FineHeightIndex result) {
        if (pending.state() != State.RUNNING) return false;
        pending = new Claim(result == null ? State.FAILED : State.READY, result);
        return true;
    }

    synchronized Claim claim() {
        var result = pending;
        // 转移所有权后，会话不再保留大索引；读取和清空必须在同一个临界区内。
        if (result.state() == State.READY) pending = new Claim(State.TAKEN, null);
        return result;
    }

    synchronized boolean settled() {
        return !pending.waiting();
    }

    synchronized void close() {
        pending = new Claim(State.CLOSED, null);
    }
}
