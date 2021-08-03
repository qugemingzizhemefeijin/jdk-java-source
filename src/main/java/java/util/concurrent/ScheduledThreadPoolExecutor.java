/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;

/**
 * A {@link ThreadPoolExecutor} that can additionally schedule
 * commands to run after a given delay, or to execute
 * periodically. This class is preferable to {@link java.util.Timer}
 * when multiple worker threads are needed, or when the additional
 * flexibility or capabilities of {@link ThreadPoolExecutor} (which
 * this class extends) are required.
 *
 * <p>Delayed tasks execute no sooner than they are enabled, but
 * without any real-time guarantees about when, after they are
 * enabled, they will commence. Tasks scheduled for exactly the same
 * execution time are enabled in first-in-first-out (FIFO) order of
 * submission.
 *
 * <p>When a submitted task is cancelled before it is run, execution
 * is suppressed. By default, such a cancelled task is not
 * automatically removed from the work queue until its delay
 * elapses. While this enables further inspection and monitoring, it
 * may also cause unbounded retention of cancelled tasks. To avoid
 * this, set {@link #setRemoveOnCancelPolicy} to {@code true}, which
 * causes tasks to be immediately removed from the work queue at
 * time of cancellation.
 *
 * <p>Successive executions of a task scheduled via
 * {@code scheduleAtFixedRate} or
 * {@code scheduleWithFixedDelay} do not overlap. While different
 * executions may be performed by different threads, the effects of
 * prior executions <a
 * href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * those of subsequent ones.
 *
 * <p>While this class inherits from {@link ThreadPoolExecutor}, a few
 * of the inherited tuning methods are not useful for it. In
 * particular, because it acts as a fixed-sized pool using
 * {@code corePoolSize} threads and an unbounded queue, adjustments
 * to {@code maximumPoolSize} have no useful effect. Additionally, it
 * is almost never a good idea to set {@code corePoolSize} to zero or
 * use {@code allowCoreThreadTimeOut} because this may leave the pool
 * without threads to handle tasks once they become eligible to run.
 *
 * <p><b>Extension notes:</b> This class overrides the
 * {@link ThreadPoolExecutor#execute(Runnable) execute} and
 * {@link AbstractExecutorService#submit(Runnable) submit}
 * methods to generate internal {@link ScheduledFuture} objects to
 * control per-task delays and scheduling.  To preserve
 * functionality, any further overrides of these methods in
 * subclasses must invoke superclass versions, which effectively
 * disables additional task customization.  However, this class
 * provides alternative protected extension method
 * {@code decorateTask} (one version each for {@code Runnable} and
 * {@code Callable}) that can be used to customize the concrete task
 * types used to execute commands entered via {@code execute},
 * {@code submit}, {@code schedule}, {@code scheduleAtFixedRate},
 * and {@code scheduleWithFixedDelay}.  By default, a
 * {@code ScheduledThreadPoolExecutor} uses a task type extending
 * {@link FutureTask}. However, this may be modified or replaced using
 * subclasses of the form:
 *
 *  <pre> {@code
 * public class CustomScheduledExecutor extends ScheduledThreadPoolExecutor {
 *
 *   static class CustomTask<V> implements RunnableScheduledFuture<V> { ... }
 *
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Runnable r, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(r, task);
 *   }
 *
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Callable<V> c, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(c, task);
 *   }
 *   // ... add constructors, etc.
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */
public class ScheduledThreadPoolExecutor
        extends ThreadPoolExecutor
        implements ScheduledExecutorService {

    /*
     * This class specializes ThreadPoolExecutor implementation by
     *
     * 1. Using a custom task type, ScheduledFutureTask for
     *    tasks, even those that don't require scheduling (i.e.,
     *    those submitted using ExecutorService execute, not
     *    ScheduledExecutorService methods) which are treated as
     *    delayed tasks with a delay of zero.
     *
     * 2. Using a custom queue (DelayedWorkQueue), a variant of
     *    unbounded DelayQueue. The lack of capacity constraint and
     *    the fact that corePoolSize and maximumPoolSize are
     *    effectively identical simplifies some execution mechanics
     *    (see delayedExecute) compared to ThreadPoolExecutor.
     *
     * 3. Supporting optional run-after-shutdown parameters, which
     *    leads to overrides of shutdown methods to remove and cancel
     *    tasks that should NOT be run after shutdown, as well as
     *    different recheck logic when task (re)submission overlaps
     *    with a shutdown.
     *
     * 4. Task decoration methods to allow interception and
     *    instrumentation, which are needed because subclasses cannot
     *    otherwise override submit methods to get this effect. These
     *    don't have any impact on pool control logic though.
     */

    /**
     * False if should cancel/suppress periodic tasks on shutdown.
     * <p>如果为false，则在线程池关闭的时候取消任务队列中的周期性任务并从任务队列中移除，默认为false
     */
    private volatile boolean continueExistingPeriodicTasksAfterShutdown;

    /**
     * False if should cancel non-periodic tasks on shutdown.
     * <p>如果为false，则在线程池关闭的时候取消任务队列中的非周期性任务，即只执行一次的任务，并从任务队列中移除，默认为true
     */
    private volatile boolean executeExistingDelayedTasksAfterShutdown = true;

    /**
     * True if ScheduledFutureTask.cancel should remove from queue
     * <p>如果ScheduledFutureTask被取消了是否将其移除
     */
    private volatile boolean removeOnCancel = false;

    /**
     * Sequence number to break scheduling ties, and in turn to
     * guarantee FIFO order among tied entries.
     * <p>用于生成表示定时任务的ScheduledFutureTask的sequenceNumber属性
     */
    private static final AtomicLong sequencer = new AtomicLong();

    /**
     * 返回当前纳秒时间
     */
    final long now() {
        return System.nanoTime();
    }

    /**
     * ScheduledFutureTask是ScheduledThreadPoolExecutor的内部类，表示一个延期执行的任务
     * @param <V>
     */
    private class ScheduledFutureTask<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {

        /** Sequence number to break ties FIFO */
        // 原子递增生成的一个序列号
        private final long sequenceNumber;

        /** The time the task is enabled to execute in nanoTime units */
        // 执行该任务的开始时间，以纳秒为单位
        private long time;

        /**
         * Period in nanoseconds for repeating tasks.  A positive
         * value indicates fixed-rate execution.  A negative value
         * indicates fixed-delay execution.  A value of 0 indicates a
         * non-repeating task.
         */
        // 重复执行下一次任务的间隔时间，单位是纳秒，如果是正值则表示是固定的速率执行
        // 如果是负值，则表示以固定的延时执行，如果0则表示不需要重复执行
        private final long period;

        /** The actual task to be re-enqueued by reExecutePeriodic */
        // 周期执行时将此实例再次添加到任务队列中，从而能够被再次执行
        RunnableScheduledFuture<V> outerTask = this;

        /**
         * Index into delay queue, to support faster cancellation.
         */
        // 用于保存当前实例在DelayedWorkQueue数组中的索引，方便从数组中移除的时候可以快速定位
        int heapIndex;

        /**
         * Creates a one-shot action with given nanoTime-based trigger time.
         */
        ScheduledFutureTask(Runnable r, V result, long ns) {
            super(r, result);
            this.time = ns;
            this.period = 0;
            // sequencer是ScheduledThreadPoolExecutor的属性
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        /**
         * Creates a periodic action with given nano time and period.
         */
        ScheduledFutureTask(Runnable r, V result, long ns, long period) {
            super(r, result);
            this.time = ns;
            this.period = period;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        /**
         * Creates a one-shot action with given nanoTime-based trigger time.
         */
        ScheduledFutureTask(Callable<V> callable, long ns) {
            super(callable);
            this.time = ns;
            this.period = 0;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        /**
         * 获取剩余的有效期，大于0表示延时未到，不能执行
         * @param unit 时间单位
         * @return 返回0或者-1表示已过期，返回一个正值表示剩余的有效期
         */
        public long getDelay(TimeUnit unit) {
            return unit.convert(time - now(), NANOSECONDS);
        }

        public int compareTo(Delayed other) {
            if (other == this) // compare zero if same object
                return 0;
            if (other instanceof ScheduledFutureTask) {
                ScheduledFutureTask<?> x = (ScheduledFutureTask<?>)other;
                // 比较两者的执行起始时间
                long diff = time - x.time;
                if (diff < 0)
                    return -1;
                else if (diff > 0)
                    return 1;
                // 起始时间一致，则比较序列号
                else if (sequenceNumber < x.sequenceNumber)
                    return -1;
                else
                    return 1;
            }
            // 如果不是ScheduledFutureTask实例，则比较剩余的有效期
            long diff = getDelay(NANOSECONDS) - other.getDelay(NANOSECONDS);
            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }

        /**
         * 如果这是一个周期性（不是一次性）动作，则返回 {@code true}。
         *
         * @return {@code true} if periodic
         */
        // 判断是否周期执行的任务
        public boolean isPeriodic() {
            return period != 0;
        }

        /**
         * Sets the next time to run for a periodic task.
         */
        private void setNextRunTime() {
            long p = period;
            if (p > 0) // 固定速率的，不考虑执行任务本身的耗时
                time += p;
            else
                // p小于0，固定延时的
                time = triggerTime(-p);
        }

        /**
         * 改写了父类FutureTask的实现
         * @param mayInterruptIfRunning 执行此任务的线程是否应该被中断；否则，允许完成正在进行的任务
         * @return boolean
         */
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            // removeOnCancel是ScheduledThreadPoolExecutor的属性，默认为false
            // heapIndex大于等于0说明该Task还在DelayedWorkQueue中
            if (cancelled && removeOnCancel && heapIndex >= 0)
                remove(this); // 从任务队列中将其移除
            return cancelled;
        }

        /**
         * 覆盖 FutureTask 版本，以便在定期重新排队。
         */
        public void run() {
            // 是否周期执行的
            boolean periodic = isPeriodic();
            if (!canRunInCurrentRunState(periodic)) // 如果当前线程池不能执行该任务，则取消
                cancel(false);
            else if (!periodic) // 非周期执行任务，调用父类的run方法
                ScheduledFutureTask.super.run();
            else if (ScheduledFutureTask.super.runAndReset()) { // runAndReset方法正常执行后不保存结果，不更新状态
                // 重新计算下一次运行的起始时间
                setNextRunTime();
                // 将outerTask提交到任务队列，outerTask通常就是this,从而实现周期性执行
                reExecutePeriodic(outerTask);
            }
        }
    }

    /**
     * 判断当前状态是否能够被运行，如果是RUNNING必能运行，否则SHUTDOWN的话，则需要判断传入的值
     *
     * @param periodic 如果此任务是周期性的，则为 true，如果延迟则为 false
     */
    boolean canRunInCurrentRunState(boolean periodic) {
        return isRunningOrShutdown(periodic ?
                                   continueExistingPeriodicTasksAfterShutdown :
                                   executeExistingDelayedTasksAfterShutdown);
    }

    /**
     * Main execution method for delayed or periodic tasks.  If pool
     * is shut down, rejects the task. Otherwise adds task to queue
     * and starts a thread, if necessary, to run it.  (We cannot
     * prestart the thread to run the task because the task (probably)
     * shouldn't be run yet.)  If the pool is shut down while the task
     * is being added, cancel and remove it if required by state and
     * run-after-shutdown parameters.
     *
     * @param task the task
     */
    private void delayedExecute(RunnableScheduledFuture<?> task) {
        if (isShutdown()) // 如果已关闭则拒绝掉该任务
            reject(task);
        else {
            // 线程池是正常的，添加到任务队列中
            super.getQueue().add(task);
            // 再一次检查线程池的状态，如果已关闭且不能执行该任务则从任务队列中移除，并取消该任务
            if (isShutdown() &&
                !canRunInCurrentRunState(task.isPeriodic()) &&
                remove(task))
                task.cancel(false);
            else
                // 线程池是正常的，尝试增加一个执行线程
                ensurePrestart();
        }
    }

    /**
     * 重新排队定期任务，除非当前运行状态排除它。除了删除任务而不是拒绝之外，与delayedExecute 的想法相同。
     *
     * @param task the task
     */
    void reExecutePeriodic(RunnableScheduledFuture<?> task) {
        if (canRunInCurrentRunState(true)) {
            super.getQueue().add(task); // 将该任务添加到任务队列中
            // 再次检查当前线程池是否可以执行任务，如果不能则将其从任务队列移除并取消掉
            if (!canRunInCurrentRunState(true) && remove(task))
                task.cancel(false);
            else
                // 可以正常执行，尝试增加一个执行任务的线程
                ensurePrestart();
        }
    }

    /**
     * 取消并清除由于关闭策略而不应运行的所有任务的队列。在 super.shutdown 中调用。
     *
     * <p>
     * 该方法是执行shutdown方法时回调的方法，
     * 如果executeExistingDelayedTasksAfterShutdown为false，则将任务队列中所有非周期性的任务从队列中移除并取消掉任务，
     * 如果continueExistingPeriodicTasksAfterShutdown为false，则将任务队列中所有周期性的任务从队列中移除并取消掉任务，
     * 前者默认为true，后者默认为false。父类的实现是不接受新的任务，但是任务队列中任务会继续执行。
     */
    @Override void onShutdown() {
        BlockingQueue<Runnable> q = super.getQueue();
        boolean keepDelayed =
            getExecuteExistingDelayedTasksAfterShutdownPolicy();
        boolean keepPeriodic =
            getContinueExistingPeriodicTasksAfterShutdownPolicy();
        if (!keepDelayed && !keepPeriodic) {
            // 如果这两个属性都是false，则遍历任务队列，将所有任务都取消掉，并清空任务队列
            for (Object e : q.toArray())
                if (e instanceof RunnableScheduledFuture<?>)
                    ((RunnableScheduledFuture<?>) e).cancel(false);
            q.clear();
        }
        else {
            // 如果有一个为true
            // Traverse snapshot to avoid iterator exceptions
            for (Object e : q.toArray()) {
                if (e instanceof RunnableScheduledFuture) {
                    RunnableScheduledFuture<?> t =
                        (RunnableScheduledFuture<?>)e;
                    // 如果t是周期性任务，则取!keepPeriodic，否则取!keepDelayed
                    if ((t.isPeriodic() ? !keepPeriodic : !keepDelayed) ||
                        t.isCancelled()) { // also remove if already cancelled 如果t被取消了
                        if (q.remove(t)) // 将t从任务队列中移除并取消
                            t.cancel(false);
                    }
                }
            }
        }
        tryTerminate();
    }

    /**
     * Modifies or replaces the task used to execute a runnable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * @param runnable the submitted Runnable
     * @param task the task created to execute the runnable
     * @param <V> the type of the task's result
     * @return a task that can execute the runnable
     * @since 1.6
     */
    // 子类可改写此方法，修改或者替换掉task
    protected <V> RunnableScheduledFuture<V> decorateTask(
        Runnable runnable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * Modifies or replaces the task used to execute a callable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * @param callable the submitted Callable
     * @param task the task created to execute the callable
     * @param <V> the type of the task's result
     * @return a task that can execute the callable
     * @since 1.6
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(
        Callable<V> callable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * Creates a new {@code ScheduledThreadPoolExecutor} with the
     * given core pool size.
     * <p>
     * 最大的线程数是Integer.MAX_VALUE，keepAliveTime是0，即如果没有待执行的任务，
     * 线程池的线程会立即退出，使用的阻塞队列实现是DelayedWorkQueue
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     */
    public ScheduledThreadPoolExecutor(int corePoolSize) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue());
    }

    /**
     * Creates a new {@code ScheduledThreadPoolExecutor} with the
     * given initial parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException if {@code threadFactory} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       ThreadFactory threadFactory) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue(), threadFactory);
    }

    /**
     * Creates a new ScheduledThreadPoolExecutor with the given
     * initial parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException if {@code handler} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue(), handler);
    }

    /**
     * Creates a new ScheduledThreadPoolExecutor with the given
     * initial parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException if {@code threadFactory} or
     *         {@code handler} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       ThreadFactory threadFactory,
                                       RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue(), threadFactory, handler);
    }

    /**
     * Returns the trigger time of a delayed action.
     */
    private long triggerTime(long delay, TimeUnit unit) {
        return triggerTime(unit.toNanos((delay < 0) ? 0 : delay));
    }

    /**
     * 返回延迟动作的触发时间.
     */
    long triggerTime(long delay) {
        // 当前时间加上固定延时，如果delay大于Long的最大值的一半，则走后面的overflowFree
        return now() +
            ((delay < (Long.MAX_VALUE >> 1)) ? delay : overflowFree(delay));
    }

    /**
     * Constrains the values of all delays in the queue to be within
     * Long.MAX_VALUE of each other, to avoid overflow in compareTo.
     * This may occur if a task is eligible to be dequeued, but has
     * not yet been, while some other task is added with a delay of
     * Long.MAX_VALUE.
     */
    private long overflowFree(long delay) {
        Delayed head = (Delayed) super.getQueue().peek();
        if (head != null) {
            long headDelay = head.getDelay(NANOSECONDS);
            if (headDelay < 0 && (delay - headDelay < 0))
                delay = Long.MAX_VALUE + headDelay;
        }
        return delay;
    }

    /**
     *
     * 只执行一遍，延迟指定的时间
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public ScheduledFuture<?> schedule(Runnable command,
                                       long delay,
                                       TimeUnit unit) {
        if (command == null || unit == null)
            throw new NullPointerException();
        // 可改写decorateTask对执行的任务做些预处理
        RunnableScheduledFuture<?> t = decorateTask(command,
            new ScheduledFutureTask<Void>(command, null,
                                          // triggerTime计算该任务开始执行的时间
                                          triggerTime(delay, unit)));
        // 将任务添加到队列中
        delayedExecute(t);
        return t;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                                           long delay,
                                           TimeUnit unit) {
        if (callable == null || unit == null)
            throw new NullPointerException();
        RunnableScheduledFuture<V> t = decorateTask(callable,
            new ScheduledFutureTask<V>(callable,
                                       triggerTime(delay, unit)));
        delayedExecute(t);
        return t;
    }

    /**
     *
     * 以固定的间隔周期的执行某个任务，间隔周期是前后两次任务的开始时间计算的，不考虑执行任务本身的耗时
     *
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     * @throws IllegalArgumentException   {@inheritDoc}
     */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                  long initialDelay,
                                                  long period,
                                                  TimeUnit unit) {
        if (command == null || unit == null)
            throw new NullPointerException();
        if (period <= 0)
            throw new IllegalArgumentException();
        ScheduledFutureTask<Void> sft =
            new ScheduledFutureTask<Void>(command,
                                          null,
                                          triggerTime(initialDelay, unit), // 起始时间
                                          unit.toNanos(period)); // 间隔的周期
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        // 通常情况下t就是sft，两者指向同一实例，当任务执行完一遍后会把outerTask重新添加到任务队列中
        // 从而实现周期性的执行某个任务
        sft.outerTask = t;
        // 提交到任务队列中
        delayedExecute(t);
        return t;
    }

    /**
     *
     * 以固定的延时周期的执行某个任务，上一个任务执行完成到下一个任务开始执行的时间间隔是固定的，即会考虑执行任务本身的耗时
     *
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     * @throws IllegalArgumentException   {@inheritDoc}
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                     long initialDelay,
                                                     long delay,
                                                     TimeUnit unit) {
        if (command == null || unit == null)
            throw new NullPointerException();
        if (delay <= 0)
            throw new IllegalArgumentException();
        ScheduledFutureTask<Void> sft =
            new ScheduledFutureTask<Void>(command,
                                          null,
                                          triggerTime(initialDelay, unit), // 执行任务的起始时间
                                          unit.toNanos(-delay)); // 间隔的周期，为负值，表示固定的延时
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        sft.outerTask = t;
        delayedExecute(t);
        return t;
    }

    /**
     * Executes {@code command} with zero required delay.
     * This has effect equivalent to
     * {@link #schedule(Runnable,long,TimeUnit) schedule(command, 0, anyUnit)}.
     * Note that inspections of the queue and of the list returned by
     * {@code shutdownNow} will access the zero-delayed
     * {@link ScheduledFuture}, not the {@code command} itself.
     *
     * <p>A consequence of the use of {@code ScheduledFuture} objects is
     * that {@link ThreadPoolExecutor#afterExecute afterExecute} is always
     * called with a null second {@code Throwable} argument, even if the
     * {@code command} terminated abruptly.  Instead, the {@code Throwable}
     * thrown by such a task can be obtained via {@link Future#get}.
     *
     * @throws RejectedExecutionException at discretion of
     *         {@code RejectedExecutionHandler}, if the task
     *         cannot be accepted for execution because the
     *         executor has been shut down
     * @throws NullPointerException {@inheritDoc}
     */
    // 重写了父类ThreadPoolExecutor的方法
    public void execute(Runnable command) {
        // 立即执行，没有延时
        schedule(command, 0, NANOSECONDS);
    }

    // Override AbstractExecutorService methods

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public Future<?> submit(Runnable task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Runnable task, T result) {
        return schedule(Executors.callable(task, result), 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Callable<T> task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * Sets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow} or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @param value if {@code true}, continue after shutdown, else don't
     * @see #getContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
        continueExistingPeriodicTasksAfterShutdown = value;
        if (!value && isShutdown())
            onShutdown();
    }

    /**
     * Gets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow} or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @return {@code true} if will continue after shutdown
     * @see #setContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    // 默认为false
    public boolean getContinueExistingPeriodicTasksAfterShutdownPolicy() {
        return continueExistingPeriodicTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @param value if {@code true}, execute after shutdown, else don't
     * @see #getExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
        executeExistingDelayedTasksAfterShutdown = value;
        if (!value && isShutdown())
            onShutdown();
    }

    /**
     * Gets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @return {@code true} if will execute after shutdown
     * @see #setExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    // 默认为true
    public boolean getExecuteExistingDelayedTasksAfterShutdownPolicy() {
        return executeExistingDelayedTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @param value if {@code true}, remove on cancellation, else don't
     * @see #getRemoveOnCancelPolicy
     * @since 1.7
     */
    public void setRemoveOnCancelPolicy(boolean value) {
        removeOnCancel = value;
    }

    /**
     * Gets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @return {@code true} if cancelled tasks are immediately removed
     *         from the queue
     * @see #setRemoveOnCancelPolicy
     * @since 1.7
     */
    public boolean getRemoveOnCancelPolicy() {
        return removeOnCancel;
    }

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * Invocation has no additional effect if already shut down.
     *
     * <p>This method does not wait for previously submitted tasks to
     * complete execution.  Use {@link #awaitTermination awaitTermination}
     * to do that.
     *
     * <p>If the {@code ExecuteExistingDelayedTasksAfterShutdownPolicy}
     * has been set {@code false}, existing delayed tasks whose delays
     * have not yet elapsed are cancelled.  And unless the {@code
     * ContinueExistingPeriodicTasksAfterShutdownPolicy} has been set
     * {@code true}, future executions of existing periodic tasks will
     * be cancelled.
     *
     * @throws SecurityException {@inheritDoc}
     */
    public void shutdown() {
        super.shutdown();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution.
     *
     * <p>This method does not wait for actively executing tasks to
     * terminate.  Use {@link #awaitTermination awaitTermination} to
     * do that.
     *
     * <p>There are no guarantees beyond best-effort attempts to stop
     * processing actively executing tasks.  This implementation
     * cancels tasks via {@link Thread#interrupt}, so any task that
     * fails to respond to interrupts may never terminate.
     *
     * @return list of tasks that never commenced execution.
     *         Each element of this list is a {@link ScheduledFuture},
     *         including those tasks submitted using {@code execute},
     *         which are for scheduling purposes used as the basis of a
     *         zero-delay {@code ScheduledFuture}.
     * @throws SecurityException {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        return super.shutdownNow();
    }

    /**
     * Returns the task queue used by this executor.  Each element of
     * this queue is a {@link ScheduledFuture}, including those
     * tasks submitted using {@code execute} which are for scheduling
     * purposes used as the basis of a zero-delay
     * {@code ScheduledFuture}.  Iteration over this queue is
     * <em>not</em> guaranteed to traverse tasks in the order in
     * which they will execute.
     *
     * @return the task queue
     */
    public BlockingQueue<Runnable> getQueue() {
        return super.getQueue();
    }

    /**
     * DelayedWorkQueue 是一个先进先出的，自动扩容的且支持排序的阻塞队列，用于保存延迟执行的任务ScheduledFutureTask，
     * 其排序的规则就是ScheduledFutureTask类的compareTo方法的实现，
     * 每次从任务队列中取出的节点都是当前队列中执行任务的起始时间最早的节点，即time属性最小的节点。
     * 从队列中获取待执行的任务时会校验当前时间是否超过了任务的起始时间，只有超过了才会将其从任务队列中移除然后开始执行，
     * 该类是实现任务延期执行的核心。
     * <br><br>
     * <p>DelayedWorkQueue是对DelayQueue的优化，DelayQueue中是直接使用PriorityQueue来实现排序的，
     * DelayedWorkQueue把实现PriorityQueue中二叉堆的这部分给揉了进来，
     * 并且在添加元素时会把元素所在的数组索引保存到ScheduledFutureTask的heapIndex属性中，
     * 方便移除该节点时可以快速定位，避免遍历数组。
     */
    static class DelayedWorkQueue extends AbstractQueue<Runnable>
        implements BlockingQueue<Runnable> {

        /*
         * DelayedWorkQueue 基于类似 DelayQueue 和 PriorityQueue 中的基于堆的数据结构，
         * 除了每个 ScheduledFutureTask 还将其索引记录到堆数组中。这消除了在取消时查找任务的需要，
         * 大大加快了删除速度（从 O(n) 下降到 O(log n)），并减少了垃圾保留，否则在清除之前等待元素上升到顶部会发生垃圾保留。
         * 但是因为队列中也可能包含不是 ScheduledFutureTasks 的 RunnableScheduledFutures，我们不能保证有这样的索引可用，
         * 在这种情况下我们会回退到线性搜索。 （我们预计大多数任务不会被修饰，并且速度更快的情况会更常见。）
         *
         * 所有堆操作都必须记录索引更改——主要是在 siftUp 和 siftDown 中。删除后，任务的 heapIndex 设置为 -1。
         * 请注意，ScheduledFutureTasks 最多可以在队列中出现一次（对于其他类型的任务或工作队列不需要如此），因此由 heapIndex 唯一标识。
         *
         */

        /*
         * A DelayedWorkQueue is based on a heap-based data structure
         * like those in DelayQueue and PriorityQueue, except that
         * every ScheduledFutureTask also records its index into the
         * heap array. This eliminates the need to find a task upon
         * cancellation, greatly speeding up removal (down from O(n)
         * to O(log n)), and reducing garbage retention that would
         * otherwise occur by waiting for the element to rise to top
         * before clearing. But because the queue may also hold
         * RunnableScheduledFutures that are not ScheduledFutureTasks,
         * we are not guaranteed to have such indices available, in
         * which case we fall back to linear search. (We expect that
         * most tasks will not be decorated, and that the faster cases
         * will be much more common.)
         *
         * All heap operations must record index changes -- mainly
         * within siftUp and siftDown. Upon removal, a task's
         * heapIndex is set to -1. Note that ScheduledFutureTasks can
         * appear at most once in the queue (this need not be true for
         * other kinds of tasks or work queues), so are uniquely
         * identified by heapIndex.
         */

        /**
         * 默认的初始容量
         */
        private static final int INITIAL_CAPACITY = 16;

        /**
         * 保存RunnableScheduledFuture的数组
         */
        private RunnableScheduledFuture<?>[] queue = new RunnableScheduledFuture<?>[INITIAL_CAPACITY];

        /**
         * 互斥锁
         */
        private final ReentrantLock lock = new ReentrantLock();

        /**
         * 数组中元素的个数
         */
        private int size = 0;

        /**
         * Thread designated to wait for the task at the head of the
         * queue.  This variant of the Leader-Follower pattern
         * (http://www.cs.wustl.edu/~schmidt/POSA/POSA2/) serves to
         * minimize unnecessary timed waiting.  When a thread becomes
         * the leader, it waits only for the next delay to elapse, but
         * other threads await indefinitely.  The leader thread must
         * signal some other thread before returning from take() or
         * poll(...), unless some other thread becomes leader in the
         * interim.  Whenever the head of the queue is replaced with a
         * task with an earlier expiration time, the leader field is
         * invalidated by being reset to null, and some waiting
         * thread, but not necessarily the current leader, is
         * signalled.  So waiting threads must be prepared to acquire
         * and lose leadership while waiting.
         *
         * <p>
         * leader线程，即第一个等待获取根节点的线程，其等待时间大于根节点的有效期，按照根节点的有效期来等待，可避免根节点已过期而线程还在等待中；
         * leader线程获取根节点后，如果还有过期节点会唤醒下一个等待的线程
         */
        private Thread leader = null;

        /**
         * Condition signalled when a newer task becomes available at the
         * head of the queue or a new thread may need to become leader.
         *
         * <p>
         * 如果队列为空，在此Condition上等待
         */
        private final Condition available = lock.newCondition();

        /**
         * 如果 f 是 ScheduledFutureTask，则设置它的 heapIndex。
         */
        private void setIndex(RunnableScheduledFuture<?> f, int idx) {
            if (f instanceof ScheduledFutureTask)
                ((ScheduledFutureTask)f).heapIndex = idx;
        }

        /**
         * 将自下而上添加到其堆排序位置的筛选元素。仅在持有锁定时调用。
         */
        private void siftUp(int k, RunnableScheduledFuture<?> key) {
            while (k > 0) {
                // 找到父节点的索引并获取父节点
                int parent = (k - 1) >>> 1;
                RunnableScheduledFuture<?> e = queue[parent];
                // 如果大于父节点则终止遍历
                if (key.compareTo(e) >= 0)
                    break;
                // 如果小于父节点，将父节点复制到k上，继续往上遍历，直到找到比该节点小的父节点或者找到了根节点
                // 即根节点是最小的，从根节点往下，值越来越大
                queue[k] = e;
                setIndex(e, k);
                k = parent;
            }
            queue[k] = key;
            setIndex(key, k);
        }

        /**
         * 将自上而下添加到其堆排序位置的筛选元素。仅在持有锁定时调用。
         */
        private void siftDown(int k, RunnableScheduledFuture<?> key) {
            int half = size >>> 1;
            // 如果k大于或者等于half说明k是二叉堆中最下面一层的节点，无需重新排序
            while (k < half) {
                // 找到左子节点索引，n * 2 + 1，子节点都比父节点大
                int child = (k << 1) + 1;
                RunnableScheduledFuture<?> c = queue[child];
                // 找到右子节点索引，(n + 1) * 2
                int right = child + 1;
                if (right < size && c.compareTo(queue[right]) > 0)
                    // 如果左子节点比右子节点大，将c置为右子节点
                    c = queue[child = right];
                // key小于c节点则终止循环，将key保存到索引k处，此时k是该节点的父节点
                if (key.compareTo(c) <= 0)
                    break;
                // 如果key大于c节点，将c保存到k处，继续往下遍历，直到找到大于key的节点
                queue[k] = c;
                setIndex(c, k);
                k = child;
            }
            queue[k] = key;
            setIndex(key, k);
        }

        /**
         * 调整堆数组的大小。仅在持有锁定时调用。
         */
        private void grow() {
            int oldCapacity = queue.length;
            // 计算新容量，每次扩容原容量的50%
            int newCapacity = oldCapacity + (oldCapacity >> 1); // grow 50%
            if (newCapacity < 0) // overflow
                newCapacity = Integer.MAX_VALUE;
            // 复制原来的老数组
            queue = Arrays.copyOf(queue, newCapacity);
        }

        /**
         * 查找给定对象的索引，如果不存在，则为 -1。
         */
        private int indexOf(Object x) {
            if (x != null) {
                if (x instanceof ScheduledFutureTask) {
                    int i = ((ScheduledFutureTask) x).heapIndex;
                    // Sanity check; x could conceivably be a
                    // ScheduledFutureTask from some other pool.
                    // 这里是做了一个小优化，通过heapIndex属性保存该对象在数组中的索引，避免二次遍历
                    if (i >= 0 && i < size && queue[i] == x)
                        return i;
                } else {
                    for (int i = 0; i < size; i++)
                        // 遍历数组找到目标对象
                        if (x.equals(queue[i]))
                            return i;
                }
            }
            return -1;
        }

        public boolean contains(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return indexOf(x) != -1;
            } finally {
                lock.unlock();
            }
        }

        public boolean remove(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = indexOf(x);
                if (i < 0) // 不存在，返回false
                    return false;
                // -1表示该元素从数组中移除了
                setIndex(queue[i], -1);
                int s = --size;
                // 获取最后一个节点
                RunnableScheduledFuture<?> replacement = queue[s];
                queue[s] = null;
                if (s != i) {
                    // 将replacement移动到合适的位置
                    siftDown(i, replacement);
                    // i如果大于half，即位于底层节点时，会直接将moved元素保存到i处
                    if (queue[i] == replacement)
                        siftUp(i, replacement); // 将其作为一个新插入的节点，调整位置
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        public int size() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return size;
            } finally {
                lock.unlock();
            }
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }

        /**
         * 检索但不删除此队列的头部，如果此队列为空，则返回 {@code null}。<br>
         * 返回索引为0的最小的节点,即有效期最短的的任务
         * @return RunnableScheduledFuture<?>
         */
        public RunnableScheduledFuture<?> peek() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return queue[0];
            } finally {
                lock.unlock();
            }
        }

        public boolean offer(Runnable x) {
            if (x == null)
                throw new NullPointerException();
            // 类型强转成RunnableScheduledFuture
            RunnableScheduledFuture<?> e = (RunnableScheduledFuture<?>)x;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = size;
                if (i >= queue.length) // 队列满了则扩容
                    grow();
                size = i + 1;
                if (i == 0) { // 第一个元素
                    queue[0] = e;
                    setIndex(e, 0); // 设置heapIndex
                } else {
                    siftUp(i, e);
                }
                if (queue[0] == e) { // 如果队列原来是空的(queue[0]在上面赋值了)
                    leader = null;
                    // 唤醒因为队列是空的而等待的线程
                    available.signal();
                }
            } finally {
                lock.unlock();
            }
            return true;
        }

        public void put(Runnable e) {
            offer(e);
        }

        public boolean add(Runnable e) {
            return offer(e);
        }

        public boolean offer(Runnable e, long timeout, TimeUnit unit) {
            return offer(e);
        }

        /**
         * Performs common bookkeeping for poll and take: 用最后一个替换第一个元素并将其筛选下来. 仅在持有锁定时调用。
         * @param f the task to remove and return
         */
        private RunnableScheduledFuture<?> finishPoll(RunnableScheduledFuture<?> f) {
            // size减1后再赋值该s
            int s = --size;
            // 获取s对应的节点
            RunnableScheduledFuture<?> x = queue[s];
            queue[s] = null;
            if (s != 0)
                siftDown(0, x); // 将x放到合适的位置
            setIndex(f, -1);
            return f;
        }

        /**
         * 检索并移除此队列的头部，如果此队列为空，则返回 {@code null}。
         * @return RunnableScheduledFuture<?>
         */
        public RunnableScheduledFuture<?> poll() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first = queue[0];
                // 如果队列为空或者first元素未过期，则返回null
                if (first == null || first.getDelay(NANOSECONDS) > 0)
                    return null;
                else
                    // 将first节点移除，然后调整二叉堆
                    return finishPoll(first);
            } finally {
                lock.unlock();
            }
        }

        // 逻辑同poll，不过没有有效期判断
        public RunnableScheduledFuture<?> take() throws InterruptedException {
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                for (;;) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if (first == null)
                        available.await();
                    else {
                        long delay = first.getDelay(NANOSECONDS);
                        if (delay <= 0)
                            return finishPoll(first);
                        first = null; // don't retain ref while waiting
                        if (leader != null)
                            available.await();
                        else {
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                available.awaitNanos(delay);
                            } finally {
                                if (leader == thisThread)
                                    leader = null;
                            }
                        }
                    }
                }
            } finally {
                if (leader == null && queue[0] != null)
                    available.signal();
                lock.unlock();
            }
        }

        public RunnableScheduledFuture<?> poll(long timeout, TimeUnit unit)
            throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                for (;;) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if (first == null) {
                        if (nanos <= 0)
                            return null; // 等待超时，返回null
                        else
                            // 继续阻塞
                            nanos = available.awaitNanos(nanos);
                    } else { // first不为null
                        // 获取first有效期
                        long delay = first.getDelay(NANOSECONDS);
                        if (delay <= 0)
                            return finishPoll(first); // 已过期，移除并返回
                        if (nanos <= 0) // 未过期，等待超时，返回null
                            return null;
                        first = null; // don't retain ref while waiting
                        if (nanos < delay || leader != null)
                            // nanos < delay下，线程肯定会等待超时
                            // nanos >= delay 且leader不为null时，leader线程会先于该线程获取first节点
                            // 非leader线程按允许的最长时间等待
                            nanos = available.awaitNanos(nanos);
                        else {
                            // nanos大于等于delay
                            // leader线程按照剩余有效期等待
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                long timeLeft = available.awaitNanos(delay);
                                nanos -= delay - timeLeft;
                            } finally {
                                // leader线程被唤醒了，将leader置为null
                                if (leader == thisThread)
                                    leader = null;
                            }
                        }
                    }
                }
            } finally {
                // leader线程不为null，说明是其他的非leader线程等待超时了
                // leader线程被唤醒后会将leader置为null，如果还有节点，则唤醒下一个等待的线程
                if (leader == null && queue[0] != null)
                    available.signal();
                lock.unlock();
            }
        }

        public void clear() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                for (int i = 0; i < size; i++) {
                    // 将所有的数组元素置为null
                    RunnableScheduledFuture<?> t = queue[i];
                    if (t != null) {
                        queue[i] = null;
                        setIndex(t, -1); // heapIndex设置为-1，表示已移除
                    }
                }
                size = 0;
            } finally {
                lock.unlock();
            }
        }

        /**
         * 仅当第一个元素过期时才返回它。仅由drainTo 使用。仅在持有锁定时调用。
         */
        // 获取已过期的元素，如果链表为空或者根节点未过期则返回null
        private RunnableScheduledFuture<?> peekExpired() {
            // assert lock.isHeldByCurrentThread();
            RunnableScheduledFuture<?> first = queue[0];
            return (first == null || first.getDelay(NANOSECONDS) > 0) ?
                null : first;
        }

        /**
         * 从此队列中移除所有可用元素并将它们添加到给定集合中。此操作可能比重复轮询此队列更有效。
         * 尝试将元素添加到集合 {@code c} 时遇到的失败可能会导致在引发相关异常时元素既不在集合中，也不在两个集合中。
         * 尝试将队列排空到自身会导致 {@code IllegalArgumentException}。
         * 此外，如果在操作进行时修改了指定的集合，则此操作的行为未定义。
         *
         * @param c 将元素转移到的集合
         * @return 转移的元素数量
         */
        public int drainTo(Collection<? super Runnable> c) {
            if (c == null)
                throw new NullPointerException();
            if (c == this)
                throw new IllegalArgumentException();
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first;
                int n = 0;
                while ((first = peekExpired()) != null) {
                    c.add(first);   // In this order, in case add() throws. 添加到集合中
                    finishPoll(first); // 移除该元素
                    ++n;
                }
                // 返回集合中元素的个数
                return n;
            } finally {
                lock.unlock();
            }
        }

        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            if (c == null)
                throw new NullPointerException();
            if (c == this)
                throw new IllegalArgumentException();
            if (maxElements <= 0)
                return 0;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first;
                int n = 0;
                // 移除最多maxElements个元素到集合中
                while (n < maxElements && (first = peekExpired()) != null) {
                    c.add(first);   // In this order, in case add() throws.
                    finishPoll(first);
                    ++n;
                }
                // 返回实际移除的元素个数
                return n;
            } finally {
                lock.unlock();
            }
        }

        public Object[] toArray() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return Arrays.copyOf(queue, size, Object[].class);
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T[] toArray(T[] a) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                if (a.length < size)
                    return (T[]) Arrays.copyOf(queue, size, a.getClass());
                System.arraycopy(queue, 0, a, 0, size);
                if (a.length > size)
                    a[size] = null;
                return a;
            } finally {
                lock.unlock();
            }
        }

        public Iterator<Runnable> iterator() {
            // 遍历的是原数组的一个副本
            return new Itr(Arrays.copyOf(queue, size));
        }

        /**
         * Snapshot iterator that works off copy of underlying q array.
         */
        private class Itr implements Iterator<Runnable> {
            final RunnableScheduledFuture<?>[] array;
            int cursor = 0;     // index of next element to return
            int lastRet = -1;   // index of last element, or -1 if no such

            Itr(RunnableScheduledFuture<?>[] array) {
                this.array = array;
            }

            public boolean hasNext() {
                return cursor < array.length;
            }

            public Runnable next() {
                if (cursor >= array.length)
                    throw new NoSuchElementException();
                lastRet = cursor;
                // 先获取cursor对应的元素，再将其加1
                return array[cursor++];
            }

            public void remove() {
                if (lastRet < 0)
                    throw new IllegalStateException();
                // 通过remove方法移除特定元素
                DelayedWorkQueue.this.remove(array[lastRet]);
                lastRet = -1;
            }
        }
    }
}
