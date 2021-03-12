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
import java.util.concurrent.locks.LockSupport;

/**
 * 可取消的异步调用对象G
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     *
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     *
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    private volatile int state;
    private static final int NEW          = 0;//新建
    private static final int COMPLETING   = 1;//正在完成
    private static final int NORMAL       = 2;//正常
    private static final int EXCEPTIONAL  = 3;//异常
    private static final int CANCELLED    = 4;//取消
    private static final int INTERRUPTING = 5;//正在中断
    private static final int INTERRUPTED  = 6;//中断

    /** The underlying callable; nulled out after running */
    private Callable<V> callable;
    /** The result to return or exception to throw from get() */
    private Object outcome; // non-volatile, protected by state reads/writes
    /** The thread running the callable; CASed during run() */
    private volatile Thread runner;
    /** Treiber stack of waiting threads */
    private volatile WaitNode waiters;

    /**
     * 返回结果或引发已完成任务的异常。
     *
     * @param s 状态值
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        if (s == NORMAL)
            return (V)x;
        if (s >= CANCELLED)//如果已经被取消
            throw new CancellationException();
        throw new ExecutionException((Throwable)x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param  callable the callable task
     * @throws NullPointerException if the callable is null
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result the result to return on successful completion. If
     * you don't need a particular result, consider using
     * constructions of the form:
     * {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;       // ensure visibility of callable
    }

    //是否被取消（取消、中断中、已中断）
    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    //是否执行完成
    public boolean isDone() {
        return state != NEW;
    }

    //尝试取消执行此任务。 此尝试将如果任务已经完成，已经被取消则失败，或由于其他原因无法取消。
    //如果成功的话并且在调用{@code cancel}时此任务尚未开始，此任务永远不会运行。
    //如果任务已经开始，然后{@code mayInterruptIfRunning}参数确定执行该任务的线程是否应在尝试停止任务。
    public boolean cancel(boolean mayInterruptIfRunning) {
        //此处通过CAS来更改状态值，只有NEW状态下才能被通过状态来更改
        //要么改成正在中断，要么改成已取消，如果无法进行更改，则返回false
        if (!(state == NEW &&
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                  mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        //走到这里意味着状态已经是NEW之后的状态了，如果确认线程已经启动了，则只能尝试着中断当前的线程了
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                try {
                    //获取当前线程并调用中断方法
                    Thread t = runner;
                    if (t != null)
                        t.interrupt();
                } finally { // final state
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);//设置为中断状态
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }

    /**
     * 获取任务的执行结果
     * @throws CancellationException {@inheritDoc}
     */
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        //如果执行中，则需要等待执行结果，否则输出数据
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        return report(s);
    }

    /**
     * 获取任务的执行结果，如果未执行或者正在执行中，则指定等待时间
     * @throws CancellationException {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        //如果任务正在执行中并且等待时间结果的时候还是执行中，则抛出TimeoutException异常
        if (s <= COMPLETING &&
            (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        //获取结果
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     *
     * 让子类来实现的方法，当任务完成(异常、中断、正常执行完等)的时候自动调用
     */
    protected void done() { }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * 设置执行的值到结果中
     *
     * @param v the value
     */
    protected void set(V v) {
        //修改状态为完成中(其实基本属于瞬时态)
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = v;
            //设置值 并且马上写入主存，该变量必须是volatile类型，最终状态为Normal
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t) {
        //设置完成中瞬时态
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            //设置为异常状态
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            //唤醒等待线程
            finishCompletion();
        }
    }

    public void run() {
        //如果状态不是初始状态或者已经有执行者了，则不要再次被调用了
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return;
        try {
            Callable<V> c = callable;
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    //执行任务并返回结果
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;
                    setException(ex);
                }
                //如果被正常调用，则设置返回值，并且解锁等待的线程
                if (ran)
                    set(result);
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            int s = state;
            //如果是中断相关的状态的话，需要等待其他线程执行interrupt方法，最终将状态改成INTERRUPTED
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * 这段代码跟上面的run差不太多，就是任务可以被多次执行，并且不会被设置执行状态，除非其他线程更改了此Future的状态
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        //一直等待其他线程将当前状态改成INTERRUPTED，否则就一直让出CPU
        //保证已经中断的状态不会因为此线程执行完后后会得到结果
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;//单链表结构
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * 删除并发出所有等待线程的解锁信号，并回调done()方法，并取消可调用。
     */
    private void finishCompletion() {
        // assert state > COMPLETING;
        //这里使用双循环是为了防止在首次获取到waiters链条的时候，进行删除操作，但是后面又有新的加入进来
        for (WaitNode q; (q = waiters) != null;) {
            //置空waiters链条
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                //挨个唤醒等待的线程
                for (;;) {
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    //迭代到最后则跳出循环
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    //获取下一个等待的线程节点
                    q = next;
                }
                break;
            }
        }

        done();

        callable = null;        // to reduce footprint
    }

    /**
     * 等待完成，或者在中断或超时时中止。.全程入队无锁，入队的时候使用到了CAS
     *
     * @param timed 如果使用定时等待，则为true
     * @param nanos 等待时间
     * @return 方法返回时Future的状态
     */
    private int awaitDone(boolean timed, long nanos) throws InterruptedException {
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;
        for (;;) {
            //如果线程被中断了，则移除等待者（第一次循环基本不会进入）
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            int s = state;
            //如果当前状态已经处理完成，则直接返回。顺便看看清理一下thread的引用
            if (s > COMPLETING) {
                if (q != null)
                    q.thread = null;
                return s;
            }
            //如果当前任务状态是正在进行中的瞬时态，则放弃当前的处理器
            else if (s == COMPLETING) // cannot time out yet
                Thread.yield();
            //当第一次循环的时候，封装一个WaitNode节点对象
            else if (q == null)
                q = new WaitNode();
            //是否把当前等待的节点加入到等待队列中(queued 一直重试直到要么状态变为完成状态，要么被成功设置到等待队列中)
            else if (!queued)
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset, q.next = waiters, q);
            //设置了等待时间
            else if (timed) {
                nanos = deadline - System.nanoTime();
                //如果到时间了，则从队列中清空自己
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            }
            //未设置等待时间则直接死等
            else
                LockSupport.park(this);
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     *
     * 尝试取消链接超时或中断的等待节点，以避免堆积。
     *
     * 内部节点只是不带CAS的未拼接，因为无论如何都要遍历他们，这是无害的。
     * 为了避免因未拼接而造成的影响删除的节点，如果出现情况，则重新遍历该列表。
     *
     * 当有很多节点时，这很慢，但是我们不希望列表足够长以超过开销。
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;//清空维护的线程对象，以方便下面的循环的时候将node节点剥离出单向链表
            retry:
            for (;;) {          // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        pred = q;
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    }
                    //替换链表头部
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset, q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
