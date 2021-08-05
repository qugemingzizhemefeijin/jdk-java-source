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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;

/**
 * An unbounded {@linkplain BlockingQueue blocking queue} of
 * {@code Delayed} elements, in which an element can only be taken
 * when its delay has expired.  The <em>head</em> of the queue is that
 * {@code Delayed} element whose delay expired furthest in the
 * past.  If no delay has expired there is no head and {@code poll}
 * will return {@code null}. Expiration occurs when an element's
 * {@code getDelay(TimeUnit.NANOSECONDS)} method returns a value less
 * than or equal to zero.  Even though unexpired elements cannot be
 * removed using {@code take} or {@code poll}, they are otherwise
 * treated as normal elements. For example, the {@code size} method
 * returns the count of both expired and unexpired elements.
 * This queue does not permit null elements.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.  The Iterator provided in method {@link
 * #iterator()} is <em>not</em> guaranteed to traverse the elements of
 * the DelayQueue in any particular order.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * <p>
 * Delayed接口并不是DelayQueue直接实现的，而是加入到DelayQueue中的元素实现的接口<br>
 * Comparable接口主要用于排序使用，getDelay方法用于判断这个元素是否过期，即是否满足指定的延迟时间，返回0或者-1表示已过期，返回一个正值表示剩余的有效期，入参unit就是为了将剩余的有效期转换成unit指定的单位，DelayQueue使用的是纳秒。
 *
 * <p>
 * DelayQueue主要适用于缓存系统，调度系统等对时间比较敏感的系统实现中
 *
 * <pre>
 * class CacheData<T> implements Delayed {
 *     private T data;
 *
 *     private long deadline;
 *
 *     public CacheData(T data, int cacheTime) {
 *         this.data = data;
 *         this.deadline=System.currentTimeMillis()+cacheTime;
 *     }
 *
 *     public T getData() {
 *         return data;
 *     }
 *
 *     public int compareTo(Delayed o) {
 *         long result = this.getDelay(TimeUnit.NANOSECONDS)
 *                 - o.getDelay(TimeUnit.NANOSECONDS);
 *         if (result < 0) {
 *             return -1;
 *         } else if (result > 0) {
 *             return 1;
 *         } else {
 *             return 0;
 *         }
 *     }
 *
 *     public long getDelay(TimeUnit unit) {
 *         #计算剩余有效期
 *         long diff = deadline-System.currentTimeMillis();
 *         if(diff>0) {
 *             #转换成指定的单位
 *             return unit.convert(diff, TimeUnit.MILLISECONDS);
 *         }
 *         return diff;
 *     }
 * }
 *
 * public class DelayQueueTest {
 *
 *     //@Test
 *     public void test() throws Exception {
 *         CacheData<String> a=new CacheData<>("a",1000);
 *         CacheData<String> a2=new CacheData<>("b",2000);
 *         CacheData<String> a3=new CacheData<>("c",3000);
 *         CacheData<String> a4=new CacheData<>("d",4000);
 *         CacheData<String> a5=new CacheData<>("e",5000);
 *         DelayQueue<CacheData> delayQueue=new DelayQueue<>();
 *         delayQueue.add(a);
 *         delayQueue.add(a2);
 *         delayQueue.add(a3);
 *         delayQueue.add(a4);
 *         delayQueue.add(a5);
 *         CountDownLatch countDownLatch=new CountDownLatch(delayQueue.size());
 *         Runnable runnable=new Runnable() {
 *             //@Override
 *             public void run() {
 *                 try {
 *                     long start=System.currentTimeMillis();
 *                     CacheData<String> cacheData=delayQueue.take();
 *                     System.out.println("get data->"+cacheData.getData()+",time->"+(System.currentTimeMillis()-start));
 *                     countDownLatch.countDown();
 *                 } catch (Exception e) {
 *                     e.printStackTrace();
 *                 }
 *             }
 *         };
 *         for(int i=0;i<5;i++){
 *             new Thread(runnable).start();
 *         }
 *         countDownLatch.await();
 *         System.out.println("main end");
 *     }
 * }
 *
 *
 * //线程阻塞等待的时间跟这条记录的有效期的时间基本一致
 * get data->a,time->996
 * get data->b,time->1995
 * get data->c,time->2995
 * get data->d,time->3994
 * get data->e,time->4993
 * </pre>
 *
 * @since 1.5
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 */
public class DelayQueue<E extends Delayed> extends AbstractQueue<E>
    implements BlockingQueue<E> {

    /**
     * 同步的锁
     */
    private final transient ReentrantLock lock = new ReentrantLock();

    /**
     * 实际保存队列元素的优先级队列
     */
    private final PriorityQueue<E> q = new PriorityQueue<E>();

    /**
     * Thread designated to wait for the element at the head of
     * the queue.  This variant of the Leader-Follower pattern
     * (http://www.cs.wustl.edu/~schmidt/POSA/POSA2/) serves to
     * minimize unnecessary timed waiting.  When a thread becomes
     * the leader, it waits only for the next delay to elapse, but
     * other threads await indefinitely.  The leader thread must
     * signal some other thread before returning from take() or
     * poll(...), unless some other thread becomes leader in the
     * interim.  Whenever the head of the queue is replaced with
     * an element with an earlier expiration time, the leader
     * field is invalidated by being reset to null, and some
     * waiting thread, but not necessarily the current leader, is
     * signalled.  So waiting threads must be prepared to acquire
     * and lose leadership while waiting.
     *
     * <p>
     * 记录第一个等待获取已过期元素的线程，leader线程阻塞时可以根据链表头元素的剩余有效期设置等待超时时间，避免无期限等待，
     * 其他非leader线程在没有设置等待超时的情形下因为无法预知链表元素的有效期只能无期限等待，等待leader线程被唤醒获取链表头元素后，
     * 就唤醒下一个等待的线程，然后按照相同的方式同样设置等待超时时间。
     */
    private Thread leader = null;

    /**
     * Condition signalled when a newer element becomes available
     * at the head of the queue or a new thread may need to
     * become leader.
     */
    private final Condition available = lock.newCondition();

    /**
     * Creates a new {@code DelayQueue} that is initially empty.
     */
    public DelayQueue() {}

    /**
     * Creates a {@code DelayQueue} initially containing the elements of the
     * given collection of {@link Delayed} instances.
     *
     * @param c the collection of elements to initially contain
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    public DelayQueue(Collection<? extends E> c) {
        this.addAll(c);
    }

    /**
     * Inserts the specified element into this delay queue.
     *
     * @param e the element to add
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws NullPointerException if the specified element is null
     */
    // 并不是严格按照BlockingQueue接口的语义来实现方法的，都是利用不带时间参数的offer方法来实现的
    public boolean add(E e) {
        return offer(e);
    }

    /**
     * Inserts the specified element into this delay queue.
     *
     * @param e the element to add
     * @return {@code true}
     * @throws NullPointerException if the specified element is null
     */
    // 并不是严格按照BlockingQueue接口的语义来实现方法的，都是利用不带时间参数的offer方法来实现的
    public boolean offer(E e) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 加入到优先级队列中
            q.offer(e);
            if (q.peek() == e) {
                // 等于e，说明该队列原来是空的
                leader = null;
                // 唤醒等待的线程
                available.signal();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this delay queue. As the queue is
     * unbounded this method will never block.
     *
     * @param e the element to add
     * @throws NullPointerException {@inheritDoc}
     */
    // 并不是严格按照BlockingQueue接口的语义来实现方法的，都是利用不带时间参数的offer方法来实现的
    public void put(E e) {
        offer(e);
    }

    /**
     * Inserts the specified element into this delay queue. As the queue is
     * unbounded this method will never block.
     *
     * @param e the element to add
     * @param timeout This parameter is ignored as the method never blocks
     * @param unit This parameter is ignored as the method never blocks
     * @return {@code true}
     * @throws NullPointerException {@inheritDoc}
     */
    // 并不是严格按照BlockingQueue接口的语义来实现方法的，都是利用不带时间参数的offer方法来实现的
    public boolean offer(E e, long timeout, TimeUnit unit) {
        return offer(e);
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null}
     * if this queue has no elements with an expired delay.
     *
     * @return the head of this queue, or {@code null} if this
     *         queue has no elements with an expired delay
     */
    // 符合BlockingQueue接口的语义，跟ArrayBlockingQueue一致，区别在于返回元素前需要检查元素是否过期了，
    // 如果未过期则返回null，否则会移除并返回队列头元素。
    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 获取队列头元素
            E first = q.peek();
            // 如果为空或者未过期则返回null
            if (first == null || first.getDelay(NANOSECONDS) > 0)
                return null;
            else
                // 不为空且已过期，则返回并移除
                return q.poll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element with an expired delay is available on this queue.
     *
     * @return the head of this queue
     * @throws InterruptedException {@inheritDoc}
     */
    // 符合BlockingQueue接口的语义，跟ArrayBlockingQueue一致，区别在于返回元素前需要检查元素是否过期了，
    // 如果未过期则返回null，否则会移除并返回队列头元素。
    // 无法指定等待的时间
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            for (;;) {
                E first = q.peek();
                if (first == null)
                    available.await();
                else {
                    long delay = first.getDelay(NANOSECONDS);
                    if (delay <= 0)
                        return q.poll();
                    first = null; // don't retain ref while waiting
                    if (leader != null)
                        // 无期限等待，直到被leader线程唤醒
                        available.await();
                    else {
                        Thread thisThread = Thread.currentThread();
                        leader = thisThread;
                        try {
                            // 释放锁后，其他进入此方法的线程后，leader属性就不为空了
                            available.awaitNanos(delay);
                        } finally {
                            if (leader == thisThread)
                                leader = null;
                        }
                    }
                }
            }
        } finally {
            // leader线程被唤醒后会将leader置为null，如果还有节点，则唤醒下一个等待的线程
            if (leader == null && q.peek() != null)
                available.signal();
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element with an expired delay is available on this queue,
     * or the specified wait time expires.
     *
     * @return the head of this queue, or {@code null} if the
     *         specified waiting time elapses before an element with
     *         an expired delay becomes available
     * @throws InterruptedException {@inheritDoc}
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            for (;;) {
                E first = q.peek();
                if (first == null) {
                    // 如果队列为空则循环等待
                    if (nanos <= 0)
                        // 等待超时返回null
                        return null;
                    else
                        nanos = available.awaitNanos(nanos);
                } else {
                    // 队列不为空，delay表示剩余的有效期
                    long delay = first.getDelay(NANOSECONDS);
                    if (delay <= 0) // 已过期
                        return q.poll();
                    if (nanos <= 0) // 未过期，等待超时
                        return null;
                    first = null; // don't retain ref while waiting
                    if (nanos < delay || leader != null)
                        // 如果等待的时间小于有效期或者leader不为空，则等待指定时间
                        nanos = available.awaitNanos(nanos);
                    else {
                        // 如果等待时间大于有效期，则等待有效期
                        Thread thisThread = Thread.currentThread();
                        // 记录等待的线程
                        leader = thisThread;
                        try {
                            // 等待指定时间，返回已经等待的时间
                            // 调用awaitNanos后会释放锁，这时其他的调用poll方法并获取锁的线程就会发现leader非空了，然后按照最长的时间来等待
                            long timeLeft = available.awaitNanos(delay);
                            nanos -= delay - timeLeft;
                        } finally {
                            // 被唤醒后将leader重置为null
                            if (leader == thisThread)
                                leader = null;
                        }
                    }
                }
            }
        } finally {
            // leader线程被唤醒后会将leader置为null，如果还有节点，则唤醒下一个等待的线程
            if (leader == null && q.peek() != null)
                available.signal(); // 唤醒等待的线程
            lock.unlock();
        }
    }

    /**
     * Retrieves, but does not remove, the head of this queue, or
     * returns {@code null} if this queue is empty.  Unlike
     * {@code poll}, if no expired elements are available in the queue,
     * this method returns the element that will expire next,
     * if one exists.
     *
     * @return the head of this queue, or {@code null} if this
     *         queue is empty
     */
    // 符合BlockingQueue接口的语义，跟ArrayBlockingQueue一致，区别在于返回元素前需要检查元素是否过期了，
    // 如果未过期则返回null，否则会移除并返回队列头元素。
    public E peek() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.peek();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns first element only if it is expired.
     * Used only by drainTo.  Call only when holding lock.
     */
    private E peekExpired() {
        // assert lock.isHeldByCurrentThread();
        // 判断链表头元素是否已过期，如果未过期返回null
        E first = q.peek();
        return (first == null || first.getDelay(NANOSECONDS) > 0) ?
            null : first;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    // 返回已添加到集合中的元素个数
    public int drainTo(Collection<? super E> c) {
        // 校验参数合法性
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = 0;
            // 将已过期的元素从队列中移除加入到集合c中
            for (E e; (e = peekExpired()) != null;) {
                c.add(e);       // In this order, in case add() throws.
                q.poll();
                ++n;
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = 0;
            for (E e; n < maxElements && (e = peekExpired()) != null;) {
                c.add(e);       // In this order, in case add() throws.
                q.poll();
                ++n;
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically removes all of the elements from this delay queue.
     * The queue will be empty after this call returns.
     * Elements with an unexpired delay are not waited for; they are
     * simply discarded from the queue.
     */
    // clear方法都是直接使用PriorityQueue的方法
    public void clear() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 清空队列
            q.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Always returns {@code Integer.MAX_VALUE} because
     * a {@code DelayQueue} is not capacity constrained.
     *
     * @return {@code Integer.MAX_VALUE}
     */
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    /**
     * Returns an array containing all of the elements in this queue.
     * The returned array elements are in no particular order.
     *
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this queue.  (In other words, this method must allocate
     * a new array).  The caller is thus free to modify the returned array.
     *
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array containing all of the elements in this queue
     */
    public Object[] toArray() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.toArray();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an array containing all of the elements in this queue; the
     * runtime type of the returned array is that of the specified array.
     * The returned array elements are in no particular order.
     * If the queue fits in the specified array, it is returned therein.
     * Otherwise, a new array is allocated with the runtime type of the
     * specified array and the size of this queue.
     *
     * <p>If this queue fits in the specified array with room to spare
     * (i.e., the array has more elements than this queue), the element in
     * the array immediately following the end of the queue is set to
     * {@code null}.
     *
     * <p>Like the {@link #toArray()} method, this method acts as bridge between
     * array-based and collection-based APIs.  Further, this method allows
     * precise control over the runtime type of the output array, and may,
     * under certain circumstances, be used to save allocation costs.
     *
     * <p>The following code can be used to dump a delay queue into a newly
     * allocated array of {@code Delayed}:
     *
     * <pre> {@code Delayed[] a = q.toArray(new Delayed[0]);}</pre>
     *
     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param a the array into which the elements of the queue are to
     *          be stored, if it is big enough; otherwise, a new array of the
     *          same runtime type is allocated for this purpose
     * @return an array containing all of the elements in this queue
     * @throws ArrayStoreException if the runtime type of the specified array
     *         is not a supertype of the runtime type of every element in
     *         this queue
     * @throws NullPointerException if the specified array is null
     */
    public <T> T[] toArray(T[] a) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.toArray(a);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a single instance of the specified element from this
     * queue, if it is present, whether or not it has expired.
     */
    // remove方法都是直接使用PriorityQueue的方法
    public boolean remove(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 移除某个元素
            return q.remove(o);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Identity-based version for use in Itr.remove
     */
    void removeEQ(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 遍历PriorityQueue，将其从队列中移除
            for (Iterator<E> it = q.iterator(); it.hasNext(); ) {
                if (o == it.next()) {
                    // 找到目标元素
                    it.remove();
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an iterator over all the elements (both expired and
     * unexpired) in this queue. The iterator does not return the
     * elements in any particular order.
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue
     */
    // 元素的遍历是基于当前队列的快照，即遍历过程中无法感知到队列的修改；元素遍历过程中被移除了，底层是通过PriorityQueue的迭代器来移除的。
    public Iterator<E> iterator() {
        // 遍历的是当前队列的一个快照，即遍历的过程中无法感知队列的修改
        return new Itr(toArray());
    }

    /**
     * Snapshot iterator that works off copy of underlying q array.
     */
    private class Itr implements Iterator<E> {
        /**
         * 保存队列元素的数组
         */
        final Object[] array; // Array of all elements
        /**
         * 下一个返回的元素的索引
         */
        int cursor;           // index of next element to return
        /**
         * 上一次返回的元素的索引
         */
        int lastRet;          // index of last element, or -1 if no such

        Itr(Object[] array) {
            // cursor的初始值就是0
            lastRet = -1;
            this.array = array;
        }

        public boolean hasNext() {
            return cursor < array.length;
        }

        @SuppressWarnings("unchecked")
        public E next() {
            if (cursor >= array.length)
                throw new NoSuchElementException();
            lastRet = cursor;
            // 返回cursor当前值对应的数组元素，然后再将其加1
            return (E)array[cursor++];
        }

        public void remove() {
            if (lastRet < 0)
                throw new IllegalStateException();
            // 移除lastRet对应的元素
            removeEQ(array[lastRet]);
            lastRet = -1;
        }
    }

}
