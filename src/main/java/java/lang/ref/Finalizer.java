/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.ref;

import java.security.PrivilegedAction;
import java.security.AccessController;
import sun.misc.JavaLangAccess;
import sun.misc.SharedSecrets;
import sun.misc.VM;

/**
 * JVM是如何实现finalize方法的呢？
 * <ul>
 *     <li>
 *         JVM在加载类的时候，会去识别该类是否实现了finalize方法并且该方法体不会空；若是含有有意义的finalize方法体会标记出该类为“finalize Class”。
 *     </li>
 *     <li>
 *         在new “finalize Class”对象时，会调用Finalizer.register方法，在该方法中new 一个Finalizer对象，Finalizer对象会引用原始对象，
 *         然后把Finalizer对象注册到Finalizer对象链里（这样就可以保证Finalizer对象一直可达的）。具体代码如下：
 *     </li>
 *     <li>
 *         在发生gc的时候，在判断原始对象除了Finalizer对象引用之外，没有其他对象引用之后，就把Finalizer对象从对象链中取出，加入到Finalizer queue队列中。
 *     </li>
 *     <li>
 *         JVM在启动时，会创建一个“finalize”线程，该线程会一直从“Finalizer queue”队列中取出对象，然后执行原始对象中的finalize方法。
 *     </li>
 *     <li>
 *         Finalizer对象以及其引用的原始对象，再也没有其他对象引用他们，属于不可达对象，再次GC的时候他们将会被回收掉。
 *         （如果在finalize方法重新使该对象再次可达，再次GC该对象也不会被回收）。
 *     </li>
 * </ul>
 *
 * 使用finalize方法带来哪些影响？
 * <ul>
 *     <li>
 *         创建一个包含finalize方法的对象时，需要额外创建Finalizer对象并且注册到Finalizer对象链中；
 *         这样就需要额外的内存空间，并且创建finalize方法的对象的时间要长。创建普通对象和含finalize方法的对象时间相差4倍左右（循环10000创建一个不含任何变量的对象）。
 *     </li>
 *     <li>
 *         和相比普通对象，含有finalize方法的对象的生存周期变长，普通对象一次GC就可以回收；
 *         而含有finalize方法的对象至少需要两次gc，这样就会导致young gc阶段Object Copy阶段时间上升。
 *     </li>
 *     <li>
 *         在gc时需要对包含finalize方法的对象做特殊处理，比如识别该对象是否只有Finalizer对象引用，
 *         把Finalizer对象添加到queue队列这些都是在gc阶段完成，需要额外处理时间，在young gc属于Ref Proc时间，必然导致Ref Proc阶段时间上升。
 *     </li>
 *     <li>
 *         因为“finalize”线程优先级比较低，如果cpu比较繁忙，可能会导致queue队列有挤压，
 *         在经历多次young gc之后原始对象和Finalizer对象就会进入old区域，那么这些对象只能等待old gc才能被释放掉。
 *     </li>
 * </ul>
 *
 * @see FinalizerThread
 */
final class Finalizer extends FinalReference<Object> { /* Package-private; must be in
                                                          same package as the Reference
                                                          class */

    private static ReferenceQueue<Object> queue = new ReferenceQueue<>();
    //双向链表的头
    private static Finalizer unfinalized = null;
    private static final Object lock = new Object();

    private Finalizer
        next = null,
        prev = null;

    /**
     * 设置为已执行过Finalize方法
     * @return boolean
     */
    private boolean hasBeenFinalized() {
        return (next == this);
    }

    /**
     * 在首次new出Finalizer的时候会将其添加到队列的头部，并且初始化next和prev的值
     */
    private void add() {
        synchronized (lock) {
            if (unfinalized != null) {
                //当前值的下一个指向原来的头对象
                this.next = unfinalized;
                //上一个指向自己
                unfinalized.prev = this;
            }
            //将头部设置为自己
            unfinalized = this;
        }
    }

    /**
     * 将自己从链中移除
     */
    private void remove() {
        synchronized (lock) {
            //如果头部是自己的话，需要将头部重置一下
            if (unfinalized == this) {
                //有下一个对象，则将头部指向下一个对象，没有下一个对象则指向上一个对象（即为空）
                if (this.next != null) {
                    unfinalized = this.next;
                } else {
                    unfinalized = this.prev;
                }
            }
            //将自己从链条中移走
            if (this.next != null) {
                this.next.prev = this.prev;
            }
            if (this.prev != null) {
                this.prev.next = this.next;
            }
            //全部指向自己，表示完成移除
            this.next = this;   /* Indicates that this has been finalized */
            this.prev = this;
        }
    }

    private Finalizer(Object finalizee) {
        super(finalizee, queue);
        add();
    }

    /* Invoked by VM */
    // 静态的register方法，注意它的注释“被vm调用”，所以jvm是通过调用这个方法将对象封装为Finalizer对象的
    static void register(Object finalizee) {
        new Finalizer(finalizee);
    }

    private void runFinalizer(JavaLangAccess jla) {
        synchronized (this) {
            //如果已经执行过了，则不要再执行了
            if (hasBeenFinalized()) return;
            //将自己从Finalizer对象链里剥离
            remove();
        }
        try {
            //获取到真正的对象
            Object finalizee = this.get();
            if (finalizee != null && !(finalizee instanceof java.lang.Enum)) {
                //执行Finalize方法
                jla.invokeFinalize(finalizee);

                /* Clear stack slot containing this variable, to decrease
                   the chances of false retention with a conservative GC */
                finalizee = null;
            }
        } catch (Throwable x) { }
        super.clear();
    }

    /* Create a privileged secondary finalizer thread in the system thread
       group for the given Runnable, and wait for it to complete.

       This method is used by both runFinalization and runFinalizersOnExit.
       The former method invokes all pending finalizers, while the latter
       invokes all uninvoked finalizers if on-exit finalization has been
       enabled.

       These two methods could have been implemented by offloading their work
       to the regular finalizer thread and waiting for that thread to finish.
       The advantage of creating a fresh thread, however, is that it insulates
       invokers of these methods from a stalled or deadlocked finalizer thread.
     */
    private static void forkSecondaryFinalizer(final Runnable proc) {
        AccessController.doPrivileged(
            new PrivilegedAction<Void>() {
                public Void run() {
                ThreadGroup tg = Thread.currentThread().getThreadGroup();
                for (ThreadGroup tgn = tg;
                     tgn != null;
                     tg = tgn, tgn = tg.getParent());
                Thread sft = new Thread(tg, proc, "Secondary finalizer");
                sft.start();
                try {
                    sft.join();
                } catch (InterruptedException x) {
                    /* Ignore */
                }
                return null;
                }});
    }

    /* Called by Runtime.runFinalization() */
    static void runFinalization() {
        if (!VM.isBooted()) {
            return;
        }

        forkSecondaryFinalizer(new Runnable() {
            private volatile boolean running;
            public void run() {
                if (running)
                    return;
                final JavaLangAccess jla = SharedSecrets.getJavaLangAccess();
                running = true;
                for (;;) {
                    Finalizer f = (Finalizer)queue.poll();
                    if (f == null) break;
                    f.runFinalizer(jla);
                }
            }
        });
    }

    /* Invoked by java.lang.Shutdown */
    static void runAllFinalizers() {
        if (!VM.isBooted()) {
            return;
        }

        forkSecondaryFinalizer(new Runnable() {
            private volatile boolean running;
            public void run() {
                if (running)
                    return;
                final JavaLangAccess jla = SharedSecrets.getJavaLangAccess();
                running = true;
                for (;;) {
                    Finalizer f;
                    synchronized (lock) {
                        f = unfinalized;
                        if (f == null) break;
                        unfinalized = f.next;
                    }
                    f.runFinalizer(jla);
                }}});
    }

    private static class FinalizerThread extends Thread {
        private volatile boolean running;
        FinalizerThread(ThreadGroup g) {
            super(g, "Finalizer");
        }
        public void run() {
            if (running)
                return;

            // Finalizer thread starts before System.initializeSystemClass
            // is called.  Wait until JavaLangAccess is available

            // 这个是等待JVM初始化完成
            while (!VM.isBooted()) {
                // delay until VM completes initialization
                try {
                    VM.awaitBooted();
                } catch (InterruptedException x) {
                    // ignore and continue
                }
            }
            final JavaLangAccess jla = SharedSecrets.getJavaLangAccess();
            running = true;
            for (;;) {
                try {
                    //从队列中移除Final引用对象并执行Finalize方法
                    Finalizer f = (Finalizer)queue.remove();
                    f.runFinalizer(jla);
                } catch (InterruptedException x) {
                    // ignore and continue
                }
            }
        }
    }

    // JVM启动之后，会默认创建一个FinalizerThread的线程，来执行对象回收之后的Finalize方法。
    static {
        ThreadGroup tg = Thread.currentThread().getThreadGroup();
        for (ThreadGroup tgn = tg;
             tgn != null;
             tg = tgn, tgn = tg.getParent());
        Thread finalizer = new FinalizerThread(tg);
        finalizer.setPriority(Thread.MAX_PRIORITY - 2);
        finalizer.setDaemon(true);
        finalizer.start();
    }

}
