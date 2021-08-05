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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * An optionally-bounded {@linkplain BlockingQueue blocking queue} based on
 * linked nodes.
 * This queue orders elements FIFO (first-in-first-out).
 * The <em>head</em> of the queue is that element that has been on the
 * queue the longest time.
 * The <em>tail</em> of the queue is that element that has been on the
 * queue the shortest time. New elements
 * are inserted at the tail of the queue, and the queue retrieval
 * operations obtain elements at the head of the queue.
 * Linked queues typically have higher throughput than array-based queues but
 * less predictable performance in most concurrent applications.
 *
 * <p>The optional capacity bound constructor argument serves as a
 * way to prevent excessive queue expansion. The capacity, if unspecified,
 * is equal to {@link Integer#MAX_VALUE}.  Linked nodes are
 * dynamically created upon each insertion unless this would bring the
 * queue above capacity.
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
 * LinkedBlockingQueue表示一个基于链表实现的可选的固定容量的，先进先出的，线程安全的队列（栈），其接口的语义和ArrayBlockingQueue基本一致。
 *
 * <br><br>
 * <p>跟ArrayBlockingQueue的实现差异总结
 * <ol>
 *     <li>
 *         ArrayBlockingQueue是基于循环数组实现的，容量是固定的，循环数组是指插入或者移除元素到达数组末尾时又会从头开始；<br>
 *         LinkedBlockingQueue是基于单向链表的，如果指定容量就是固定容量，如果没有指定，则容量默认是int的最大值。
 *     </li>
 *     <li>
 *         ArrayBlockingQueue中维护元素的个数的属性是一个普通的int类型，读取和修改该属性必须获取锁；<br>
 *         LinkedBlockingQueue中是一个AtomicInteger类型，这样在判断队列是否为空时就不需要加锁了，可以快速返回，避免因为获取锁而等待。
 *     </li>
 *     <li>
 *         ArrayBlockingQueue中插入元素和移除元素时都需要获取同一个锁，即这两个动作不能同时进行，这是因为移除元素时，移除元素的索引takeIndex不能超过插入元素的索引putIndex，如果等于就表示队列中元素已经都空了；<br>
 *         LinkedBlockingQueue中插入元素和从链表头移除元素是两把锁，putLock和takeLock，这样可以让两个操作同时进行效率，效率更高；之所以能使用两把锁，这是因为往链表中插入元素是插入到链表尾，而移除元素是移除链表头的元素，两个是互相独立的。但是执行clear和remove方法，执行Itr的next和remove方法时必须同时获取两把锁，这是因为这种场景下不允许队列发生修改。
 *     </li>
 *     <li>
 *         ArrayBlockingQueue中通过内部类Itr执行遍历时不需要上锁，ArrayBlockingQueue执行特定操作时会通过负责跟踪Itr的Itrs的回调方法，通过Itr比如某个对象被删除了，然后Itr会重新调整自己下一个遍历的元素，整体逻辑比较复杂；<br>
 *         LinkedBlockingQueue的实现比较简单，执行next和remove方法时要获取两个锁，就避免了再遍历时链表发生改变了，实现相对简单明了。
 *     </li>
 *     <li>
 *         ArrayBlockingQueue每插入一个元素后都需要调用signal方法唤醒因为队列是空的而阻塞的线程，即使此时没有等待的线程；<br>
 *         LinkedBlockingQueue会校验插入元素前的队列容量，如果插入前的队列容量是0，那么有可能有线程因为队列是空的而阻塞，才会调用signal方法，减少不必要的调用。
 *     </li>
 *     <li>
 *         ArrayBlockingQueue每移除一个元素后都需要调用signal方法唤醒因为队列是满的而阻塞的线程，即使此时没有等待的线程；<br>
 *         LinkedBlockingQueue会校验移除元素前队列的容量，如果队列是满的，才有可能存在因为队列是满的而阻塞的线程，才会调用signal方法唤醒阻塞的线程，减少不必要的调用。
 *     </li>
 *     <li>
 *         ArrayBlockingQueue执行clear或者drainTo方法时，移除了指定数量的元素，就会通过for循环调用该数量次的signal方法，唤醒因为线程满了而阻塞的线程，不能超过该数量且在Condition上有等待的线程。之所以不能超过该数量，是为了避免唤醒过多的线程，他们会因为队列满了而再次阻塞。<br>
 *         LinkedBlockingQueue判断移除前队列的数量是满的，则通过signal方法唤醒因为队列是满的而阻塞的线程，但是只调用一次，因为队列是满的线程被唤醒后，会尝试插入到队列中，如果插入成功后还有多余的容量，则会尝试唤醒下一个因为队列是满的而阻塞的线程，从而巧妙的避免了线程被唤醒后因为队列是满的而再次阻塞的问题。
 *     </li>
 *     <li>
 *         LinkedBlockingQueue在移除元素时，如果移除前队列容量大于1，会唤醒因为队列是空的而阻塞的线程，这是因为LinkedBlockingQueue下元素插入时，只有队列原来是空才会唤醒因为队列是空的而阻塞的线程，即如果有多个阻塞的线程，这种情形下只会唤醒一个。这时已经被唤醒的线程会判断队列中是否有多余的元素，如果有则继续唤醒下一个阻塞的线程，如此将所有阻塞的线程都唤醒。<br>
 *         ArrayBlockingQueue下因为每次插入时都要唤醒一次因为队列是空而阻塞的线程，就不存在上述问题了。
 *     </li>
 * </ol>
 *
 * @since 1.5
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 */
public class LinkedBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -6903933977591709194L;

    /*
     * A variant of the "two lock queue" algorithm.  The putLock gates
     * entry to put (and offer), and has an associated condition for
     * waiting puts.  Similarly for the takeLock.  The "count" field
     * that they both rely on is maintained as an atomic to avoid
     * needing to get both locks in most cases. Also, to minimize need
     * for puts to get takeLock and vice-versa, cascading notifies are
     * used. When a put notices that it has enabled at least one take,
     * it signals taker. That taker in turn signals others if more
     * items have been entered since the signal. And symmetrically for
     * takes signalling puts. Operations such as remove(Object) and
     * iterators acquire both locks.
     *
     * Visibility between writers and readers is provided as follows:
     *
     * Whenever an element is enqueued, the putLock is acquired and
     * count updated.  A subsequent reader guarantees visibility to the
     * enqueued Node by either acquiring the putLock (via fullyLock)
     * or by acquiring the takeLock, and then reading n = count.get();
     * this gives visibility to the first n items.
     *
     * To implement weakly consistent iterators, it appears we need to
     * keep all Nodes GC-reachable from a predecessor dequeued Node.
     * That would cause two problems:
     * - allow a rogue Iterator to cause unbounded memory retention
     * - cause cross-generational linking of old Nodes to new Nodes if
     *   a Node was tenured while live, which generational GCs have a
     *   hard time dealing with, causing repeated major collections.
     * However, only non-deleted Nodes need to be reachable from
     * dequeued Nodes, and reachability does not necessarily have to
     * be of the kind understood by the GC.  We use the trick of
     * linking a Node that has just been dequeued to itself.  Such a
     * self-link implicitly means to advance to head.next.
     */

    /**
     * Linked list node class
     */
    static class Node<E> {
        E item;

        /**
         * One of:
         * - the real successor Node
         * - this Node, meaning the successor is head.next
         * - null, meaning there is no successor (this is the last node)
         */
        Node<E> next;

        Node(E x) { item = x; }
    }

    /** 容量限制，如果没有，则为 Integer.MAX_VALUE */
    private final int capacity;

    /** 当前队列中元素的个数 */
    private final AtomicInteger count = new AtomicInteger();

    /**
     * 链表头.
     * Invariant: head.item == null
     */
    transient Node<E> head;

    /**
     * 链表尾.
     * Invariant: last.next == null
     */
    private transient Node<E> last;

    /** 取出元素时获取的锁 */
    private final ReentrantLock takeLock = new ReentrantLock();

    /** 队列是空时的等待条件 */
    private final Condition notEmpty = takeLock.newCondition();

    /** 插入元素时获取的锁 */
    private final ReentrantLock putLock = new ReentrantLock();

    /** 队列是满的等待条件 */
    private final Condition notFull = putLock.newCondition();

    /**
     * Signals a waiting take. Called only from put/offer (which do not
     * otherwise ordinarily lock takeLock.)<br><br>
     *
     * 发出信号，通知在等待读锁线程竞争
     */
    private void signalNotEmpty() {
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            // 唤醒因为队列是空的而等待的线程
            notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
    }

    /**
     * Signals a waiting put. Called only from take/poll.<br><br>
     *
     * 唤醒因为队列是满的而等待的线程
     */
    private void signalNotFull() {
        final ReentrantLock putLock = this.putLock;
        putLock.lock();
        try {
            notFull.signal();
        } finally {
            putLock.unlock();
        }
    }

    /**
     * 在队列末尾链接节点。
     *
     * @param node the node
     */
    private void enqueue(Node<E> node) {
        // assert putLock.isHeldByCurrentThread();
        // assert last.next == null;
        last = last.next = node;
    }

    /**
     * 从队列的头部移除一个节点。并将对头指向的内容清空（保持对头一直指向的是一个item=Null的节点）
     *
     * @return the node
     */
    private E dequeue() {
        // assert takeLock.isHeldByCurrentThread();
        // assert head.item == null;
        // 获取并移除链表头
        Node<E> h = head;
        Node<E> first = h.next;
        h.next = h; // help GC
        head = first;
        E x = first.item;
        first.item = null;
        return x;
    }

    /**
     * 锁定所有的Lock.
     */
    void fullyLock() {
        putLock.lock();
        takeLock.lock();
    }

    /**
     * 解锁所有的的Lock.
     */
    void fullyUnlock() {
        takeLock.unlock();
        putLock.unlock();
    }

//     /**
//      * Tells whether both locks are held by current thread.
//      */
//     boolean isFullyLocked() {
//         return (putLock.isHeldByCurrentThread() &&
//                 takeLock.isHeldByCurrentThread());
//     }

    /**
     * 创建一个无界的队列。{@link Integer#MAX_VALUE}相当于是无界的
     */
    public LinkedBlockingQueue() {
        this(Integer.MAX_VALUE);
    }

    /**
     * 创建一个有界队列。
     *
     * @param capacity the capacity of this queue
     * @throws IllegalArgumentException if {@code capacity} is not greater
     *         than zero
     */
    public LinkedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        // 初始化时head节点
        last = head = new Node<E>(null);//初始的时候队尾和对头指向一个空节点
    }

    /**
     * Creates a {@code LinkedBlockingQueue} with a capacity of
     * {@link Integer#MAX_VALUE}, initially containing the elements of the
     * given collection,
     * added in traversal order of the collection's iterator.
     *
     * @param c the collection of elements to initially contain
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    public LinkedBlockingQueue(Collection<? extends E> c) {
        this(Integer.MAX_VALUE);
        final ReentrantLock putLock = this.putLock;
        putLock.lock(); // Never contended, but necessary for visibility 加锁的目的主要是为了保证修改对其他CPU立即可见
        try {
            int n = 0;
            // 遍历集合c，将里面的元素加入到队列中
            for (E e : c) {
                if (e == null)
                    throw new NullPointerException();
                if (n == capacity)
                    throw new IllegalStateException("Queue full");
                // 加入到队列中
                enqueue(new Node<E>(e));
                ++n;
            }
            count.set(n);
        } finally {
            putLock.unlock();
        }
    }

    // this doc comment is overridden to remove the reference to collections
    // greater in size than Integer.MAX_VALUE
    /**
     * Returns the number of elements in this queue.
     *
     * @return the number of elements in this queue
     */
    public int size() {
        return count.get();
    }

    // this doc comment is a modified copy of the inherited doc comment,
    // without the reference to unlimited queues.
    /**
     * Returns the number of additional elements that this queue can ideally
     * (in the absence of memory or resource constraints) accept without
     * blocking. This is always equal to the initial capacity of this queue
     * less the current {@code size} of this queue.
     *
     * <p>Note that you <em>cannot</em> always tell if an attempt to insert
     * an element will succeed by inspecting {@code remainingCapacity}
     * because it may be the case that another thread is about to
     * insert or remove an element.<br><br>
     *
     * 还可放入的数量，不准
     */
    public int remainingCapacity() {
        return capacity - count.get();
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting if
     * necessary for space to become available.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        // Note: convention in all put/take/etc is to preset local var
        // holding count negative to indicate failure unless set.
        int c = -1;
        Node<E> node = new Node<E>(e);
        final ReentrantLock putLock = this.putLock;//为什么弄成局部变量，是因为可以在循环的时候少调用一个getField字节码指令
        final AtomicInteger count = this.count;
        putLock.lockInterruptibly();//写锁
        try {
            /*
             * Note that count is used in wait guard even though it is
             * not protected by lock. This works because count can
             * only decrease at this point (all other puts are shut
             * out by lock), and we (or some other waiting put) are
             * signalled if it ever changes from capacity. Similarly
             * for all other uses of count in other wait guards.
             */
            // 队列满了，循环等待，直到列队有可用容量
            while (count.get() == capacity) {//如果队列满了，则当前线程等待
                notFull.await();
            }
            enqueue(node);//将节点插入到队尾
            c = count.getAndIncrement();//计数器+1
            if (c + 1 < capacity)//如果当前计数器+1小于容量限制，则通知其他等待写入数据的线程
                notFull.signal();//将原等待队列放入到阻塞队列，等待锁解除后竞争锁
        } finally {
            putLock.unlock();
        }
        if (c == 0)//当首次加入的话，通知读锁等待的线程启动(这里面的方法需要获取take锁)
            signalNotEmpty();
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting if
     * necessary up to the specified wait time for space to become available.<br><br>
     *
     * 将指定的元素插入此队列的末尾，并且指定超时等待的时间<br><br>
     *
     * @return {@code true} if successful, or {@code false} if
     *         the specified waiting time elapses before space is available
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {

        if (e == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        int c = -1;
        final ReentrantLock putLock = this.putLock;
        final AtomicInteger count = this.count;
        // 如果被中断了抛出异常
        putLock.lockInterruptibly();
        try {
            // 队列满了，循环等待，直到列队有可用容量或者等待超时了
            while (count.get() == capacity) {
                if (nanos <= 0)//如果等待时间到了，则返回插入失败
                    return false;
                nanos = notFull.awaitNanos(nanos);//返回剩余需要等待的时间
            }
            enqueue(new Node<E>(e));//下面代码与上面类同
            c = count.getAndIncrement();
            if (c + 1 < capacity)
                // 队列中还有可用容量，唤醒因为队列满了而阻塞的线程
                notFull.signal();
        } finally {
            putLock.unlock();
        }
        if (c == 0)
            signalNotEmpty();
        return true;
    }

    /**
     * Inserts the specified element at the tail of this queue if it is
     * possible to do so immediately without exceeding the queue's capacity,
     * returning {@code true} upon success and {@code false} if this queue
     * is full.
     * When using a capacity-restricted queue, this method is generally
     * preferable to method {@link BlockingQueue#add add}, which can fail to
     * insert an element only by throwing an exception.<br><br>
     *
     * 尝试着立即插入数据，如果队列满了则直接返回失败。否则获取锁执行插入逻辑
     *
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        // 此处使用AtomicInteger，避免因为获取锁导致等待
        final AtomicInteger count = this.count;
        if (count.get() == capacity)
            return false;
        int c = -1;
        // 创建一个新节点
        Node<E> node = new Node<E>(e);
        final ReentrantLock putLock = this.putLock;
        putLock.lock();
        try {
            if (count.get() < capacity) {
                // 加入到队列中
                enqueue(node);
                // 增加元素个数
                c = count.getAndIncrement();
                if (c + 1 < capacity)
                    // 队列中还有可用容量，唤醒因为队列满了而阻塞的线程
                    // ArrayBlockingQueue中插入元素和获取元素是同一个锁，从队列中取出一个元素时会唤醒因为队列满而等待的线程
                    // LinkedBlockingQueue是两个锁，从队列中取出元素
                    notFull.signal();
            }
        } finally {
            putLock.unlock();
        }
        // c是插入之前的元素个数，只有个数是0的情况下才可能存在因为队列是空的而阻塞的线程
        if (c == 0)
            // 唤醒因为队列是空的而等待的线程
            signalNotEmpty();
        return c >= 0;
    }

    public E take() throws InterruptedException {
        E x;
        int c = -1;
        final AtomicInteger count = this.count;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lockInterruptibly();
        try {
            // 列为空则循环等待
            while (count.get() == 0) {//当队列为空，则等待
                notEmpty.await();
            }
            // 获取并移除链表头
            x = dequeue();
            c = count.getAndDecrement();//队列元素计数-1
            if (c > 1)//如果队列还有数据，则通知等待的线程进入sync队列，锁释放后即唤醒
                // 唤醒因为队列是空的而阻塞的线程，c大于1，这次take移除了1个，还剩至少1个
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        // c 是在移除前的数量
        if (c == capacity)//如果c=满了，则通知等待写锁的线程解锁
            // 唤醒因为队列是满的而等待的线程
            signalNotFull();
        return x;
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E x = null;
        int c = -1;
        long nanos = unit.toNanos(timeout);
        final AtomicInteger count = this.count;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lockInterruptibly();
        try {
            // 队列为空则循环等待，如果超时则返回null
            while (count.get() == 0) {//队列为0继续等待
                if (nanos <= 0)//时间到了返回null
                    return null;
                nanos = notEmpty.awaitNanos(nanos);//否则继续等待
            }
            x = dequeue();
            c = count.getAndDecrement();//与take方法一样
            if (c > 1)
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        if (c == capacity)
            signalNotFull();
        return x;
    }

    //尝试获取队列头数据，获取不到则返回。
    public E poll() {
        final AtomicInteger count = this.count;
        // 为空直接返回null
        if (count.get() == 0)
            return null;
        E x = null;
        int c = -1;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            if (count.get() > 0) {
                x = dequeue();
                c = count.getAndDecrement();
                if (c > 1)
                    notEmpty.signal();
            }
        } finally {
            takeLock.unlock();
        }
        if (c == capacity)
            signalNotFull();
        return x;
    }

    //获取队列头数据而不从队列中移除
    public E peek() {
        if (count.get() == 0)
            return null;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            // 为空直接返回null，不用等待
            Node<E> first = head.next;
            if (first == null)
                return null;
            else
                return first.item;
        } finally {
            takeLock.unlock();
        }
    }

    /**
     * Unlinks interior Node p with predecessor trail.<br><br>
     *
     * 取消内部节点p与先前路径的链接。
     * @param p     当前节点
     * @param trail p的前一个节点
     */
    void unlink(Node<E> p, Node<E> trail) {
        // assert isFullyLocked();
        // p.next is not changed, to allow iterators that are
        // traversing p to maintain their weak-consistency guarantee.
        // 将p从链表中移除
        p.item = null;
        trail.next = p.next;
        if (last == p)
            // 如果p是最后一个节点，将trail作为last
            last = trail;
        if (count.getAndDecrement() == capacity)//如果队列由满-1，则通知put等待线程可以存放数据了
            // 如果移除前链表是满的，则唤醒因为队列满了而等待的线程
            notFull.signal();
    }

    /**
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.
     * Returns {@code true} if this queue contained the specified element
     * (or equivalently, if this queue changed as a result of the call).
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     */
    //从队列中移除指定的元素
    public boolean remove(Object o) {
        if (o == null) return false;
        // 需要同时获取两个锁，因为移除的时候可能是移除链表尾部的节点也可能是链表中间的节点
        // 前者需要获取takeLock，后者必须获取putLock，因为需要修改链表关系
        fullyLock();//需要全加锁
        try {
            // 从head往后遍历，p表示当前节点，trail表示该节点的前一个节点
            for (Node<E> trail = head, p = trail.next; p != null; trail = p, p = p.next) {
                if (o.equals(p.item)) {
                    // 如果找到目标节点，将其从链表中移除
                    unlink(p, trail);
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();//全部解锁
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
    //查找队列中是否存在指定的元素
    public boolean contains(Object o) {
        if (o == null) return false;
        fullyLock();
        try {
            for (Node<E> p = head.next; p != null; p = p.next)
                if (o.equals(p.item))
                    return true;
            return false;
        } finally {
            fullyUnlock();
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
        fullyLock();
        try {
            int size = count.get();
            Object[] a = new Object[size];
            int k = 0;
            for (Node<E> p = head.next; p != null; p = p.next)
                a[k++] = p.item;
            return a;
        } finally {
            fullyUnlock();
        }
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
        fullyLock();
        try {
            int size = count.get();
            if (a.length < size)
                a = (T[])java.lang.reflect.Array.newInstance
                    (a.getClass().getComponentType(), size);

            int k = 0;
            for (Node<E> p = head.next; p != null; p = p.next)
                a[k++] = (T)p.item;
            if (a.length > k)
                a[k] = null;
            return a;
        } finally {
            fullyUnlock();
        }
    }

    public String toString() {
        fullyLock();
        try {
            Node<E> p = head.next;
            if (p == null)
                return "[]";

            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (;;) {
                E e = p.item;
                sb.append(e == this ? "(this Collection)" : e);
                p = p.next;
                if (p == null)
                    return sb.append(']').toString();
                sb.append(',').append(' ');
            }
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Atomically removes all of the elements from this queue.
     * The queue will be empty after this call returns.
     */
    //清空队列
    public void clear() {
        fullyLock();//全加锁
        try {
            // 遍历整个链表，将各节点的链表关系和item属性都去除
            for (Node<E> p, h = head; (p = h.next) != null; h = p) {
                h.next = h;
                p.item = null;
            }
            head = last;
            // assert head.item == null && head.next == null;
            if (count.getAndSet(0) == capacity)//设置队列元素数量为0，如果队列本来是满的，则通知可能阻塞的写线程启动
                // 如果修改前容量是满的，则唤醒因为队列是满的而阻塞的线程
                notFull.signal();
        } finally {
            fullyUnlock();
        }
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        // 校验参数合法性
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;
        boolean signalNotFull = false;
        // 因为是取的链表头，所以不需要获取两个锁，只需要一个takeLock即可
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            // 取maxElements和元素个数的最小值
            int n = Math.min(maxElements, count.get());
            // count.get provides visibility to first n Nodes
            Node<E> h = head;
            int i = 0;
            try {
                // 从head开始往后遍历，取n个元素，head是最早插入的
                while (i < n) {
                    Node<E> p = h.next;
                    // 将链表节点保存的元素引用放入集合中
                    c.add(p.item);
                    // 链表引用关系清除
                    p.item = null;
                    h.next = h;
                    h = p;
                    ++i;
                }
                return n;
            } finally {
                // Restore invariants even if c.add() threw
                // n大于0，此处i肯定大于0，因为获取锁takeLock后，就只有当前线程可以移除节点
                if (i > 0) {
                    // assert h.item == null;
                    // 重置head
                    head = h;
                    // 如果取出元素前队列是满的，则唤醒因为队列满的而等待的线程
                    signalNotFull = (count.getAndAdd(-i) == capacity);
                }
            }
        } finally {
            takeLock.unlock();
            if (signalNotFull)
                signalNotFull();
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

    private class Itr implements Iterator<E> {
        /*
         * Basic weakly-consistent iterator.  At all times hold the next
         * item to hand out so that if hasNext() reports true, we will
         * still have it to return even if lost race with a take etc.
         */

        /**
         * next方法返回的节点
         */
        private Node<E> current;

        /**
         * 上一次next方法返回的节点
         */
        private Node<E> lastRet;

        /**
         * next方法返回的元素
         */
        private E currentElement;

        Itr() {
            fullyLock();
            try {
                // 因为head节点初始化时是一个空的Node节点，所以遍历的第一个节点是head的next节点
                current = head.next;
                if (current != null)
                    currentElement = current.item;
            } finally {
                fullyUnlock();
            }
        }

        public boolean hasNext() {
            return current != null;
        }

        /**
         * Returns the next live successor of p, or null if no such.
         *
         * Unlike other traversal methods, iterators need to handle both:
         * - dequeued nodes (p.next == p)
         * - (possibly multiple) interior removed nodes (p.item == null)
         */
        private Node<E> nextNode(Node<E> p) {
            for (;;) {
                Node<E> s = p.next;
                if (s == p)
                    // s等于p说明这是一个无效节点，返回链表头的有效节点
                    return head.next;
                // s等于null，p是最后一个节点
                if (s == null || s.item != null)
                    return s;
                // 如果s不等于null但是item为null，则进入下一个节点
                // 此时通常s等于p了
                p = s;
            }
        }

        public E next() {
            // 获取两个锁
            fullyLock();
            try {
                if (current == null)
                    throw new NoSuchElementException();
                E x = currentElement;
                // 保存上一次的
                lastRet = current;
                // 获取下一个节点
                current = nextNode(current);
                // 获取下一个返回的元素
                currentElement = (current == null) ? null : current.item;
                return x;
            } finally {
                fullyUnlock();
            }
        }

        public void remove() {
            if (lastRet == null)
                throw new IllegalStateException();
            fullyLock();
            try {
                Node<E> node = lastRet;
                lastRet = null;
                // 从head开始往后遍历
                for (Node<E> trail = head, p = trail.next;
                     p != null;
                     trail = p, p = p.next) {
                    if (p == node) {
                        // 找到目标节点并移除
                        unlink(p, trail);
                        break;
                    }
                }
            } finally {
                fullyUnlock();
            }
        }
    }

    /** A customized variant of Spliterators.IteratorSpliterator */
    static final class LBQSpliterator<E> implements Spliterator<E> {
        static final int MAX_BATCH = 1 << 25;  // max batch array size;
        final LinkedBlockingQueue<E> queue;
        Node<E> current;    // current node; null until initialized
        int batch;          // batch size for splits
        boolean exhausted;  // true when no more nodes
        long est;           // size estimate
        LBQSpliterator(LinkedBlockingQueue<E> queue) {
            this.queue = queue;
            this.est = queue.size();
        }

        public long estimateSize() { return est; }

        public Spliterator<E> trySplit() {
            Node<E> h;
            final LinkedBlockingQueue<E> q = this.queue;
            int b = batch;
            int n = (b <= 0) ? 1 : (b >= MAX_BATCH) ? MAX_BATCH : b + 1;
            if (!exhausted &&
                ((h = current) != null || (h = q.head.next) != null) &&
                h.next != null) {
                Object[] a = new Object[n];
                int i = 0;
                Node<E> p = current;
                q.fullyLock();
                try {
                    if (p != null || (p = q.head.next) != null) {
                        do {
                            if ((a[i] = p.item) != null)
                                ++i;
                        } while ((p = p.next) != null && i < n);
                    }
                } finally {
                    q.fullyUnlock();
                }
                if ((current = p) == null) {
                    est = 0L;
                    exhausted = true;
                }
                else if ((est -= i) < 0L)
                    est = 0L;
                if (i > 0) {
                    batch = i;
                    return Spliterators.spliterator
                        (a, 0, i, Spliterator.ORDERED | Spliterator.NONNULL |
                         Spliterator.CONCURRENT);
                }
            }
            return null;
        }

        public void forEachRemaining(Consumer<? super E> action) {
            if (action == null) throw new NullPointerException();
            final LinkedBlockingQueue<E> q = this.queue;
            if (!exhausted) {
                exhausted = true;
                Node<E> p = current;
                do {
                    E e = null;
                    q.fullyLock();
                    try {
                        if (p == null)
                            p = q.head.next;
                        while (p != null) {
                            e = p.item;
                            p = p.next;
                            if (e != null)
                                break;
                        }
                    } finally {
                        q.fullyUnlock();
                    }
                    if (e != null)
                        action.accept(e);
                } while (p != null);
            }
        }

        public boolean tryAdvance(Consumer<? super E> action) {
            if (action == null) throw new NullPointerException();
            final LinkedBlockingQueue<E> q = this.queue;
            if (!exhausted) {
                E e = null;
                q.fullyLock();
                try {
                    if (current == null)
                        current = q.head.next;
                    while (current != null) {
                        e = current.item;
                        current = current.next;
                        if (e != null)
                            break;
                    }
                } finally {
                    q.fullyUnlock();
                }
                if (current == null)
                    exhausted = true;
                if (e != null) {
                    action.accept(e);
                    return true;
                }
            }
            return false;
        }

        public int characteristics() {
            return Spliterator.ORDERED | Spliterator.NONNULL |
                Spliterator.CONCURRENT;
        }
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
        return new LBQSpliterator<E>(this);
    }

    /**
     * Saves this queue to a stream (that is, serializes it).
     *
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     * @serialData The capacity is emitted (int), followed by all of
     * its elements (each an {@code Object}) in the proper order,
     * followed by a null
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {

        fullyLock();
        try {
            // Write out any hidden stuff, plus capacity
            s.defaultWriteObject();

            // Write out all elements in the proper order.
            for (Node<E> p = head.next; p != null; p = p.next)
                s.writeObject(p.item);

            // Use trailing null as sentinel
            s.writeObject(null);
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Reconstitutes this queue from a stream (that is, deserializes it).
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *         could not be found
     * @throws java.io.IOException if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        // Read in capacity, and any hidden stuff
        s.defaultReadObject();

        count.set(0);
        last = head = new Node<E>(null);

        // Read in all elements and place in queue
        for (;;) {
            @SuppressWarnings("unchecked")
            E item = (E)s.readObject();
            if (item == null)
                break;
            add(item);
        }
    }
}
