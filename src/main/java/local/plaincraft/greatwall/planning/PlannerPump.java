package local.plaincraft.greatwall.planning;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import local.plaincraft.greatwall.GreatWallMod;

/** 捕获调度任务被 Future 收走的异常，并区分是否有权结束开局等待。 */
final class PlannerPump extends ScheduledThreadPoolExecutor {
    /** 推进器单线程；每次结束必须清除权限标志，普通任务不能继承启动门。 */
    private final ThreadLocal<Boolean> inPrewarmBody = ThreadLocal.withInitial(() -> Boolean.FALSE);

    PlannerPump(ThreadFactory factory) { super(1, factory); }

    /** 持有门的任务先打标，抛错后执行器仍能识别。 */
    void enterPrewarmBody() { inPrewarmBody.set(Boolean.TRUE); }

    @Override protected void afterExecute(Runnable task, Throwable failure) {
        super.afterExecute(task, failure);
        boolean gateHolder = Boolean.TRUE.equals(inPrewarmBody.get());
        inPrewarmBody.remove();
        Throwable actual = failure;
        if (actual == null && task instanceof java.util.concurrent.FutureTask<?> future && future.isDone()) {
            try {
                future.get();
            } catch (java.util.concurrent.CancellationException expected) {
                return;
            } catch (java.util.concurrent.ExecutionException failed) {
                actual = failed.getCause();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (actual == null) return;
        if (!gateHolder) {
            // 普通调度任务报错不能替开局阶段宣告结案。
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PUMP_TASK_FAILED_GATE_KEPT reason={} - the startup "
                    + "gates stay closed; this task does not own them.", actual.toString());
            return;
        }
        GreatWallMod.LOGGER.error("[GreatWall:S1C] PUMP_TASK_FAILED the wall releases its startup gates; "
                + "reason={}", actual.toString(), actual);
        // 启动任务失败须结束加载等待，同时保持严格就绪为假。
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
    }
}
