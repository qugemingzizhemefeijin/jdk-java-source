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

import sun.misc.Cleaner;
import sun.misc.JavaLangRefAccess;
import sun.misc.SharedSecrets;

/**
 * Abstract base class for reference objects.  This class defines the
 * operations common to all reference objects.  Because reference objects are
 * implemented in close cooperation with the garbage collector, this class may
 * not be subclassed directly.
 *
 * <br><br>
 *
 * Reference引用对象有以下几种状态：
 * <ul>
 *     <li>
 *         Active：新创建的引用对象的状态为Active。 GC检测到其可达性发生变化时会更改其状态。
 *         此时分两种情况，如果该引用对象创建时有注册引用队列，则会进入Pending状态，否则会进入Inactive状态。
 *     </li>
 *     <li>
 *         Pending：在Pending列表中的元素状态为Pending状态，等待被ReferenceHandler线程消费并加入其注册的引用队列。
 *         如果该引用对象未注册引用队列，则永远不会处于这个状态。
 *     </li>
 *     <li>
 *         Enqueued：该引用对象创建时有注册引用队列并且当前引用对象在此队列中。当该引用对象从其注册引用队列中移除后状态变为Inactive。
 *         如果该引用对象未注册引用队列，则永远不会处于这个状态。
 *     </li>
 *     <li>
 *         Inactive：当处于Inactive状态时无须任何处理，因为一旦变成Inactive状态，其状态永远不会再发生改变
 *     </li>
 * </ul>
 *
 * <b>Active、 Pending、 Enqueued和Inactive四个状态并没有一个专门的字段进行描述， 而是通过queue和next字段虚拟出来的状态。</b>
 *
 * <p>
 *     HotSpot VM如果判断queue字段为ReferenceQueue，而next字段为null时，就会认为当前引用对象的状态为Active，
 *     而不是通过查找某个状态字段的值得出当前引用对象的状态为Active。
 * </p>
 *
 * <ul>
 *     <li>
 *         Active：当实例注册了引用队列，则queue = ReferenceQueue；当实例没有注册引用队列，那么queue = ReferenceQueue.NULL。next = null。
 *     </li>
 *     <li>
 *         Pending：处在这个状态下的实例肯定注册了引用队列，queue = ReferenceQueue；next = this。
 *     </li>
 *     <li>
 *         Enqueued：处在这个状态下的实例肯定注册了引用队列，queue = ReferenceQueue.ENQUEUED，
 *         next指向下一个在此队列中的元素，或者如果队列中只有当前对象时为当前对象this；
 *     </li>
 *     <li>
 *         Inactive：queue = ReferenceQueue.NULL；next = this。
 *     </li>
 * </ul>
 *
 * <p>
 *     查找引用类型并添加到Discovered-List是由HotSpot VM内部负责的。首先要初始化引用类型相关的变量。
 *     HotSpot VM在启动时，调用的init_globals()函数中会调用referenceProcessor_init()函数，
 *     最终会调用Reference-Processor::init_statics()函数初始化引用处理器ReferenceProcessor的相关属性。
 * </p>
 *
 * <p>
 *     hotspot/src/share/vm/memory/referenceProcessor.cpp
 * </p>
 *
 * <p>
 *     HotSpot VM查找引用类型的过程。 在GenCollectedHeap::do_collection()函数中
 * </p>
 *
 * @author   Mark Reinhold
 * @since    1.2
 */
public abstract class Reference<T> {

    /* A Reference instance is in one of four possible internal states:
     *
     *     Active: Subject to special treatment by the garbage collector.  Some
     *     time after the collector detects that the reachability of the
     *     referent has changed to the appropriate state, it changes the
     *     instance's state to either Pending or Inactive, depending upon
     *     whether or not the instance was registered with a queue when it was
     *     created.  In the former case it also adds the instance to the
     *     pending-Reference list.  Newly-created instances are Active.
     *
     *     Pending: An element of the pending-Reference list, waiting to be
     *     enqueued by the Reference-handler thread.  Unregistered instances
     *     are never in this state.
     *
     *     Enqueued: An element of the queue with which the instance was
     *     registered when it was created.  When an instance is removed from
     *     its ReferenceQueue, it is made Inactive.  Unregistered instances are
     *     never in this state.
     *
     *     Inactive: Nothing more to do.  Once an instance becomes Inactive its
     *     state will never change again.
     *
     * The state is encoded in the queue and next fields as follows:
     *
     *     Active: queue = ReferenceQueue with which instance is registered, or
     *     ReferenceQueue.NULL if it was not registered with a queue; next =
     *     null.
     *
     *     Pending: queue = ReferenceQueue with which instance is registered;
     *     next = this
     *
     *     Enqueued: queue = ReferenceQueue.ENQUEUED; next = Following instance
     *     in queue, or this if at end of list.
     *
     *     Inactive: queue = ReferenceQueue.NULL; next = this.
     *
     * With this scheme the collector need only examine the next field in order
     * to determine whether a Reference instance requires special treatment: If
     * the next field is null then the instance is active; if it is non-null,
     * then the collector should treat the instance normally.
     *
     * To ensure that a concurrent collector can discover active Reference
     * objects without interfering with application threads that may apply
     * the enqueue() method to those objects, collectors should link
     * discovered objects through the discovered field. The discovered
     * field is also used for linking Reference objects in the pending list.
     */

    //引用的对象
    private T referent;         /* Treated specially by GC */

    //回收队列，由使用者在Reference的构造函数中指定
    volatile ReferenceQueue<? super T> queue;

    /* When active:   NULL
     *     pending:   this
     *    Enqueued:   next reference in queue (or this if last)
     *    Inactive:   this
     */
    //当该引用被加入到queue中的时候，该字段被设置为queue中的下一个元素，以形成链表结构
    @SuppressWarnings("rawtypes")
    Reference next;

    /* When active:   next element in a discovered reference list maintained by GC (or this if last)
     *     pending:   next element in the pending list (or null if last)
     *   otherwise:   NULL
     */
    //在GC时，HotSpot底层会维护一个叫DiscoveredList的链表，存放的是Reference对象，discovered字段指向的就是链表中的下一个元素，由HotSpot设置
    transient private Reference<T> discovered;  /* used by VM */


    /* Object used to synchronize with the garbage collector.  The collector
     * must acquire this lock at the beginning of each collection cycle.  It is
     * therefore critical that any code holding this lock complete as quickly
     * as possible, allocate no new objects, and avoid calling user code.
     */
    // 进行线程同步的锁对象
    static private class Lock { }
    // hotspot/src/share/vm/gc_implementation/vmGCOperations.cpp
    // VM_GC_Operation::acquire_pending_list_lock() 执行获取锁的操作
    // 当GC处理完成后
    // VM_GC_Operation::release_and_notify_pending_list_lock()函数释放锁并通知ReferenceHandler线程处理PendingList
    private static Lock lock = new Lock();


    /* List of References waiting to be enqueued.  The collector adds
     * References to this list, while the Reference-handler thread removes
     * them.  This list is protected by the above lock object. The
     * list uses the discovered field to link its elements.
     */
    //等待加入queue的Reference对象，在GC时由JVM设置，会有一个java层的线程(ReferenceHandler)源源不断的从pending中提取元素加入到queue
    private static Reference<Object> pending = null;

    /* High-priority thread to enqueue pending References
     * 引用对象生命周期 https://img2020.cnblogs.com/blog/1236123/202004/1236123-20200409200531032-1604663906.png
     */
    private static class ReferenceHandler extends Thread {

        private static void ensureClassInitialized(Class<?> clazz) {
            try {
                Class.forName(clazz.getName(), true, clazz.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw (Error) new NoClassDefFoundError(e.getMessage()).initCause(e);
            }
        }

        static {
            // pre-load and initialize InterruptedException and Cleaner classes
            // so that we don't get into trouble later in the run loop if there's
            // memory shortage while loading/initializing them lazily.
            ensureClassInitialized(InterruptedException.class);
            ensureClassInitialized(Cleaner.class);
        }

        ReferenceHandler(ThreadGroup g, String name) {
            super(g, name);
        }

        public void run() {
            while (true) {
                tryHandlePending(true);
            }
        }
    }

    /**
     * Try handle pending {@link Reference} if there is one.<p>
     * Return {@code true} as a hint that there might be another
     * {@link Reference} pending or {@code false} when there are no more pending
     * {@link Reference}s at the moment and the program can do some other
     * useful work instead of looping. <br><br>
     *
     * 不断从DiscoveredList链表中获取元素，然后加入ReferenceQueue中，开发者可以通过调用ReferenceQueue的poll()方法来感知对象被回收的事件。<br><br>
     *
     * Cleaner类型（继承自虚引用）的对象会有额外的处理：在其指向的对象被回收时会调用clean()方法。
     * 该方法主要用于进行对应的资源回收，堆外内存DirectByteBuffer就是通过Cleaner回收的，这也是虚引用在Java中的典型应用
     *
     * @param waitForNotify if {@code true} and there was no pending
     *                      {@link Reference}, wait until notified from VM
     *                      or interrupted; if {@code false}, return immediately
     *                      when there is no pending {@link Reference}.
     * @return {@code true} if there was a {@link Reference} pending and it
     *         was processed, or we waited for notification and either got it
     *         or thread was interrupted before being notified;
     *         {@code false} otherwise.
     */
    static boolean tryHandlePending(boolean waitForNotify) {
        Reference<Object> r;
        Cleaner c;
        try {
            synchronized (lock) {
                if (pending != null) {
                    r = pending;
                    // 'instanceof' might throw OutOfMemoryError sometimes
                    // so do this before un-linking 'r' from the 'pending' chain...
                    // 如果是Cleaner对象，则记录下来，下面做特殊处理
                    c = r instanceof Cleaner ? (Cleaner) r : null;
                    // unlink 'r' from 'pending' chain
                    // 指向PendingList的下一个对象
                    pending = r.discovered;
                    // 从pending中取消链条
                    r.discovered = null;
                } else {
                    // The waiting on the lock may cause an OutOfMemoryError
                    // because it may try to allocate exception objects.
                    // 如果pending为null就先等待，当有对象加入到PendingList中时，jvm会执行notify
                    if (waitForNotify) {
                        lock.wait();
                    }
                    // retry if waited
                    return waitForNotify;
                }
            }
        } catch (OutOfMemoryError x) {
            // Give other threads CPU time so they hopefully drop some live references
            // and GC reclaims some space.
            // Also prevent CPU intensive spinning in case 'r instanceof Cleaner' above
            // persistently throws OOME for some time...
            Thread.yield();
            // retry
            return true;
        } catch (InterruptedException x) {
            // retry
            return true;
        }

        // Fast path for cleaners
        // 如果时Cleaner对象，则调用clean方法进行资源回收
        if (c != null) {
            c.clean();
            return true;
        }

        // 将Reference加入到ReferenceQueue，开发者可以通过从ReferenceQueue中poll元素感知到对象被回收的事件
        ReferenceQueue<? super Object> q = r.queue;
        if (q != ReferenceQueue.NULL) q.enqueue(r);
        return true;
    }

    static {
        ThreadGroup tg = Thread.currentThread().getThreadGroup();
        for (ThreadGroup tgn = tg;
             tgn != null;
             tg = tgn, tgn = tg.getParent());
        Thread handler = new ReferenceHandler(tg, "Reference Handler");
        /* If there were a special system-only priority greater than
         * MAX_PRIORITY, it would be used here
         */
        handler.setPriority(Thread.MAX_PRIORITY);
        handler.setDaemon(true);
        handler.start();

        // provide access in SharedSecrets
        SharedSecrets.setJavaLangRefAccess(new JavaLangRefAccess() {
            @Override
            public boolean tryHandlePendingReference() {
                return tryHandlePending(false);
            }
        });
    }

    /* -- Referent accessor and setters -- */

    /**
     * Returns this reference object's referent.  If this reference object has
     * been cleared, either by the program or by the garbage collector, then
     * this method returns <code>null</code>.
     *
     * @return   The object to which this reference refers, or
     *           <code>null</code> if this reference object has been cleared
     */
    public T get() {
        return this.referent;
    }

    /**
     * Clears this reference object.  Invoking this method will not cause this
     * object to be enqueued.
     *
     * <p> This method is invoked only by Java code; when the garbage collector
     * clears references it does so directly, without invoking this method.
     */
    public void clear() {
        this.referent = null;
    }


    /* -- Queue operations -- */

    /**
     * Tells whether or not this reference object has been enqueued, either by
     * the program or by the garbage collector.  If this reference object was
     * not registered with a queue when it was created, then this method will
     * always return <code>false</code>.
     *
     * @return   <code>true</code> if and only if this reference object has
     *           been enqueued
     */
    public boolean isEnqueued() {
        return (this.queue == ReferenceQueue.ENQUEUED);
    }

    /**
     * Adds this reference object to the queue with which it is registered,
     * if any.
     *
     * <p> This method is invoked only by Java code; when the garbage collector
     * enqueues references it does so directly, without invoking this method.
     *
     * @return   <code>true</code> if this reference object was successfully
     *           enqueued; <code>false</code> if it was already enqueued or if
     *           it was not registered with a queue when it was created
     */
    public boolean enqueue() {
        return this.queue.enqueue(this);
    }


    /* -- Constructors -- */

    Reference(T referent) {
        this(referent, null);
    }

    Reference(T referent, ReferenceQueue<? super T> queue) {
        this.referent = referent;
        this.queue = (queue == null) ? ReferenceQueue.NULL : queue;
    }

}
