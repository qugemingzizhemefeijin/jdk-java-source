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

/**
 * Reference queues, to which registered reference objects are appended by the
 * garbage collector after the appropriate reachability changes are detected.<br>
 * <br>
 * ReferenceQueue是引用队列，垃圾收集器在检测到适当的可达性更改后将已注册的引用对象追加到该队列。
 *
 * @author   Mark Reinhold
 * @since    1.2
 */

public class ReferenceQueue<T> {

    /**
     * Constructs a new reference-object queue.
     */
    public ReferenceQueue() { }

    // 内部类Null类继承自ReferenceQueue，覆盖了enqueue方法返回false
    private static class Null<S> extends ReferenceQueue<S> {
        boolean enqueue(Reference<? extends S> r) {
            return false;
        }
    }

    // 默认的Reference都会使用这个NULL的队列，此队列不会存放任何要被回收的引用
    static ReferenceQueue<Object> NULL = new Null<>();
    // 这个队列在第一次存放到指定的queue之后，会将引用类型的队列指向此，防止再次被放入到队列中
    static ReferenceQueue<Object> ENQUEUED = new Null<>();

    // 静态内部类，作为锁对象
    static private class Lock { };
    private Lock lock = new Lock();
    // 引用链表的头节点，每次丢一个，则将头设置为最后加入到队列的Reference对象
    private volatile Reference<? extends T> head = null;
    // 引用队列长度，入队则增加1，出队则减少1
    private long queueLength = 0;

    // 入队操作，只会被Reference实例调用
    boolean enqueue(Reference<? extends T> r) { /* Called only by Reference class */
        synchronized (lock) {
            // Check that since getting the lock this reference hasn't already been
            // enqueued (and even then removed)
            // 如果引用实例持有的队列为ReferenceQueue.NULL或者ReferenceQueue.ENQUEUED，则入队失败返回false
            ReferenceQueue<?> queue = r.queue;
            if ((queue == NULL) || (queue == ENQUEUED)) {
                return false;
            }
            assert queue == this;
            // 将当前入队Reference实例置为ReferenceQueue.ENQUEUED，防止重复入队
            r.queue = ENQUEUED;
            // 如果链表没有元素，则此引用实例直接作为头节点，否则把前一个引用实例作为下一个节点
            r.next = (head == null) ? r : head;
            // 当前实例更新为头节点，也就是每一个新入队的引用实例都是作为头节点，已有的引用实例会作为后继节点
            head = r;
            // 队列长度增加1
            queueLength++;
            // 特殊处理FinalReference，VM进行计数
            if (r instanceof FinalReference) {
                sun.misc.VM.addFinalRefCount(1);
            }
            // 唤醒所有等待的线程
            lock.notifyAll();
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    // 引用队列的poll操作，此方法必须在加锁情况下调用
    private Reference<? extends T> reallyPoll() {       /* Must hold lock */
        Reference<? extends T> r = head;
        if (r != null) {
            // 更新next节点为头节点，如果next节点为自身，那么队列中只有当前这个对象一个元素
            head = (r.next == r) ?
                null :
                r.next; // Unchecked due to the next field having a raw type in Reference
            r.queue = NULL;
            r.next = r;
            // 队列长度减少1
            queueLength--;
            // 特殊处理FinalReference，VM进行计数
            if (r instanceof FinalReference) {
                sun.misc.VM.addFinalRefCount(-1);
            }
            return r;
        }
        return null;
    }

    /**
     * Polls this queue to see if a reference object is available.  If one is
     * available without further delay then it is removed from the queue and
     * returned.  Otherwise this method immediately returns <tt>null</tt>.
     *
     * <p> 队列的公有poll操作，主要是加锁后调用reallyPoll
     *
     * @return  A reference object, if one was immediately available,
     *          otherwise <code>null</code>
     */
    public Reference<? extends T> poll() {
        if (head == null)
            return null;
        synchronized (lock) {
            return reallyPoll();
        }
    }

    /**
     * Removes the next reference object in this queue, blocking until either
     * one becomes available or the given timeout period expires.
     *
     * <p> This method does not offer real-time guarantees: It schedules the
     * timeout as if by invoking the {@link Object#wait(long)} method.
     *
     * @param  timeout  If positive, block for up to <code>timeout</code>
     *                  milliseconds while waiting for a reference to be
     *                  added to this queue.  If zero, block indefinitely.
     *
     * @return  A reference object, if one was available within the specified
     *          timeout period, otherwise <code>null</code>
     *
     * @throws  IllegalArgumentException
     *          If the value of the timeout argument is negative
     *
     * @throws  InterruptedException
     *          If the timeout wait is interrupted
     */
    public Reference<? extends T> remove(long timeout) throws IllegalArgumentException, InterruptedException {
        if (timeout < 0) {
            throw new IllegalArgumentException("Negative timeout value");
        }
        synchronized (lock) {
            //移除队列头部引用，如果队列为空，则需要等待指定的毫秒再返回
            Reference<? extends T> r = reallyPoll();
            if (r != null) return r;
            long start = (timeout == 0) ? 0 : System.nanoTime();
            for (;;) {
                lock.wait(timeout);
                r = reallyPoll();
                if (r != null) return r;
                if (timeout != 0) {
                    long end = System.nanoTime();
                    timeout -= (end - start) / 1000_000;
                    if (timeout <= 0) return null;
                    start = end;
                }
            }
        }
    }

    /**
     * Removes the next reference object in this queue, blocking until one
     * becomes available.
     *
     * <p> 移除此队列中的下一个引用对象，阻塞直到有一个可用。
     *
     * @return A reference object, blocking until one becomes available
     * @throws  InterruptedException  If the wait is interrupted
     */
    public Reference<? extends T> remove() throws InterruptedException {
        return remove(0);
    }

}
