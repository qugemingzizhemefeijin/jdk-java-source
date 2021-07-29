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

import java.security.AccessControlContext;
import java.security.ProtectionDomain;

/**
 * A thread managed by a {@link ForkJoinPool}, which executes
 * {@link ForkJoinTask}s.
 * This class is subclassable solely for the sake of adding
 * functionality -- there are no overridable methods dealing with
 * scheduling or execution.  However, you can override initialization
 * and termination methods surrounding the main task processing loop.
 * If you do create such a subclass, you will also need to supply a
 * custom {@link ForkJoinPool.ForkJoinWorkerThreadFactory} to
 * {@linkplain ForkJoinPool#ForkJoinPool use it} in a {@code ForkJoinPool}.
 *
 * @since 1.7
 * @author Doug Lea
 */
public class ForkJoinWorkerThread extends Thread {
    /*
     * ForkJoinWorkerThreads are managed by ForkJoinPools and perform
     * ForkJoinTasks. For explanation, see the internal documentation
     * of class ForkJoinPool.
     *
     * This class just maintains links to its pool and WorkQueue.  The
     * pool field is set immediately upon construction, but the
     * workQueue field is not set until a call to registerWorker
     * completes. This leads to a visibility race, that is tolerated
     * by requiring that the workQueue field is only accessed by the
     * owning thread.
     *
     * Support for (non-public) subclass InnocuousForkJoinWorkerThread
     * requires that we break quite a lot of encapsulation (via Unsafe)
     * both here and in the subclass to access and set Thread fields.
     */

    // 负责管理该线程的线程池实现
    final ForkJoinPool pool;                // the pool this thread works in
    // 保存待执行任务的任务队列
    final ForkJoinPool.WorkQueue workQueue; // work-stealing mechanics

    /**
     * Creates a ForkJoinWorkerThread operating in the given pool.
     *
     * @param pool the pool this thread works in
     * @throws NullPointerException if pool is null
     */
    protected ForkJoinWorkerThread(ForkJoinPool pool) {
        // Use a placeholder until a useful name can be set in registerWorker
        // 指定初始线程名，registerWorker方法中会修改线程名
        super("aForkJoinWorkerThread");
        this.pool = pool;
        // 将当前线程注册到Pool，注册完成后返回WorkQueue实例
        this.workQueue = pool.registerWorker(this);
    }

    /**
     * Version for InnocuousForkJoinWorkerThread
     */
    ForkJoinWorkerThread(ForkJoinPool pool, ThreadGroup threadGroup,
                         AccessControlContext acc) {
        // 指定初始线程名和ThreadGroup，registerWorker方法中会修改线程名
        super(threadGroup, null, "aForkJoinWorkerThread");
        // 修改属性inheritedAccessControlContext
        U.putOrderedObject(this, INHERITEDACCESSCONTROLCONTEXT, acc);
        // 清空本地线程变量
        eraseThreadLocals(); // clear before registering
        this.pool = pool;
        // 将当前线程注册到Pool，注册完成后返回WorkQueue实例
        this.workQueue = pool.registerWorker(this);
    }

    /**
     * Returns the pool hosting this thread.
     *
     * @return the pool
     */
    public ForkJoinPool getPool() {
        return pool;
    }

    /**
     * Returns the unique index number of this thread in its pool.
     * The returned value ranges from zero to the maximum number of
     * threads (minus one) that may exist in the pool, and does not
     * change during the lifetime of the thread.  This method may be
     * useful for applications that track status or collect results
     * per-worker-thread rather than per-task.
     *
     * 返回当前线程在线程池中的编号
     *
     * @return the index number
     */
    public int getPoolIndex() {
        // 注意workQueue是registerWorker后返回的，与线程是1对1的关系
        return workQueue.getPoolIndex();
    }

    /**
     * Initializes internal state after construction but before
     * processing any tasks. If you override this method, you must
     * invoke {@code super.onStart()} at the beginning of the method.
     * Initialization requires care: Most fields must have legal
     * default values, to ensure that attempted accesses from other
     * threads work correctly even before this thread starts
     * processing tasks.
     */
    protected void onStart() {
    }

    /**
     * Performs cleanup associated with termination of this worker
     * thread.  If you override this method, you must invoke
     * {@code super.onTermination} at the end of the overridden method.
     *
     * @param exception the exception causing this thread to abort due
     * to an unrecoverable error, or {@code null} if completed normally
     */
    protected void onTermination(Throwable exception) {
    }

    /**
     * This method is required to be public, but should never be
     * called explicitly. It performs the main run loop to execute
     * {@link ForkJoinTask}s.
     */
    public void run() {
        if (workQueue.array == null) { // only run once
            Throwable exception = null;
            try {
                // 启动前的回调方法，默认空实现
                onStart();
                // 启动该线程，从任务队列中获取任务并执行
                pool.runWorker(workQueue);
            } catch (Throwable ex) {
                exception = ex;
            } finally {
                try {
                    // 线程退出时的回调方法
                    onTermination(exception);
                } catch (Throwable ex) {
                    if (exception == null)
                        exception = ex;
                } finally {
                    // 通知线程池当前线程退出
                    pool.deregisterWorker(this, exception);
                }
            }
        }
    }

    /**
     * Erases ThreadLocals by nulling out Thread maps.
     */
    final void eraseThreadLocals() {
        // 将保存本地线程变量的两个Map置为null，即清空可能残留的本地线程变量
        U.putObject(this, THREADLOCALS, null);
        U.putObject(this, INHERITABLETHREADLOCALS, null);
    }

    /**
     * Non-public hook method for InnocuousForkJoinWorkerThread
     */
    void afterTopLevelExec() {
    }

    // Set up to allow setting thread fields in constructor
    private static final sun.misc.Unsafe U;
    private static final long THREADLOCALS;
    private static final long INHERITABLETHREADLOCALS;
    private static final long INHERITEDACCESSCONTROLCONTEXT;
    static {
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> tk = Thread.class;
            THREADLOCALS = U.objectFieldOffset
                (tk.getDeclaredField("threadLocals"));
            INHERITABLETHREADLOCALS = U.objectFieldOffset
                (tk.getDeclaredField("inheritableThreadLocals"));
            INHERITEDACCESSCONTROLCONTEXT = U.objectFieldOffset
                (tk.getDeclaredField("inheritedAccessControlContext"));

        } catch (Exception e) {
            throw new Error(e);
        }
    }

    /**
     * A worker thread that has no permissions, is not a member of any
     * user-defined ThreadGroup, and erases all ThreadLocals after
     * running each top-level task.
     *
     * <p>
     * 表示一个特殊的无任何访问权限的线程，不属于任何用户定义的线程组，当执行afterTopLevelExec后会将原线程的本地变量都清空。
     */
    static final class InnocuousForkJoinWorkerThread extends ForkJoinWorkerThread {
        /** The ThreadGroup for all InnocuousForkJoinWorkerThreads */
        // 创建一个全局的线程组
        private static final ThreadGroup innocuousThreadGroup =
            createThreadGroup();

        /** An AccessControlContext supporting no privileges */
        private static final AccessControlContext INNOCUOUS_ACC =
            new AccessControlContext(
                new ProtectionDomain[] {
                    // 无任务访问权限
                    new ProtectionDomain(null, null)
                });

        InnocuousForkJoinWorkerThread(ForkJoinPool pool) {
            // 此构造方法会清空本地线程变量
            super(pool, innocuousThreadGroup, INNOCUOUS_ACC);
        }

        @Override // to erase ThreadLocals
        void afterTopLevelExec() {
            // 清空本地线程变量
            eraseThreadLocals();
        }

        @Override // to always report system loader
        public ClassLoader getContextClassLoader() {
            // 默认是返回父线程的contextClassLoader，此处改写直接返回SystemClassLoader
            return ClassLoader.getSystemClassLoader();
        }

        // 无法设置该线程的异常处理器，即默认为null，出现异常后线程自动退出
        @Override // to silently fail
        public void setUncaughtExceptionHandler(UncaughtExceptionHandler x) { }

        // 不支持设置ClassLoader，直接抛出异常
        @Override // paranoically
        public void setContextClassLoader(ClassLoader cl) {
            throw new SecurityException("setContextClassLoader");
        }

        /**
         * Returns a new group with the system ThreadGroup (the
         * topmost, parent-less group) as parent.  Uses Unsafe to
         * traverse Thread.group and ThreadGroup.parent fields.
         */
        // Java中ThreadGroup只提供关联线程的管理维护功能，跟底层的Linux线程组无关联
        private static ThreadGroup createThreadGroup() {
            try {
                sun.misc.Unsafe u = sun.misc.Unsafe.getUnsafe();
                Class<?> tk = Thread.class;
                Class<?> gk = ThreadGroup.class;
                long tg = u.objectFieldOffset(tk.getDeclaredField("group"));
                long gp = u.objectFieldOffset(gk.getDeclaredField("parent"));
                // 获取当前线程的线程组
                ThreadGroup group = (ThreadGroup)
                    u.getObject(Thread.currentThread(), tg);
                while (group != null) {
                    // 获取父线程组
                    ThreadGroup parent = (ThreadGroup)u.getObject(group, gp);
                    if (parent == null)
                        // 找到了最初的祖先级的线程组，使用该线程组创建一个新的线程组
                        return new ThreadGroup(group,
                                               "InnocuousForkJoinWorkerThreadGroup");
                    group = parent;
                }
            } catch (Exception e) {
                throw new Error(e);
            }
            // 正常不会进入此代码，直接抛出异常
            // fall through if null as cannot-happen safeguard
            throw new Error("Cannot create ThreadGroup");
        }
    }

}
