/*
 * Copyright (c) 1997, 2003, Oracle and/or its affiliates. All rights reserved.
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
 * Soft reference objects, which are cleared at the discretion of the garbage
 * collector in response to memory demand.  Soft references are most often used
 * to implement memory-sensitive caches.
 *
 * <p> Suppose that the garbage collector determines at a certain point in time
 * that an object is <a href="package-summary.html#reachability">softly
 * reachable</a>.  At that time it may choose to clear atomically all soft
 * references to that object and all soft references to any other
 * softly-reachable objects from which that object is reachable through a chain
 * of strong references.  At the same time or at some later time it will
 * enqueue those newly-cleared soft references that are registered with
 * reference queues.
 *
 * <p> All soft references to softly-reachable objects are guaranteed to have
 * been cleared before the virtual machine throws an
 * <code>OutOfMemoryError</code>.  Otherwise no constraints are placed upon the
 * time at which a soft reference will be cleared or the order in which a set
 * of such references to different objects will be cleared.  Virtual machine
 * implementations are, however, encouraged to bias against clearing
 * recently-created or recently-used soft references.
 *
 * <p> Direct instances of this class may be used to implement simple caches;
 * this class or derived subclasses may also be used in larger data structures
 * to implement more sophisticated caches.  As long as the referent of a soft
 * reference is strongly reachable, that is, is actually in use, the soft
 * reference will not be cleared.  Thus a sophisticated cache can, for example,
 * prevent its most recently used entries from being discarded by keeping
 * strong referents to those entries, leaving the remaining entries to be
 * discarded at the discretion of the garbage collector.<br>
 *
 * <pre>
 *   //在源码 hotspot/src/share/vm/memory/referenceProcessor.hpp 中会根据传入的 always_clear 来选择用什么策略清理软引用
 *   ReferencePolicy* setup_policy(bool always_clear) {
 *     _current_soft_ref_policy = always_clear ?
 *       _always_clear_soft_ref_policy : _default_soft_ref_policy;
 *     _current_soft_ref_policy->setup();   // snapshot the policy threshold
 *     return _current_soft_ref_policy;
 *   }
 * </pre>
 *
 * @author   Mark Reinhold
 * @since    1.2
 */
public class SoftReference<T> extends Reference<T> {

    /**
     * 时间戳时钟，由垃圾收集器更新（此戳的功能就是用于更新软引用的timestamp，用于软引用对象的referent置空）<br>
     * 在JVM源码 hotspot/src/share/vm/memory/referenceProcessor.cpp 初始化的时候会调用<br>
     * java_lang_ref_SoftReference::set_clock(_soft_ref_timestamp_clock); 来更新此戳（此戳单位是毫秒）<br>
     * process_discovered_references 会执行软引用的查找，并将到期的软引用的 referent 置空<br>
     * 影响软引用的回收策略未 JVM参数 SoftRefLRUPolicyMSPerMB ，默认值1000，字面意思是 每MB的软引用存活时间为1秒，
     * 当 SoftRefLRUPolicyMSPerMB = 0的时候，则软引用经历一次GC即被回收。<br>
     * <br>
     * 回收策略有两种，代码在 hotspot/src/share/vm/memory/referencePolicy.cpp ：<br>
     * <pre>
     * LRUCurrentHeapPolicy 和 LRUMaxHeapPolicy策略
     *
     * _default_soft_ref_policy      = new COMPILER2_PRESENT(LRUMaxHeapPolicy())  这个是Server端策略
     *                                  NOT_COMPILER2(LRUCurrentHeapPolicy());     这个是Client端策略
     *
     * LRUCurrentHeapPolicy :
     *     上次GC后空闲堆内存或者说当前可用的堆内存 / M * SoftRefLRUPolicyMSPerMB 最后算出软引用的最终存活时间
     * LRUMaxHeapPolicy策略 :
     *     (最大堆内存 - 上次GC后已使用的堆内存) / M * SoftRefLRUPolicyMSPerMB   最后算出软引用的最终存活时间
     *
     * 按照策略来看最终Server端会让软引用多存活一点时间。
     *
     * </pre>
     */
    static private long clock;

    /**
     * Timestamp updated by each invocation of the get method.  The VM may use
     * this field when selecting soft references to be cleared, but it is not
     * required to do so.
     *
     * 每次调用 get 方法都会更新时间戳。在选择要清除的软引用时，VM可以使用此字段，但不需要执行此操作
     */
    private long timestamp;

    /**
     * Creates a new soft reference that refers to the given object.  The new
     * reference is not registered with any queue.
     *
     * @param referent object the new soft reference will refer to
     */
    public SoftReference(T referent) {
        super(referent);
        this.timestamp = clock;
    }

    /**
     * Creates a new soft reference that refers to the given object and is
     * registered with the given queue.
     *
     * @param referent object the new soft reference will refer to
     * @param q the queue with which the reference is to be registered,
     *          or <tt>null</tt> if registration is not required
     *
     */
    public SoftReference(T referent, ReferenceQueue<? super T> q) {
        super(referent, q);
        this.timestamp = clock;
    }

    /**
     * Returns this reference object's referent.  If this reference object has
     * been cleared, either by the program or by the garbage collector, then
     * this method returns <code>null</code>.
     *
     * @return   The object to which this reference refers, or
     *           <code>null</code> if this reference object has been cleared
     */
    public T get() {
        T o = super.get();
        if (o != null && this.timestamp != clock)
            this.timestamp = clock;
        return o;
    }

}
