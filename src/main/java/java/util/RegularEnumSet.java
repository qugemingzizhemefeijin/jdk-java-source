/*
 * Copyright (c) 2003, 2012, Oracle and/or its affiliates. All rights reserved.
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

package java.util;

/**
 * Private implementation class for EnumSet, for "regular sized" enum types
 * (i.e., those with 64 or fewer enum constants).
 * <br><br>
 * <p>
 * RegularEnumSet适用于枚举值个数小于等于64的EnumSet，
 * RegularEnumSet定义了一个私有的long类型变量elements，
 * long类型一共64位，如果某一位为1则表示该位对应的值对应的枚举值已经添加到RegularEnumSet中了
 *
 * @author Josh Bloch
 * @since 1.5
 * @serial exclude
 */
class RegularEnumSet<E extends Enum<E>> extends EnumSet<E> {
    private static final long serialVersionUID = 3411599620347842686L;
    /**
     * Bit vector representation of this set.  The 2^k bit indicates the
     * presence of universe[k] in this set.
     */
    private long elements = 0L;

    RegularEnumSet(Class<E>elementType, Enum<?>[] universe) {
        super(elementType, universe);
    }

    void addRange(E from, E to) {
        // 将指定范围内的位标记为1，注意此处from.ordinal() - to.ordinal()是一个负值，再减1是为了将to也包含进来
        elements = (-1L >>>  (from.ordinal() - to.ordinal() - 1)) << from.ordinal();
    }

    void addAll() {
        if (universe.length != 0) // 将所有枚举值对应的位都标识为1
            // -1实际就是64个1，注意此处是右移一个负值，实际是右移该负值的补码的后6位对应的位数，因为超过6位就大于64了
            // 以universe.length为8为例，-8的补码的后6位是111000，该值为56,64个1右移56位的结果就是8个1
            elements = -1L >>> -universe.length;
    }

    void complement() {
        if (universe.length != 0) {
            // 取非，原来为1的变成0了，原来为0的变成1了
            elements = ~elements;
            // 将universe范围以外的1都置为0
            elements &= -1L >>> -universe.length;  // Mask unused bits
        }
    }

    /**
     * Returns an iterator over the elements contained in this set.  The
     * iterator traverses the elements in their <i>natural order</i> (which is
     * the order in which the enum constants are declared). The returned
     * Iterator is a "snapshot" iterator that will never throw {@link
     * ConcurrentModificationException}; the elements are traversed as they
     * existed when this call was invoked.
     *
     * @return an iterator over the elements contained in this set
     */
    public Iterator<E> iterator() {
        return new EnumSetIterator<>();
    }

    private class EnumSetIterator<E extends Enum<E>> implements Iterator<E> {
        /**
         * A bit vector representing the elements in the set not yet
         * returned by this iterator.
         *
         * 当前elements
         *
         */
        long unseen;

        /**
         * The bit representing the last element returned by this iterator
         * but not removed, or zero if no such element exists.
         *
         * 上一次返回的枚举值对应位为1的值，比如上一次返回的枚举值的ordinal为4，则lastReturned的后8位为00010000
         *
         */
        long lastReturned = 0;

        EnumSetIterator() {
            unseen = elements;
        }

        public boolean hasNext() {
            return unseen != 0;
        }

        @SuppressWarnings("unchecked")
        public E next() {
            if (unseen == 0)
                throw new NoSuchElementException();
            // unseen是奇数时，求且为1，是偶数时，求且是一个2的整数次幂
            lastReturned = unseen & -unseen;
            // 遍历一个位对应的枚举值，就通过减法，将对应位置为0
            unseen -= lastReturned;
            return (E) universe[Long.numberOfTrailingZeros(lastReturned)];
        }

        public void remove() {
            if (lastReturned == 0)
                throw new IllegalStateException();
            // 将对应的位置为0
            elements &= ~lastReturned;
            lastReturned = 0;
        }
    }

    /**
     * Returns the number of elements in this set.
     *
     * @return the number of elements in this set
     */
    public int size() {
        // 统计位为1的位的总数，比如bitCount(8)返回1,bitCount(3)返回2
        return Long.bitCount(elements);
    }

    /**
     * Returns <tt>true</tt> if this set contains no elements.
     *
     * @return <tt>true</tt> if this set contains no elements
     */
    public boolean isEmpty() {
        return elements == 0;
    }

    /**
     * Returns <tt>true</tt> if this set contains the specified element.
     *
     * @param e element to be checked for containment in this collection
     * @return <tt>true</tt> if this set contains the specified element
     */
    public boolean contains(Object e) {
        if (e == null)
            return false;
        Class<?> eClass = e.getClass();
        if (eClass != elementType && eClass.getSuperclass() != elementType)
            return false; // 枚举类型不符，返回false
        // 判断ordinal对应的位是否为0，为0表示不存在
        return (elements & (1L << ((Enum<?>)e).ordinal())) != 0;
    }

    // Modification Operations

    /**
     * Adds the specified element to this set if it is not already present.
     *
     * @param e element to be added to this set
     * @return <tt>true</tt> if the set changed as a result of the call
     *
     * @throws NullPointerException if <tt>e</tt> is null
     */
    public boolean add(E e) {
        // 检查e的枚举类型是否指定的类型
        typeCheck(e);
        // 获取原来的elements值
        long oldElements = elements;
        // 将ordinal对应的位标识为1，表示添加了对应元素
        elements |= (1L << ((Enum<?>)e).ordinal());
        // 如果不等说明原来不存在e，返回true
        return elements != oldElements;
    }

    /**
     * Removes the specified element from this set if it is present.
     *
     * @param e element to be removed from this set, if present
     * @return <tt>true</tt> if the set contained the specified element
     */
    public boolean remove(Object e) {
        if (e == null)
            return false;
        Class<?> eClass = e.getClass();
        // 不是指定枚举类，返回false
        if (eClass != elementType && eClass.getSuperclass() != elementType)
            return false;

        long oldElements = elements;
        // 将ordinal对应的位置为0
        elements &= ~(1L << ((Enum<?>)e).ordinal());
        // 如果不等于说明该元素原来是存在的，返回true
        return elements != oldElements;
    }

    // Bulk Operations

    /**
     * Returns <tt>true</tt> if this set contains all of the elements
     * in the specified collection.
     *
     * @param c collection to be checked for containment in this set
     * @return <tt>true</tt> if this set contains all of the elements
     *        in the specified collection
     * @throws NullPointerException if the specified collection is null
     */
    public boolean containsAll(Collection<?> c) {
        if (!(c instanceof RegularEnumSet))
            // 调用父类的containsAll，遍历c中的元素，如果有一个在当前Set中不存在则返回false
            return super.containsAll(c);

        RegularEnumSet<?> es = (RegularEnumSet<?>)c;
        if (es.elementType != elementType) // 枚举类型不符，如果c是空的则返回true
            return es.isEmpty();
        // elements求反后跟 es.elements 求且，如果结果为0，说明c中的元素都在当前Set中，返回true
        return (es.elements & ~elements) == 0;
    }

    /**
     * Adds all of the elements in the specified collection to this set.
     *
     * @param c collection whose elements are to be added to this set
     * @return <tt>true</tt> if this set changed as a result of the call
     * @throws NullPointerException if the specified collection or any
     *     of its elements are null
     */
    public boolean addAll(Collection<? extends E> c) {
        if (!(c instanceof RegularEnumSet))
            // 如果不是RegularEnumSet类型，则通过父类的addAll方法添加，底层是遍历c中的元素，循环调用add方法
            return super.addAll(c);

        // 如果是RegularEnumSet类型
        RegularEnumSet<?> es = (RegularEnumSet<?>)c;
        if (es.elementType != elementType) {
            if (es.isEmpty())
                return false;
            else
                // 枚举类型不符，抛出异常
                throw new ClassCastException(
                    es.elementType + " != " + elementType);
        }

        long oldElements = elements;
        // 直接或运算，有一个为1则结果为1
        elements |= es.elements;
        // 两者不等说明有添加新的元素了
        return elements != oldElements;
    }

    /**
     * Removes from this set all of its elements that are contained in
     * the specified collection.
     *
     * @param c elements to be removed from this set
     * @return <tt>true</tt> if this set changed as a result of the call
     * @throws NullPointerException if the specified collection is null
     */
    public boolean removeAll(Collection<?> c) {
        if (!(c instanceof RegularEnumSet))
            // 调用父类removeAll，底层遍历c，循环调用remove方法
            return super.removeAll(c);

        RegularEnumSet<?> es = (RegularEnumSet<?>)c;
        if (es.elementType != elementType)
            return false; // 枚举类型不符

        long oldElements = elements;
        // 先对es.elements求非，原来的1变成0，再求且，就会将elements中同样是1的位置为0了
        elements &= ~es.elements;
        // 如果不等于说明有元素被删除了
        return elements != oldElements;
    }

    /**
     * Retains only the elements in this set that are contained in the
     * specified collection.
     *
     * @param c elements to be retained in this set
     * @return <tt>true</tt> if this set changed as a result of the call
     * @throws NullPointerException if the specified collection is null
     */
    // retainAll求两者的交集，将不在c中的元素移除，返回true表示有元素被移除了
    public boolean retainAll(Collection<?> c) {
        if (!(c instanceof RegularEnumSet))
            // 调用父类retainAll，遍历当前集合Set，如果不在c中则移除
            return super.retainAll(c);

        RegularEnumSet<?> es = (RegularEnumSet<?>)c;
        if (es.elementType != elementType) {
            // 枚举类型不符，将当前元素全部清空
            // 不等于0说明原来的RegularEnumSet非空，此处清除了，发生修改了，返回true
            boolean changed = (elements != 0);
            elements = 0;
            return changed;
        }

        // 枚举类型一致
        long oldElements = elements;
        // 两者求且，如果某个元素在elements存在而在es.elements中不存在则将elements中对应位置为0，实现删除的效果
        elements &= es.elements;
        return elements != oldElements;
    }

    /**
     * Removes all of the elements from this set.
     */
    public void clear() {
        // 置为0，所有为1的位都置为0了
        elements = 0;
    }

    /**
     * Compares the specified object with this set for equality.  Returns
     * <tt>true</tt> if the given object is also a set, the two sets have
     * the same size, and every member of the given set is contained in
     * this set.
     *
     * @param o object to be compared for equality with this set
     * @return <tt>true</tt> if the specified object is equal to this set
     */
    public boolean equals(Object o) {
        if (!(o instanceof RegularEnumSet))
            return super.equals(o);

        RegularEnumSet<?> es = (RegularEnumSet<?>)o;
        if (es.elementType != elementType)
            return elements == 0 && es.elements == 0;
        return es.elements == elements;
    }
}
