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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.lang.ref.WeakReference;
import java.util.Spliterators;
import java.util.Spliterator;

/**
 * A bounded {@linkplain BlockingQueue blocking queue} backed by an
 * array.  This queue orders elements FIFO (first-in-first-out).  The
 * <em>head</em> of the queue is that element that has been on the
 * queue the longest time.  The <em>tail</em> of the queue is that
 * element that has been on the queue the shortest time. New elements
 * are inserted at the tail of the queue, and the queue retrieval
 * operations obtain elements at the head of the queue.
 *
 * <p>This is a classic &quot;bounded buffer&quot;, in which a
 * fixed-sized array holds elements inserted by producers and
 * extracted by consumers.  Once created, the capacity cannot be
 * changed.  Attempts to {@code put} an element into a full queue
 * will result in the operation blocking; attempts to {@code take} an
 * element from an empty queue will similarly block.
 *
 * <p>This class supports an optional fairness policy for ordering
 * waiting producer and consumer threads.  By default, this ordering
 * is not guaranteed. However, a queue constructed with fairness set
 * to {@code true} grants threads access in FIFO order. Fairness
 * generally decreases throughput but reduces variability and avoids
 * starvation.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 * <br><br>
 * <p>
 * ArrayBlockingQueue表示一个基于数组实现的固定容量的，先进先出的，线程安全的队列（栈），本篇博客就详细讲解该类的实现细节。
 *
 * @since 1.5
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 */
public class ArrayBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, java.io.Serializable {

    /**
     * Serialization ID. This class relies on default serialization
     * even for the items array, which is default-serialized, even if
     * it is empty. Otherwise it could not be declared final, which is
     * necessary here.
     */
    private static final long serialVersionUID = -817911632652898426L;

    /** 队列元素数组 这里跟下面的takeIndex以及putIndex合作使用，可以理解成是一个循环定长队列 */
    final Object[] items;

    /** 可以移除的数组位置索引 */
    int takeIndex;

    /** 可以添加的数组位置索引 */
    int putIndex;

    /** 队列中元素的数量 */
    int count;

    /*
     * Concurrency control uses the classic two-condition algorithm
     * found in any textbook.
     */

    /** 主锁 */
    final ReentrantLock lock;

    /** 等待获取的条件 TAKE */
    private final Condition notEmpty;

    /** 等待添加的条件 PUTS */
    private final Condition notFull;

    /**
     * Shared state for currently active iterators, or null if there
     * are known not to be any.  Allows queue operations to update
     * iterator state. 遍历器实现
     */
    transient Itrs itrs = null;

    // Internal helper methods

    /**
     * Circularly decrement i.
     */
    final int dec(int i) {
        return ((i == 0) ? items.length : i) - 1;
    }

    /**
     * Returns item at index i.
     */
    @SuppressWarnings("unchecked")
    final E itemAt(int i) {
        return (E) items[i];
    }

    /**
     * Throws NullPointerException if argument is null.
     *
     * @param v the element
     */
    private static void checkNotNull(Object v) {
        if (v == null)
            throw new NullPointerException();
    }

    /**
     * 插入数据到当前数组中的putIndex位置，并且将putIndex+1，同时通知等待提取数据的线程。必须要在索引中使用。
     */
    private void enqueue(E x) {
        // assert lock.getHoldCount() == 1;
        // assert items[putIndex] == null;
        final Object[] items = this.items;
        // 保存到putIndex索引处
        items[putIndex] = x;
        // 数组满了，将其重置为0
        if (++putIndex == items.length)
            putIndex = 0;
        // 数组元素个数增加
        count++;
        // 唤醒因为队列是空的而等待的线程
        notEmpty.signal();
    }

    /**
     * 从takeIndex获取数据，并且将takeIndex+1，同时通知等待插入数据的线程。必须要在索引中使用。
     */
    private E dequeue() {
        // assert lock.getHoldCount() == 1;
        // assert items[takeIndex] != null;
        final Object[] items = this.items;
        @SuppressWarnings("unchecked")
        // 取出takeIndex的元素，将其置为null
        E x = (E) items[takeIndex];
        items[takeIndex] = null;
        if (++takeIndex == items.length)
            takeIndex = 0; // 取完了，从头开始取
        count--;
        if (itrs != null)
            // 通知itrs栈顶的元素被移除了
            itrs.elementDequeued();
        // 唤醒因为栈满了等待的线程
        notFull.signal();
        return x;
    }

    /**
     * 删除数组索引为removeIndex的元素。
     * Utility for remove(Object) and iterator.remove.
     * 必须要在锁中使用.
     */
    void removeAt(final int removeIndex) {
        // assert lock.getHoldCount() == 1;
        // assert items[removeIndex] != null;
        // assert removeIndex >= 0 && removeIndex < items.length;
        final Object[] items = this.items;
        if (removeIndex == takeIndex) {//如果移动的是最后一个节点，则直接takeIndex+1
            // removing front item; just advance
            items[takeIndex] = null;
            if (++takeIndex == items.length)
                takeIndex = 0;
            count--;//元素数量-1
            if (itrs != null)
                itrs.elementDequeued();//如果
        } else {
            // an "interior" remove

            // slide over all others up through putIndex.
            // 如果移除的是栈中间的某个元素，需要将该元素后面的元素往前挪动
            final int putIndex = this.putIndex;
            for (int i = removeIndex;;) {//以putIndex为结束点，一直循环将removeIndex开始到putIndex之间的元素往前移动，并将putIndex指向前一位
                int next = i + 1;
                // 到数组末尾了，从头开始
                if (next == items.length)
                    next = 0;
                if (next != putIndex) {
                    // 将后面一个元素复制到前面来
                    items[i] = items[next];
                    i = next;
                } else {
                    // 如果下一个元素的索引等于putIndex，说明i就是栈中最后一个元素了，直接将该元素置为null
                    items[i] = null;
                    // 重置putIndex为i
                    this.putIndex = i;
                    break;
                }
            }
            count--;
            if (itrs != null)
                // 通知itrs节点移除了
                itrs.removedAt(removeIndex);
        }
        notFull.signal();//通知等待插入数据的线程
    }

    /**
     * Creates an {@code ArrayBlockingQueue} with the given (fixed)
     * capacity and default access policy.
     *
     * @param capacity 队列的容量上限
     * @throws IllegalArgumentException if {@code capacity < 1}
     */
    public ArrayBlockingQueue(int capacity) {
        this(capacity, false);
    }

    /**
     * Creates an {@code ArrayBlockingQueue} with the given (fixed)
     * capacity and the specified access policy.
     *
     * @param capacity 队列的容量上限
     * @param fair 是否使用公平锁
     * @throws IllegalArgumentException if {@code capacity < 1}
     */
    public ArrayBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0)
            throw new IllegalArgumentException();
        this.items = new Object[capacity];
        lock = new ReentrantLock(fair);
        notEmpty = lock.newCondition();
        notFull =  lock.newCondition();
    }

    /**
     * Creates an {@code ArrayBlockingQueue} with the given (fixed)
     * capacity, the specified access policy and initially containing the
     * elements of the given collection,
     * added in traversal order of the collection's iterator.<br><br>
     *
     * 这里得注意传入的集合元素数量不能超过capacity，否则会报ArrayIndexOutOfBoundsException错误。
     *
     * @param capacity the capacity of this queue
     * @param fair if {@code true} then queue accesses for threads blocked
     *        on insertion or removal, are processed in FIFO order;
     *        if {@code false} the access order is unspecified.
     * @param c the collection of elements to initially contain
     * @throws IllegalArgumentException if {@code capacity} is less than
     *         {@code c.size()}, or less than 1.
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    public ArrayBlockingQueue(int capacity, boolean fair,
                              Collection<? extends E> c) {
        this(capacity, fair);

        final ReentrantLock lock = this.lock;
        // 加锁的目的是为了其他CPU能够立即看到修改
        // 加锁和解锁底层都是CAS，会强制修改写回主存，对其他CPU可见
        lock.lock(); // Lock only for visibility, not mutual exclusion
        try {
            int i = 0;
            try {
                for (E e : c) {
                    checkNotNull(e);
                    items[i++] = e;
                }
            } catch (ArrayIndexOutOfBoundsException ex) {
                throw new IllegalArgumentException();
            }
            count = i;
            putIndex = (i == capacity) ? 0 : i;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue if it is
     * possible to do so immediately without exceeding the queue's capacity,
     * returning {@code true} upon success and throwing an
     * {@code IllegalStateException} if this queue is full.
     *
     * <p>add方法依赖于offer方法，如果队列满了则抛出异常，否则添加成功返回true；
     *
     * @param e the element to add
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws IllegalStateException if this queue is full
     * @throws NullPointerException if the specified element is null
     */
    public boolean add(E e) {
        // 调用子类offer方法实现，如果队列满了则抛出异常
        return super.add(e);
    }

    /**
     * Inserts the specified element at the tail of this queue if it is
     * possible to do so immediately without exceeding the queue's capacity,
     * returning {@code true} upon success and {@code false} if this queue
     * is full.  This method is generally preferable to method {@link #add},
     * which can fail to insert an element only by throwing an exception.<br><br>
     *
     * <p>如果队列中元素未满，则立即插入成功，否则直接返回false（不响应线程中断）
     *
     * <p>offer方法有两个重载版本，只有一个参数的版本，如果队列满了就返回false，否则加入到队列中，返回true，add方法就是调用此版本的offer方法；
     * 另一个带时间参数的版本，如果队列满了则等待，可指定等待的时间，如果这期间中断了则抛出异常，如果等待超时了则返回false，否则加入到队列中返回true；
     *
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        // 非空检测
        checkNotNull(e);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 数组满了
            if (count == items.length)
                return false;
            else {
                enqueue(e);
                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting
     * for space to become available if the queue is full.<br><br>
     *
     * <p>响应线程中断的Put，如果队列满了，则线程等待
     * <p>put方法跟带时间参数的offer方法逻辑一样，不过没有等待的时间限制，会一直等待直到队列有空余位置了，再插入到队列中，返回true。
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public void put(E e) throws InterruptedException {
        checkNotNull(e);
        final ReentrantLock lock = this.lock;
        // 等待的过程被中断了则抛出异常
        lock.lockInterruptibly();
        try {
            // 如果队列满了，则当前线程等待，让出锁
            while (count == items.length)
                notFull.await();
            // 加入到数组中
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting
     * up to the specified wait time for space to become available if
     * the queue is full.<br><br>
     *
     * 响应线程中断的添加队列元素。如果队列满了则线程等待指定时间
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {

        checkNotNull(e);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            // 如果队列满了，则判断当前等待时间是否到达，如果到达了，则返回false，否则继续等待(防止线程意外唤醒)
            while (count == items.length) {
                if (nanos <= 0)
                    return false;
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 尝试去获取队列头元素，如果没有获取到则返回null
     * @return E
     */
    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return (count == 0) ? null : dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 响应线程中断的从队列中获取元素，如果队列为空，则等待。
     * @return E
     * @throws InterruptedException
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            // 如果空的，则等待
            while (count == 0)
                notEmpty.await();
            // 取出一个元素
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 响应线程中断的从队列中获取元素，如果队列为空，则等待指定时间。
     * @param timeout how long to wait before giving up, in units of
     *        {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the
     *        {@code timeout} parameter
     * @return E
     * @throws InterruptedException
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (nanos <= 0)
                    return null; // 等待超时，返回null
                // 数组为空，等待
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取队列头元素并不从队列中移除元素，如果返回为空则代表队列是空的
     * @return E
     */
    public E peek() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 返回栈顶元素，takeIndex值不变
            return itemAt(takeIndex); // null when queue is empty
        } finally {
            lock.unlock();
        }
    }

    // this doc comment is overridden to remove the reference to collections
    // greater in size than Integer.MAX_VALUE
    /**
     * 获取队列的元素数量
     *
     * @return the number of elements in this queue
     */
    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    // this doc comment is a modified copy of the inherited doc comment,
    // without the reference to unlimited queues.
    /**
     * Returns the number of additional elements that this queue can ideally
     * (in the absence of memory or resource constraints) accept without
     * blocking. This is always equal to the initial capacity of this queue
     * less the current {@code size} of this queue.<br><br>
     *
     * 返回队列中剩余可接受元素的数量
     *
     * <p>Note that you <em>cannot</em> always tell if an attempt to insert
     * an element will succeed by inspecting {@code remainingCapacity}
     * because it may be the case that another thread is about to
     * insert or remove an element.
     */
    public int remainingCapacity() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return items.length - count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.
     * Returns {@code true} if this queue contained the specified element
     * (or equivalently, if this queue changed as a result of the call).
     *
     * <p>Removal of interior elements in circular array based queues
     * is an intrinsically slow and disruptive operation, so should
     * be undertaken only in exceptional circumstances, ideally
     * only when the queue is known not to be accessible by other
     * threads.
     *
     * <br><br>
     * <p>
     * 用于移除某个元素，如果栈为空或者没有找到该元素则返回false，否则从栈中移除该元素；
     * 移除时，如果该元素位于栈顶则直接移除，如果位于栈中间，则需要将该元素后面的其他元素往前面挪动，
     * 移除后需要唤醒因为栈满了而阻塞的线程。
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     */
    public boolean remove(Object o) {
        if (o == null) return false;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            //只有有元素的时候才需要比较判断
            if (count > 0) {
                final int putIndex = this.putIndex;
                int i = takeIndex;
                do {
                    if (o.equals(items[i])) {
                        // 找到目标元素，将其移除
                        removeAt(i);
                        return true;
                    }
                    // 走到数组末尾了又从头开始，put时也按照这个规则来
                    if (++i == items.length)
                        i = 0;
                } while (i != putIndex);//循环判断，如果存在则移除元素(此方法只能一次移除一个相同的元素)
            }
            // 如果数组为空，返回false
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue contains the specified element.
     * More formally, returns {@code true} if and only if this queue contains
     * at least one element {@code e} such that {@code o.equals(e)}.
     *
     * @param o object to be checked for containment in this queue
     * @return {@code true} if this queue contains the specified element
     */
    public boolean contains(Object o) {
        if (o == null) return false;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count > 0) {
                final int putIndex = this.putIndex;
                int i = takeIndex;
                do {
                    if (o.equals(items[i]))
                        return true;
                    if (++i == items.length)
                        i = 0;
                } while (i != putIndex);//与上面的remove(Object)差不多
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an array containing all of the elements in this queue, in
     * proper sequence.
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
        Object[] a;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final int count = this.count;
            a = new Object[count];
            int n = items.length - takeIndex;
            if (count <= n)//这一段比较绕了，需要画图才能理解，反正就是要么直接复制一次，要么需要分段复制
                System.arraycopy(items, takeIndex, a, 0, count);
            else {
                System.arraycopy(items, takeIndex, a, 0, n);
                System.arraycopy(items, 0, a, n, count - n);
            }
        } finally {
            lock.unlock();
        }
        return a;
    }

    /**
     * Returns an array containing all of the elements in this queue, in
     * proper sequence; the runtime type of the returned array is that of
     * the specified array.  If the queue fits in the specified array, it
     * is returned therein.  Otherwise, a new array is allocated with the
     * runtime type of the specified array and the size of this queue.
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
     * <p>Suppose {@code x} is a queue known to contain only strings.
     * The following code can be used to dump the queue into a newly
     * allocated array of {@code String}:
     *
     *  <pre> {@code String[] y = x.toArray(new String[0]);}</pre>
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
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final int count = this.count;
            final int len = a.length;
            if (len < count)//传入的数组小的话，需要重新new一个数组
                a = (T[])java.lang.reflect.Array.newInstance(
                    a.getClass().getComponentType(), count);
            int n = items.length - takeIndex;//跟上面一样
            if (count <= n)
                System.arraycopy(items, takeIndex, a, 0, count);
            else {
                System.arraycopy(items, takeIndex, a, 0, n);
                System.arraycopy(items, 0, a, n, count - n);
            }
            if (len > count)//如果传入的数组长度大于count，则需要将数组[count]设置为空，主要为了防止一些误判
                a[count] = null;
        } finally {
            lock.unlock();
        }
        return a;
    }

    public String toString() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int k = count;
            if (k == 0)
                return "[]";

            final Object[] items = this.items;
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = takeIndex; ; ) {
                Object e = items[i];
                sb.append(e == this ? "(this Collection)" : e);
                if (--k == 0)
                    return sb.append(']').toString();
                sb.append(',').append(' ');
                if (++i == items.length)
                    i = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically removes all of the elements from this queue.
     * The queue will be empty after this call returns.
     *
     * <br><br>
     * <p>
     * 用于整个栈，同时将takeIndex置为putIndex，保证栈中的元素先进先出；
     * 最后会唤醒最多count个线程，因为正常一个线程插入一个元素，如果唤醒超过count个线程，可能导致部分线程因为栈满了又再次被阻塞。
     */
    public void clear() {
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int k = count;
            if (k > 0) {
                final int putIndex = this.putIndex;
                int i = takeIndex;
                // 从takeIndex开始遍历直到i等于putIndex，将数组元素置为null
                do {
                    items[i] = null;
                    if (++i == items.length)
                        i = 0;
                } while (i != putIndex);//循环将所有的元素重置为空
                // 注意此处没有将这两个index置为0，只是让他们相等，因为只要相等就可以实现栈先进先出了
                takeIndex = putIndex;//takeIndex=putIndex
                count = 0;
                if (itrs != null)
                    itrs.queueIsEmpty();
                // 如果有因为栈满了而等待的线程，则将其唤醒
                // 注意这里没有使用signalAll而是通过for循环来signal多次，单纯从唤醒线程来看是可以使用signalAll的，效果跟这里的for循环是一样的
                // 如果有等待的线程，说明count就是当前线程的最大容量了，这里清空了，最多只能put count次，一个线程只能put 1次，只唤醒最多count个线程就避免了
                // 线程被唤醒后再次因为栈满了而阻塞
                for (; k > 0 && lock.hasWaiters(notFull); k--)//循环唤醒生产者线程(要么等待的全部唤醒，要么只唤醒count个)
                    notFull.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     *
     * 将所有的元素都移除并拷贝到指定的集合中。移除后需要重置takeIndex和count，并唤醒最多移除个数的因为栈满而阻塞的线程。
     *
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    /**
     *
     * 将指定个数的元素移除并拷贝到指定的集合中。移除后需要重置takeIndex和count，并唤醒最多移除个数的因为栈满而阻塞的线程。
     *
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        checkNotNull(c);
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = Math.min(maxElements, count);
            int take = takeIndex;
            int i = 0;
            try {
                // 从takeIndex开始遍历，取出元素然后添加到c中，直到满足个数要求为止
                while (i < n) {
                    @SuppressWarnings("unchecked")
                    E x = (E) items[take];
                    c.add(x);
                    items[take] = null;
                    if (++take == items.length)
                        take = 0;
                    i++;
                }
                return n;
            } finally {
                // Restore invariants even if c.add() threw
                if (i > 0) {
                    // 取完了，修改count减去i
                    count -= i;
                    takeIndex = take;
                    if (itrs != null) {
                        if (count == 0)
                            // 通知itrs 栈空了
                            itrs.queueIsEmpty();
                        else if (i > take)
                            // 说明take中间变成0了，通知itrs
                            itrs.takeIndexWrapped();
                    }
                    // 唤醒在因为栈满而等待的线程，最多唤醒i个，同上避免线程被唤醒了因为栈又满了而阻塞
                    for (; i > 0 && lock.hasWaiters(notFull); i--)//循环唤醒
                        notFull.signal();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an iterator over the elements in this queue in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue in proper sequence
     */
    public Iterator<E> iterator() {
        return new Itr();
    }

    /**
     * Shared data between iterators and their queue, allowing queue
     * modifications to update iterators when elements are removed.
     *
     * This adds a lot of complexity for the sake of correctly
     * handling some uncommon operations, but the combination of
     * circular-arrays and supporting interior removes (i.e., those
     * not at head) would cause iterators to sometimes lose their
     * places and/or (re)report elements they shouldn't.  To avoid
     * this, when a queue has one or more iterators, it keeps iterator
     * state consistent by:
     *
     * (1) keeping track of the number of "cycles", that is, the
     *     number of times takeIndex has wrapped around to 0.
     * (2) notifying all iterators via the callback removedAt whenever
     *     an interior element is removed (and thus other elements may
     *     be shifted).
     *
     * These suffice to eliminate iterator inconsistencies, but
     * unfortunately add the secondary responsibility of maintaining
     * the list of iterators.  We track all active iterators in a
     * simple linked list (accessed only when the queue's lock is
     * held) of weak references to Itr.  The list is cleaned up using
     * 3 different mechanisms:
     *
     * (1) Whenever a new iterator is created, do some O(1) checking for
     *     stale list elements.
     *
     * (2) Whenever takeIndex wraps around to 0, check for iterators
     *     that have been unused for more than one wrap-around cycle.
     *
     * (3) Whenever the queue becomes empty, all iterators are notified
     *     and this entire data structure is discarded.
     *
     * So in addition to the removedAt callback that is necessary for
     * correctness, iterators have the shutdown and takeIndexWrapped
     * callbacks that help remove stale iterators from the list.
     *
     * Whenever a list element is examined, it is expunged if either
     * the GC has determined that the iterator is discarded, or if the
     * iterator reports that it is "detached" (does not need any
     * further state updates).  Overhead is maximal when takeIndex
     * never advances, iterators are discarded before they are
     * exhausted, and all removals are interior removes, in which case
     * all stale iterators are discovered by the GC.  But even in this
     * case we don't increase the amortized complexity.
     *
     * Care must be taken to keep list sweeping methods from
     * reentrantly invoking another such method, causing subtle
     * corruption bugs.<br><br>
     *
     * 维护了所有创建的迭代器，迭代器创建的时候会加入到这个itrs中。
     */
    class Itrs {

        /**
         * Node in a linked list of weak iterator references.
         */
        private class Node extends WeakReference<Itr> {
            Node next;

            Node(Itr iterator, Node next) {
                super(iterator);
                this.next = next;
            }
        }

        /** 当takeIndex变成0以后加1 Incremented whenever takeIndex wraps around to 0 */
        int cycles = 0;

        /** Iter实例链表的链表头 Linked list of weak iterator references */
        private Node head;

        /** Used to expunge stale iterators */
        private Node sweeper = null;

        private static final int SHORT_SWEEP_PROBES = 4;
        private static final int LONG_SWEEP_PROBES = 16;

        Itrs(Itr initial) {
            register(initial);
        }

        /**
         * Sweeps itrs, looking for and expunging stale iterators.
         * If at least one was found, tries harder to find more.
         * Called only from iterating thread.
         *
         * <br><br>
         * <p>
         * 用于清除该链表中无效的Itr实例，查找这类实例是通过for循环实现的，初始for循环的次数是通过参数tryHandler控制的，
         * 如果为true，则循环16次，如果为false，则循环4次，在循环的过程中找到了一个无效的Itr实例，则需要再遍历16次，直到所有节点都遍历完成。
         *
         * 注意这里的无效指的这个Itr实例已经同Itrs datached了，当ArrayBlockingQueue执行Itrs的回调方法时就不会处理这种Itr实例了，
         * 即Itr实例无法感知到ArrayBlockingQueue的改变了，这时基于Itr实例遍历的结果可能就不准确了。
         *
         * @param tryHarder whether to start in try-harder mode, because
         * there is known to be at least one iterator to collect
         */
        // 用于清理那些陈旧的Node
        void doSomeSweeping(boolean tryHarder) {
            // assert lock.getHoldCount() == 1;
            // assert head != null;
            // probes表示循环查找的次数
            int probes = tryHarder ? LONG_SWEEP_PROBES : SHORT_SWEEP_PROBES;
            Node o, p;
            final Node sweeper = this.sweeper;
            boolean passedGo;   // to limit search to one full sweep

            // o表示上一个有效节点，p表示当前遍历的节点，如果o为空，则p是head，否则是o的下一个节点
            // 进入此方法，sweeper可能为null，head不会为null
            if (sweeper == null) {
                o = null;
                p = head;
                passedGo = true;
            } else {
                o = sweeper;
                p = o.next;
                passedGo = false;
            }

            for (; probes > 0; probes--) {
                if (p == null) {
                    if (passedGo)
                        break; // sweeper为null时，passedGo为true，终止循环，将sweeper赋值
                    // passedGo为false，sweeper不为null，因为p为null，还是将其置为true，即只能循环一次
                    o = null;
                    p = head;
                    passedGo = true;
                }
                // 获取关联的Itr
                final Itr it = p.get();
                final Node next = p.next;
                if (it == null || it.isDetached()) {
                    // 如果it为null或者已经解除关联了
                    // 只要找到了一个无效节点，则需要再遍历LONG_SWEEP_PROBES次，直到所有节点遍历完为止
                    // found a discarded/exhausted iterator
                    probes = LONG_SWEEP_PROBES; // "try harder"
                    // unlink p
                    // 将关联的Itr置为null
                    p.clear();
                    p.next = null;
                    if (o == null) { // sweeper为null或者sweeper的next为null时
                        // 没有找到有效节点，重置head，将next之前的节点都移除
                        head = next;
                        if (next == null) {
                            // We've run out of iterators to track; retire
                            // next为null，没有待处理的节点了，所以Itr都退出了，将itrs置为null
                            itrs = null;
                            return;
                        }
                    }
                    else
                        // 将next作为o的下一个节点，即将p移除了
                        o.next = next;
                } else {
                    // p对应的Itr实例是有效的，将o置为p
                    o = p;
                }
                // 处理下一个节点
                p = next;
            }
            // 重置sweeper，p等于null说明节点都遍历完了，sweeper为null
            // 如果p不等于null，说明还有未遍历的节点，将sweeper置为0，下一次遍历时可以重新从该节点开始遍历
            this.sweeper = (p == null) ? null : o;
        }

        /**
         * Adds a new iterator to the linked list of tracked iterators.
         *
         * 创建一个新的Itr实例时，会调用此方法将该实例添加到Node链表中
         *
         */
        void register(Itr itr) {
            // assert lock.getHoldCount() == 1;
            // 创建一个新节点将其插入到head节点的前面
            head = new Node(itr, head);
        }

        /**
         * Called whenever takeIndex wraps around to 0.
         *
         * Notifies all iterators, and expunges any that are now stale.
         *
         * <br><br>
         * <p>
         * 用于takeIndex变成0了后调用，会增加cycles计数，如果当前cycles属性减去Itr实例的prevCycles属性大于1，
         * 则说明Itr初始化时栈中的元素都被移除了，此时再遍历无意义，将这类节点从Itr实例链表中移除，将对Itr的引用置为null，
         * 将Itr实例的各属性置为null或者特殊index值。
         */
        // 当takeIndex变成0的时候回调的
        void takeIndexWrapped() {
            // assert lock.getHoldCount() == 1;
            cycles++;
            // 遍历链表，o表示上一个有效节点，p表示当前遍历的节点
            for (Node o = null, p = head; p != null;) {
                final Itr it = p.get();
                final Node next = p.next;
                if (it == null || it.takeIndexWrapped()) {
                    // unlink p
                    // assert it == null || it.isDetached();
                    p.clear();
                    p.next = null;
                    if (o == null)
                        // 之前的节点是无效节点，重置head，把next之前的节点都移除了
                        head = next;
                    else
                        // 移除p这一个节点
                        o.next = next;
                } else {
                    // 保存上一个有效节点
                    o = p;
                }
                // 处理下一个节点
                p = next;
            }
            // 没有有效节点，itrs置为null
            if (head == null)   // no more iterators to track
                itrs = null;
        }

        /**
         * Called whenever an interior remove (not at takeIndex) occurred.
         *
         * Notifies all iterators, and expunges any that are now stale.
         *
         * <br><br>
         * <p>
         * 用于从栈中注意不是栈顶移除元素时调用的，会重新计算cursor，lastRet，nextIndex等属性，
         * 如果计算出来的属性小于0，则将这个节点从Itr实例链表中移除，将Itr实例的各属性置为null或者特殊index值.
         */
        // 栈中某个元素被移除时调用
        void removedAt(int removedIndex) {
            // 遍历链表
            for (Node o = null, p = head; p != null;) {
                final Itr it = p.get();
                final Node next = p.next;
                // removedAt方法判断这个节点是否被移除了
                if (it == null || it.removedAt(removedIndex)) {
                    // unlink p
                    // assert it == null || it.isDetached();
                    // 将p从链表中移除
                    p.clear();
                    p.next = null;
                    if (o == null)
                        head = next;
                    else
                        o.next = next;
                } else {
                    // 记录上一个有效节点
                    o = p;
                }
                // 处理下一个有效节点
                p = next;
            }
            // 无有效节点
            if (head == null)   // no more iterators to track
                itrs = null;
        }

        /**
         * Called whenever the queue becomes empty.
         *
         * Notifies all active iterators that the queue is empty,
         * clears all weak refs, and unlinks the itrs datastructure.
         *
         * <br><br>
         * <p>
         * 用于因为元素移除栈空了时调用的，会将Itr链表中的所有元素对Itr实例的引用置为null，将Itr实例的各属性置为null或者特殊index值。
         */
        // 栈变成空的以后回调此方法
        void queueIsEmpty() {
            // assert lock.getHoldCount() == 1;
            // 遍历链表
            for (Node p = head; p != null; p = p.next) {
                Itr it = p.get();
                if (it != null) {
                    // 将引用清除
                    p.clear();
                    // 通知Itr队列空了，将各参数置为null或者特殊index值
                    it.shutdown();
                }
            }
            // 重置为null
            head = null;
            itrs = null;
        }

        /**
         * Called whenever an element has been dequeued (at takeIndex).
         *
         * <br><br>
         * <p>
         * 元素从栈顶移除时调用，如果当前栈空了则调用queueIsEmpty方法，如果takeIndex变成0了，则调用takeIndexWrapped方法。
         */
        // 从栈顶移除一个元素时回调的
        void elementDequeued() {
            // assert lock.getHoldCount() == 1;
            if (count == 0)
                // 如果栈空了
                queueIsEmpty();
            else if (takeIndex == 0)
                takeIndexWrapped();
        }
    }

    /**
     * Iterator for ArrayBlockingQueue.
     *
     * To maintain weak consistency with respect to puts and takes, we
     * read ahead one slot, so as to not report hasNext true but then
     * not have an element to return.
     *
     * We switch into "detached" mode (allowing prompt unlinking from
     * itrs without help from the GC) when all indices are negative, or
     * when hasNext returns false for the first time.  This allows the
     * iterator to track concurrent updates completely accurately,
     * except for the corner case of the user calling Iterator.remove()
     * after hasNext() returned false.  Even in this case, we ensure
     * that we don't remove the wrong element by keeping track of the
     * expected element to remove, in lastItem.  Yes, we may fail to
     * remove lastItem from the queue if it moved due to an interleaved
     * interior remove while in detached mode.
     */
    private class Itr implements Iterator<E> {
        /** 查找下一个nextItem的索引，如果是NONE表示到栈底了 */
        private int cursor;

        /** 下次调用 next() 时要返回的元素；如果没有则为空 */
        private E nextItem;

        /** nextItem元素对应的索引，如果是NONE表示nextItem是null, 如果是REMOVED表示nextItem被移除了，初始值就等于takeIndex */
        private int nextIndex;

        /** 返回的最后一个元素；如果没有或未分离，则为 null */
        private E lastItem;

        /** lastItem对应的索引 Index of lastItem, NONE if none, REMOVED if removed elsewhere */
        private int lastRet;

        /** Previous value of takeIndex, or DETACHED when detached */
        private int prevTakeIndex;

        /** Itr初始化时takeIndex的值 Previous value of iters.cycles */
        private int prevCycles;

        /** 特殊的索引值，表示这个索引对应的元素不存在 Special index value indicating "not available" or "undefined" */
        private static final int NONE = -1;

        /**
         *
         * 特殊的索引值，表示该索引对应的元素已经被移除了
         *
         * Special index value indicating "removed elsewhere", that is,
         * removed by some operation other than a call to this.remove().
         */
        private static final int REMOVED = -2;

        /**
         * 特殊的prevTakeIndex值，表示当前Iter已经从Iters中解除关联了
         * Special value for prevTakeIndex indicating "detached mode" */
        private static final int DETACHED = -3;

        Itr() {
            // assert lock.getHoldCount() == 0;
            lastRet = NONE;
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                if (count == 0) {
                    // assert itrs == null;
                    // NONE和DETACHED都是常量
                    cursor = NONE;
                    nextIndex = NONE;
                    prevTakeIndex = DETACHED;
                } else {
                    // 初始化各属性
                    final int takeIndex = ArrayBlockingQueue.this.takeIndex;
                    prevTakeIndex = takeIndex;
                    nextItem = itemAt(nextIndex = takeIndex);
                    cursor = incCursor(takeIndex);
                    if (itrs == null) {
                        // 初始化Itrs，将当前线程注册到Itrs
                        itrs = new Itrs(this);
                    } else {
                        // 将当前线程注册到Itrs中，并执行清理
                        itrs.register(this); // in this order
                        itrs.doSomeSweeping(false);
                    }
                    prevCycles = itrs.cycles;
                    // assert takeIndex >= 0;
                    // assert prevTakeIndex == takeIndex;
                    // assert nextIndex >= 0;
                    // assert nextItem != null;
                }
            } finally {
                lock.unlock();
            }
        }

        boolean isDetached() {
            // assert lock.getHoldCount() == 1;
            return prevTakeIndex < 0;
        }

        // 根据index计算cursor
        private int incCursor(int index) {
            // assert lock.getHoldCount() == 1;
            if (++index == items.length)
                index = 0;
            if (index == putIndex)
                index = NONE;
            return index;
        }

        /**
         * Returns true if index is invalidated by the given number of
         * dequeues, starting from prevTakeIndex.
         */
        // 校验这个index是否有效，返回true表示无效
        private boolean invalidated(int index, int prevTakeIndex,
                                    long dequeues, int length) {
            if (index < 0)
                return false;
            int distance = index - prevTakeIndex;
            if (distance < 0)
                distance += length;
            return dequeues > distance;
        }

        /**
         * Adjusts indices to incorporate all dequeues since the last
         * operation on this iterator.  Call only from iterating thread.
         */
        // 校验并调整相关属性
        private void incorporateDequeues() {
            // assert lock.getHoldCount() == 1;
            // assert itrs != null;
            // assert !isDetached();
            // assert count > 0;

            final int cycles = itrs.cycles;
            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            final int prevCycles = this.prevCycles;
            final int prevTakeIndex = this.prevTakeIndex;

            if (cycles != prevCycles || takeIndex != prevTakeIndex) {
                final int len = items.length;
                // how far takeIndex has advanced since the previous
                // operation of this iterator
                long dequeues = (cycles - prevCycles) * len
                    + (takeIndex - prevTakeIndex);

                // Check indices for invalidation
                // 校验各属性是否有效
                if (invalidated(lastRet, prevTakeIndex, dequeues, len))
                    lastRet = REMOVED;
                if (invalidated(nextIndex, prevTakeIndex, dequeues, len))
                    nextIndex = REMOVED;
                if (invalidated(cursor, prevTakeIndex, dequeues, len))
                    cursor = takeIndex;

                if (cursor < 0 && nextIndex < 0 && lastRet < 0)
                    detach();
                else {
                    // 如果是有效的，则重置相关属性
                    this.prevCycles = cycles;
                    this.prevTakeIndex = takeIndex;
                }
            }
        }

        /**
         * Called when itrs should stop tracking this iterator, either
         * because there are no more indices to update (cursor < 0 &&
         * nextIndex < 0 && lastRet < 0) or as a special exception, when
         * lastRet >= 0, because hasNext() is about to return false for the
         * first time.  Call only from iterating thread.
         */
        private void detach() {
            // Switch to detached mode
            // assert lock.getHoldCount() == 1;
            // assert cursor == NONE;
            // assert nextIndex < 0;
            // assert lastRet < 0 || nextItem == null;
            // assert lastRet < 0 ^ lastItem != null;
            if (prevTakeIndex >= 0) {
                // assert itrs != null;
                // 将当前Itr标记为无效的
                prevTakeIndex = DETACHED;
                // try to unlink from itrs (but not too hard)
                // 尝试将当前Itr从链表中移除
                itrs.doSomeSweeping(true);
            }
        }

        /**
         * For performance reasons, we would like not to acquire a lock in
         * hasNext in the common case.  To allow for this, we only access
         * fields (i.e. nextItem) that are not modified by update operations
         * triggered by queue modifications.
         */
        public boolean hasNext() {
            // assert lock.getHoldCount() == 0;
            if (nextItem != null)
                return true;
            // 如果没有下一个元素了
            noNext();
            return false;
        }

        private void noNext() {
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                // assert cursor == NONE;
                // assert nextIndex == NONE;
                if (!isDetached()) {
                    // assert lastRet >= 0;
                    // 如果当前Itr还是有效的
                    incorporateDequeues(); // might update lastRet
                    if (lastRet >= 0) {
                        lastItem = itemAt(lastRet);
                        // assert lastItem != null;
                        detach();
                    }
                }
                // assert isDetached();
                // assert lastRet < 0 ^ lastItem != null;
            } finally {
                lock.unlock();
            }
        }

        public E next() {
            // assert lock.getHoldCount() == 0;
            final E x = nextItem;
            if (x == null)
                throw new NoSuchElementException();
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                if (!isDetached())
                    incorporateDequeues();
                // 如果isDetached为true还会继续执行
                // assert nextIndex != NONE;
                // assert lastItem == null;
                lastRet = nextIndex;
                final int cursor = this.cursor;
                if (cursor >= 0) {
                    // 获取指定cursor的元素
                    nextItem = itemAt(nextIndex = cursor);
                    // assert nextItem != null;
                    // 重新计算cursor，如果等于putIndex就将其置为None
                    this.cursor = incCursor(cursor);
                } else {
                    // 已经遍历到putIndex处了，上次incCursor计算时将其变成负值
                    nextIndex = NONE;
                    nextItem = null;
                }
            } finally {
                lock.unlock();
            }
            return x;
        }

        public void remove() {
            // assert lock.getHoldCount() == 0;
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                if (!isDetached())
                    incorporateDequeues(); // might update lastRet or detach
                final int lastRet = this.lastRet;
                this.lastRet = NONE;
                if (lastRet >= 0) {
                    if (!isDetached())
                        // 移除lastRet处的元素
                        removeAt(lastRet);
                    else {
                        final E lastItem = this.lastItem;
                        // assert lastItem != null;
                        this.lastItem = null;
                        if (itemAt(lastRet) == lastItem)
                            removeAt(lastRet);
                    }
                } else if (lastRet == NONE)
                    throw new IllegalStateException();
                // else lastRet == REMOVED and the last returned element was
                // previously asynchronously removed via an operation other
                // than this.remove(), so nothing to do.

                if (cursor < 0 && nextIndex < 0)
                    detach(); // 将当前Itr标记为无效并尝试清理掉
            } finally {
                lock.unlock();
                // assert lastRet == NONE;
                // assert lastItem == null;
            }
        }

        /**
         * Called to notify the iterator that the queue is empty, or that it
         * has fallen hopelessly behind, so that it should abandon any
         * further iteration, except possibly to return one more element
         * from next(), as promised by returning true from hasNext().
         */
        void shutdown() {
            // assert lock.getHoldCount() == 1;
            // nextItem没有被置为null，通过next方法还可以返回
            cursor = NONE;
            if (nextIndex >= 0)
                nextIndex = REMOVED;
            if (lastRet >= 0) {
                lastRet = REMOVED;
                lastItem = null;
            }
            prevTakeIndex = DETACHED;
            // Don't set nextItem to null because we must continue to be
            // able to return it on next().
            //
            // Caller will unlink from itrs when convenient.
        }

        private int distance(int index, int prevTakeIndex, int length) {
            int distance = index - prevTakeIndex;
            if (distance < 0)
                distance += length;
            return distance;
        }

        /**
         * Called whenever an interior remove (not at takeIndex) occurred.
         *
         * @return true if this iterator should be unlinked from itrs
         */
        boolean removedAt(int removedIndex) {
            // assert lock.getHoldCount() == 1;
            if (isDetached())
                return true;

            final int cycles = itrs.cycles;
            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            final int prevCycles = this.prevCycles;
            final int prevTakeIndex = this.prevTakeIndex;
            final int len = items.length;
            int cycleDiff = cycles - prevCycles;
            if (removedIndex < takeIndex)
                cycleDiff++;
            final int removedDistance =
                (cycleDiff * len) + (removedIndex - prevTakeIndex);
            // assert removedDistance >= 0;
            int cursor = this.cursor;
            // 按照特定的逻辑重新计算cursor，lastRet，nextIndex等属性
            if (cursor >= 0) {
                int x = distance(cursor, prevTakeIndex, len);
                if (x == removedDistance) {
                    if (cursor == putIndex)
                        this.cursor = cursor = NONE;
                }
                else if (x > removedDistance) {
                    // assert cursor != prevTakeIndex;
                    this.cursor = cursor = dec(cursor);
                }
            }
            int lastRet = this.lastRet;
            if (lastRet >= 0) {
                int x = distance(lastRet, prevTakeIndex, len);
                if (x == removedDistance)
                    this.lastRet = lastRet = REMOVED;
                else if (x > removedDistance)
                    this.lastRet = lastRet = dec(lastRet);
            }
            int nextIndex = this.nextIndex;
            if (nextIndex >= 0) {
                int x = distance(nextIndex, prevTakeIndex, len);
                if (x == removedDistance)
                    this.nextIndex = nextIndex = REMOVED;
                else if (x > removedDistance)
                    this.nextIndex = nextIndex = dec(nextIndex);
            }
            else if (cursor < 0 && nextIndex < 0 && lastRet < 0) {
                this.prevTakeIndex = DETACHED;
                return true;
            }
            return false;
        }

        /**
         * Called whenever takeIndex wraps around to zero.
         *
         * @return true if this iterator should be unlinked from itrs
         */
        // 判断Itr这个节点是否应该被移除
        boolean takeIndexWrapped() {
            // assert lock.getHoldCount() == 1;
            if (isDetached())
                return true;
            if (itrs.cycles - prevCycles > 1) {
                // All the elements that existed at the time of the last
                // operation are gone, so abandon further iteration.
                // cycles不一致了，则原来栈中的元素可能都没了
                shutdown();
                return true;
            }
            return false;
        }

//         /** Uncomment for debugging. */
//         public String toString() {
//             return ("cursor=" + cursor + " " +
//                     "nextIndex=" + nextIndex + " " +
//                     "lastRet=" + lastRet + " " +
//                     "nextItem=" + nextItem + " " +
//                     "lastItem=" + lastItem + " " +
//                     "prevCycles=" + prevCycles + " " +
//                     "prevTakeIndex=" + prevTakeIndex + " " +
//                     "size()=" + size() + " " +
//                     "remainingCapacity()=" + remainingCapacity());
//         }
    }

    /**
     * Returns a {@link Spliterator} over the elements in this queue.
     *
     * <p>The returned spliterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The {@code Spliterator} reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#ORDERED}, and {@link Spliterator#NONNULL}.
     *
     * @implNote
     * The {@code Spliterator} implements {@code trySplit} to permit limited
     * parallelism.
     *
     * @return a {@code Spliterator} over the elements in this queue
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return Spliterators.spliterator
            (this, Spliterator.ORDERED | Spliterator.NONNULL |
             Spliterator.CONCURRENT);
    }

}
