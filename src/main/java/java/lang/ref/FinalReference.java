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
 * Final references, used to implement finalization<br>
 * FinalReference继承Reference类，对象最终会被封装为Finalizer对象，Finalizer的构造方法是不对外暴漏，所以我们无法自己创建Finalizer对象，FinalReference是由jvm自动封装。<br>
 * <br>
 * <pre>
 * 什么样的类才能被封装为Finalizer：
 *    当前类或其父类含有一个参数为空，返回值为void，名为finalize的方法；
 *    这个finalize方法体不能为空；
 * 满足以上条件的类称之为Final类，实际就是Finalizer子类。
 *
 * JVM通过其Finalizer子类的创建出对象
 * static void register(Objectfinalizee) {
 *       new Finalizer(finalizee);
 * }
 *
 * 那么jvm又是在何时调用register方法的呢？
 * 取决于-XX:+RegisterFinalizersAtInit这个参数，默认为true，在调用构造函数返回之前调用Finalizer.register方法（先分配空间，再调用构造函数）
 * 如果通过-XX:-RegisterFinalizersAtInit关闭了该参数，那将在对象空间分配好之后就将这个对象注册进去。
 *
 *
 * RegisterFinalizersAtInit=true时，详细请见：c1_GraphBuilder.cpp
 *
 * void GraphBuilder::method_return(Value x) {
 *  ...
 *  if (RegisterFinalizersAtInit &&
 *      method()->intrinsic_id() == vmIntrinsics::_Object_init) {
 *      call_register_finalizer();
 *  }
 *  ...
 * }
 *
 * RegisterFinalizersAtInit=false时，详细请见：instanceKlass.cpp
 *
 * instanceOop InstanceKlass::allocate_instance(TRAPS) {
 *   bool has_finalizer_flag = has_finalizer(); // Query before possible GC
 *   int size = size_helper();  // Query before forming handle.
 *
 *   KlassHandle h_k(THREAD, this);
 *
 *   instanceOop i;
 *
 *   i = (instanceOop)CollectedHeap::obj_allocate(h_k, size, CHECK_NULL);
 *   if (has_finalizer_flag && !RegisterFinalizersAtInit) {
 *     i = register_finalizer(i, CHECK_NULL);
 *   }
 *   return i;
 * }
 *
 * </pre>
 *
 * @see java.lang.ref.Finalizer
 */
class FinalReference<T> extends Reference<T> {

    public FinalReference(T referent, ReferenceQueue<? super T> q) {
        super(referent, q);
    }

}
