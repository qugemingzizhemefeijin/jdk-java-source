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
 * Private implementation class for EnumSet, for "jumbo" enum types
 * (i.e., those with more than 64 elements).
 *
 * <br><br>
 * <p>
 * JumboEnumSet适用于枚举值个数大于64个的枚举类，
 * 其底层实现跟RegularEnumSet一样都是根据位是否为1来判断该枚举值是否添加到了Set中，
 * 不过因为枚举值个数大于64个，无法用64位的long类型来记录所有的枚举值，
 * 所以将RegularEnumSet中long类型的elements改成了一个long类型数组，
 * 添加某个枚举值时，先将某个枚举值的ordinal属性除以64，算出该枚举值所属的elements数组索引，
 * 再用ordinal属性对64求余，将对应的位标识位1。
 *
 * @author Josh Bloch
 * @since 1.5
 * @serial exclude
 */
class JumboEnumSet<E extends Enum<E>> extends EnumSet<E> {
    private static final long serialVersionUID = 334349849919042784L;

    /**
     * Bit vector representation of this set.  The ith bit of the jth
     * element of this array represents the  presence of universe[64*j +i]
     * in this set.
     */
    private long elements[];

    // Redundant - maintained for performance
    private int size = 0;

    JumboEnumSet(Class<E>elementType, Enum<?>[] universe) {
        super(elementType, universe);
        // 加上63的目的是为了对64整除后算出来的值能包含原来的
        // 以70为例，64整除的结果是1，1*64 小于原来的70，如果加上63，则整除的结果是2，大于原来的70
        elements = new long[(universe.length + 63) >>> 6];
    }

    void addRange(E from, E to) {
        int fromIndex = from.ordinal() >>> 6;
        int toIndex = to.ordinal() >>> 6;

        // 如果起始枚举值位于同一个数组元素中
        if (fromIndex == toIndex) {
            elements[fromIndex] = (-1L >>>  (from.ordinal() - to.ordinal() - 1))
                            << from.ordinal();
        } else {
            // 起始枚举值不在同一个数组元素中
            // 此处是左移，将低于from的位都置为0
            elements[fromIndex] = (-1L << from.ordinal());
            // 将fromIndex + 1到toIndex-1之间的数组元素都置为-1，所有位变成1
            for (int i = fromIndex + 1; i < toIndex; i++)
                elements[i] = -1;
            // 将to之前的低位都置为1
            elements[toIndex] = -1L >>> (63 - to.ordinal());
        }
        // 注意调用addRange时，JumboEnumSet是空的，所以此处直接赋值size
        size = to.ordinal() - from.ordinal() + 1;
    }

    void addAll() {
        // 遍历所有的数组元素，将值置为-1，-1时所有位都是1
        for (int i = 0; i < elements.length; i++)
            elements[i] = -1;
        // 最后一个数组元素不是所有位都是1的，只有universe.length对64求余的结果对应的位置为1，所以需要右移将其他的位置为0
        // 以70为例，对64求余的结果就是6，-70的补码的后6位是111010，即58，64个1右移58后还剩6个1
        elements[elements.length - 1] >>>= -universe.length;
        size = universe.length;
    }

    void complement() {
        // 遍历所有数组元素，取非，原来为0的位变成1，原来为1的位变成0
        for (int i = 0; i < elements.length; i++)
            elements[i] = ~elements[i];
        // 处理最后一个数组元素，将多余的位都置为0
        elements[elements.length - 1] &= (-1L >>> -universe.length);
        // 总长度减去原来的长度
        size = universe.length - size;
    }

    /**
     * Returns an iterator over the elements contained in this set.  The
     * iterator traverses the elements in their <i>natural order</i> (which is
     * the order in which the enum constants are declared). The returned
     * Iterator is a "weakly consistent" iterator that will never throw {@link
     * ConcurrentModificationException}.
     *
     * @return an iterator over the elements contained in this set
     */
    public Iterator<E> iterator() {
        return new EnumSetIterator<>();
    }

    private class EnumSetIterator<E extends Enum<E>> implements Iterator<E> {
        /**
         * A bit vector representing the elements in the current "word"
         * of the set not yet returned by this iterator.
         *
         * 当前遍历的elements元素
         *
         */
        long unseen;

        /**
         * The index corresponding to unseen in the elements array.
         *
         * 当前遍历的elements数组索引
         *
         */
        int unseenIndex = 0;

        /**
         * The bit representing the last element returned by this iterator
         * but not removed, or zero if no such element exists.
         *
         * 上一次返回的为1的位
         *
         */
        long lastReturned = 0;

        /**
         * The index corresponding to lastReturned in the elements array.
         *
         * 元素数组中 lastReturned 对应的索引。
         *
         */
        int lastReturnedIndex = 0;

        EnumSetIterator() {
            unseen = elements[0];
        }

        @Override
        public boolean hasNext() {
            // 遍历找到下一个数组元素不为0的数组元素
            while (unseen == 0 && unseenIndex < elements.length - 1)
                unseen = elements[++unseenIndex];
            return unseen != 0;
        }

        @Override
        @SuppressWarnings("unchecked")
        public E next() {
            if (!hasNext())
                throw new NoSuchElementException();
            // 逻辑同RegularEnumSet
            lastReturned = unseen & -unseen;
            lastReturnedIndex = unseenIndex;
            unseen -= lastReturned;
            return (E) universe[(lastReturnedIndex << 6)
                                + Long.numberOfTrailingZeros(lastReturned)];
        }

        @Override
        public void remove() {
            if (lastReturned == 0)
                throw new IllegalStateException();
            final long oldElements = elements[lastReturnedIndex];
            // 对应的位置为0
            elements[lastReturnedIndex] &= ~lastReturned;
            if (oldElements != elements[lastReturnedIndex]) {
                size--;
            }
            lastReturned = 0;
        }
    }

    /**
     * Returns the number of elements in this set.
     *
     * @return the number of elements in this set
     */
    public int size() {
        return size;
    }

    /**
     * Returns <tt>true</tt> if this set contains no elements.
     *
     * @return <tt>true</tt> if this set contains no elements
     */
    public boolean isEmpty() {
        return size == 0;
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

        int eOrdinal = ((Enum<?>)e).ordinal();
        // 判断对应数组元素的对应位是否为0，如果是则说明不存在，返回false，否则返回true
        return (elements[eOrdinal >>> 6] & (1L << eOrdinal)) != 0;
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
        // 检查e的枚举类型是否合法
        typeCheck(e);

        int eOrdinal = e.ordinal();
        // 左移6位就是除以64，算出该枚举所属的elements数组索引
        int eWordNum = eOrdinal >>> 6;
        // 获取原来的值
        long oldElements = elements[eWordNum];
        // eOrdinal的值是大于64的，此处右移，实际移动的位数是eOrdinal对64求余的结果
        // 比如eOrdinal的值是68,1L<<68的结果就是1<<4,10000
        elements[eWordNum] |= (1L << eOrdinal);
        // 判断原elements数组索引的元素是否发生改变，如果改变则说明添加的枚举值原来不存在
        boolean result = (elements[eWordNum] != oldElements);
        if (result) // 为true说明添加了一个新元素，size加1
            size++;
        return result;
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
        if (eClass != elementType && eClass.getSuperclass() != elementType)
            return false; // 元素类型不符，返回false
        // 对64整除，算出所属的数组索引
        int eOrdinal = ((Enum<?>)e).ordinal();
        int eWordNum = eOrdinal >>> 6;
        // 将对应的数组元素的对应位置为0
        long oldElements = elements[eWordNum];
        elements[eWordNum] &= ~(1L << eOrdinal);
        boolean result = (elements[eWordNum] != oldElements);
        if (result) // 如果改变说明原来是有这个枚举值的，size减1
            size--;
        return result;
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
        if (!(c instanceof JumboEnumSet))
            // 调用父类containsAll，遍历c，如果有一个元素不在当前Set中，则返回false
            return super.containsAll(c);

        JumboEnumSet<?> es = (JumboEnumSet<?>)c;
        if (es.elementType != elementType) // 元素类型不符
            return es.isEmpty();

        for (int i = 0; i < elements.length; i++)
            // elements[i]先求非，原来为1的位变成0，再求且，如果结果不为0，说明某个元素在
            // elements[i]中不存在，在es.elements[i]中存在，即c中某个元素不在当前Set中，返回false
            if ((es.elements[i] & ~elements[i]) != 0)
                return false;
        return true;
    }

    /**
     * Adds all of the elements in the specified collection to this set.
     *
     * @param c collection whose elements are to be added to this set
     * @return <tt>true</tt> if this set changed as a result of the call
     * @throws NullPointerException if the specified collection or any of
     *     its elements are null
     */
    public boolean addAll(Collection<? extends E> c) {
        if (!(c instanceof JumboEnumSet))
            return super.addAll(c);

        JumboEnumSet<?> es = (JumboEnumSet<?>)c;
        if (es.elementType != elementType) {
            if (es.isEmpty())
                return false;
            else
                throw new ClassCastException(
                    es.elementType + " != " + elementType);
        }

        for (int i = 0; i < elements.length; i++)
            elements[i] |= es.elements[i];
        return recalculateSize();
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
        if (!(c instanceof JumboEnumSet))
            // 调用父类removeAll，底层是遍历c，调用remove方法
            return super.removeAll(c);

        JumboEnumSet<?> es = (JumboEnumSet<?>)c;
        if (es.elementType != elementType)
            return false; // 元素类型不符，返回false

        // 遍历当前Set，先对es.elements[i]求非，将原来为1的位变成0，再和elements[i]求且，
        // 实现将位于es.elements[i]中的元素都从elements[i]中移除了
        for (int i = 0; i < elements.length; i++)
            elements[i] &= ~es.elements[i];
        // 重新计算size，返回size是否改变
        return recalculateSize();
    }

    /**
     * Retains only the elements in this set that are contained in the
     * specified collection.
     *
     * @param c elements to be retained in this set
     * @return <tt>true</tt> if this set changed as a result of the call
     * @throws NullPointerException if the specified collection is null
     */
    public boolean retainAll(Collection<?> c) {
        if (!(c instanceof JumboEnumSet))
            // 调用父类retainAll，底层是遍历当前Set，如果不在c中则将其移除
            return super.retainAll(c);

        JumboEnumSet<?> es = (JumboEnumSet<?>)c;
        if (es.elementType != elementType) {
            // 元素类型不一致，将当前Set清空
            boolean changed = (size != 0);
            clear();
            return changed;
        }

        // 遍历当前Set，两者的数组元素求且，不在es.elements[i]中的位就会被置为0，实现元素移除的效果
        for (int i = 0; i < elements.length; i++)
            elements[i] &= es.elements[i];
        // 重新计算size，返回size是否改变
        return recalculateSize();
    }

    /**
     * Removes all of the elements from this set.
     */
    public void clear() {
        // 所有元素都置为0
        Arrays.fill(elements, 0);
        size = 0;
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
        if (!(o instanceof JumboEnumSet))
            return super.equals(o);

        JumboEnumSet<?> es = (JumboEnumSet<?>)o;
        if (es.elementType != elementType)
            return size == 0 && es.size == 0;

        return Arrays.equals(es.elements, elements);
    }

    /**
     * Recalculates the size of the set.  Returns true if it's changed.
     */
    // 重新计算size，并返回size是否发生改变
    private boolean recalculateSize() {
        int oldSize = size;
        size = 0;
        for (long elt : elements)
            // 遍历elements，累加位为1的总数
            size += Long.bitCount(elt);
        // 如果size发生改变，说明有删除元素了，返回true
        return size != oldSize;
    }

    public EnumSet<E> clone() {
        JumboEnumSet<E> result = (JumboEnumSet<E>) super.clone();
        result.elements = result.elements.clone();
        return result;
    }
}
