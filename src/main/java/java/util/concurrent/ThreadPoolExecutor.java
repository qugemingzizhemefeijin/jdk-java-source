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
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

/**
 * An {@link ExecutorService} that executes each submitted task using
 * one of possibly several pooled threads, normally configured
 * using {@link Executors} factory methods.
 *
 * <p>Thread pools address two different problems: they usually
 * provide improved performance when executing large numbers of
 * asynchronous tasks, due to reduced per-task invocation overhead,
 * and they provide a means of bounding and managing the resources,
 * including threads, consumed when executing a collection of tasks.
 * Each {@code ThreadPoolExecutor} also maintains some basic
 * statistics, such as the number of completed tasks.
 *
 * <p>To be useful across a wide range of contexts, this class
 * provides many adjustable parameters and extensibility
 * hooks. However, programmers are urged to use the more convenient
 * {@link Executors} factory methods {@link
 * Executors#newCachedThreadPool} (unbounded thread pool, with
 * automatic thread reclamation), {@link Executors#newFixedThreadPool}
 * (fixed size thread pool) and {@link
 * Executors#newSingleThreadExecutor} (single background thread), that
 * preconfigure settings for the most common usage
 * scenarios. Otherwise, use the following guide when manually
 * configuring and tuning this class:
 *
 * <dl>
 *
 * <dt>Core and maximum pool sizes</dt>
 *
 * <dd>A {@code ThreadPoolExecutor} will automatically adjust the
 * pool size (see {@link #getPoolSize})
 * according to the bounds set by
 * corePoolSize (see {@link #getCorePoolSize}) and
 * maximumPoolSize (see {@link #getMaximumPoolSize}).
 *
 * When a new task is submitted in method {@link #execute(Runnable)},
 * and fewer than corePoolSize threads are running, a new thread is
 * created to handle the request, even if other worker threads are
 * idle.  If there are more than corePoolSize but less than
 * maximumPoolSize threads running, a new thread will be created only
 * if the queue is full.  By setting corePoolSize and maximumPoolSize
 * the same, you create a fixed-size thread pool. By setting
 * maximumPoolSize to an essentially unbounded value such as {@code
 * Integer.MAX_VALUE}, you allow the pool to accommodate an arbitrary
 * number of concurrent tasks. Most typically, core and maximum pool
 * sizes are set only upon construction, but they may also be changed
 * dynamically using {@link #setCorePoolSize} and {@link
 * #setMaximumPoolSize}. </dd>
 *
 * <dt>On-demand construction</dt>
 *
 * <dd>By default, even core threads are initially created and
 * started only when new tasks arrive, but this can be overridden
 * dynamically using method {@link #prestartCoreThread} or {@link
 * #prestartAllCoreThreads}.  You probably want to prestart threads if
 * you construct the pool with a non-empty queue. </dd>
 *
 * <dt>Creating new threads</dt>
 *
 * <dd>New threads are created using a {@link ThreadFactory}.  If not
 * otherwise specified, a {@link Executors#defaultThreadFactory} is
 * used, that creates threads to all be in the same {@link
 * ThreadGroup} and with the same {@code NORM_PRIORITY} priority and
 * non-daemon status. By supplying a different ThreadFactory, you can
 * alter the thread's name, thread group, priority, daemon status,
 * etc. If a {@code ThreadFactory} fails to create a thread when asked
 * by returning null from {@code newThread}, the executor will
 * continue, but might not be able to execute any tasks. Threads
 * should possess the "modifyThread" {@code RuntimePermission}. If
 * worker threads or other threads using the pool do not possess this
 * permission, service may be degraded: configuration changes may not
 * take effect in a timely manner, and a shutdown pool may remain in a
 * state in which termination is possible but not completed.</dd>
 *
 * <dt>Keep-alive times</dt>
 *
 * <dd>If the pool currently has more than corePoolSize threads,
 * excess threads will be terminated if they have been idle for more
 * than the keepAliveTime (see {@link #getKeepAliveTime(TimeUnit)}).
 * This provides a means of reducing resource consumption when the
 * pool is not being actively used. If the pool becomes more active
 * later, new threads will be constructed. This parameter can also be
 * changed dynamically using method {@link #setKeepAliveTime(long,
 * TimeUnit)}.  Using a value of {@code Long.MAX_VALUE} {@link
 * TimeUnit#NANOSECONDS} effectively disables idle threads from ever
 * terminating prior to shut down. By default, the keep-alive policy
 * applies only when there are more than corePoolSize threads. But
 * method {@link #allowCoreThreadTimeOut(boolean)} can be used to
 * apply this time-out policy to core threads as well, so long as the
 * keepAliveTime value is non-zero. </dd>
 *
 * <dt>Queuing</dt>
 *
 * <dd>Any {@link BlockingQueue} may be used to transfer and hold
 * submitted tasks.  The use of this queue interacts with pool sizing:
 *
 * <ul>
 *
 * <li> If fewer than corePoolSize threads are running, the Executor
 * always prefers adding a new thread
 * rather than queuing.</li>
 *
 * <li> If corePoolSize or more threads are running, the Executor
 * always prefers queuing a request rather than adding a new
 * thread.</li>
 *
 * <li> If a request cannot be queued, a new thread is created unless
 * this would exceed maximumPoolSize, in which case, the task will be
 * rejected.</li>
 *
 * </ul>
 *
 * There are three general strategies for queuing:
 * <ol>
 *
 * <li> <em> Direct handoffs.</em> A good default choice for a work
 * queue is a {@link SynchronousQueue} that hands off tasks to threads
 * without otherwise holding them. Here, an attempt to queue a task
 * will fail if no threads are immediately available to run it, so a
 * new thread will be constructed. This policy avoids lockups when
 * handling sets of requests that might have internal dependencies.
 * Direct handoffs generally require unbounded maximumPoolSizes to
 * avoid rejection of new submitted tasks. This in turn admits the
 * possibility of unbounded thread growth when commands continue to
 * arrive on average faster than they can be processed.  </li>
 *
 * <li><em> Unbounded queues.</em> Using an unbounded queue (for
 * example a {@link LinkedBlockingQueue} without a predefined
 * capacity) will cause new tasks to wait in the queue when all
 * corePoolSize threads are busy. Thus, no more than corePoolSize
 * threads will ever be created. (And the value of the maximumPoolSize
 * therefore doesn't have any effect.)  This may be appropriate when
 * each task is completely independent of others, so tasks cannot
 * affect each others execution; for example, in a web page server.
 * While this style of queuing can be useful in smoothing out
 * transient bursts of requests, it admits the possibility of
 * unbounded work queue growth when commands continue to arrive on
 * average faster than they can be processed.  </li>
 *
 * <li><em>Bounded queues.</em> A bounded queue (for example, an
 * {@link ArrayBlockingQueue}) helps prevent resource exhaustion when
 * used with finite maximumPoolSizes, but can be more difficult to
 * tune and control.  Queue sizes and maximum pool sizes may be traded
 * off for each other: Using large queues and small pools minimizes
 * CPU usage, OS resources, and context-switching overhead, but can
 * lead to artificially low throughput.  If tasks frequently block (for
 * example if they are I/O bound), a system may be able to schedule
 * time for more threads than you otherwise allow. Use of small queues
 * generally requires larger pool sizes, which keeps CPUs busier but
 * may encounter unacceptable scheduling overhead, which also
 * decreases throughput.  </li>
 *
 * </ol>
 *
 * </dd>
 *
 * <dt>Rejected tasks</dt>
 *
 * <dd>New tasks submitted in method {@link #execute(Runnable)} will be
 * <em>rejected</em> when the Executor has been shut down, and also when
 * the Executor uses finite bounds for both maximum threads and work queue
 * capacity, and is saturated.  In either case, the {@code execute} method
 * invokes the {@link
 * RejectedExecutionHandler#rejectedExecution(Runnable, ThreadPoolExecutor)}
 * method of its {@link RejectedExecutionHandler}.  Four predefined handler
 * policies are provided:
 *
 * <ol>
 *
 * <li> In the default {@link ThreadPoolExecutor.AbortPolicy}, the
 * handler throws a runtime {@link RejectedExecutionException} upon
 * rejection. </li>
 *
 * <li> In {@link ThreadPoolExecutor.CallerRunsPolicy}, the thread
 * that invokes {@code execute} itself runs the task. This provides a
 * simple feedback control mechanism that will slow down the rate that
 * new tasks are submitted. </li>
 *
 * <li> In {@link ThreadPoolExecutor.DiscardPolicy}, a task that
 * cannot be executed is simply dropped.  </li>
 *
 * <li>In {@link ThreadPoolExecutor.DiscardOldestPolicy}, if the
 * executor is not shut down, the task at the head of the work queue
 * is dropped, and then execution is retried (which can fail again,
 * causing this to be repeated.) </li>
 *
 * </ol>
 *
 * It is possible to define and use other kinds of {@link
 * RejectedExecutionHandler} classes. Doing so requires some care
 * especially when policies are designed to work only under particular
 * capacity or queuing policies. </dd>
 *
 * <dt>Hook methods</dt>
 *
 * <dd>This class provides {@code protected} overridable
 * {@link #beforeExecute(Thread, Runnable)} and
 * {@link #afterExecute(Runnable, Throwable)} methods that are called
 * before and after execution of each task.  These can be used to
 * manipulate the execution environment; for example, reinitializing
 * ThreadLocals, gathering statistics, or adding log entries.
 * Additionally, method {@link #terminated} can be overridden to perform
 * any special processing that needs to be done once the Executor has
 * fully terminated.
 *
 * <p>If hook or callback methods throw exceptions, internal worker
 * threads may in turn fail and abruptly terminate.</dd>
 *
 * <dt>Queue maintenance</dt>
 *
 * <dd>Method {@link #getQueue()} allows access to the work queue
 * for purposes of monitoring and debugging.  Use of this method for
 * any other purpose is strongly discouraged.  Two supplied methods,
 * {@link #remove(Runnable)} and {@link #purge} are available to
 * assist in storage reclamation when large numbers of queued tasks
 * become cancelled.</dd>
 *
 * <dt>Finalization</dt>
 *
 * <dd>A pool that is no longer referenced in a program <em>AND</em>
 * has no remaining threads will be {@code shutdown} automatically. If
 * you would like to ensure that unreferenced pools are reclaimed even
 * if users forget to call {@link #shutdown}, then you must arrange
 * that unused threads eventually die, by setting appropriate
 * keep-alive times, using a lower bound of zero core threads and/or
 * setting {@link #allowCoreThreadTimeOut(boolean)}.  </dd>
 *
 * </dl>
 *
 * <p><b>Extension example</b>. Most extensions of this class
 * override one or more of the protected hook methods. For example,
 * here is a subclass that adds a simple pause/resume feature:
 *
 *  <pre> {@code
 * class PausableThreadPoolExecutor extends ThreadPoolExecutor {
 *   private boolean isPaused;
 *   private ReentrantLock pauseLock = new ReentrantLock();
 *   private Condition unpaused = pauseLock.newCondition();
 *
 *   public PausableThreadPoolExecutor(...) { super(...); }
 *
 *   protected void beforeExecute(Thread t, Runnable r) {
 *     super.beforeExecute(t, r);
 *     pauseLock.lock();
 *     try {
 *       while (isPaused) unpaused.await();
 *     } catch (InterruptedException ie) {
 *       t.interrupt();
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 *
 *   public void pause() {
 *     pauseLock.lock();
 *     try {
 *       isPaused = true;
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 *
 *   public void resume() {
 *     pauseLock.lock();
 *     try {
 *       isPaused = false;
 *       unpaused.signalAll();
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */
public class ThreadPoolExecutor extends AbstractExecutorService {
    /**
     * The main pool control state, ctl, is an atomic integer packing
     * two conceptual fields
     *   workerCount, indicating the effective number of threads
     *   runState,    indicating whether running, shutting down etc
     *
     * In order to pack them into one int, we limit workerCount to
     * (2^29)-1 (about 500 million) threads rather than (2^31)-1 (2
     * billion) otherwise representable. If this is ever an issue in
     * the future, the variable can be changed to be an AtomicLong,
     * and the shift/mask constants below adjusted. But until the need
     * arises, this code is a bit faster and simpler using an int.
     *
     * The workerCount is the number of workers that have been
     * permitted to start and not permitted to stop.  The value may be
     * transiently different from the actual number of live threads,
     * for example when a ThreadFactory fails to create a thread when
     * asked, and when exiting threads are still performing
     * bookkeeping before terminating. The user-visible pool size is
     * reported as the current size of the workers set.
     *
     * The runState provides the main lifecycle control, taking on values:
     *
     *   RUNNING:  Accept new tasks and process queued tasks
     *   SHUTDOWN: Don't accept new tasks, but process queued tasks
     *   STOP:     Don't accept new tasks, don't process queued tasks,
     *             and interrupt in-progress tasks
     *   TIDYING:  All tasks have terminated, workerCount is zero,
     *             the thread transitioning to state TIDYING
     *             will run the terminated() hook method
     *   TERMINATED: terminated() has completed
     *
     * The numerical order among these values matters, to allow
     * ordered comparisons. The runState monotonically increases over
     * time, but need not hit each state. The transitions are:
     *
     * RUNNING -> SHUTDOWN
     *    On invocation of shutdown(), perhaps implicitly in finalize()
     * (RUNNING or SHUTDOWN) -> STOP
     *    On invocation of shutdownNow()
     * SHUTDOWN -> TIDYING
     *    When both queue and pool are empty
     * STOP -> TIDYING
     *    When pool is empty
     * TIDYING -> TERMINATED
     *    When the terminated() hook method has completed
     *
     * Threads waiting in awaitTermination() will return when the
     * state reaches TERMINATED.
     *
     * Detecting the transition from SHUTDOWN to TIDYING is less
     * straightforward than you'd like because the queue may become
     * empty after non-empty and vice versa during SHUTDOWN state, but
     * we can only terminate if, after seeing that it is empty, we see
     * that workerCount is 0 (which sometimes entails a recheck -- see
     * below).
     */

    // 记录当前线程池的线程数量和状态，最高的3位记录状态，后29位记录线程池数量
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));

    // Integer.SIZE是32，COUNT_BITS就是29
    private static final int COUNT_BITS = Integer.SIZE - 3;

    // CAPACITY的后29位都是1，即32位中后29位用来保存容量
    private static final int CAPACITY   = (1 << COUNT_BITS) - 1;//工作线程数存储在低29位

    //线程有五种状态：新建，就绪，运行，阻塞，死亡，线程池同样有五种状态：Running, SHUTDOWN, STOP, TIDYING, TERMINATED。

    // runState is stored in the high-order bits (状态存储在高三位)
    //处于RUNNING状态的线程池能够接受新任务，以及对新添加的任务进行处理
    private static final int RUNNING    = -1 << COUNT_BITS;//对应的高3位值是111
    //处于SHUTDOWN状态的线程池不可以接受新任务，但是可以对已添加的任务进行处理。
    private static final int SHUTDOWN   =  0 << COUNT_BITS;//对应的高3位值是000
    //处于STOP状态的线程池不接收新任务，不处理已添加的任务，并且会中断正在处理的任务。
    private static final int STOP       =  1 << COUNT_BITS;//对应的高3位值是001
    //当所有的任务已终止，ctl记录的"任务数量"为0，线程池会变为TIDYING状态。当线程池变为TIDYING状态时，会执行钩子函数terminated()。
    //terminated()在ThreadPoolExecutor类中是空的，若用户想在线程池变为TIDYING时，进行相应的处理；可以通过重载terminated()函数来实现。
    private static final int TIDYING    =  2 << COUNT_BITS;//对应的高3位值是010
    //线程池彻底终止的状态
    private static final int TERMINATED =  3 << COUNT_BITS;//对应的高3位值是011

    // Packing and unpacking ctl
    //获取当前线程池的状态值
    private static int runStateOf(int c)     { return c & ~CAPACITY; }
    //提取工作线程数
    private static int workerCountOf(int c)  { return c & CAPACITY; }
    //计算runState和workCount的合集数值
    private static int ctlOf(int rs, int wc) { return rs | wc; }

    /*
     * Bit field accessors that don't require unpacking ctl.
     * These depend on the bit layout and on workerCount being never negative.
     */

    /**
     * 判断当前状态是否小于s值
     * @param c 当前值
     * @param s 预期小于此值
     * @return boolean
     */
    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    /**
     * 判断当前状态至少是超过s值
     * @param c 当前值
     * @param s 预期大于的状态
     * @return boolean
     */
    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    /**
     * 判断线程池是否在运行中
     * @param c ctl的值
     * @return boolean
     */
    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    /**
     * Attempts to CAS-increment the workerCount field of ctl.
     * 增加线程数
     */
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    /**
     * Attempts to CAS-decrement the workerCount field of ctl.
     */
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    /**
     * Decrements the workerCount field of ctl. This is called only on
     * abrupt termination of a thread (see processWorkerExit). Other
     * decrements are performed within getTask.
     * <br><br>
     * 减少ctl的workerCount字段。仅在线程突然终止时调用此方法（请参阅processWorkerExit）。其他减量在getTask中执行
     */
    private void decrementWorkerCount() {
        do {} while (! compareAndDecrementWorkerCount(ctl.get()));
    }

    /**
     * The queue used for holding tasks and handing off to worker
     * threads.  We do not require that workQueue.poll() returning
     * null necessarily means that workQueue.isEmpty(), so rely
     * solely on isEmpty to see if the queue is empty (which we must
     * do for example when deciding whether to transition from
     * SHUTDOWN to TIDYING).  This accommodates special-purpose
     * queues such as DelayQueues for which poll() is allowed to
     * return null even if it may later return non-null when delays
     * expire.
     */
    //等待执行的任务队列
    private final BlockingQueue<Runnable> workQueue;

    /**
     * Lock held on access to workers set and related bookkeeping.
     * While we could use a concurrent set of some sort, it turns out
     * to be generally preferable to use a lock. Among the reasons is
     * that this serializes interruptIdleWorkers, which avoids
     * unnecessary interrupt storms, especially during shutdown.
     * Otherwise exiting threads would concurrently interrupt those
     * that have not yet interrupted. It also simplifies some of the
     * associated statistics bookkeeping of largestPoolSize etc. We
     * also hold mainLock on shutdown and shutdownNow, for the sake of
     * ensuring workers set is stable while separately checking
     * permission to interrupt and actually interrupting.
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * Set containing all worker threads in pool. Accessed only when
     * holding mainLock.
     */
    //线程池中包含的所有worker线程
    private final HashSet<Worker> workers = new HashSet<Worker>();

    /**
     * Wait condition to support awaitTermination
     */
    private final Condition termination = mainLock.newCondition();

    /**
     * Tracks largest attained pool size. Accessed only under
     * mainLock.
     */
    private int largestPoolSize;

    /**
     * Counter for completed tasks. Updated only on termination of
     * worker threads. Accessed only under mainLock.
     */
    private long completedTaskCount;

    /*
     * All user control parameters are declared as volatiles so that
     * ongoing actions are based on freshest values, but without need
     * for locking, since no internal invariants depend on them
     * changing synchronously with respect to other actions.
     */

    /**
     * Factory for new threads. All threads are created using this
     * factory (via method addWorker).  All callers must be prepared
     * for addWorker to fail, which may reflect a system or user's
     * policy limiting the number of threads.  Even though it is not
     * treated as an error, failure to create threads may result in
     * new tasks being rejected or existing ones remaining stuck in
     * the queue.
     *
     * We go further and preserve pool invariants even in the face of
     * errors such as OutOfMemoryError, that might be thrown while
     * trying to create threads.  Such errors are rather common due to
     * the need to allocate a native stack in Thread.start, and users
     * will want to perform clean pool shutdown to clean up.  There
     * will likely be enough memory available for the cleanup code to
     * complete without encountering yet another OutOfMemoryError.
     */
    //创建线程的工厂类
    private volatile ThreadFactory threadFactory;

    /**
     * Handler called when saturated or shutdown in execute.
     */
    //当线程池饱和或者关闭时，会执行该句柄的钩子(hook)
    private volatile RejectedExecutionHandler handler;

    /**
     * Timeout in nanoseconds for idle threads waiting for work.
     * Threads use this timeout when there are more than corePoolSize
     * present or if allowCoreThreadTimeOut. Otherwise they wait
     * forever for new work.
     */
    //空闲线程的等待时间（纳秒），如果线程数超过corePoolSize或者设置了allowCoreThreadTimeOut=true，则使用此参数，否则线程永远在等待中
    private volatile long keepAliveTime;

    /**
     * If false (default), core threads stay alive even when idle.
     * If true, core threads use keepAliveTime to time out waiting
     * for work.
     */
    //默认为false，此时核心线程会保持活跃（即使处于空闲状态）；如果为true，则核心线程会在空闲状态超时等待keepAliveTime时间等待任务
    private volatile boolean allowCoreThreadTimeOut;

    /**
     * Core pool size is the minimum number of workers to keep alive
     * (and not allow to time out etc) unless allowCoreThreadTimeOut
     * is set, in which case the minimum is zero.
     */
    //线程池中保持的线程数，即使有些线程已经处于空闲状态，任然保持存活
    private volatile int corePoolSize;

    /**
     * Maximum pool size. Note that the actual maximum is internally
     * bounded by CAPACITY.
     */
    //线程池最大值，最大边界是CAPACITY
    private volatile int maximumPoolSize;

    /**
     * 默认的拒绝策略（直接抛出RejectedExecutionException异常）
     */
    private static final RejectedExecutionHandler defaultHandler = new AbortPolicy();

    /**
     * Permission required for callers of shutdown and shutdownNow.
     * We additionally require (see checkShutdownAccess) that callers
     * have permission to actually interrupt threads in the worker set
     * (as governed by Thread.interrupt, which relies on
     * ThreadGroup.checkAccess, which in turn relies on
     * SecurityManager.checkAccess). Shutdowns are attempted only if
     * these checks pass.
     *
     * All actual invocations of Thread.interrupt (see
     * interruptIdleWorkers and interruptWorkers) ignore
     * SecurityExceptions, meaning that the attempted interrupts
     * silently fail. In the case of shutdown, they should not fail
     * unless the SecurityManager has inconsistent policies, sometimes
     * allowing access to a thread and sometimes not. In such cases,
     * failure to actually interrupt threads may disable or delay full
     * termination. Other uses of interruptIdleWorkers are advisory,
     * and failure to actually interrupt will merely delay response to
     * configuration changes so is not handled exceptionally.
     */
    // 调用shutdown或者shutdownNow方法时校验调用方访问权限
    private static final RuntimePermission shutdownPerm =
        new RuntimePermission("modifyThread");

    /**
     * Class Worker mainly maintains interrupt control state for
     * threads running tasks, along with other minor bookkeeping.
     * This class opportunistically extends AbstractQueuedSynchronizer
     * to simplify acquiring and releasing a lock surrounding each
     * task execution.  This protects against interrupts that are
     * intended to wake up a worker thread waiting for a task from
     * instead interrupting a task being run.  We implement a simple
     * non-reentrant mutual exclusion lock rather than use
     * ReentrantLock because we do not want worker tasks to be able to
     * reacquire the lock when they invoke pool control methods like
     * setCorePoolSize.  Additionally, to suppress interrupts until
     * the thread actually starts running tasks, we initialize lock
     * state to a negative value, and clear it upon start (in
     * runWorker).
     */
    // 线程池中的工作线程对象
    // 此处可以看出 worker 既是一个 Runnable 任务，也实现了 AQS（实际上是用 AQS 实现了一个独占锁，这样由于 worker 运行时会上锁，执行 shutdown，
    // setCorePoolSize，setMaximumPoolSize等方法时会试着中断线程（interruptIdleWorkers），在这个方法中断方法中会先尝试获取 worker 的锁，
    // 如果不成功，说明 worker 在运行中，此时会先让 worker 执行完任务再关闭 worker 的线程，实现优雅关闭线程的目的）
    private final class Worker
        extends AbstractQueuedSynchronizer
        implements Runnable
    {
        /**
         * This class will never be serialized, but we provide a
         * serialVersionUID to suppress a javac warning.
         */
        private static final long serialVersionUID = 6138294804551838833L;

        /** Thread this worker is running in.  Null if factory fails. 执行任务的线程 */
        final Thread thread;
        /** 要运行的初始任务。可能为空. */
        // 如果当前线程数少于核心线程数，创建线程并将提交的任务交给 worker 处理处理，此时 firstTask 即为此提交的任务，如果 worker 从 workQueue 中获取任务，则 firstTask 为空
        Runnable firstTask;
        /** 每个线程任务计数器 */
        volatile long completedTasks;

        /**
         * Creates with given first task and thread from ThreadFactory.
         * @param firstTask the first task (null if none)
         */
        Worker(Runnable firstTask) {
            // 初始化为 -1，这样在线程运行前（调用runWorker）禁止中断，在 interruptIfStarted() 方法中会判断 getState()>=0
            setState(-1); // inhibit interrupts until runWorker
            this.firstTask = firstTask;
            //调用工厂类并创建线程
            this.thread = getThreadFactory().newThread(this);
        }

        /** 将主运行循环委托给外部runWorker  */
        public void run() {
            // thread 启动后会调用此方法
            runWorker(this);
        }

        // Lock methods
        //
        // The value 0 represents the unlocked state.
        // The value 1 represents the locked state.

        //是否状态被占用
        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        //尝试获取锁
        protected boolean tryAcquire(int unused) {
            // 从这里可以看出它是一个独占锁，因为当获取锁后，cas 设置 state 不可能成功，这里我们也能明白上文中将 state 设置为 -1 的作用，
            // 这种情况下永远不可能获取得锁，而 worker 要被中断首先必须获取锁
            if (compareAndSetState(0, 1)) {
                // 将state从0改成1，将当前线程设置为持有锁的线程
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        //实现解锁的关键方法，解锁成功返回true
        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        /**
         * acquire依赖于tryAcquire加锁，如果失败则阻塞
         */
        public void lock()        { acquire(1); }
        public boolean tryLock()  { return tryAcquire(1); }

        /**
         * release方法依赖tryRelease
         */
        public void unlock()      { release(1); }
        public boolean isLocked() { return isHeldExclusively(); }

        /**
         * 如果当前Worker已经开始，并且thread不等于空，并且是非中断状态，则触发线程中断。
         * <p>如果线程已启动，将其中断，state的初始值是-1，runWorker方法将其改成0后再加锁
         */
        // 中断线程，这个方法会被 shutdownNow 调用，从中可以看出 shutdownNow 要中断线程不需要获取锁，也就是说如果线程正在运行，照样会给你中断掉，
        // 所以一般来说我们不用 shutdownNow 来中断线程，太粗暴了，中断时线程很可能在执行任务，影响任务执行
        void interruptIfStarted() {
            Thread t;
            // 中断也是有条件的，必须是 state >= 0 且 t != null 且线程未被中断
            // 如果 state == -1 ，不执行中断，再次明白了为啥上文中 setState(-1) 的意义
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }

    /*
     * Methods for setting control state
     */

    /**
     * Transitions runState to given target, or leaves it alone if
     * already at least the given target.<br><br>
     *
     * 直接将状态修改为指定的状态，不能是 TIDYING 和 TERMINATED 状态。只能为STOP和SHUTDOWN状态。此处依然会将worker数量维护在里面
     *
     * @param targetState the desired state, either SHUTDOWN or STOP
     *        (but not TIDYING or TERMINATED -- use tryTerminate for that)
     */
    private void advanceRunState(int targetState) {
        for (;;) {
            int c = ctl.get();
            //如果c<=targetState，则直接设置状态码
            if (runStateAtLeast(c, targetState) || // 如果当前状态大于指定值
                // 如果小于，则cas修改，失败则重试
                ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }

    /**
     * Transitions to TERMINATED state if either (SHUTDOWN and pool
     * and queue empty) or (STOP and pool empty).  If otherwise
     * eligible to terminate but workerCount is nonzero, interrupts an
     * idle worker to ensure that shutdown signals propagate. This
     * method must be called following any action that might make
     * termination possible -- reducing worker count or removing tasks
     * from the queue during shutdown. The method is non-private to
     * allow access from ScheduledThreadPoolExecutor.
     * <br><br>
     * <p>如果状态是SHUTDOWN，且线程数为0，则将其改成TIDYING，然后执行回调方法terminated，将状态改成TERMINATED，并唤醒等待线程池终止的线程。
     * 线程退出前调用的processWorkerExit方法，添加Worker失败后调用的addWorkerFailed方法等多个方法都会调用tryTerminate。
     *
     * <br><br>
     * <p>正常情况下是最后一个退出的线程通过 tryTerminate方法将线程池的状态由SHUTDOWN最终流转成TERMINATED。
     */
    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            //如果是运行中状态 或者已经马上要终止 或者 关闭状态但是队列还有值，则直接返回不做处理
            if (isRunning(c) ||
                runStateAtLeast(c, TIDYING) ||
                (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()))
                return;
            //如果当前还有运行的线程，则不需要处理，会被自动调用终止流程
            if (workerCountOf(c) != 0) { // Eligible to terminate
                interruptIdleWorkers(ONLY_ONE);
                return;
            }
            //上锁
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                //cas 替换为 TIDYING 状态
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        //执行terminated钩子函数
                        terminated();
                    } finally {
                        //执行完毕后，将线程池设置为终止状态
                        ctl.set(ctlOf(TERMINATED, 0));
                        //唤醒在 awaitTermination 方法上等待的所有的线程
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }

    /*
     * Methods for controlling interrupts to worker threads.
     */

    /**
     * If there is a security manager, makes sure caller has
     * permission to shut down threads in general (see shutdownPerm).
     * If this passes, additionally makes sure the caller is allowed
     * to interrupt each worker thread. This might not be true even if
     * first check passed, if the SecurityManager treats some threads
     * specially.
     */
    // 检查当前线程是否有权限关闭
    private void checkShutdownAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                for (Worker w : workers)
                    security.checkAccess(w.thread);
            } finally {
                mainLock.unlock();
            }
        }
    }

    /**
     * Interrupts all threads, even if active. Ignores SecurityExceptions
     * (in which case some threads may remain uninterrupted).
     */
    private void interruptWorkers() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers)
                // 将所有已启动的，可能在执行任务，也可能空闲的线程标记为中断
                w.interruptIfStarted();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Interrupts threads that might be waiting for tasks (as
     * indicated by not being locked) so they can check for
     * termination or configuration changes. Ignores
     * SecurityExceptions (in which case some threads may remain
     * uninterrupted).<br><br>
     *
     * 中断空闲的线程
     *
     * @param onlyOne If true, interrupt at most one worker. This is
     * called only from tryTerminate when termination is otherwise
     * enabled but there are still other workers.  In this case, at
     * most one waiting worker is interrupted to propagate shutdown
     * signals in case all threads are currently waiting.
     * Interrupting any arbitrary thread ensures that newly arriving
     * workers since shutdown began will also eventually exit.
     * To guarantee eventual termination, it suffices to always
     * interrupt only one idle worker, but shutdown() interrupts all
     * idle workers so that redundant workers exit promptly, not
     * waiting for a straggler task to finish.
     */
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        //这里上锁，是为了防止没有线程去主动中断阻塞中的线程
        //如：假设A，B获得了任务，C,D阻塞
        //A, B接下来的步骤是：
        //step1.任务执行完成后，再次getTask()，此时符合条件1 ，返回null，线程准备被回收。
        //step2.processWorkerExit(Worker w, boolean completedAbruptly) 将线程回收。
        //问题就来了，中断一条空闲线程，也没说是一定中断正在阻塞的线程啊。如果A, B同时退出，有没有可能出现A中断B, B中断A，AB互相中断，从而没有线程去中断唤醒阻塞的线程呢？
        //假设A能走到这里，说明A已经从工作线程的集合workers里面移除了。那么A中断B，B来到这里中断，就不会在workers里面找到A了。
        //退出的线程不能互相中断，我从集合中退出后，中断了你，你不能中断我，因为我已经退出集合，你只能中断别人。那么，即使有N个线程同时退出，至少在最后，也会有一条线程，会中断剩余的阻塞线程。
        //阻塞的C，D中的任意一条被中断唤醒后，又会重复step1的动作，周而复始，直到所有阻塞线程都被中断，唤醒。
        //onlyOne = true则至多中断1个，然后依次唤醒中断
        //onlyOne = false shutdown（）中断所有闲置的工人，以便多余的工人迅速退出，而不是等待散乱的任务完成。
        try {
            // 遍历所有的Worker
            for (Worker w : workers) {
                Thread t = w.thread;
                // 如果未中断，则尝试加锁，如果加锁失败则表示该线程正在执行任务
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        // 标记为已中断
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                if (onlyOne) // 如果只遍历一次则终止循环
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Common form of interruptIdleWorkers, to avoid having to
     * remember what the boolean argument means.
     */
    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }

    private static final boolean ONLY_ONE = true;

    /*
     * Misc utilities, most of which are also exported to
     * ScheduledThreadPoolExecutor
     */

    /**
     * Invokes the rejected execution handler for the given command.
     * Package-protected for use by ScheduledThreadPoolExecutor.
     */
    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    /**
     * Performs any further cleanup following run state transition on
     * invocation of shutdown.  A no-op here, but used by
     * ScheduledThreadPoolExecutor to cancel delayed tasks.
     */
    void onShutdown() {
    }

    /**
     * State check needed by ScheduledThreadPoolExecutor to
     * enable running tasks during shutdown.
     *
     * @param shutdownOK true if should return true if SHUTDOWN
     */
    final boolean isRunningOrShutdown(boolean shutdownOK) {
        int rs = runStateOf(ctl.get());
        return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
    }

    /**
     * 将任务队列排到一个新列表中，通常使用drainTo。但是如果队列是 DelayQueue 或任何其他类型的队列，
     * 对于它的 poll 或 drainTo 可能无法删除某些元素，它会一个一个地删除它们。
     */
    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<Runnable>();
        // 队列中的所有任务都移除然后添加到列表中
        q.drainTo(taskList);
        if (!q.isEmpty()) { // 某些队列的drainTo方法不能移除所有元素则通过remove方法移除剩余的
            for (Runnable r : q.toArray(new Runnable[0])) {
                if (q.remove(r)) // 将任务从队列中移除，添加到列表中
                    taskList.add(r);
            }
        }
        return taskList;
    }

    /*
     * Methods for creating, running and cleaning up after workers
     */

    /**
     * Checks if a new worker can be added with respect to current
     * pool state and the given bound (either core or maximum). If so,
     * the worker count is adjusted accordingly, and, if possible, a
     * new worker is created and started, running firstTask as its
     * first task. This method returns false if the pool is stopped or
     * eligible to shut down. It also returns false if the thread
     * factory fails to create a thread when asked.  If the thread
     * creation fails, either due to the thread factory returning
     * null, or due to an exception (typically OutOfMemoryError in
     * Thread.start()), we roll back cleanly.
     *
     * <p>firstTask为空的话可能是一种补偿措施，在状态为SHUTDOWN的时候对已经添加到队列中的任务进行消费，比如初始大小为0的时候，就会产生这个问题。
     * <br><br>
     * <p>
     * addWorker会校验当前线程池的状态和线程的数量，
     * 如果校验失败则返回false，否则将线程数加1并创建一个新的Worker，将新的Worker添加到workers集合中，并启动线程的执行，
     * 如果创新新线程失败或者线程池即将被关闭，则将线程数减1并尝试终止线程池的运行。
     * 该方法的第二个参数为true，则当前线程池的数量不能超过corePoolSize，为false，则不能超过maximumPoolSize，如果超过则直接返回false。
     *
     * @param firstTask the task the new thread should run first (or
     * null if none). Workers are created with an initial first task
     * (in method execute()) to bypass queuing when there are fewer
     * than corePoolSize threads (in which case we always start one),
     * or when the queue is full (in which case we must bypass queue).
     * Initially idle threads are usually created via
     * prestartCoreThread or to replace other dying workers.
     * @param core 如果为true则是用corePoolSize来判断是否应该启动新线程，否则使用maximumPoolSize来判断
     * (A boolean indicator is used here rather than a value to ensure reads of fresh values after checking other pool state).
     * @return true if successful
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
        retry:
        for (;;) {
            int c = ctl.get();
            // 获取当前线程池的状态
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            //线程池已经被关闭 并且如果线程池刚提交了一个任务(需要new新的线程)或者队列为空，则直接return掉，不需要开启新的工作线程
            if (rs >= SHUTDOWN &&
                ! (rs == SHUTDOWN &&
                   firstTask == null &&
                   ! workQueue.isEmpty()))
                return false;

            // 当前的状态小于SHUTDOWN
            // 如果等于SHUTDOWN，则firstTask为null且workQueue是空的，进入下面的for循环
            for (;;) {
                // 获取线程数
                int wc = workerCountOf(c);
                //如果当前线程最高线程数 或者大于指定线程池要求的数量，则直接返回false
                if (wc >= CAPACITY ||
                    wc >= (core ? corePoolSize : maximumPoolSize))
                    return false;
                // 不断的重试，并对当前的线程数+1
                // 成功的话则跳出循环并执行创建线程的逻辑
                // 失败的话继续重新读取线程数等信息并重试
                if (compareAndIncrementWorkerCount(c)) // 增加线程数，如果成功则终止外层的for循环
                    break retry;
                // 增加线程数失败，重新获取线程数
                c = ctl.get();  // Re-read ctl
                //如果线程的状态改变了，则再次重新判断线程状态等信息
                if (runStateOf(c) != rs)
                    continue retry;
                // else CAS failed due to workerCount change; retry inner loop
                // 状态没变，通过内层for循环重试
            }
        } // for循环结束

        //开始创建线程，并开启任务接收
        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = null;
        try {
            // 创建一个新的Worker
            w = new Worker(firstTask);
            //为什么要判断为空呢？在使用ThreadFactory.newThread()的时候可能由于业务人员自己写了错误的代码，造成返回null
            final Thread t = w.thread;
            if (t != null) {
                //加锁是为了防止并发操作workers对象
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // Recheck while holding lock.
                    // Back out on ThreadFactory failure or if
                    // shut down before lock acquired.
                    // 再次检查状态，如果状态小于shutdown，或者等于shutdown并且没有任务才添加到
                    // (core=0则在添加任务的时候不会立即启动线程，而是先放入到队列中)
                    int rs = runStateOf(ctl.get());

                    // 如果线程池状态小于 SHUTDOWN（即为 RUNNING），
                    // 或者状态为 SHUTDOWN 但 firstTask == null（代表不接收任务，只是创建线程处理 workQueue 中的任务），则满足添加 worker 的条件
                    if (rs < SHUTDOWN ||
                        (rs == SHUTDOWN && firstTask == null)) {
                        if (t.isAlive()) // 如果线程已启动，显然有问题（因为创建 worker 后，还没启动线程呢），抛出异常
                            throw new IllegalThreadStateException();
                        //将线程添加到工作组中
                        workers.add(w);
                        int s = workers.size();
                        // 记录一下线程池达到的最大值
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }
                if (workerAdded) { // 成功添加到workers Set中
                    //启动线程。这个也有可能启动失败，所以需要记录workerStarted值
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            // 添加线程失败，执行 addWorkerFailed 方法，主要做了将 worker 从 workers 中移除，减少线程数，并尝试着关闭线程池这样的操作
            if (! workerStarted)
                addWorkerFailed(w);
        }
        return workerStarted;
    }

    /**
     * 回滚工作线程的创建。
     * - 如果存在的话先从worker中删除
     * - 将worker数量-1
     * - 重新检查终止方法, 防止此因为此work的终止而造成线程池终止逻辑卡住
     */
    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (w != null)
                workers.remove(w); // 从workers中移除
            // 线程数减1
            decrementWorkerCount();
            // 尝试让线程池停止运行
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Performs cleanup and bookkeeping for a dying worker. Called
     * only from worker threads. Unless completedAbruptly is set,
     * assumes that workerCount has already been adjusted to account
     * for exit.  This method removes thread from worker set, and
     * possibly terminates the pool or replaces the worker if either
     * it exited due to user task exception or if fewer than
     * corePoolSize workers are running or queue is non-empty but
     * there are no workers.
     *
     * @param w the worker
     * @param completedAbruptly 异常结束为true，正常结束则false
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        //如果因为用户线程异常，则需要将工作线程减1
        if (completedAbruptly) // If abrupt, then workerCount wasn't adjusted
            decrementWorkerCount();

        //将线程池中总的任务完成次数累计，并从workers中移除当前的工作线程
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 累加已完成的任务数
            completedTaskCount += w.completedTasks;
            // 加锁确保线程安全地移除 worker
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }

        // worker 既然异常退出，可能线程池状态变了（如执行 shutdown 等），尝试判断是否线程池中断了，并进行一些资源回收
        tryTerminate();

        int c = ctl.get();
        //如果当前状态少于STOP，则需要开启线程(意味着线程是因为异常而退出去的)
        if (runStateLessThan(c, STOP)) {
            //如果是正常结束的线程，此处判断核心线程数量，如果没有核心线程数但是队列还有数据，并且当前正在工作的线程如果>0则不需要再启动一个新的线程了
            if (!completedAbruptly) {
                // 获取最低的线程数
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                if (min == 0 && ! workQueue.isEmpty())
                    min = 1;
                if (workerCountOf(c) >= min) // 线程数满足要求
                    return; // replacement not needed
            }
            //非正常退出或者正常退出但是当前核心线程数为0，则需要再次启动一个新的线程
            addWorker(null, false);
        }
    }

    /**
     * 执行获取或定时等待任务，具体取决于当前的配置，如果此工作程序返回null，则由于以下任何原因而必须退出：
     * 1. 超过了maximumPoolSize线程数.
     * 2. 线程出被STOP了.
     * 3. 线程池被shutdown并且等待队列为空.
     * 4. 线程超时等待任务并且核心线程可以被回收或者工作线程大于核心线程数，且队列不为空，当前worker不是最后一个线程.
     *
     * @return 获取任务，如果获取到null的话，则work线程必须退出并且workCount需要-1
     */
    private Runnable getTask() {
        boolean timedOut = false; // Did the last poll() time out?

        for (;;) {
            int c = ctl.get();
            // 获取线程池状态
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            //如果线程池至少是shutdown，并且要么是stop，要么等待为空，则需要将线程数量-1
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
            }

            //获取当前池中拥有的线程数
            int wc = workerCountOf(c);

            // 如果allowCoreThreadTimeOut为true，则所有的线程都最多空闲等待keepAliveTime
            // 为false且当前线程数大于corePoolSize，则同样最多空闲等待keepAliveTime
            // 当线程陆续退出后，发现线程数低于corePoolSize又会创建相应的线程数
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            //如果当前线程大于最大线程数 或者 线程能够被移除并且是因为timeout
            //同时 线程数>1 或者 等待队列为空，则减少线程数量，并且返回null，当前线程将被退出线程池
            if ((wc > maximumPoolSize || (timed && timedOut)) // 如果超过最大线程数或者等待超时
                && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c)) // 将线程数减1，返回null
                    return null;
                continue; // 修改线程数失败，重试
            }

            try {
                // timed判断是需要定时等待还是死等，如果有等待时间限制则等待keepAliveTime纳秒，否则无期限等待。
                // 线程超时阻塞，超时唤醒后CAS减少工作线程数，如果CAS成功，返回null，线程回收，否则进入下一次循环。
                // 当工作者线程数量小于等于corePoolSize，就可以一直阻塞了。
                Runnable r = timed ?
                    workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                    workQueue.take();
                if (r != null) // 获取待执行任务
                    return r;
                //如果没有获取到任务，跑到这里了，肯定是超时造成的
                timedOut = true;
            } catch (InterruptedException retry) {
                //响应中断，其实是在调用shutdown或者stop或者其他用户原因调用了线程中断，让线程早点退出来
                timedOut = false;
            }
        }
    }

    /**
     * Main worker run loop.  Repeatedly gets tasks from queue and
     * executes them, while coping with a number of issues:
     *
     * 1. We may start out with an initial task, in which case we
     * don't need to get the first one. Otherwise, as long as pool is
     * running, we get tasks from getTask. If it returns null then the
     * worker exits due to changed pool state or configuration
     * parameters.  Other exits result from exception throws in
     * external code, in which case completedAbruptly holds, which
     * usually leads processWorkerExit to replace this thread.
     *
     * 2. Before running any task, the lock is acquired to prevent
     * other pool interrupts while the task is executing, and then we
     * ensure that unless pool is stopping, this thread does not have
     * its interrupt set.
     *
     * 3. Each task run is preceded by a call to beforeExecute, which
     * might throw an exception, in which case we cause thread to die
     * (breaking loop with completedAbruptly true) without processing
     * the task.
     *
     * 4. Assuming beforeExecute completes normally, we run the task,
     * gathering any of its thrown exceptions to send to afterExecute.
     * We separately handle RuntimeException, Error (both of which the
     * specs guarantee that we trap) and arbitrary Throwables.
     * Because we cannot rethrow Throwables within Runnable.run, we
     * wrap them within Errors on the way out (to the thread's
     * UncaughtExceptionHandler).  Any thrown exception also
     * conservatively causes thread to die.
     *
     * 5. After task.run completes, we call afterExecute, which may
     * also throw an exception, which will also cause thread to
     * die. According to JLS Sec 14.20, this exception is the one that
     * will be in effect even if task.run throws.
     *
     * The net effect of the exception mechanics is that afterExecute
     * and the thread's UncaughtExceptionHandler have as accurate
     * information as we can provide about any problems encountered by
     * user code.
     *
     * <br><br>
     * <p>runWorker就是线程池中的线程执行任务的核心方法，该方法会执行firstTask，
     * 如果firstTask为null则从任务队列中获取任务，如果此时任务队列为空则阻塞当前线程。
     * 默认配置下，如果线程数大于corePoolSize，则此时所有的线程都是最多空闲等待keepAliveTime，如果等待超时了则返回null，线程陆续退出，
     * 最后退出前会检查线程数是否低于corePoolSize，如果低于则会创建对应数量的新线程，使线程数等于corePoolSize，
     * 然后这部分线程就可以无期限等待待处理的任务了；
     * 如果通过allowCoreThreadTimeOut方法将allowCoreThreadTimeOut置为true，该属性默认为false，
     * 则所有线程在任何时候都会最多等待keepAliveTime，且线程退出后不再检查线程数是否不小于corePoolSize，此时线程数可能为0。
     *
     * <br><br>
     * <p>runWorker执行任务前会调用lock方法加锁，执行完成或者异常退出后会调用unlock方法解锁，
     * 加锁是为了表明当前线程正在执行任务，解锁是为了表明当前线程的任务执行完成了，
     * 设计锁的目的主要是为了避免对刚创建的未调用runWorker方法即未启动的线程中断。
     * runWorker执行任务前会回调beforeExecute方法，在执行完成后会回调afterExecute方法，这两方法默认实现都是空的。
     *
     * <br><br>
     * <p>runWorker执行任务的过程中如果抛出了异常，则Worker会原样的将异常抛出，这会导致Worker对应的线程退出。
     * 无论是因为等待任务超时还是执行任务异常退出，都会将该Worker从workers集合中移除，然后累加completedTasks，
     * 如果是异常退出的或者此时线程数不满足最低线程数则创新一个新的Worker。
     *
     * @param w the worker
     */
    final void runWorker(Worker w) {
        Thread wt = Thread.currentThread();
        //当一个线程第一次过来，一般会带有firstTask的任务，也就是为到核心线程数的时候，提交的任务会直接被Worker持有并优先执行
        Runnable task = w.firstTask;
        w.firstTask = null;
        // unlock 会调用 tryRelease 方法将 state 设置成 0，代表允许中断
        w.unlock(); // allow interrupts 将state的值由初始的-1改成0，表示该Worker已启动
        //如果getTask()抛出异常或者beforeExecute、afterExecute异常，则true，正常结束则false
        boolean completedAbruptly = true;
        try {
            // 如果在提交任务时创建了线程，并把任务丢给此线程，则会先执行此 task
            // getTask方法从任务队列中获取任务，如果队列为空会阻塞当前线程
            while (task != null || (task = getTask()) != null) {
                w.lock(); //加锁是为了防止 interruptIdleWorkers() 地方造成线程被中断
                // If pool is stopping, ensure thread is interrupted;
                // if not, ensure thread is not interrupted.  This
                // requires a recheck in second case to deal with
                // shutdownNow race while clearing interrupt
                // 如果线程池正在停止，确保线程是中断状态。如果不是的话，则需要确保线程不是中断状态。
                // 需要重新检查以解决第二种情况，清除中断状态。
                // Thread.interrupted() 会将线程的中断状态返回并给重置掉。所以需要二次确定中断状态并且线程池状态来真正实现线程中断状态
                if ((runStateAtLeast(ctl.get(), STOP) || // 如果线程池的状态大于等于STOP
                     (Thread.interrupted() &&
                      runStateAtLeast(ctl.get(), STOP))) &&
                    !wt.isInterrupted()) // wt未中断
                    // 标记为已中断
                    wt.interrupt();
                try {
                    //这里是任务执行前的钩子，注意不要抛出异常，否则会导致当前线程死亡
                    beforeExecute(wt, task);
                    Throwable thrown = null;
                    try {
                        //执行任务
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        //执行after钩子
                        afterExecute(task, thrown);
                    }
                } finally {
                    //恢复任务信息，完成的任务数+1，解锁
                    task = null;
                    w.completedTasks++; // 已完成的任务数加1
                    w.unlock(); // 解锁，表示任务执行完成
                }
            }
            // 如果task为null，且getTask从队列中获取待执行任务也为null，则退出
            // 正常退出while循环completedAbruptly为false，如果是异常退出则为true
            completedAbruptly = false;
        } finally {
            //此方法要么是做线程池清理工作，要么是因为用户任务异常造成的非正常退出需要重新开启新的线程来替换等动作
            processWorkerExit(w, completedAbruptly);
        }
    }

    // Public constructors and methods

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default thread factory and rejected execution handler.
     * It may be more convenient to use one of the {@link Executors} factory
     * methods instead of this general purpose constructor.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default rejected execution handler.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code threadFactory} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             threadFactory, defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default thread factory.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code handler} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), handler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters.
     *
     * <p>
     * 构造方法中除ThreadFactory和RejectedExecutionHandler可以使用默认值外，其他的都必须指定，
     * RejectedExecutionHandler默认使用AbortPolicy，会抛出RejectedExecutionException异常；
     * ThreadFactory默认使用Executors的内部类DefaultThreadFactory。
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code threadFactory} or {@code handler} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,
                              RejectedExecutionHandler handler) {
        // 校验参数合法
        if (corePoolSize < 0 ||
            maximumPoolSize <= 0 ||
            maximumPoolSize < corePoolSize ||
            keepAliveTime < 0)
            throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        // 统一转换成纳秒了
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    /**
     * Executes the given task sometime in the future.  The task
     * may execute in a new thread or in an existing pooled thread.
     *
     * If the task cannot be submitted for execution, either because this
     * executor has been shutdown or because its capacity has been reached,
     * the task is handled by the current {@code RejectedExecutionHandler}.
     * <br><br>
     * <p>execute方法就是线程池执行任务的核心方法了，
     * 如果线程数量低于corePoolSize，则创建一个新Worker立即执行任务；
     * 如果线程数量大于等于corePoolSize，则将任务加入到队列中，等待被执行，
     * 如果队列满了添加失败则创建一个新的Worker立即执行任务，
     * 不断创建新的Worker直到线程数量达到maximumPoolSize，
     * 则使用指定的策略拒绝掉该任务，这期间如果线程池被关闭了也会拒绝掉任务。
     *
     * @param command the task to execute
     * @throws RejectedExecutionException at discretion of
     *         {@code RejectedExecutionHandler}, if the task
     *         cannot be accepted for execution
     * @throws NullPointerException if {@code command} is null
     */
    public void execute(Runnable command) {
        if (command == null)
            throw new NullPointerException();
        /*
         * Proceed in 3 steps:
         *
         * 1. If fewer than corePoolSize threads are running, try to
         * start a new thread with the given command as its first
         * task.  The call to addWorker atomically checks runState and
         * workerCount, and so prevents false alarms that would add
         * threads when it shouldn't, by returning false.
         *
         * 2. If a task can be successfully queued, then we still need
         * to double-check whether we should have added a thread
         * (because existing ones died since last checking) or that
         * the pool shut down since entry into this method. So we
         * recheck state and if necessary roll back the enqueuing if
         * stopped, or start a new thread if there are none.
         *
         * 3. If we cannot queue task, then we try to add a new
         * thread.  If it fails, we know we are shut down or saturated
         * and so reject the task.
         */

        /*
         * 1.如果正在运行的线程少于corePoolSize线程，则尝试启动一个新线程。
         * 对addWorker的调用从原子上检查runState和workerCount，从而通过返回false来防止在不应该添加线程的情况下发出错误警报。
         *
         * 2.如果一个任务可以成功地排队，那么我们仍然需要再次检查是否应该添加一个线程（因为现有线程自上次检查后就死了），或者自进入该方法以来该池已关闭。
         * 因此，我们重新检查状态，并在必要时回滚排队，如果停止，或者如果没有线程，则启动一个新线程。
         *
         * 3.如果我们无法将任务排队，则尝试添加一个新的线程。如果失败，则拒绝该任务。
         *
         */
        int c = ctl.get();//线程池的状态控制变量
        //如果当前线程小于核心线程数，则启动新线程
        if (workerCountOf(c) < corePoolSize) {
            // 如果小于corePoolSize，则创建一个新的worker，创建成功返回true
            // 如果因为线程数量大于corePoolSize或者线程池即将被关闭则返回false
            if (addWorker(command, true))
                return;
            c = ctl.get();
        }
        // 如果线程数量大于corePoolSize
        if (isRunning(c) && workQueue.offer(command)) {
            // 如果线程池正在运行且成功把任务加入到队列中
            int recheck = ctl.get();
            // 再次检查状态，如果状态不是正常运行，则将任务从队列中移除
            if (! isRunning(recheck) && remove(command))
                reject(command); // 拒绝任务
            // 如果状态是正常的，再次检查线程数，有可能之前创建的线程异常退出了
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false); // 重新创建一个新Worker，线程数量不能大于maximumPoolSize
        }
        // 如果添加任务到队列失败，即队列满了，则尝试添加一个新的Worker
        // 如果状态不是正常运行，addWorker会立即返回false
        else if (!addWorker(command, false))
            // 拒绝这个任务
            reject(command);
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
     * <br><br>
     * <p>shutdown方法将状态改成SHUTDOWN，未执行任务的线程标记为已中断，然后执行回调onShutdown，
     * 此时线程池会继续处理任务队列中的任务，但是不接受新的任务了，未执行任务的线程因为中断从等待任务的休眠状态中被唤醒，
     * 发现状态变成SHUTDOWN且任务队列是空的则getTask返回null，线程退出。
     *
     * <br><br>
     * <p>
     * 调用shutdown()之后，未执行完的任务要执行完毕，池子才能结束。所以此时有可能线程还在工作。
     * <pre>
     * 1.任务较多，工作线程都能获得任务：
     *   将所有线程都中断，如果任务很多的话，中断的线程基于底层AQS的机制(AbstractQueuedSynchronizer.acquireInterruptibly())，还是会自动恢复并继续执行剩余的任务。
     * 2.任务刚好要执行完了
     *   看interruptIdleWorkers()方法中的注释
     * </pre>
     *
     * @throws SecurityException {@inheritDoc}
     */
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 校验访问权限
            checkShutdownAccess();
            // 将状态改成SHUTDOWN
            advanceRunState(SHUTDOWN);
            // 将未执行任务的线程标记为中断
            interruptIdleWorkers();
            // 执行回调
            onShutdown(); // hook for ScheduledThreadPoolExecutor
        } finally {
            mainLock.unlock();
        }
        // 尝试终止线程池
        tryTerminate();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution. These tasks are drained (removed)
     * from the task queue upon return from this method.
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
     * <p>
     * shutdownNow将状态改成STOP，并将所有已启动的线程标记为已中断，包含正在执行任务的和空闲等待的线程，
     * 将任务队列中所有未处理的任务都移除并返回，此时既不接受新的任务，也不处理任务队列中剩余的任务。
     *
     * @throws SecurityException {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 验访问权限
            checkShutdownAccess();
            // 将状态改成STOP
            advanceRunState(STOP);
            // 将未执行任务的线程标记为中断
            interruptWorkers();
            // 移除并返回所有未处理任务
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }
        // 尝试终止线程池
        tryTerminate();
        return tasks;
    }

    public boolean isShutdown() {
        return ! isRunning(ctl.get());
    }

    /**
     * Returns true if this executor is in the process of terminating
     * after {@link #shutdown} or {@link #shutdownNow} but has not
     * completely terminated.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, causing this executor not
     * to properly terminate.
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        int c = ctl.get();
        return ! isRunning(c) && runStateLessThan(c, TERMINATED);
    }

    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    /**
     *
     * awaitTermination方法是等待线程池的状态变更成TERMINATED，会将当前线程阻塞等待指定的时间，如果超时则返回false，成功返回true。
     *
     * @param timeout 最大等待时间
     * @param unit 时间单位
     * @return boolean
     * @throws InterruptedException
     */
    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (;;) {
                //如果执行前已经终止，则返回true
                if (runStateAtLeast(ctl.get(), TERMINATED))
                    return true;
                //否则到达timeout时间还未终止，则返回false
                if (nanos <= 0)
                    return false;
                // 等待指定的时间
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Invokes {@code shutdown} when this executor is no longer
     * referenced and it has no threads.
     */
    protected void finalize() {
        shutdown();
    }

    /**
     * Sets the thread factory used to create new threads.
     *
     * @param threadFactory the new thread factory
     * @throws NullPointerException if threadFactory is null
     * @see #getThreadFactory
     */
    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null)
            throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    /**
     * Returns the thread factory used to create new threads.
     *
     * @return the current thread factory
     * @see #setThreadFactory(ThreadFactory)
     */
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    /**
     * Sets a new handler for unexecutable tasks.
     *
     * @param handler the new handler
     * @throws NullPointerException if handler is null
     * @see #getRejectedExecutionHandler
     */
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null)
            throw new NullPointerException();
        this.handler = handler;
    }

    /**
     * Returns the current handler for unexecutable tasks.
     *
     * @return the current handler
     * @see #setRejectedExecutionHandler(RejectedExecutionHandler)
     */
    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    /**
     * Sets the core number of threads.  This overrides any value set
     * in the constructor.  If the new value is smaller than the
     * current value, excess existing threads will be terminated when
     * they next become idle.  If larger, new threads will, if needed,
     * be started to execute any queued tasks.
     *
     * @param corePoolSize the new core size
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @see #getCorePoolSize
     */
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0)
            throw new IllegalArgumentException();
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize)
            interruptIdleWorkers();
        else if (delta > 0) {
            // We don't really know how many new threads are "needed".
            // As a heuristic, prestart enough new workers (up to new
            // core size) to handle the current number of tasks in
            // queue, but stop if queue becomes empty while doing so.
            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) {
                if (workQueue.isEmpty())
                    break;
            }
        }
    }

    /**
     * Returns the core number of threads.
     *
     * @return the core number of threads
     * @see #setCorePoolSize
     */
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /**
     * 启动一个核心线程，使其闲置地等待工作。
     * 仅在执行新任务时才覆盖启动核心线程的默认策略。如果所有核心线程均已启动，则此方法将返回{@code false} *。
     *
     * @return {@code true} if a thread was started
     */
    public boolean prestartCoreThread() {
        return workerCountOf(ctl.get()) < corePoolSize &&
            addWorker(null, true);
    }

    /**
     * 与prestartCoreThread相同，即使corePoolSize为0至少启动一个线程。
     */
    void ensurePrestart() {
        int wc = workerCountOf(ctl.get());
        if (wc < corePoolSize)
            addWorker(null, true);
        else if (wc == 0)
            addWorker(null, false);
    }

    /**
     * 启动所有核心线程，使它们闲置地等待工作。 仅在执行新任务时才覆盖启动核心线程的默认策略。<br>
     * 如果预料到系统一开始可执行的任务非常的多，则可以在提交任务前先执行次方法，将所有的核心线程预先启动。
     *
     * @return the number of threads started
     */
    public int prestartAllCoreThreads() {
        int n = 0;
        while (addWorker(null, true))
            ++n;
        return n;
    }

    /**
     * Returns true if this pool allows core threads to time out and
     * terminate if no tasks arrive within the keepAlive time, being
     * replaced if needed when new tasks arrive. When true, the same
     * keep-alive policy applying to non-core threads applies also to
     * core threads. When false (the default), core threads are never
     * terminated due to lack of incoming tasks.
     *
     * @return {@code true} if core threads are allowed to time out,
     *         else {@code false}
     *
     * @since 1.6
     */
    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    /**
     * Sets the policy governing whether core threads may time out and
     * terminate if no tasks arrive within the keep-alive time, being
     * replaced if needed when new tasks arrive. When false, core
     * threads are never terminated due to lack of incoming
     * tasks. When true, the same keep-alive policy applying to
     * non-core threads applies also to core threads. To avoid
     * continual thread replacement, the keep-alive time must be
     * greater than zero when setting {@code true}. This method
     * should in general be called before the pool is actively used.
     *
     * @param value {@code true} if should time out, else {@code false}
     * @throws IllegalArgumentException if value is {@code true}
     *         and the current keep-alive time is not greater than zero
     *
     * @since 1.6
     */
    public void allowCoreThreadTimeOut(boolean value) {
        // 参数校验
        if (value && keepAliveTime <= 0)
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if (value != allowCoreThreadTimeOut) {
            // 修改属性
            allowCoreThreadTimeOut = value;
            if (value) // 如果是true，则将所有空闲的线程标记为已中断
                interruptIdleWorkers();
        }
    }

    /**
     * Sets the maximum allowed number of threads. This overrides any
     * value set in the constructor. If the new value is smaller than
     * the current value, excess existing threads will be
     * terminated when they next become idle.
     *
     * @param maximumPoolSize the new maximum
     * @throws IllegalArgumentException if the new maximum is
     *         less than or equal to zero, or
     *         less than the {@linkplain #getCorePoolSize core pool size}
     * @see #getMaximumPoolSize
     */
    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        if (workerCountOf(ctl.get()) > maximumPoolSize)
            interruptIdleWorkers();
    }

    /**
     * Returns the maximum allowed number of threads.
     *
     * @return the maximum allowed number of threads
     * @see #setMaximumPoolSize
     */
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    /**
     * Sets the time limit for which threads may remain idle before
     * being terminated.  If there are more than the core number of
     * threads currently in the pool, after waiting this amount of
     * time without processing a task, excess threads will be
     * terminated.  This overrides any value set in the constructor.
     *
     * @param time the time to wait.  A time value of zero will cause
     *        excess threads to terminate immediately after executing tasks.
     * @param unit the time unit of the {@code time} argument
     * @throws IllegalArgumentException if {@code time} less than zero or
     *         if {@code time} is zero and {@code allowsCoreThreadTimeOut}
     * @see #getKeepAliveTime(TimeUnit)
     */
    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0)
            throw new IllegalArgumentException();
        if (time == 0 && allowsCoreThreadTimeOut())
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        long keepAliveTime = unit.toNanos(time);
        long delta = keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        if (delta < 0)
            interruptIdleWorkers();
    }

    /**
     * Returns the thread keep-alive time, which is the amount of time
     * that threads in excess of the core pool size may remain
     * idle before being terminated.
     *
     * @param unit the desired time unit of the result
     * @return the time limit
     * @see #setKeepAliveTime(long, TimeUnit)
     */
    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
    }

    /* User-level queue utilities */

    /**
     * Returns the task queue used by this executor. Access to the
     * task queue is intended primarily for debugging and monitoring.
     * This queue may be in active use.  Retrieving the task queue
     * does not prevent queued tasks from executing.
     *
     * @return the task queue
     */
    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    /**
     * 如果执行器存在该任务，则将其从执行器的内部队列中移除，如果尚未启动，则导致该任务无法运行。
     *
     * <p>This method may be useful as one part of a cancellation
     * scheme.  It may fail to remove tasks that have been converted
     * into other forms before being placed on the internal queue. For
     * example, a task entered using {@code submit} might be
     * converted into a form that maintains {@code Future} status.
     * However, in such cases, method {@link #purge} may be used to
     * remove those Futures that have been cancelled.
     *
     * @param task the task to remove
     * @return {@code true} if the task was removed
     */
    public boolean remove(Runnable task) {
        // 从队列中移除任务
        boolean removed = workQueue.remove(task);
        // 尝试关闭线程池
        tryTerminate(); // In case SHUTDOWN and now empty
        return removed;
    }

    /**
     * 尝试清理掉队列中所有被取消的Future任务. 这个方法可以用于存储回收的地方, 对其他没有任何影响功能.
     * <br><br>
     * <p>因为取消的任务永远不会执行，但是可能还会存在于工作队列中直到工作线程主动将其移除.
     * <p>所以可以调用此方法可以尝试立即将其删除.
     * <p>但是如果存在其他线程干扰的情况下可能无法删除任务.
     */
    public void purge() {
        //迭代循环并尝试删除任务，可能存在并发问题
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            // 遍历队列，清除掉被取消的任务
            while (it.hasNext()) {
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    it.remove();
            }
        } catch (ConcurrentModificationException fallThrough) {
            // Take slow path if we encounter interference during traversal.
            // Make copy for traversal and call remove for cancelled entries.
            // The slow path is more likely to be O(N*N).
            // 如果遍历器不支持修改，则通过toArray方法遍历
            for (Object r : q.toArray())
                // 清除掉被取消的任务
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    q.remove(r);
        }
        // 尝试终止线程池
        tryTerminate(); // In case SHUTDOWN and now empty
    }

    /* Statistics */

    /**
     * 返回当前工作线程数
     *
     * @return the number of threads
     */
    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // Remove rare and surprising possibility of
            // isTerminated() && getPoolSize() > 0
            return runStateAtLeast(ctl.get(), TIDYING) ? 0
                : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 返回当前活跃的线程数（即运行中的）
     *
     * @return the number of threads
     */
    public int getActiveCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int n = 0;
            for (Worker w : workers)
                if (w.isLocked())
                    ++n;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 返回同时存在于池中的最大线程数。
     *
     * @return the number of threads
     */
    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 获取当前的任务数（正在执行的任务 + 等待队列的任务）
     *
     * @return the number of tasks
     */
    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
                if (w.isLocked())
                    ++n;
            }
            return n + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 获得此线程池总计完成的任务数
     *
     * @return the number of tasks
     */
    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers)
                n += w.completedTasks;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns a string identifying this pool, as well as its state,
     * including indications of run state and estimated worker and
     * task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        long ncompleted;
        int nworkers, nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked())
                    ++nactive;
            }
        } finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String rs = (runStateLessThan(c, SHUTDOWN) ? "Running" :
                     (runStateAtLeast(c, TERMINATED) ? "Terminated" :
                      "Shutting down"));
        return super.toString() +
            "[" + rs +
            ", pool size = " + nworkers +
            ", active threads = " + nactive +
            ", queued tasks = " + workQueue.size() +
            ", completed tasks = " + ncompleted +
            "]";
    }

    /* Extension hooks */

    /**
     * Method invoked prior to executing the given Runnable in the
     * given thread.  This method is invoked by thread {@code t} that
     * will execute task {@code r}, and may be used to re-initialize
     * ThreadLocals, or to perform logging.
     *
     * <p>This implementation does nothing, but may be customized in
     * subclasses. Note: To properly nest multiple overridings, subclasses
     * should generally invoke {@code super.beforeExecute} at the end of
     * this method.
     *
     * @param t the thread that will run task {@code r}
     * @param r the task that will be executed
     */
    protected void beforeExecute(Thread t, Runnable r) { }

    /**
     * Method invoked upon completion of execution of the given Runnable.
     * This method is invoked by the thread that executed the task. If
     * non-null, the Throwable is the uncaught {@code RuntimeException}
     * or {@code Error} that caused execution to terminate abruptly.
     *
     * <p>This implementation does nothing, but may be customized in
     * subclasses. Note: To properly nest multiple overridings, subclasses
     * should generally invoke {@code super.afterExecute} at the
     * beginning of this method.
     *
     * <p><b>Note:</b> When actions are enclosed in tasks (such as
     * {@link FutureTask}) either explicitly or via methods such as
     * {@code submit}, these task objects catch and maintain
     * computational exceptions, and so they do not cause abrupt
     * termination, and the internal exceptions are <em>not</em>
     * passed to this method. If you would like to trap both kinds of
     * failures in this method, you can further probe for such cases,
     * as in this sample subclass that prints either the direct cause
     * or the underlying exception if a task has been aborted:
     *
     *  <pre> {@code
     * class ExtendedExecutor extends ThreadPoolExecutor {
     *   // ...
     *   protected void afterExecute(Runnable r, Throwable t) {
     *     super.afterExecute(r, t);
     *     if (t == null && r instanceof Future<?>) {
     *       try {
     *         Object result = ((Future<?>) r).get();
     *       } catch (CancellationException ce) {
     *           t = ce;
     *       } catch (ExecutionException ee) {
     *           t = ee.getCause();
     *       } catch (InterruptedException ie) {
     *           Thread.currentThread().interrupt(); // ignore/reset
     *       }
     *     }
     *     if (t != null)
     *       System.out.println(t);
     *   }
     * }}</pre>
     *
     * @param r the runnable that has completed
     * @param t the exception that caused termination, or null if
     * execution completed normally
     */
    protected void afterExecute(Runnable r, Throwable t) { }

    /**
     * Method invoked when the Executor has terminated.  Default
     * implementation does nothing. Note: To properly nest multiple
     * overridings, subclasses should generally invoke
     * {@code super.terminated} within this method.
     */
    protected void terminated() { }

    /* Predefined RejectedExecutionHandlers */

    /**
     * 直接在调用线程中执行任务，如果线程池关闭，则丢次此任务
     */
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code CallerRunsPolicy}.
         */
        public CallerRunsPolicy() { }

        /**
         * Executes task r in the caller's thread, unless the executor
         * has been shut down, in which case the task is discarded.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                // 执行任务
                r.run();
            }
        }
    }

    /**
     * 直接抛出一个 RejectedExecutionException 异常
     */
    public static class AbortPolicy implements RejectedExecutionHandler {
        /**
         * Creates an {@code AbortPolicy}.
         */
        public AbortPolicy() { }

        /**
         * Always throws RejectedExecutionException.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         * @throws RejectedExecutionException always
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                                                 " rejected from " +
                                                 e.toString());
        }
    }

    /**
     * 直接丢弃掉任务并不抛出任何错误信息
     */
    public static class DiscardPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardPolicy}.
         */
        public DiscardPolicy() { }

        /**
         * Does nothing, which has the effect of discarding task r.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }

    /**
     * 移除队列中最早的任务并将此任务尝试添加到队列中或者运行
     */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardOldestPolicy} for the given executor.
         */
        public DiscardOldestPolicy() { }

        /**
         * Obtains and ignores the next task that the executor
         * would otherwise execute, if one is immediately available,
         * and then retries execution of task r, unless the executor
         * is shut down, in which case task r is instead discarded.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) { // 如果线程池正在运行
                e.getQueue().poll(); // 获取并移除队列中最早入队的元素
                // 重新提交到线程池
                e.execute(r);
            }
        }
    }
}
