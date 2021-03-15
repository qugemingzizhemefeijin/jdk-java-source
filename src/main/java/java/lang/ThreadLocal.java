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

package java.lang;
import java.lang.ref.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * This class provides thread-local variables.  These variables differ from
 * their normal counterparts in that each thread that accesses one (via its
 * {@code get} or {@code set} method) has its own, independently initialized
 * copy of the variable.  {@code ThreadLocal} instances are typically private
 * static fields in classes that wish to associate state with a thread (e.g.,
 * a user ID or Transaction ID).
 *
 * <p>For example, the class below generates unique identifiers local to each
 * thread.
 * A thread's id is assigned the first time it invokes {@code ThreadId.get()}
 * and remains unchanged on subsequent calls.
 * <pre>
 * import java.util.concurrent.atomic.AtomicInteger;
 *
 * public class ThreadId {
 *     // Atomic integer containing the next thread ID to be assigned
 *     private static final AtomicInteger nextId = new AtomicInteger(0);
 *
 *     // Thread local variable containing each thread's ID
 *     private static final ThreadLocal&lt;Integer&gt; threadId =
 *         new ThreadLocal&lt;Integer&gt;() {
 *             &#64;Override protected Integer initialValue() {
 *                 return nextId.getAndIncrement();
 *         }
 *     };
 *
 *     // Returns the current thread's unique ID, assigning it if necessary
 *     public static int get() {
 *         return threadId.get();
 *     }
 * }
 * </pre>
 * <p>Each thread holds an implicit reference to its copy of a thread-local
 * variable as long as the thread is alive and the {@code ThreadLocal}
 * instance is accessible; after a thread goes away, all of its copies of
 * thread-local instances are subject to garbage collection (unless other
 * references to these copies exist).
 *
 * 计算哈希的方式包括；除法散列法、平方散列法、斐波那契（Fibonacci）散列法、随机数法等。
 *
 * ThreadLocal 使用的就是 斐波那契（Fibonacci）散列法 + 拉链法存储数据到数组结构中。之所以使用斐波那契数列，是为了让数据更加散列，减少哈希碰撞。
 * 具体来自数学公式的计算求值，公式：f(k) = ((k * 2654435769) >> X) << Y对于常见的32位整数而言，也就是 f(k) = (k * 2654435769) >> 28。
 *
 * 数字 0x61c88647，是怎么来的？其实这是一个哈希值的黄金分割点，也就是 0.618。
 * 黄金分割点：(√5 - 1) / 2 = 0.6180339887     1.618:1 == 1:0.618。黄金分割点是，(√5 - 1) / 2，取10位近似 0.6180339887。
 * 之后用 2 ^ 32 * 0.6180339887，得到的结果是：-1640531527，也就是 16 进制的，0x61c88647
 *
 * Netty中用到的FastThreadLocal，还有TransmittableThreadLocal，另外还有 JDK 本身自带的一种线程传递解决方案 InheritableThreadLocal。
 * 此外在我们经常会看到探测式清理，其实这也是非常耗时。为此我们在使用ThreadLocal一定要记得remove()操作。避免弱引用发生GC后，导致内存泄漏的问题。
 *
 * @author  Josh Bloch and Doug Lea
 * @since   1.2
 */
public class ThreadLocal<T> {
    /**
     * ThreadLocals rely on per-thread linear-probe hash maps attached
     * to each thread (Thread.threadLocals and
     * inheritableThreadLocals).  The ThreadLocal objects act as keys,
     * searched via threadLocalHashCode.  This is a custom hash code
     * (useful only within ThreadLocalMaps) that eliminates collisions
     * in the common case where consecutively constructed ThreadLocals
     * are used by the same threads, while remaining well-behaved in
     * less common cases.
     */
    private final int threadLocalHashCode = nextHashCode();

    /**
     * The next hash code to be given out. Updated atomically. Starts at
     * zero.
     */
    private static AtomicInteger nextHashCode =
        new AtomicInteger();

    /**
     * The difference between successively generated hash codes - turns
     * implicit sequential thread-local IDs into near-optimally spread
     * multiplicative hash values for power-of-two-sized tables.
     *
     *
     */
    private static final int HASH_INCREMENT = 0x61c88647;

    /**
     * Returns the next hash code.计算哈希
     */
    private static int nextHashCode() {
        return nextHashCode.getAndAdd(HASH_INCREMENT);
    }

    /**
     * Returns the current thread's "initial value" for this
     * thread-local variable.  This method will be invoked the first
     * time a thread accesses the variable with the {@link #get}
     * method, unless the thread previously invoked the {@link #set}
     * method, in which case the {@code initialValue} method will not
     * be invoked for the thread.  Normally, this method is invoked at
     * most once per thread, but it may be invoked again in case of
     * subsequent invocations of {@link #remove} followed by {@link #get}.
     *
     * <p>This implementation simply returns {@code null}; if the
     * programmer desires thread-local variables to have an initial
     * value other than {@code null}, {@code ThreadLocal} must be
     * subclassed, and this method overridden.  Typically, an
     * anonymous inner class will be used.
     *
     * @return the initial value for this thread-local
     *
     * 当初始化ThreadLocal的时候会自动调用此方法来初始化其维护的值
     */
    protected T initialValue() {
        return null;
    }

    /**
     * Creates a thread local variable. The initial value of the variable is
     * determined by invoking the {@code get} method on the {@code Supplier}.
     *
     * @param <S> the type of the thread local's value
     * @param supplier the supplier to be used to determine the initial value
     * @return a new thread local variable
     * @throws NullPointerException if the specified supplier is null
     * @since 1.8
     */
    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        return new SuppliedThreadLocal<>(supplier);
    }

    /**
     * Creates a thread local variable.
     * @see #withInitial(java.util.function.Supplier)
     */
    public ThreadLocal() {
    }

    /**
     * Returns the value in the current thread's copy of this
     * thread-local variable.  If the variable has no value for the
     * current thread, it is first initialized to the value returned
     * by an invocation of the {@link #initialValue} method.
     *
     * @return the current thread's value of this thread-local
     */
    public T get() {
        Thread t = Thread.currentThread();
        //获取Thread的threadLocals的变量
        ThreadLocalMap map = getMap(t);
        //map为空则需要初始化，否则需要进行查找
        if (map != null) {
            ThreadLocalMap.Entry e = map.getEntry(this);
            if (e != null) {
                @SuppressWarnings("unchecked")
                T result = (T)e.value;//获取维护的内容
                return result;
            }
        }
        //如果不存在Entry，则需要首次初始化一个，其实也意味着有可能维护的是一个null值
        return setInitialValue();
    }

    /**
     * Variant of set() to establish initialValue. Used instead
     * of set() in case user has overridden the set() method.
     *
     * 当get的时候，如果ThreadLocalMap中不存在当前ThreadLocal的Entry，则调用此方法来初始化一个值
     *
     * @return the initial value
     */
    private T setInitialValue() {
        //获取初始化value
        T value = initialValue();
        Thread t = Thread.currentThread();
        //获取当前线程维护的ThreadLocalMap对象
        ThreadLocalMap map = getMap(t);
        //map不为空则set，否则创建Map
        if (map != null)
            map.set(this, value);
        else
            createMap(t, value);
        //返回初始化的值
        return value;
    }

    /**
     * Sets the current thread's copy of this thread-local variable
     * to the specified value.  Most subclasses will have no need to
     * override this method, relying solely on the {@link #initialValue}
     * method to set the values of thread-locals.
     *
     * 将值set到ThreadLocalMap中，如果Map不存在，则初始化。
     *
     * @param value the value to be stored in the current thread's copy of this thread-local.
     */
    public void set(T value) {
        Thread t = Thread.currentThread();
        ThreadLocalMap map = getMap(t);
        if (map != null)
            map.set(this, value);
        else
            createMap(t, value);
    }

    /**
     * Removes the current thread's value for this thread-local
     * variable.  If this thread-local variable is subsequently
     * {@linkplain #get read} by the current thread, its value will be
     * reinitialized by invoking its {@link #initialValue} method,
     * unless its value is {@linkplain #set set} by the current thread
     * in the interim.  This may result in multiple invocations of the
     * {@code initialValue} method in the current thread.
     *
     * 将当前的ThreadLocal从Thread的threadLocals中移除
     *
     * @since 1.5
     */
     public void remove() {
         ThreadLocalMap m = getMap(Thread.currentThread());
         if (m != null)
             m.remove(this);
     }

    /**
     * Get the map associated with a ThreadLocal. Overridden in InheritableThreadLocal.
     *
     * 获取当前线程维护的threadLocals变量
     *
     * @param  t the current thread
     * @return the map
     */
    ThreadLocalMap getMap(Thread t) {
        return t.threadLocals;
    }

    /**
     * 创建线程的threadLocals变量，并将当前的ThreadLocal实例作为第一个key. Overridden in InheritableThreadLocal.
     *
     * @param t the current thread
     * @param firstValue value for the initial entry of the map
     */
    void createMap(Thread t, T firstValue) {
        //创建当前线程的threadLocals，并且首个key为当前调用此值的ThreadLocal，value为firstValue
        t.threadLocals = new ThreadLocalMap(this, firstValue);
    }

    /**
     * 创建继承的线程局部变量的映射的工厂方法。设计为仅从Thread构造函数调用。
     *
     * @param  parentMap the map associated with parent thread
     * @return a map containing the parent's inheritable bindings
     */
    static ThreadLocalMap createInheritedMap(ThreadLocalMap parentMap) {
        return new ThreadLocalMap(parentMap);
    }

    /**
     * Method childValue is visibly defined in subclass
     * InheritableThreadLocal, but is internally defined here for the
     * sake of providing createInheritedMap factory method without
     * needing to subclass the map class in InheritableThreadLocal.
     * This technique is preferable to the alternative of embedding
     * instanceof tests in methods.
     */
    T childValue(T parentValue) {
        throw new UnsupportedOperationException();
    }

    /**
     * ThreadLocal的扩展，从执行的Supplier获取初始值 {@code Supplier}.
     */
    static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {

        private final Supplier<? extends T> supplier;

        SuppliedThreadLocal(Supplier<? extends T> supplier) {
            this.supplier = Objects.requireNonNull(supplier);
        }

        @Override
        protected T initialValue() {
            return supplier.get();
        }
    }

    /**
     * ThreadLocalMap是一个自定义的哈希映射，仅适用于维护线程局部值。没有在ThreadLocal类之外导出任何操作。
     * 该类是包私有的，允许在Thread类中声明字段。为了处理非常大且长期存在的对象而使用，哈希表元素使用WeakReferences作为键。
     * 但是，由于未使用引用队列，因此仅在表开始空间不足时，才保证删除过时的元素。
     */
    static class ThreadLocalMap {

        /**
         * 该哈希表中的元素扩展WeakReference，使用其主引用字段作为键（始终是ThreadLocal对象）。
         * 请注意，空键（即entry.get（）== null）意味着不再引用该键，因此可以从表中删除元素。在下面的代码中，此类条目被称为“陈旧条目”。
         *
         * 这里为什么要使用WeakReference呢？主要是为了防止在代码中将ThreadLocal的强引用给删除前，忘记主动调用remove移除map中的变量而造成内存泄漏
         */
        static class Entry extends WeakReference<ThreadLocal<?>> {
            /** 与ThreadLocal关联的值 */
            Object value;

            Entry(ThreadLocal<?> k, Object v) {
                super(k);
                value = v;
            }
        }

        /**
         * Map桶的初始值 -- 必须是2的次幂.
         */
        private static final int INITIAL_CAPACITY = 16;

        /**
         * The table, resized as necessary.
         * table.length MUST always be a power of two.
         */
        private Entry[] table;

        /**
         * 当前table中维护的元素数量.
         */
        private int size = 0;

        /**
         * 下一个要调整大小的阈值.
         */
        private int threshold; // Default to 0

        /**
         * 当维护的元素数量到达table的2/3的时候将resize.
         */
        private void setThreshold(int len) {
            threshold = len * 2 / 3;
        }

        /**
         * 寻找下一个索引下标.
         */
        private static int nextIndex(int i, int len) {
            return ((i + 1 < len) ? i + 1 : 0);
        }

        /**
         * 寻找上一个索引下标.
         */
        private static int prevIndex(int i, int len) {
            return ((i - 1 >= 0) ? i - 1 : len - 1);
        }

        /**
         * 构造一个最初包含（firstKey，firstValue）的新Map。 ThreadLocalMaps是惰性构造的，因此只有在至少要放置一个条目时才创建
         */
        ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
            table = new Entry[INITIAL_CAPACITY];
            int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
            table[i] = new Entry(firstKey, firstValue);
            size = 1;
            setThreshold(INITIAL_CAPACITY);
        }

        /**
         * 构造一个新Map，其中包括给定父Map中的所有Inheritable ThreadLocals 。仅由createInheritedMap调用。
         *
         * @param parentMap the map associated with parent thread.
         */
        private ThreadLocalMap(ThreadLocalMap parentMap) {
            Entry[] parentTable = parentMap.table;
            int len = parentTable.length;
            setThreshold(len);
            table = new Entry[len];

            for (int j = 0; j < len; j++) {
                Entry e = parentTable[j];
                if (e != null) {
                    @SuppressWarnings("unchecked")
                    ThreadLocal<Object> key = (ThreadLocal<Object>) e.get();
                    if (key != null) {
                        Object value = key.childValue(e.value);
                        Entry c = new Entry(key, value);
                        int h = key.threadLocalHashCode & (len - 1);
                        while (table[h] != null)
                            h = nextIndex(h, len);
                        table[h] = c;
                        size++;
                    }
                }
            }
        }

        /**
         * 获取与键关联的元素。该方法本身仅处理快速路径：
         * 1.直接命中现有键。
         * 2.调用getEntryAfterMiss。
         *
         * @param  key ThreadLocal对象
         * @return 与键关联的元素；如果没有，则为null
         */
        private Entry getEntry(ThreadLocal<?> key) {
            // 计算下标
            int i = key.threadLocalHashCode & (table.length - 1);
            Entry e = table[i];
            if (e != null && e.get() == key)
                return e;
            else
                return getEntryAfterMiss(key, i, e);
        }

        /**
         * 当直接定位槽位中找不到信息则调用此方法(拉链法寻找)。
         *
         * @param  key the thread local object
         * @param  i the table index for key's hash code
         * @param  e the entry at table[i]
         * @return the entry associated with key, or null if no such
         */
        private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
            Entry[] tab = table;
            int len = tab.length;

            while (e != null) {
                ThreadLocal<?> k = e.get();
                // 找到了
                if (k == key)
                    return e;
                // 如果槽位的key已经被回收了，则执行探测式清理
                if (k == null)
                    expungeStaleEntry(i);
                else
                    // 否则定位到下一个槽位，直至遇到了null
                    i = nextIndex(i, len);
                e = tab[i];
            }
            return null;
        }

        /**
         * 设置Map的值.
         *
         * @param key the thread local object
         * @param value the value to be set
         */
        private void set(ThreadLocal<?> key, Object value) {

            // We don't use a fast path as with get() because it is at
            // least as common to use set() to create new entries as
            // it is to replace existing ones, in which case, a fast
            // path would fail more often than not.

            Entry[] tab = table;//map中维护元素的table
            int len = tab.length;//当前table的长度
            int i = key.threadLocalHashCode & (len-1);//通过ThreadLocal维护的HashCode来计算数组下标

            // 1. 情况1，待插入的下标，是空位置直接插入；
            // 2. 情况2，待插入的下标，不为空，key 相同，直接更新；
            // 3. 情况3，待插入的下标，不为空，key 不相同，拉链法寻址；
            // 4. 情况4，不为空，key 不相同，碰到过期key。其实情况4，遇到的是弱引用发生GC时，产生的情况。碰到这种情况，ThreadLocal 会进行探测清理过期key

            // Entry，是一个弱引用对象的实现类，在没有外部强引用下，会发生GC，删除key。
            // for循环判断元素是否存在，当前下标不存在元素时，直接设置元素 tab[i] = new Entry(key, value);
            for (Entry e = tab[i]; e != null; e = tab[i = nextIndex(i, len)]) {
                ThreadLocal<?> k = e.get();
                // 如果元素存在，则会判断是否key值相等 if (k == key)，相等则更新值
                if (k == key) {
                    e.value = value;
                    return;
                }
                // 如果槽位中的ThreadLocal被回收了，就调用 探测式清理过期元素 replaceStaleEntry (这里会定位i之前的一个槽位为null的索引 至 i 之间所有可被回收的元素)
                if (k == null) {
                    replaceStaleEntry(key, value, i);
                    return;
                }
            }

            // 将元素存放到指定的位置，并size+1
            tab[i] = new Entry(key, value);
            int sz = ++size;
            // 进行启发式清理，把过期元素清理掉，看空间是否还是大于等于扩容阈值
            // 判断sz >= threshold，其中 threshold = len * 2 / 3，也就是说数组中填充的元素，大于 len * 2 / 3，就需要扩容了
            if (!cleanSomeSlots(i, sz) && sz >= threshold)
                // rehash()，扩容重新计算元素位置
                rehash();
        }

        /**
         * 将指定的ThreadLocal变量移除
         */
        private void remove(ThreadLocal<?> key) {
            Entry[] tab = table;
            int len = tab.length;
            // 计算下标索引
            int i = key.threadLocalHashCode & (len-1);
            // 循环并通过拉链法找寻
            for (Entry e = tab[i]; e != null; e = tab[i = nextIndex(i, len)]) {
                // 找到对象，则执行clear以及启动探测式清理 expungeStaleEntry
                if (e.get() == key) {
                    e.clear();// 执行WeakReference的主动清空引用
                    expungeStaleEntry(i);// 启动探测式清理
                    return;
                }
            }
        }

        /**
         * 将设置操作中遇到的过期元素替换为指定键的元素。传入的值value参数存储在元素中，无论指定键的元素是否已存在。<br>
         * <br>
         * <b>副作用是，此方法删除了包含过期元素的“运行”中的所有过期元素。（运行时两个空槽之间的一系列数据）。</b>
         *
         * @param  key ThreadLocal
         * @param  value 与键关联的值
         * @param  staleSlot 搜索Entry时遇到的第一个过期元素的索引。
         */
        private void replaceStaleEntry(ThreadLocal<?> key, Object value, int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;
            Entry e;

            // Back up to check for prior stale entry in current run.
            // We clean out whole runs at a time to avoid continual
            // incremental rehashing due to garbage collector freeing
            // up refs in bunches (i.e., whenever the collector runs).
            // 这段代码就是找到staleSlot之前的一个null槽位的下标
            // 这里采用的是从当前的staleSlot 位置向前面遍历，i--
            // 这样的话是为了把前面所有的的已经被垃圾回收的也一起释放空间出来
            // （注意这里只是key 被回收，value还没被回收，entry更加没回收，所以需要让他们回收），
            // 同时也避免这样存在很多过期的对象的占用,导致这个时候刚好来了一个新的元素达到阀值而触发一次新的rehash
            int slotToExpunge = staleSlot;
            for (int i = prevIndex(staleSlot, len); (e = tab[i]) != null; i = prevIndex(i, len))
                // slotToExpunge 记录staleSlot左手边第一个空的entry 到staleSlot 之间key过期最小的index
                if (e.get() == null)
                    slotToExpunge = i;

            // Find either the key or trailing null slot of run, whichever
            // occurs first
            // 这个时候是从数组下标小的往下标大的方向遍历，i++，刚好跟上面相反。
            // 这两个遍历就是为了在左边遇到的第一个空的entry到右边遇到的第一空的 entry之间查询所有过期的对象。
            // 注意：在右边如果找到需要设置值的key相同的时候就开始清理，然后返回，不再继续遍历下去了
            for (int i = nextIndex(staleSlot, len); (e = tab[i]) != null; i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();

                // If we find key, then we need to swap it
                // with the stale entry to maintain hash table order.
                // The newly stale slot, or any other stale slot
                // encountered above it, can then be sent to expungeStaleEntry
                // to remove or rehash all of the other entries in run.
                // 说明之前已经存在相同的key,所以需要替换旧的值并且和前面那个过期的对象的进行交换位置(为了将指定key存放到正确位置上，否则下次插入可能会出现相同key在不同桶)
                if (k == key) {
                    e.value = value;

                    tab[i] = tab[staleSlot];
                    tab[staleSlot] = e;

                    // Start expunge at preceding stale entry if it exists
                    // 这里的意思就是前面的第一个for 循环(i--)往前查找的时候没有找到过期的，只有staleSlot这个过期，
                    // 由于前面过期的对象已经通过交换位置的方式放到index=i上了，所以需要清理的位置是i,而不是传过来的staleSlot
                    if (slotToExpunge == staleSlot)
                        slotToExpunge = i;
                    // 进行清理过期数据
                    cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
                    return;
                }

                // If we didn't find stale entry on backward scan, the
                // first stale entry seen while scanning for key is the
                // first still present in the run.
                // 如果我们在第一个for 循环(i--) 向前遍历的时候没有找到任何过期的对象
                // 那么我们需要把slotToExpunge 设置为向后遍历(i++) 的第一个过期对象的位置
                // 因为如果整个数组都没有找到要设置的key 的时候，该key会设置在该staleSlot的位置上
                // 如果数组中存在要设置的key,那么上面也会通过交换位置的时候把有效值移到staleSlot位置上
                // 综上所述，staleSlot位置上不管怎么样，存放的都是有效的值，所以不需要清理的
                if (k == null && slotToExpunge == staleSlot)
                    slotToExpunge = i;
            }

            // 如果key在数组中没有存在，那么直接新建一个新的放进去就可以
            tab[staleSlot].value = null;
            tab[staleSlot] = new Entry(key, value);

            // 如果有其他已经过期的对象，那么需要清理他
            if (slotToExpunge != staleSlot)
                cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
        }

        /**
         * 探测式清理，是以当前遇到的 GC 元素开始，向后不断的清理。直到遇到 null 为止，才停止 rehash。
         *
         * get() -> map.getEntry(this) -> getEntryAfterMiss(key, i, e) -> expungeStaleEntry(i) 等几个方法都用到了探测式清理
         *
         * @param staleSlot 当前为null的槽位（起始点）
         * @return 从staleSlot 到 下一个为null的槽位 (这之间的过期数据都将被清理掉).
         */
        private int expungeStaleEntry(int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;

            // 删除在staleSlot的元素
            tab[staleSlot].value = null;
            tab[staleSlot] = null;
            size--;

            // 重新计算hash，直到遇到null
            Entry e;
            int i;
            // 一直查找，直到遇到null为止
            for (i = nextIndex(staleSlot, len); (e = tab[i]) != null; i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();
                // 清空已经过期的元素
                if (k == null) {
                    e.value = null;
                    tab[i] = null;
                    size--;
                } else {
                    // 如果当前计算的下标索引与循环到的索引不一致，则需要重新计算
                    int h = k.threadLocalHashCode & (len - 1);
                    if (h != i) {
                        tab[i] = null;

                        // Unlike Knuth 6.4 Algorithm R, we must scan until
                        // null because multiple entries could have been stale.
                        // 一直查找为null的槽位
                        while (tab[h] != null)
                            h = nextIndex(h, len);
                        tab[h] = e;
                    }
                }
            }
            return i;
        }

        /**
         * 启发式扫描某些单元以查找陈旧的条目。
         * 当添加一个新元素，或删除了一个过时的元素时，将调用此方法。
         * 它执行对数扫描，作为无扫描（快速但保留垃圾）和与元素数量成比例的扫描数之间的平衡，这会发现所有垃圾，但会导致某些插入采用O（n）时间。
         *
         * @param i 已知生效的位置。扫描将从i之后的元素开始。
         *
         * @param n 扫描控制的数量: {@code log2(n)} cells are scanned,
         * unless a stale entry is found, in which case
         * {@code log2(table.length)-1} additional cells are scanned.
         * When called from insertions, this parameter is the number
         * of elements, but when from replaceStaleEntry, it is the
         * table length. (Note: all this could be changed to be either
         * more or less aggressive by weighting n instead of just
         * using straight log n. But this version is simple, fast, and
         * seems to work well.)
         *
         * @return 如果删除任何过时的元素，则返回true。
         */
        private boolean cleanSomeSlots(int i, int n) {
            boolean removed = false;
            Entry[] tab = table;
            int len = tab.length;
            do {
                i = nextIndex(i, len);
                Entry e = tab[i];
                if (e != null && e.get() == null) {
                    n = len;
                    removed = true;
                    // while 循环中不断的右移进行寻找需要被清理的过期元素，最终都会使用 expungeStaleEntry 进行处理，这里还包括元素的移位。
                    i = expungeStaleEntry(i);
                }
            } while ( (n >>>= 1) != 0);
            return removed;
        }

        /**
         * 重新包装调整table大小。首先扫描整个表，删除过期的元素。如果这还不足以缩小table的大小，则将table的大小加倍。
         */
        private void rehash() {
            // 探测式清理过期元素
            expungeStaleEntries();

            // 判断清理后是否满足扩容条件，size >= threshold * 3/4
            if (size >= threshold - threshold / 4)
                resize();
        }

        /**
         * 将table的容量添加一倍
         */
        private void resize() {
            // 1. 首先把数组长度扩容到原来的2倍，oldLen * 2，实例化新数组。
            // 2. 遍历for，所有的旧数组中的元素，重新放到新数组中。
            // 3. 在放置数组的过程中，如果发生哈希碰撞，则链式法顺延。
            // 4. 同时这还有检测key值的操作 if (k == null)，方便GC。

            Entry[] oldTab = table;
            int oldLen = oldTab.length;
            int newLen = oldLen * 2;
            Entry[] newTab = new Entry[newLen];
            int count = 0;

            // 遍历table数组
            for (int j = 0; j < oldLen; ++j) {
                Entry e = oldTab[j];
                // 如果当前槽位有值
                if (e != null) {
                    ThreadLocal<?> k = e.get();
                    //如果ThreadLocal已被回收，则顺便清理一下value值
                    if (k == null) {
                        e.value = null; // Help the GC
                    } else {
                        // 如果未被回收，则计算在新table中的槽位
                        int h = k.threadLocalHashCode & (newLen - 1);
                        // 拉链法找到能够被存放的索引槽位
                        while (newTab[h] != null)
                            h = nextIndex(h, newLen);
                        newTab[h] = e;
                        count++;
                    }
                }
            }

            // 设置新的扩容阈值，设置新的size，替换table变量
            setThreshold(newLen);
            size = count;
            table = newTab;
        }

        /**
         * 清除table中所有过期的元素
         */
        private void expungeStaleEntries() {
            Entry[] tab = table;
            int len = tab.length;
            for (int j = 0; j < len; j++) {
                Entry e = tab[j];
                if (e != null && e.get() == null)
                    expungeStaleEntry(j);
            }
        }
    }
}
