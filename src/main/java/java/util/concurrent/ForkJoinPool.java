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

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.security.AccessControlContext;
import java.security.ProtectionDomain;
import java.security.Permissions;

/**
 * An {@link ExecutorService} for running {@link ForkJoinTask}s.
 * A {@code ForkJoinPool} provides the entry point for submissions
 * from non-{@code ForkJoinTask} clients, as well as management and
 * monitoring operations.
 *
 * <p>A {@code ForkJoinPool} differs from other kinds of {@link
 * ExecutorService} mainly by virtue of employing
 * <em>work-stealing</em>: all threads in the pool attempt to find and
 * execute tasks submitted to the pool and/or created by other active
 * tasks (eventually blocking waiting for work if none exist). This
 * enables efficient processing when most tasks spawn other subtasks
 * (as do most {@code ForkJoinTask}s), as well as when many small
 * tasks are submitted to the pool from external clients.  Especially
 * when setting <em>asyncMode</em> to true in constructors, {@code
 * ForkJoinPool}s may also be appropriate for use with event-style
 * tasks that are never joined.
 *
 * <p>A static {@link #commonPool()} is available and appropriate for
 * most applications. The common pool is used by any ForkJoinTask that
 * is not explicitly submitted to a specified pool. Using the common
 * pool normally reduces resource usage (its threads are slowly
 * reclaimed during periods of non-use, and reinstated upon subsequent
 * use).
 *
 * <p>For applications that require separate or custom pools, a {@code
 * ForkJoinPool} may be constructed with a given target parallelism
 * level; by default, equal to the number of available processors.
 * The pool attempts to maintain enough active (or available) threads
 * by dynamically adding, suspending, or resuming internal worker
 * threads, even if some tasks are stalled waiting to join others.
 * However, no such adjustments are guaranteed in the face of blocked
 * I/O or other unmanaged synchronization. The nested {@link
 * ManagedBlocker} interface enables extension of the kinds of
 * synchronization accommodated.
 *
 * <p>In addition to execution and lifecycle control methods, this
 * class provides status check methods (for example
 * {@link #getStealCount}) that are intended to aid in developing,
 * tuning, and monitoring fork/join applications. Also, method
 * {@link #toString} returns indications of pool state in a
 * convenient form for informal monitoring.
 *
 * <p>As is the case with other ExecutorServices, there are three
 * main task execution methods summarized in the following table.
 * These are designed to be used primarily by clients not already
 * engaged in fork/join computations in the current pool.  The main
 * forms of these methods accept instances of {@code ForkJoinTask},
 * but overloaded forms also allow mixed execution of plain {@code
 * Runnable}- or {@code Callable}- based activities as well.  However,
 * tasks that are already executing in a pool should normally instead
 * use the within-computation forms listed in the table unless using
 * async event-style tasks that are not usually joined, in which case
 * there is little difference among choice of methods.
 *
 * <table BORDER CELLPADDING=3 CELLSPACING=1>
 * <caption>Summary of task execution methods</caption>
 *  <tr>
 *    <td></td>
 *    <td ALIGN=CENTER> <b>Call from non-fork/join clients</b></td>
 *    <td ALIGN=CENTER> <b>Call from within fork/join computations</b></td>
 *  </tr>
 *  <tr>
 *    <td> <b>Arrange async execution</b></td>
 *    <td> {@link #execute(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#fork}</td>
 *  </tr>
 *  <tr>
 *    <td> <b>Await and obtain result</b></td>
 *    <td> {@link #invoke(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#invoke}</td>
 *  </tr>
 *  <tr>
 *    <td> <b>Arrange exec and obtain Future</b></td>
 *    <td> {@link #submit(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#fork} (ForkJoinTasks <em>are</em> Futures)</td>
 *  </tr>
 * </table>
 *
 * <p>The common pool is by default constructed with default
 * parameters, but these may be controlled by setting three
 * {@linkplain System#getProperty system properties}:
 * <ul>
 * <li>{@code java.util.concurrent.ForkJoinPool.common.parallelism}
 * - the parallelism level, a non-negative integer
 * <li>{@code java.util.concurrent.ForkJoinPool.common.threadFactory}
 * - the class name of a {@link ForkJoinWorkerThreadFactory}
 * <li>{@code java.util.concurrent.ForkJoinPool.common.exceptionHandler}
 * - the class name of a {@link UncaughtExceptionHandler}
 * </ul>
 * If a {@link SecurityManager} is present and no factory is
 * specified, then the default pool uses a factory supplying
 * threads that have no {@link Permissions} enabled.
 * The system class loader is used to load these classes.
 * Upon any error in establishing these settings, default parameters
 * are used. It is possible to disable or limit the use of threads in
 * the common pool by setting the parallelism property to zero, and/or
 * using a factory that may return {@code null}. However doing so may
 * cause unjoined tasks to never be executed.
 *
 * <p><b>Implementation notes</b>: This implementation restricts the
 * maximum number of running threads to 32767. Attempts to create
 * pools with greater than the maximum number result in
 * {@code IllegalArgumentException}.
 *
 * <p>This implementation rejects submitted tasks (that is, by throwing
 * {@link RejectedExecutionException}) only when the pool is shut down
 * or internal resources have been exhausted.
 *
 * @since 1.7
 * @author Doug Lea
 */
@sun.misc.Contended
public class ForkJoinPool extends AbstractExecutorService {

    /*
     * Implementation Overview
     *
     * This class and its nested classes provide the main
     * functionality and control for a set of worker threads:
     * Submissions from non-FJ threads enter into submission queues.
     * Workers take these tasks and typically split them into subtasks
     * that may be stolen by other workers.  Preference rules give
     * first priority to processing tasks from their own queues (LIFO
     * or FIFO, depending on mode), then to randomized FIFO steals of
     * tasks in other queues.  This framework began as vehicle for
     * supporting tree-structured parallelism using work-stealing.
     * Over time, its scalability advantages led to extensions and
     * changes to better support more diverse usage contexts.  Because
     * most internal methods and nested classes are interrelated,
     * their main rationale and descriptions are presented here;
     * individual methods and nested classes contain only brief
     * comments about details.
     *
     * WorkQueues
     * ==========
     *
     * Most operations occur within work-stealing queues (in nested
     * class WorkQueue).  These are special forms of Deques that
     * support only three of the four possible end-operations -- push,
     * pop, and poll (aka steal), under the further constraints that
     * push and pop are called only from the owning thread (or, as
     * extended here, under a lock), while poll may be called from
     * other threads.  (If you are unfamiliar with them, you probably
     * want to read Herlihy and Shavit's book "The Art of
     * Multiprocessor programming", chapter 16 describing these in
     * more detail before proceeding.)  The main work-stealing queue
     * design is roughly similar to those in the papers "Dynamic
     * Circular Work-Stealing Deque" by Chase and Lev, SPAA 2005
     * (http://research.sun.com/scalable/pubs/index.html) and
     * "Idempotent work stealing" by Michael, Saraswat, and Vechev,
     * PPoPP 2009 (http://portal.acm.org/citation.cfm?id=1504186).
     * The main differences ultimately stem from GC requirements that
     * we null out taken slots as soon as we can, to maintain as small
     * a footprint as possible even in programs generating huge
     * numbers of tasks. To accomplish this, we shift the CAS
     * arbitrating pop vs poll (steal) from being on the indices
     * ("base" and "top") to the slots themselves.
     *
     * Adding tasks then takes the form of a classic array push(task):
     *    q.array[q.top] = task; ++q.top;
     *
     * (The actual code needs to null-check and size-check the array,
     * properly fence the accesses, and possibly signal waiting
     * workers to start scanning -- see below.)  Both a successful pop
     * and poll mainly entail a CAS of a slot from non-null to null.
     *
     * The pop operation (always performed by owner) is:
     *   if ((base != top) and
     *        (the task at top slot is not null) and
     *        (CAS slot to null))
     *           decrement top and return task;
     *
     * And the poll operation (usually by a stealer) is
     *    if ((base != top) and
     *        (the task at base slot is not null) and
     *        (base has not changed) and
     *        (CAS slot to null))
     *           increment base and return task;
     *
     * Because we rely on CASes of references, we do not need tag bits
     * on base or top.  They are simple ints as used in any circular
     * array-based queue (see for example ArrayDeque).  Updates to the
     * indices guarantee that top == base means the queue is empty,
     * but otherwise may err on the side of possibly making the queue
     * appear nonempty when a push, pop, or poll have not fully
     * committed. (Method isEmpty() checks the case of a partially
     * completed removal of the last element.)  Because of this, the
     * poll operation, considered individually, is not wait-free. One
     * thief cannot successfully continue until another in-progress
     * one (or, if previously empty, a push) completes.  However, in
     * the aggregate, we ensure at least probabilistic
     * non-blockingness.  If an attempted steal fails, a thief always
     * chooses a different random victim target to try next. So, in
     * order for one thief to progress, it suffices for any
     * in-progress poll or new push on any empty queue to
     * complete. (This is why we normally use method pollAt and its
     * variants that try once at the apparent base index, else
     * consider alternative actions, rather than method poll, which
     * retries.)
     *
     * This approach also enables support of a user mode in which
     * local task processing is in FIFO, not LIFO order, simply by
     * using poll rather than pop.  This can be useful in
     * message-passing frameworks in which tasks are never joined.
     * However neither mode considers affinities, loads, cache
     * localities, etc, so rarely provide the best possible
     * performance on a given machine, but portably provide good
     * throughput by averaging over these factors.  Further, even if
     * we did try to use such information, we do not usually have a
     * basis for exploiting it.  For example, some sets of tasks
     * profit from cache affinities, but others are harmed by cache
     * pollution effects. Additionally, even though it requires
     * scanning, long-term throughput is often best using random
     * selection rather than directed selection policies, so cheap
     * randomization of sufficient quality is used whenever
     * applicable.  Various Marsaglia XorShifts (some with different
     * shift constants) are inlined at use points.
     *
     * WorkQueues are also used in a similar way for tasks submitted
     * to the pool. We cannot mix these tasks in the same queues used
     * by workers. Instead, we randomly associate submission queues
     * with submitting threads, using a form of hashing.  The
     * ThreadLocalRandom probe value serves as a hash code for
     * choosing existing queues, and may be randomly repositioned upon
     * contention with other submitters.  In essence, submitters act
     * like workers except that they are restricted to executing local
     * tasks that they submitted (or in the case of CountedCompleters,
     * others with the same root task).  Insertion of tasks in shared
     * mode requires a lock (mainly to protect in the case of
     * resizing) but we use only a simple spinlock (using field
     * qlock), because submitters encountering a busy queue move on to
     * try or create other queues -- they block only when creating and
     * registering new queues. Additionally, "qlock" saturates to an
     * unlockable value (-1) at shutdown. Unlocking still can be and
     * is performed by cheaper ordered writes of "qlock" in successful
     * cases, but uses CAS in unsuccessful cases.
     *
     * Management
     * ==========
     *
     * The main throughput advantages of work-stealing stem from
     * decentralized control -- workers mostly take tasks from
     * themselves or each other, at rates that can exceed a billion
     * per second.  The pool itself creates, activates (enables
     * scanning for and running tasks), deactivates, blocks, and
     * terminates threads, all with minimal central information.
     * There are only a few properties that we can globally track or
     * maintain, so we pack them into a small number of variables,
     * often maintaining atomicity without blocking or locking.
     * Nearly all essentially atomic control state is held in two
     * volatile variables that are by far most often read (not
     * written) as status and consistency checks. (Also, field
     * "config" holds unchanging configuration state.)
     *
     * Field "ctl" contains 64 bits holding information needed to
     * atomically decide to add, inactivate, enqueue (on an event
     * queue), dequeue, and/or re-activate workers.  To enable this
     * packing, we restrict maximum parallelism to (1<<15)-1 (which is
     * far in excess of normal operating range) to allow ids, counts,
     * and their negations (used for thresholding) to fit into 16bit
     * subfields.
     *
     * Field "runState" holds lockable state bits (STARTED, STOP, etc)
     * also protecting updates to the workQueues array.  When used as
     * a lock, it is normally held only for a few instructions (the
     * only exceptions are one-time array initialization and uncommon
     * resizing), so is nearly always available after at most a brief
     * spin. But to be extra-cautious, after spinning, method
     * awaitRunStateLock (called only if an initial CAS fails), uses a
     * wait/notify mechanics on a builtin monitor to block when
     * (rarely) needed. This would be a terrible idea for a highly
     * contended lock, but most pools run without the lock ever
     * contending after the spin limit, so this works fine as a more
     * conservative alternative. Because we don't otherwise have an
     * internal Object to use as a monitor, the "stealCounter" (an
     * AtomicLong) is used when available (it too must be lazily
     * initialized; see externalSubmit).
     *
     * Usages of "runState" vs "ctl" interact in only one case:
     * deciding to add a worker thread (see tryAddWorker), in which
     * case the ctl CAS is performed while the lock is held.
     *
     * Recording WorkQueues.  WorkQueues are recorded in the
     * "workQueues" array. The array is created upon first use (see
     * externalSubmit) and expanded if necessary.  Updates to the
     * array while recording new workers and unrecording terminated
     * ones are protected from each other by the runState lock, but
     * the array is otherwise concurrently readable, and accessed
     * directly. We also ensure that reads of the array reference
     * itself never become too stale. To simplify index-based
     * operations, the array size is always a power of two, and all
     * readers must tolerate null slots. Worker queues are at odd
     * indices. Shared (submission) queues are at even indices, up to
     * a maximum of 64 slots, to limit growth even if array needs to
     * expand to add more workers. Grouping them together in this way
     * simplifies and speeds up task scanning.
     *
     * All worker thread creation is on-demand, triggered by task
     * submissions, replacement of terminated workers, and/or
     * compensation for blocked workers. However, all other support
     * code is set up to work with other policies.  To ensure that we
     * do not hold on to worker references that would prevent GC, All
     * accesses to workQueues are via indices into the workQueues
     * array (which is one source of some of the messy code
     * constructions here). In essence, the workQueues array serves as
     * a weak reference mechanism. Thus for example the stack top
     * subfield of ctl stores indices, not references.
     *
     * Queuing Idle Workers. Unlike HPC work-stealing frameworks, we
     * cannot let workers spin indefinitely scanning for tasks when
     * none can be found immediately, and we cannot start/resume
     * workers unless there appear to be tasks available.  On the
     * other hand, we must quickly prod them into action when new
     * tasks are submitted or generated. In many usages, ramp-up time
     * to activate workers is the main limiting factor in overall
     * performance, which is compounded at program start-up by JIT
     * compilation and allocation. So we streamline this as much as
     * possible.
     *
     * The "ctl" field atomically maintains active and total worker
     * counts as well as a queue to place waiting threads so they can
     * be located for signalling. Active counts also play the role of
     * quiescence indicators, so are decremented when workers believe
     * that there are no more tasks to execute. The "queue" is
     * actually a form of Treiber stack.  A stack is ideal for
     * activating threads in most-recently used order. This improves
     * performance and locality, outweighing the disadvantages of
     * being prone to contention and inability to release a worker
     * unless it is topmost on stack.  We park/unpark workers after
     * pushing on the idle worker stack (represented by the lower
     * 32bit subfield of ctl) when they cannot find work.  The top
     * stack state holds the value of the "scanState" field of the
     * worker: its index and status, plus a version counter that, in
     * addition to the count subfields (also serving as version
     * stamps) provide protection against Treiber stack ABA effects.
     *
     * Field scanState is used by both workers and the pool to manage
     * and track whether a worker is INACTIVE (possibly blocked
     * waiting for a signal), or SCANNING for tasks (when neither hold
     * it is busy running tasks).  When a worker is inactivated, its
     * scanState field is set, and is prevented from executing tasks,
     * even though it must scan once for them to avoid queuing
     * races. Note that scanState updates lag queue CAS releases so
     * usage requires care. When queued, the lower 16 bits of
     * scanState must hold its pool index. So we place the index there
     * upon initialization (see registerWorker) and otherwise keep it
     * there or restore it when necessary.
     *
     * Memory ordering.  See "Correct and Efficient Work-Stealing for
     * Weak Memory Models" by Le, Pop, Cohen, and Nardelli, PPoPP 2013
     * (http://www.di.ens.fr/~zappa/readings/ppopp13.pdf) for an
     * analysis of memory ordering requirements in work-stealing
     * algorithms similar to the one used here.  We usually need
     * stronger than minimal ordering because we must sometimes signal
     * workers, requiring Dekker-like full-fences to avoid lost
     * signals.  Arranging for enough ordering without expensive
     * over-fencing requires tradeoffs among the supported means of
     * expressing access constraints. The most central operations,
     * taking from queues and updating ctl state, require full-fence
     * CAS.  Array slots are read using the emulation of volatiles
     * provided by Unsafe.  Access from other threads to WorkQueue
     * base, top, and array requires a volatile load of the first of
     * any of these read.  We use the convention of declaring the
     * "base" index volatile, and always read it before other fields.
     * The owner thread must ensure ordered updates, so writes use
     * ordered intrinsics unless they can piggyback on those for other
     * writes.  Similar conventions and rationales hold for other
     * WorkQueue fields (such as "currentSteal") that are only written
     * by owners but observed by others.
     *
     * Creating workers. To create a worker, we pre-increment total
     * count (serving as a reservation), and attempt to construct a
     * ForkJoinWorkerThread via its factory. Upon construction, the
     * new thread invokes registerWorker, where it constructs a
     * WorkQueue and is assigned an index in the workQueues array
     * (expanding the array if necessary). The thread is then
     * started. Upon any exception across these steps, or null return
     * from factory, deregisterWorker adjusts counts and records
     * accordingly.  If a null return, the pool continues running with
     * fewer than the target number workers. If exceptional, the
     * exception is propagated, generally to some external caller.
     * Worker index assignment avoids the bias in scanning that would
     * occur if entries were sequentially packed starting at the front
     * of the workQueues array. We treat the array as a simple
     * power-of-two hash table, expanding as needed. The seedIndex
     * increment ensures no collisions until a resize is needed or a
     * worker is deregistered and replaced, and thereafter keeps
     * probability of collision low. We cannot use
     * ThreadLocalRandom.getProbe() for similar purposes here because
     * the thread has not started yet, but do so for creating
     * submission queues for existing external threads.
     *
     * Deactivation and waiting. Queuing encounters several intrinsic
     * races; most notably that a task-producing thread can miss
     * seeing (and signalling) another thread that gave up looking for
     * work but has not yet entered the wait queue.  When a worker
     * cannot find a task to steal, it deactivates and enqueues. Very
     * often, the lack of tasks is transient due to GC or OS
     * scheduling. To reduce false-alarm deactivation, scanners
     * compute checksums of queue states during sweeps.  (The
     * stability checks used here and elsewhere are probabilistic
     * variants of snapshot techniques -- see Herlihy & Shavit.)
     * Workers give up and try to deactivate only after the sum is
     * stable across scans. Further, to avoid missed signals, they
     * repeat this scanning process after successful enqueuing until
     * again stable.  In this state, the worker cannot take/run a task
     * it sees until it is released from the queue, so the worker
     * itself eventually tries to release itself or any successor (see
     * tryRelease).  Otherwise, upon an empty scan, a deactivated
     * worker uses an adaptive local spin construction (see awaitWork)
     * before blocking (via park). Note the unusual conventions about
     * Thread.interrupts surrounding parking and other blocking:
     * Because interrupts are used solely to alert threads to check
     * termination, which is checked anyway upon blocking, we clear
     * status (using Thread.interrupted) before any call to park, so
     * that park does not immediately return due to status being set
     * via some other unrelated call to interrupt in user code.
     *
     * Signalling and activation.  Workers are created or activated
     * only when there appears to be at least one task they might be
     * able to find and execute.  Upon push (either by a worker or an
     * external submission) to a previously (possibly) empty queue,
     * workers are signalled if idle, or created if fewer exist than
     * the given parallelism level.  These primary signals are
     * buttressed by others whenever other threads remove a task from
     * a queue and notice that there are other tasks there as well.
     * On most platforms, signalling (unpark) overhead time is
     * noticeably long, and the time between signalling a thread and
     * it actually making progress can be very noticeably long, so it
     * is worth offloading these delays from critical paths as much as
     * possible. Also, because inactive workers are often rescanning
     * or spinning rather than blocking, we set and clear the "parker"
     * field of WorkQueues to reduce unnecessary calls to unpark.
     * (This requires a secondary recheck to avoid missed signals.)
     *
     * Trimming workers. To release resources after periods of lack of
     * use, a worker starting to wait when the pool is quiescent will
     * time out and terminate (see awaitWork) if the pool has remained
     * quiescent for period IDLE_TIMEOUT, increasing the period as the
     * number of threads decreases, eventually removing all workers.
     * Also, when more than two spare threads exist, excess threads
     * are immediately terminated at the next quiescent point.
     * (Padding by two avoids hysteresis.)
     *
     * Shutdown and Termination. A call to shutdownNow invokes
     * tryTerminate to atomically set a runState bit. The calling
     * thread, as well as every other worker thereafter terminating,
     * helps terminate others by setting their (qlock) status,
     * cancelling their unprocessed tasks, and waking them up, doing
     * so repeatedly until stable (but with a loop bounded by the
     * number of workers).  Calls to non-abrupt shutdown() preface
     * this by checking whether termination should commence. This
     * relies primarily on the active count bits of "ctl" maintaining
     * consensus -- tryTerminate is called from awaitWork whenever
     * quiescent. However, external submitters do not take part in
     * this consensus.  So, tryTerminate sweeps through queues (until
     * stable) to ensure lack of in-flight submissions and workers
     * about to process them before triggering the "STOP" phase of
     * termination. (Note: there is an intrinsic conflict if
     * helpQuiescePool is called when shutdown is enabled. Both wait
     * for quiescence, but tryTerminate is biased to not trigger until
     * helpQuiescePool completes.)
     *
     *
     * Joining Tasks
     * =============
     *
     * Any of several actions may be taken when one worker is waiting
     * to join a task stolen (or always held) by another.  Because we
     * are multiplexing many tasks on to a pool of workers, we can't
     * just let them block (as in Thread.join).  We also cannot just
     * reassign the joiner's run-time stack with another and replace
     * it later, which would be a form of "continuation", that even if
     * possible is not necessarily a good idea since we may need both
     * an unblocked task and its continuation to progress.  Instead we
     * combine two tactics:
     *
     *   Helping: Arranging for the joiner to execute some task that it
     *      would be running if the steal had not occurred.
     *
     *   Compensating: Unless there are already enough live threads,
     *      method tryCompensate() may create or re-activate a spare
     *      thread to compensate for blocked joiners until they unblock.
     *
     * A third form (implemented in tryRemoveAndExec) amounts to
     * helping a hypothetical compensator: If we can readily tell that
     * a possible action of a compensator is to steal and execute the
     * task being joined, the joining thread can do so directly,
     * without the need for a compensation thread (although at the
     * expense of larger run-time stacks, but the tradeoff is
     * typically worthwhile).
     *
     * The ManagedBlocker extension API can't use helping so relies
     * only on compensation in method awaitBlocker.
     *
     * The algorithm in helpStealer entails a form of "linear
     * helping".  Each worker records (in field currentSteal) the most
     * recent task it stole from some other worker (or a submission).
     * It also records (in field currentJoin) the task it is currently
     * actively joining. Method helpStealer uses these markers to try
     * to find a worker to help (i.e., steal back a task from and
     * execute it) that could hasten completion of the actively joined
     * task.  Thus, the joiner executes a task that would be on its
     * own local deque had the to-be-joined task not been stolen. This
     * is a conservative variant of the approach described in Wagner &
     * Calder "Leapfrogging: a portable technique for implementing
     * efficient futures" SIGPLAN Notices, 1993
     * (http://portal.acm.org/citation.cfm?id=155354). It differs in
     * that: (1) We only maintain dependency links across workers upon
     * steals, rather than use per-task bookkeeping.  This sometimes
     * requires a linear scan of workQueues array to locate stealers,
     * but often doesn't because stealers leave hints (that may become
     * stale/wrong) of where to locate them.  It is only a hint
     * because a worker might have had multiple steals and the hint
     * records only one of them (usually the most current).  Hinting
     * isolates cost to when it is needed, rather than adding to
     * per-task overhead.  (2) It is "shallow", ignoring nesting and
     * potentially cyclic mutual steals.  (3) It is intentionally
     * racy: field currentJoin is updated only while actively joining,
     * which means that we miss links in the chain during long-lived
     * tasks, GC stalls etc (which is OK since blocking in such cases
     * is usually a good idea).  (4) We bound the number of attempts
     * to find work using checksums and fall back to suspending the
     * worker and if necessary replacing it with another.
     *
     * Helping actions for CountedCompleters do not require tracking
     * currentJoins: Method helpComplete takes and executes any task
     * with the same root as the task being waited on (preferring
     * local pops to non-local polls). However, this still entails
     * some traversal of completer chains, so is less efficient than
     * using CountedCompleters without explicit joins.
     *
     * Compensation does not aim to keep exactly the target
     * parallelism number of unblocked threads running at any given
     * time. Some previous versions of this class employed immediate
     * compensations for any blocked join. However, in practice, the
     * vast majority of blockages are transient byproducts of GC and
     * other JVM or OS activities that are made worse by replacement.
     * Currently, compensation is attempted only after validating that
     * all purportedly active threads are processing tasks by checking
     * field WorkQueue.scanState, which eliminates most false
     * positives.  Also, compensation is bypassed (tolerating fewer
     * threads) in the most common case in which it is rarely
     * beneficial: when a worker with an empty queue (thus no
     * continuation tasks) blocks on a join and there still remain
     * enough threads to ensure liveness.
     *
     * The compensation mechanism may be bounded.  Bounds for the
     * commonPool (see commonMaxSpares) better enable JVMs to cope
     * with programming errors and abuse before running out of
     * resources to do so. In other cases, users may supply factories
     * that limit thread construction. The effects of bounding in this
     * pool (like all others) is imprecise.  Total worker counts are
     * decremented when threads deregister, not when they exit and
     * resources are reclaimed by the JVM and OS. So the number of
     * simultaneously live threads may transiently exceed bounds.
     *
     * Common Pool
     * ===========
     *
     * The static common pool always exists after static
     * initialization.  Since it (or any other created pool) need
     * never be used, we minimize initial construction overhead and
     * footprint to the setup of about a dozen fields, with no nested
     * allocation. Most bootstrapping occurs within method
     * externalSubmit during the first submission to the pool.
     *
     * When external threads submit to the common pool, they can
     * perform subtask processing (see externalHelpComplete and
     * related methods) upon joins.  This caller-helps policy makes it
     * sensible to set common pool parallelism level to one (or more)
     * less than the total number of available cores, or even zero for
     * pure caller-runs.  We do not need to record whether external
     * submissions are to the common pool -- if not, external help
     * methods return quickly. These submitters would otherwise be
     * blocked waiting for completion, so the extra effort (with
     * liberally sprinkled task status checks) in inapplicable cases
     * amounts to an odd form of limited spin-wait before blocking in
     * ForkJoinTask.join.
     *
     * As a more appropriate default in managed environments, unless
     * overridden by system properties, we use workers of subclass
     * InnocuousForkJoinWorkerThread when there is a SecurityManager
     * present. These workers have no permissions set, do not belong
     * to any user-defined ThreadGroup, and erase all ThreadLocals
     * after executing any top-level task (see WorkQueue.runTask).
     * The associated mechanics (mainly in ForkJoinWorkerThread) may
     * be JVM-dependent and must access particular Thread class fields
     * to achieve this effect.
     *
     * Style notes
     * ===========
     *
     * Memory ordering relies mainly on Unsafe intrinsics that carry
     * the further responsibility of explicitly performing null- and
     * bounds- checks otherwise carried out implicitly by JVMs.  This
     * can be awkward and ugly, but also reflects the need to control
     * outcomes across the unusual cases that arise in very racy code
     * with very few invariants. So these explicit checks would exist
     * in some form anyway.  All fields are read into locals before
     * use, and null-checked if they are references.  This is usually
     * done in a "C"-like style of listing declarations at the heads
     * of methods or blocks, and using inline assignments on first
     * encounter.  Array bounds-checks are usually performed by
     * masking with array.length-1, which relies on the invariant that
     * these arrays are created with positive lengths, which is itself
     * paranoically checked. Nearly all explicit checks lead to
     * bypass/return, not exception throws, because they may
     * legitimately arise due to cancellation/revocation during
     * shutdown.
     *
     * There is a lot of representation-level coupling among classes
     * ForkJoinPool, ForkJoinWorkerThread, and ForkJoinTask.  The
     * fields of WorkQueue maintain data structures managed by
     * ForkJoinPool, so are directly accessed.  There is little point
     * trying to reduce this, since any associated future changes in
     * representations will need to be accompanied by algorithmic
     * changes anyway. Several methods intrinsically sprawl because
     * they must accumulate sets of consistent reads of fields held in
     * local variables.  There are also other coding oddities
     * (including several unnecessary-looking hoisted null checks)
     * that help some methods perform reasonably even when interpreted
     * (not compiled).
     *
     * The order of declarations in this file is (with a few exceptions):
     * (1) Static utility functions
     * (2) Nested (static) classes
     * (3) Static fields
     * (4) Fields, along with constants used when unpacking some of them
     * (5) Internal control methods
     * (6) Callbacks and other support for ForkJoinTask methods
     * (7) Exported methods
     * (8) Static block initializing statics in minimally dependent order
     */

    // Static utilities

    /**
     * If there is a security manager, makes sure caller has
     * permission to modify threads.
     */
    // 检查当前线程是否有操作权限
    private static void checkPermission() {
        SecurityManager security = System.getSecurityManager();
        if (security != null)
            security.checkPermission(modifyThreadPermission);
    }

    // Nested classes

    /**
     * Factory for creating new {@link ForkJoinWorkerThread}s.
     * A {@code ForkJoinWorkerThreadFactory} must be defined and used
     * for {@code ForkJoinWorkerThread} subclasses that extend base
     * functionality or initialize threads with different contexts.
     *
     * <p>
     * 用于创建ForkJoinWorkerThread，该接口有两个对应的实现类DefaultForkJoinWorkerThreadFactory和InnocuousForkJoinWorkerThreadFactory，
     * 分别用于创建普通的ForkJoinWorkerThread和特殊的InnocuousForkJoinWorkerThread
     */
    public static interface ForkJoinWorkerThreadFactory {
        /**
         * Returns a new worker thread operating in the given pool.
         *
         * @param pool the pool this thread works in
         * @return the new worker thread
         * @throws NullPointerException if the pool is null
         */
        public ForkJoinWorkerThread newThread(ForkJoinPool pool);
    }

    /**
     * Default ForkJoinWorkerThreadFactory implementation; creates a
     * new ForkJoinWorkerThread.
     */
    static final class DefaultForkJoinWorkerThreadFactory
        implements ForkJoinWorkerThreadFactory {
        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return new ForkJoinWorkerThread(pool);
        }
    }

    /**
     * Class for artificial tasks that are used to replace the target
     * of local joins if they are removed from an interior queue slot
     * in WorkQueue.tryRemoveAndExec. We don't need the proxy to
     * actually do anything beyond having a unique identity.
     */
    static final class EmptyTask extends ForkJoinTask<Void> {
        private static final long serialVersionUID = -7721805057305804111L;
        EmptyTask() { status = ForkJoinTask.NORMAL; } // force done
        public final Void getRawResult() { return null; }
        public final void setRawResult(Void x) {}
        public final boolean exec() { return true; }
    }

    // Constants shared across ForkJoinPool and WorkQueue

    // Bounds
    // 用于获取保存在config属性低16位中的parallelism属性
    static final int SMASK        = 0xffff;        // short bits == max index
    // parallelism属性的最大值
    static final int MAX_CAP      = 0x7fff;        // max #workers - 1
    // 跟SMASK相比，最后一位是0而不是1，用来计算遍历workQueues的步长
    static final int EVENMASK     = 0xfffe;        // even short bits
    // 取值是126，二进制表示是1111110,WorkQueue数组元素个数的最大值
    static final int SQMASK       = 0x007e;        // max 64 (even) slots

    // Masks and units for WorkQueue.scanState and ctl sp subfield
    // WorkQueue.scanState属性中表示状态的位
    // 最低位为1表示关联的Worker线程正在扫描获取待执行的任务，为0表示正在执行任务
    static final int SCANNING     = 1;             // false when running tasks
    // 最高位为1表示当前WorkQueue为非激活状态，刚创建时状态就是INACTIVE，可通过signalWork方法将其变成激活状态
    /// 最高位为0表示当前WorkQueue为激活状态
    static final int INACTIVE     = 1 << 31;       // must be negative
    // 一个自增值，ctl属性的低32位加上SS_SEQ，计算一个新的scanState
    static final int SS_SEQ       = 1 << 16;       // version count

    // Mode bits for ForkJoinPool.config and WorkQueue.config
    // ForkJoinPool.config 和 WorkQueue.config 属性中，高16位表示队列的模式
    static final int MODE_MASK    = 0xffff << 16;  // top half of int
    static final int LIFO_QUEUE   = 0; // 后进先出模式
    static final int FIFO_QUEUE   = 1 << 16; // 先进先出模式
    static final int SHARED_QUEUE = 1 << 31;       // must be negative 共享模式

    /**
     * Queues supporting work-stealing as well as external task
     * submission. See above for descriptions and algorithms.
     * Performance on most platforms is very sensitive to placement of
     * instances of both WorkQueues and their arrays -- we absolutely
     * do not want multiple WorkQueue instances or multiple queue
     * arrays sharing cache lines. The @Contended annotation alerts
     * JVMs to try to keep instances apart.
     *
     * <p>
     * 用来保存待执行的任务的队列，该类没有实现队列接口，完全是ForkJoinPool定制的，其访问权限是包级访问权限。
     */
    @sun.misc.Contended
    static final class WorkQueue {

        /**
         * Capacity of work-stealing queue array upon initialization.
         * Must be a power of two; at least 4, but should be larger to
         * reduce or eliminate cacheline sharing among queues.
         * Currently, it is much larger, as a partial workaround for
         * the fact that JVMs often place arrays in locations that
         * share GC bookkeeping (especially cardmarks) such that
         * per-write accesses encounter serious memory contention.
         */
        // 初始队列容量
        static final int INITIAL_QUEUE_CAPACITY = 1 << 13;

        /**
         * Maximum size for queue arrays. Must be a power of two less
         * than or equal to 1 << (31 - width of array entry) to ensure
         * lack of wraparound of index calculations, but defined to a
         * value a bit less than this to help users trap runaway
         * programs before saturating systems.
         */
        // 最大队列容量
        static final int MAXIMUM_QUEUE_CAPACITY = 1 << 26; // 64M

        // Instance fields
        // 当前队列的状态，如果小于0表示未激活，大于0表示已激活
        // 如果最后一位是1，即是奇数，则表示关联的Worker线程正在扫描获取待执行的任务，最后一位是0，即偶数，表示关联的Worker线程正在执行任务
        // scanState是会变化的，每次改变时是在当前ctl的基础上加上SS_SEQ
        volatile int scanState;    // versioned, <0: inactive; odd:scanning
        // 当前WorkQueue被标记成未激活状态时ctl属性的低32位的值，其中低16位保存在此之间被标记为未激活状态的WorkQueue在数组中的索引
        int stackPred;             // pool stack (ctl) predecessor
        // 表示当前队列从其他队列中偷过来并执行的任务数
        int nsteals;               // number of steals
        // hint是在WorkerQueue创建时初始化的，初始值为当前线程的Probe
        // 当从其他WorkerQueue获取未执行的任务时，用来临时保存被偷取任务的WorkerQueue的索引
        int hint;                  // randomization and stealer index hint
        // 高16位保存队列的类型，是先进先出还是后进先出，取决于线程池的配置，如果小于0表示该队列是共享的
        // 低16位保存当前队列在队列数组中的位置
        int config;                // pool index and mode
        // 1表示已加锁，负值表示当前队列已终止，不能接受新的任务，Worker退出或者线程池终止时会将qlock属性置为-1
        // 正常是0
        volatile int qlock;        // 1: locked, < 0: terminate; else 0
        // 下一次poll时对应的数组索引
        volatile int base;         // index of next slot for poll
        // 下一次push时对应的数组索引
        int top;                   // index of next slot for push
        // 实际保存ForkJoinTask的数组
        ForkJoinTask<?>[] array;   // the elements (initially unallocated)
        // 关联的线程池
        final ForkJoinPool pool;   // the containing pool (may be null)
        // 拥有此队列的ForkJoinWorkerThread
        final ForkJoinWorkerThread owner; // owning thread or null if shared
        // 如果队列长期为空则会被置为未激活状态，关联的Worker线程也会休眠，parker属性就是记录关联的Worker线程，跟上面的owner是一样的
        volatile Thread parker;    // == owner during call to park; else null
        // 必须等待currentJoin执行完成而让当前线程阻塞
        volatile ForkJoinTask<?> currentJoin;  // task being joined in awaitJoin
        // 当前线程通过scan方法获取待执行的任务，该任务可能是当前队列的，也可能是其他某个队列的
        volatile ForkJoinTask<?> currentSteal; // mainly used by helpStealer

        WorkQueue(ForkJoinPool pool, ForkJoinWorkerThread owner) {
            this.pool = pool;
            this.owner = owner;
            // Place indices in the center of array (that is not yet allocated)
            // base和top的初始值都不是0，而是初始值的一半，即从数组的中间开始插入新元素或者移除元素
            base = top = INITIAL_QUEUE_CAPACITY >>> 1;
        }

        /**
         * Returns an exportable index (used by ForkJoinWorkerThread).
         */
        final int getPoolIndex() {
            return (config & 0xffff) >>> 1; // ignore odd/even tag bit
        }

        /**
         * Returns the approximate number of tasks in the queue.
         */
        final int queueSize() {
            int n = base - top;       // non-owner callers must read base first
            return (n >= 0) ? 0 : -n; // ignore transient negative
        }

        /**
         * Provides a more accurate estimate of whether this queue has
         * any tasks than does queueSize, by checking whether a
         * near-empty queue has at least one unclaimed task.
         */
        final boolean isEmpty() {
            ForkJoinTask<?>[] a; int n, m, s;
            return ((n = base - (s = top)) >= 0 ||
                    (n == -1 &&           // possibly one task
                     ((a = array) == null || (m = a.length - 1) < 0 ||
                      U.getObject
                      (a, (long)((m & (s - 1)) << ASHIFT) + ABASE) == null)));
        }

        /**
         * Pushes a task. Call only by owner in unshared queues.  (The
         * shared-queue version is embedded in method externalPush.)
         *
         * @param task the task. Caller must ensure non-null.
         * @throws RejectedExecutionException if array cannot be resized
         */
        // 插入一个新任务
        final void push(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a; ForkJoinPool p;
            int b = base, s = top, n;
            // 执行runWorker方法时会通过growArray方法初始化array
            // 一旦关联的ForkJoinWorkerThread解除注册了则会将其置为null
            if ((a = array) != null) {    // ignore if queue removed 如果队列被移除了则忽略
                int m = a.length - 1;     // fenced write for task visibility
                // 将task保存到数组中，m & s的结果就是新元素在数组中的位置，如果s大于m则求且的结果是0，又从0逐步增长到索引最大值
                U.putOrderedObject(a, ((m & s) << ASHIFT) + ABASE, task);
                // 修改top属性，加1，注意top属性会一直加1
                U.putOrderedInt(this, QTOP, s + 1);
                if ((n = s - b) <= 1) { // 如果原来数组是空的
                    if ((p = pool) != null)
                        // 唤醒因为待执行任务为空而阻塞的线程
                        p.signalWork(p.workQueues, this);
                }
                else if (n >= m) // 说明数组满了
                    growArray();
            }
        }

        /**
         * Initializes or doubles the capacity of array. Call either
         * by owner or with lock held -- it is OK for base, but not
         * top, to move while resizings are in progress.
         */
        // 数组扩容，会将原老数组的元素复制到新数组中，老数组中对应的位置置为null
        final ForkJoinTask<?>[] growArray() {
            ForkJoinTask<?>[] oldA = array;
            // 获取扩容后的容量，如果array未初始化则是INITIAL_QUEUE_CAPACITY
            int size = oldA != null ? oldA.length << 1 : INITIAL_QUEUE_CAPACITY;
            if (size > MAXIMUM_QUEUE_CAPACITY)
                throw new RejectedExecutionException("Queue capacity exceeded");
            int oldMask, t, b;
            // 创建一个新数组
            ForkJoinTask<?>[] a = array = new ForkJoinTask<?>[size];
            if (oldA != null && (oldMask = oldA.length - 1) >= 0 &&
                (t = top) - (b = base) > 0) { // 老数组中还有未被移除的元素
                int mask = size - 1;
                do { // emulate poll from old array, push to new array 从base往top遍历，将老数组中的元素拷贝到新数组中，老数组对应位置的数组元素置为null
                    ForkJoinTask<?> x;
                    int oldj = ((b & oldMask) << ASHIFT) + ABASE;
                    int j    = ((b &    mask) << ASHIFT) + ABASE;
                    // 获取原数组oldj处的元素
                    x = (ForkJoinTask<?>)U.getObjectVolatile(oldA, oldj);
                    if (x != null &&
                        U.compareAndSwapObject(oldA, oldj, x, null)) // 将老数组oldj处的元素置为null，将x保存到新数组的j处
                        U.putObjectVolatile(a, j, x);
                } while (++b != t);
            }
            return a;
        }

        /**
         * Takes next task, if one exists, in LIFO order.  Call only
         * by owner in unshared queues.
         */
        // 弹出上一次push的元素
        final ForkJoinTask<?> pop() {
            ForkJoinTask<?>[] a; ForkJoinTask<?> t; int m;
            if ((a = array) != null && (m = a.length - 1) >= 0) {
                for (int s; (s = top - 1) - base >= 0;) { // 有未移除的元素
                    // 获取上一次push的元素在数组中的索引
                    long j = ((m & s) << ASHIFT) + ABASE;
                    // 如果该索引的元素为null，则终止循环
                    if ((t = (ForkJoinTask<?>)U.getObject(a, j)) == null)
                        break;
                    // 将j处的元素置为null，修改top属性，返回j处的元素
                    if (U.compareAndSwapObject(a, j, t, null)) {
                        U.putOrderedInt(this, QTOP, s);
                        return t;
                    }
                }
            }
            // 数组未初始化或者数组为空
            return null;
        }

        /**
         * Takes a task in FIFO order if b is base of queue and a task
         * can be claimed without contention. Specialized versions
         * appear in ForkJoinPool methods scan and helpStealer.
         */
        // 获取指定数组索引的元素，要求b等于base，否则返回null
        final ForkJoinTask<?> pollAt(int b) {
            ForkJoinTask<?> t; ForkJoinTask<?>[] a;
            if ((a = array) != null) {
                int j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                if ((t = (ForkJoinTask<?>)U.getObjectVolatile(a, j)) != null &&
                    // 只有base等于b的时候才会将对应的数组元素置为null并返回该元素
                    base == b && U.compareAndSwapObject(a, j, t, null)) {
                    base = b + 1;
                    return t;
                }
            }
            return null;
        }

        /**
         * Takes next task, if one exists, in FIFO order.
         */
        // 获取并移除base对应的数组元素
        final ForkJoinTask<?> poll() {
            ForkJoinTask<?>[] a; int b; ForkJoinTask<?> t;
            // 数组不为空且还有未移除的元素
            while ((b = base) - top < 0 && (a = array) != null) {
                // 获取base对应数组索引处的元素
                int j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                t = (ForkJoinTask<?>)U.getObjectVolatile(a, j);
                if (base == b) { // 如果base未发生改变
                    if (t != null) {
                        // 通过cas将其置为null，base加1，返回该元素
                        if (U.compareAndSwapObject(a, j, t, null)) {
                            base = b + 1;
                            return t;
                        }
                    }
                    else if (b + 1 == top) // now empty
                        break;
                }
            } // 如果base发生改变则下一次while循环重新读取base
            return null;
        }

        /**
         * Takes next task, if one exists, in order specified by mode.
         */
        final ForkJoinTask<?> nextLocalTask() {
            return (config & FIFO_QUEUE) == 0 ? pop() : poll();
        }

        /**
         * Returns next task, if one exists, in order specified by mode.
         */
        final ForkJoinTask<?> peek() {
            ForkJoinTask<?>[] a = array; int m;
            // 如果数组未初始化
            if (a == null || (m = a.length - 1) < 0)
                return null;
            // 如果是后进先出则是top - 1 ，如果是先进先出则是base
            int i = (config & FIFO_QUEUE) == 0 ? top - 1 : base;
            int j = ((i & m) << ASHIFT) + ABASE;
            // 获取队列头或者队列尾的元素，取决于队列的类型
            return (ForkJoinTask<?>)U.getObjectVolatile(a, j);
        }

        /**
         * Pops the given task only if it is at the current top.
         * (A shared version is available only via FJP.tryExternalUnpush)
        */
        // 如果t是上一次push的元素，则将top减1，对应的数组元素置为null，返回true
        final boolean tryUnpush(ForkJoinTask<?> t) {
            ForkJoinTask<?>[] a; int s;
            if ((a = array) != null && (s = top) != base &&
                U.compareAndSwapObject
                // 如果t是上一次push插入的元素，则将其置为null，同时修改s
                (a, (((a.length - 1) & --s) << ASHIFT) + ABASE, t, null)) {
                U.putOrderedInt(this, QTOP, s);
                return true;
            }
            return false;
        }

        /**
         * Removes and cancels all known tasks, ignoring any exceptions.
         */
        // 取消掉所有未执行完成的任务
        final void cancelAll() {
            ForkJoinTask<?> t;
            if ((t = currentJoin) != null) {
                currentJoin = null;
                ForkJoinTask.cancelIgnoringExceptions(t);
            }
            if ((t = currentSteal) != null) {
                currentSteal = null;
                ForkJoinTask.cancelIgnoringExceptions(t);
            }
            while ((t = poll()) != null)
                ForkJoinTask.cancelIgnoringExceptions(t);
        }

        // Specialized execution methods

        /**
         * Polls and runs tasks until empty.
         */
        final void pollAndExecAll() {
            for (ForkJoinTask<?> t; (t = poll()) != null;)
                t.doExec();
        }

        /**
         * Removes and executes all local tasks. If LIFO, invokes
         * pollAndExecAll. Otherwise implements a specialized pop loop
         * to exec until empty.
         */
        final void execLocalTasks() {
            int b = base, m, s;
            ForkJoinTask<?>[] a = array;
            // 如果数组已经初始化且包含未处理的元素
            if (b - (s = top - 1) <= 0 && a != null &&
                (m = a.length - 1) >= 0) {
                if ((config & FIFO_QUEUE) == 0) {
                    // 如果是后进先出队列，从top开始往前遍历直到base
                    for (ForkJoinTask<?> t;;) {
                        // 获取s对应的数组元素
                        if ((t = (ForkJoinTask<?>)U.getAndSetObject
                             (a, ((m & s) << ASHIFT) + ABASE, null)) == null)
                            break;
                        // 修改top
                        U.putOrderedInt(this, QTOP, s);
                        t.doExec(); // 执行任务
                        if (base - (s = top - 1) > 0) // 所有元素都遍历完了
                            break;
                    }
                }
                else
                    // 如果是先进先出
                    pollAndExecAll();
            }
        }

        /**
         * Executes the given task and any remaining local tasks.
         *
         * runTask方法会执行指定的任务，并且将任务数组中包含的所有未处理任务都执行完成，注意指定的任务不一定是当前WorkQueue中包含的，有可能是从其他WorkQueue中偷过来的。
         *
         */
        // 执行特定任务，并执行完当前数组中包含的所有未处理任务
        final void runTask(ForkJoinTask<?> task) {
            if (task != null) {
                // SCANNING的值就是1,将最后一位置为0，表示正在执行任务
                scanState &= ~SCANNING; // mark as busy
                // 执行task
                (currentSteal = task).doExec();
                // 将currentSteal属性置为null
                U.putOrderedObject(this, QCURRENTSTEAL, null); // release for GC
                // 执行当前数组中包含的未处理的任务
                execLocalTasks();
                ForkJoinWorkerThread thread = owner;
                if (++nsteals < 0)      // collect on overflow
                    // nsteals已经超过最大值了，变成负值了，则将其累加到ForkJoinPool的stealCounter属性中并重置为0
                    transferStealCount(pool);
                // 重新打标成SCANNING
                scanState |= SCANNING;
                if (thread != null)
                    // 执行回调方法
                    thread.afterTopLevelExec();
            }
        }

        /**
         * Adds steal count to pool stealCounter if it exists, and resets.
         */
        final void transferStealCount(ForkJoinPool p) {
            AtomicLong sc;
            if (p != null && (sc = p.stealCounter) != null) {
                int s = nsteals;
                // 恢复成0
                nsteals = 0;            // if negative, correct for overflow
                // 累加到ForkJoinPool的stealCounter属性中
                sc.getAndAdd((long)(s < 0 ? Integer.MAX_VALUE : s));
            }
        }

        /**
         * If present, removes from queue and executes the given task,
         * or any other cancelled task. Used only by awaitJoin.
         *
         * @return true if queue empty and task not known to be done
         */
        // 如果队列为空且不知道task的状态，则返回true
        // 如果找到目标task，会将其从数组中移除并执行
        // 在遍历数组的过程中，如果发现任务被取消了，则会将其从数组中丢弃
        final boolean tryRemoveAndExec(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a; int m, s, b, n;
            if ((a = array) != null && (m = a.length - 1) >= 0 &&
                task != null) { // 数组和task非空
                while ((n = (s = top) - (b = base)) > 0) { // 还有未处理的任务
                    for (ForkJoinTask<?> t;;) {      // traverse from s to b
                        // s先减1再求且
                        long j = ((--s & m) << ASHIFT) + ABASE;
                        // 如果索引为j的数组元素为null
                        if ((t = (ForkJoinTask<?>)U.getObject(a, j)) == null)
                            return s + 1 == top;     // shorter than expected 如果为true，说明数组是空的
                        else if (t == task) {
                            // 找到了匹配的任务
                            boolean removed = false;
                            if (s + 1 == top) {      // pop 目标task就是上一次push的元素
                                if (U.compareAndSwapObject(a, j, task, null)) {
                                    // 将对应的数组元素置为null，然后修改top属性
                                    U.putOrderedInt(this, QTOP, s);
                                    removed = true;
                                }
                            }
                            // s+1不等于top，即目标task位于中间的位置，将用一个空的EmptyTask代替
                            else if (base == b)      // replace with proxy
                                removed = U.compareAndSwapObject(
                                    a, j, task, new EmptyTask());
                            if (removed)
                                // 如果成功移除该task，则执行该任务
                                task.doExec();
                            break; // 终止内存for循环，通过外层的while循环，重启for循环
                        }
                        // t不等于task
                        // 如果t被取消了，且t位于栈顶
                        else if (t.status < 0 && s + 1 == top) {
                            if (U.compareAndSwapObject(a, j, t, null))
                                U.putOrderedInt(this, QTOP, s); // 修改top属性，即将被取消掉的任务给丢弃掉
                            break;                  // was cancelled
                        }
                        // 先减n减1，再判断n是否为0，如果为0，说明数组中所有元素都遍历完了，返回false
                        if (--n == 0)
                            return false;
                    } // for循环结束
                    if (task.status < 0) // task被取消了，返回false
                        return false;
                } // while循环结束
            }
            return true;
        }

        /**
         * Pops task if in the same CC computation as the given task,
         * in either shared or owned mode. Used only by helpComplete.
         */
        // 如果栈顶的元素是CountedCompleter，则以该元素为起点向上遍历他的父节点，直到找到目标task元素，将栈顶的
        // CountedCompleter从数组中移除并返回，top减1
        final CountedCompleter<?> popCC(CountedCompleter<?> task, int mode) {
            int s; ForkJoinTask<?>[] a; Object o;
            if (base - (s = top) < 0 && (a = array) != null) {
                // 如果数组非空
                long j = (((a.length - 1) & (s - 1)) << ASHIFT) + ABASE;
                if ((o = U.getObjectVolatile(a, j)) != null &&
                    (o instanceof CountedCompleter)) {
                    // 如果栈顶的任务是CountedCompleter
                    CountedCompleter<?> t = (CountedCompleter<?>)o;
                    for (CountedCompleter<?> r = t;;) {
                        if (r == task) { // 如果找到目标task
                            if (mode < 0) { // must lock 该队列是共享的，会被多个线程修改，必须加锁
                                if (U.compareAndSwapInt(this, QLOCK, 0, 1)) { // 加锁
                                    if (top == s && array == a && // 如果top属性和array属性都未改变，将索引为j的元素置为null
                                        U.compareAndSwapObject(a, j, t, null)) {
                                        U.putOrderedInt(this, QTOP, s - 1); // top减1
                                        U.putOrderedInt(this, QLOCK, 0); // 释放锁
                                        return t;
                                    }
                                    // 上述if不成立，解锁
                                    U.compareAndSwapInt(this, QLOCK, 1, 0);
                                }
                            }
                            // mode大于等于0，是某个Worker线程独享的，不需要加锁
                            else if (U.compareAndSwapObject(a, j, t, null)) { // 将索引为j的元素置为null
                                U.putOrderedInt(this, QTOP, s - 1);
                                return t;
                            }
                            break;
                        }
                        else if ((r = r.completer) == null) // try parent 遍历父节点，如果为null说明都遍历完了
                            break;
                    }
                }
            }
            return null;
        }

        /**
         * Steals and runs a task in the same CC computation as the
         * given task if one exists and can be taken without
         * contention. Otherwise returns a checksum/control value for
         * use by method helpComplete.
         *
         * @return 1 if successful, 2 if retryable (lost to another
         * stealer), -1 if non-empty but no matching task found, else
         * the base index, forced negative.
         */
        // 如果base对应的数组元素是CountedCompleter，则以该节点作为起点，往上遍历其父节点，如果找到目标节点task，则执行并返回1
        final int pollAndExecCC(CountedCompleter<?> task) {
            int b, h; ForkJoinTask<?>[] a; Object o;
            if ((b = base) - top >= 0 || (a = array) == null) // 如果数组为空
                h = b | Integer.MIN_VALUE;  // to sense movement on re-poll
            else {
                // 如果数组不为空，获取base对应的元素
                long j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                if ((o = U.getObjectVolatile(a, j)) == null) // 如果base对应的元素为null，返回2
                    h = 2;                  // retryable
                else if (!(o instanceof CountedCompleter)) // 如果base对应的元素不是CountedCompleter，返回-1
                    h = -1;                 // unmatchable
                else {
                    // base对应的元素是CountedCompleter
                    CountedCompleter<?> t = (CountedCompleter<?>)o;
                    for (CountedCompleter<?> r = t;;) {
                        if (r == task) {
                            // 匹配到目标元素
                            if (base == b &&
                                U.compareAndSwapObject(a, j, t, null)) {
                                // base未改变且成功将j对应的数组元素置为null，将base加1，并执行该任务，返回1
                                base = b + 1;
                                t.doExec();
                                h = 1;      // success
                            }
                            else
                                // base变了或者cas失败，返回2
                                h = 2;      // lost CAS
                            break;
                        }
                        else if ((r = r.completer) == null) { // 往上遍历父节点，如果为null，则返回-1
                            h = -1;         // unmatched
                            break;
                        }
                    }
                }
            }
            return h;
        }

        /**
         * Returns true if owned and not known to be blocked.
         */
        final boolean isApparentlyUnblocked() {
            Thread wt; Thread.State s;
            return (scanState >= 0 &&
                    (wt = owner) != null &&
                    (s = wt.getState()) != Thread.State.BLOCKED &&
                    s != Thread.State.WAITING &&
                    s != Thread.State.TIMED_WAITING);
        }

        // Unsafe mechanics. Note that some are (and must be) the same as in FJP
        private static final sun.misc.Unsafe U;
        private static final int  ABASE;
        private static final int  ASHIFT;
        private static final long QTOP;
        private static final long QLOCK;
        private static final long QCURRENTSTEAL;
        static {
            try {
                U = sun.misc.Unsafe.getUnsafe();
                Class<?> wk = WorkQueue.class;
                Class<?> ak = ForkJoinTask[].class;
                QTOP = U.objectFieldOffset
                    (wk.getDeclaredField("top"));
                QLOCK = U.objectFieldOffset
                    (wk.getDeclaredField("qlock"));
                QCURRENTSTEAL = U.objectFieldOffset
                    (wk.getDeclaredField("currentSteal"));
                ABASE = U.arrayBaseOffset(ak);
                int scale = U.arrayIndexScale(ak);
                if ((scale & (scale - 1)) != 0)
                    throw new Error("data type scale not a power of two");
                ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    // static fields (initialized in static initializer below)

    /**
     * Creates a new ForkJoinWorkerThread. This factory is used unless
     * overridden in ForkJoinPool constructors.
     */
    // 创建ForkJoinWorkerThread的工厂类
    public static final ForkJoinWorkerThreadFactory
        defaultForkJoinWorkerThreadFactory;

    /**
     * Permission required for callers of methods that may start or
     * kill threads.
     */
    // 线程池关闭时检查当前线程是否有此权限
    private static final RuntimePermission modifyThreadPermission;

    /**
     * Common (static) pool. Non-null for public use unless a static
     * construction exception, but internal usages null-check on use
     * to paranoically avoid potential initialization circularities
     * as well as to simplify generated code.
     */
    // common线程池
    static final ForkJoinPool common;

    /**
     * Common pool parallelism. To allow simpler use and management
     * when common pool threads are disabled, we allow the underlying
     * common.parallelism field to be zero, but in that case still report
     * parallelism as 1 to reflect resulting caller-runs mechanics.
     */
    // common线程池的parallelism属性
    static final int commonParallelism;

    /**
     * Limit on spare thread construction in tryCompensate.
     */
    // 限制最大的线程数，默认值为常量DEFAULT_COMMON_MAX_SPARES，256
    private static int commonMaxSpares;

    /**
     * Sequence number for creating workerNamePrefix.
     */
    // 生成poolId使用
    private static int poolNumberSequence;

    /**
     * Returns the next sequence number. We don't expect this to
     * ever contend, so use simple builtin sync.
     */
    private static final synchronized int nextPoolId() {
        return ++poolNumberSequence;
    }

    // static configuration constants

    /**
     * Initial timeout value (in nanoseconds) for the thread
     * triggering quiescence to park waiting for new work. On timeout,
     * the thread will instead try to shrink the number of
     * workers. The value should be large enough to avoid overly
     * aggressive shrinkage during most transient stalls (long GCs
     * etc).
     */
    // Worker线程的空闲时间，单位是纳秒
    private static final long IDLE_TIMEOUT = 2000L * 1000L * 1000L; // 2sec

    /**
     * Tolerance for idle timeouts, to cope with timer undershoots
     */
    // 允许的阻塞时间误差
    private static final long TIMEOUT_SLOP = 20L * 1000L * 1000L;  // 20ms

    /**
     * The initial value for commonMaxSpares during static
     * initialization. The value is far in excess of normal
     * requirements, but also far short of MAX_CAP and typical
     * OS thread limits, so allows JVMs to catch misuse/abuse
     * before running out of resources needed to do so.
     */
    // commonMaxSpares的初始值，线程池中线程数的最大值
    private static final int DEFAULT_COMMON_MAX_SPARES = 256;

    /**
     * Number of times to spin-wait before blocking. The spins (in
     * awaitRunStateLock and awaitWork) currently use randomized
     * spins. Currently set to zero to reduce CPU usage.
     *
     * If greater than zero the value of SPINS must be a power
     * of two, at least 4.  A value of 2048 causes spinning for a
     * small fraction of typical context-switch times.
     *
     * If/when MWAIT-like intrinsics becomes available, they
     * may allow quieter spinning.
     */
    // 自旋等待的次数，设置成0可减少对CPU的占用，且没有对应的方法可以修改SPINS属性
    private static final int SPINS  = 0;

    /**
     * Increment for seed generators. See class ThreadLocal for
     * explanation.
     */
    // 生成随机数的种子
    private static final int SEED_INCREMENT = 0x9e3779b9;

    /*
     * Bits and masks for field ctl, packed with 4 16 bit subfields:
     * AC: Number of active running workers minus target parallelism
     * TC: Number of total workers minus target parallelism
     * SS: version count and status of top waiting thread
     * ID: poolIndex of top of Treiber stack of waiters
     *
     * When convenient, we can extract the lower 32 stack top bits
     * (including version bits) as sp=(int)ctl.  The offsets of counts
     * by the target parallelism and the positionings of fields makes
     * it possible to perform the most common checks via sign tests of
     * fields: When ac is negative, there are not enough active
     * workers, when tc is negative, there are not enough total
     * workers.  When sp is non-zero, there are waiting workers.  To
     * deal with possibly negative fields, we use casts in and out of
     * "short" and/or signed shifts to maintain signedness.
     *
     * Because it occupies uppermost bits, we can add one active count
     * using getAndAddLong of AC_UNIT, rather than CAS, when returning
     * from a blocked join.  Other updates entail multiple subfields
     * and masking, requiring CAS.
     */

    // Lower and upper word masks
    // 获取低32位的值
    private static final long SP_MASK    = 0xffffffffL;
    // 获取高32位的值
    private static final long UC_MASK    = ~SP_MASK;

    // Active counts
    // 活跃线程数，保存在ctl中的最高16位
    private static final int  AC_SHIFT   = 48;
    private static final long AC_UNIT    = 0x0001L << AC_SHIFT;
    private static final long AC_MASK    = 0xffffL << AC_SHIFT;

    // Total counts
    // 总的线程数，保存在ctl中的第二个16位
    private static final int  TC_SHIFT   = 32;
    private static final long TC_UNIT    = 0x0001L << TC_SHIFT;
    private static final long TC_MASK    = 0xffffL << TC_SHIFT;

    // 如果第48位为1，表示需要新增一个Worker线程
    // 第48位对应于TC的最高位，如果为1，说明总的线程数小于parallelism属性，可以新增线程
    private static final long ADD_WORKER = 0x0001L << (TC_SHIFT + 15); // sign

    // runState bits: SHUTDOWN must be negative, others arbitrary powers of two
    // runState属性不同的位表示的含义，描述线程池的状态
    // 如果runState小于0，即最高位为1，说明线程池正在关闭的过程中
    // 如果runState大于0，说明线程池是正常运行的
    private static final int  RSLOCK     = 1; // 表示已加锁
    private static final int  RSIGNAL    = 1 << 1; // 表示有线程等待获取锁
    private static final int  STARTED    = 1 << 2; // 表示线程已启动
    // 表示线程池的状态是STOP，不接受新的任务且会丢弃掉任务队列中未执行的任务
    private static final int  STOP       = 1 << 29;
    // 表示线程池已终止
    private static final int  TERMINATED = 1 << 30;
    // 表示线程池的状态是SHUTDOWN，不接受新的任务
    private static final int  SHUTDOWN   = 1 << 31;

    // Instance fields
    // 最高的16位表示获取线程数，第二个16位表示总的线程数
    // 如果有空闲线程，最低的16位中保存空闲线程关联的WorkQueue在WorkQueue数组中的索引
    volatile long ctl;                   // main pool control
    // 描述线程池的状态
    volatile int runState;               // lockable status
    // 高16位保存线程池的队列模式，FIFO或者LIFO
    // 低16位保存线程池的parallelism属性
    final int config;                    // parallelism, mode
    // 累加SEED_INCREMENT生成的一个随机数，决定新增的Worker对应的WorkQueue在数组中的索引
    int indexSeed;                       // to generate worker index
    // WorkQueue数组
    volatile WorkQueue[] workQueues;     // main registry
    // 生成Worker线程的工厂类
    final ForkJoinWorkerThreadFactory factory;
    // 异常处理器
    final UncaughtExceptionHandler ueh;  // per-worker UEH
    // 生成的Worker线程的线程名前缀
    final String workerNamePrefix;       // to create worker name string
    // 累计的从其他WorkQueue中偷过来的待执行的任务
    volatile AtomicLong stealCounter;    // also used as sync monitor

    /**
     * Acquires the runState lock; returns current (locked) runState.
     *
     * <p>将当前线程池加锁，如果当前runState已加锁或者CAS加锁失败则调用awaitRunStateLock等待锁释放并加锁
     */
    // 执行加锁
    private int lockRunState() {
        int rs;
        return ((((rs = runState) & RSLOCK) != 0 || // 如果已经加锁
                 !U.compareAndSwapInt(this, RUNSTATE, rs, rs |= RSLOCK)) ? // 如果未加锁则CAS加锁
                // 如果加锁失败调用awaitRunStateLock，阻塞当前线程等待获取锁，否则返回rs
                awaitRunStateLock() : rs);
    }

    /**
     * Spins and/or blocks until runstate lock is available.  See
     * above for explanation.
     *
     * <p>阻塞当前线程，直到锁释放被唤醒，然后尝试获取锁，如果获取失败则继续阻塞，直到获取成功为止
     */
    // 阻塞当前线程，直到锁释放被唤醒，然后尝试获取锁
    private int awaitRunStateLock() {
        Object lock;
        boolean wasInterrupted = false;
        // SPINS默认是0
        for (int spins = SPINS, r = 0, rs, ns;;) {
            if (((rs = runState) & RSLOCK) == 0) { // 如果未加锁
                if (U.compareAndSwapInt(this, RUNSTATE, rs, ns = rs | RSLOCK)) {
                    // 尝试加锁成功
                    if (wasInterrupted) {
                        try {
                            // 将当前线程标记为中断
                            Thread.currentThread().interrupt();
                        } catch (SecurityException ignore) {
                        }
                    }
                    return ns;
                }
            }
            // 如果已加锁
            else if (r == 0)
                // 初始化r
                r = ThreadLocalRandom.nextSecondarySeed();
            else if (spins > 0) {
                // 自旋等待
                r ^= r << 6; r ^= r >>> 21; r ^= r << 7; // xorshift
                if (r >= 0)
                    --spins;
            }
            // spins等于0
            else if ((rs & STARTED) == 0 // 线程池未初始化
                    || (lock = stealCounter) == null)
                Thread.yield();   // initialization race 执行yeild，等待线程池初始化
            // 线程池已初始化
            else if (U.compareAndSwapInt(this, RUNSTATE, rs, rs | RSIGNAL)) { // 状态加上RSIGNAL
                synchronized (lock) {
                    if ((runState & RSIGNAL) != 0) { // 再次校验
                        try {
                            lock.wait(); // 阻塞当前线程
                        } catch (InterruptedException ie) {
                            if (!(Thread.currentThread() instanceof
                                  ForkJoinWorkerThread))
                                wasInterrupted = true;
                        }
                    }
                    else
                        // 如果RSIGNAL标识没了，说明unlockRunState将该标识清除了
                        // 此时还在synchronized代码块中未释放锁，所以unlockRunState中的synchronized会被阻塞
                        // 唤醒所有等待获取锁的线程
                        lock.notifyAll();
                }
                // 下一次for循环会尝试获取锁
            }
        } // for循环结束
    }

    /**
     * Unlocks and sets runState to newRunState.
     *
     * <p>将当前线程池解锁，如果oldRunState跟当前的runstate属性不一致，说明有某个线程在等待获取锁，
     * 在runstate上加上了RSIGNAL标识，则直接修改runstate，去掉RSIGNAL标识，并唤醒所有等待获取锁的线程
     *
     * @param oldRunState a value returned from lockRunState
     * @param newRunState the next value (must have lock bit clear).
     */
    // 执行解锁
    private void unlockRunState(int oldRunState, int newRunState) {
        if (!U.compareAndSwapInt(this, RUNSTATE, oldRunState, newRunState)) {
            // 如果cas修改失败，只能是oldRunState发生改变了，加上了RSIGNAL
            // 所以此处是直接重置runState，去除RSLOCK和RSIGNAL，并唤醒所有等待获取锁的线程
            Object lock = stealCounter;
            runState = newRunState;              // clears RSIGNAL bit
            if (lock != null)
                // 唤醒所有等待获取锁的线程
                synchronized (lock) { lock.notifyAll(); }
        }
    }

    // Creating, registering and deregistering workers

    /**
     * Tries to construct and start one worker. Assumes that total
     * count has already been incremented as a reservation.  Invokes
     * deregisterWorker on any failure.
     *
     * @return true if successful
     */
    // 创建新的Worker，如果创建失败或者启动执行异常则通过deregisterWorker方法通知线程池将其解除注册
    private boolean createWorker() {
        ForkJoinWorkerThreadFactory fac = factory;
        Throwable ex = null;
        ForkJoinWorkerThread wt = null;
        try {
            if (fac != null && (wt = fac.newThread(this)) != null) {
                wt.start(); // 通过ThreadFactory创建一个新的Worker，并启动线程的执行
                return true;
            }
        } catch (Throwable rex) {
            ex = rex;
        }
        // 创建的过程中有异常，通知线程池将其解除注册
        deregisterWorker(wt, ex);
        return false;
    }

    /**
     * Tries to add one worker, incrementing ctl counts before doing
     * so, relying on createWorker to back out on failure.
     *
     * @param c incoming ctl value, with total count negative and no
     * idle workers.  On CAS failure, c is refreshed and retried if
     * this holds (otherwise, a new worker is not needed).
     */
    // 尝试增加新线程
    private void tryAddWorker(long c) {
        boolean add = false;
        do {
            // AC和TC都加1
            long nc = ((AC_MASK & (c + AC_UNIT)) |
                       (TC_MASK & (c + TC_UNIT)));
            if (ctl == c) { // 如果ctl未变更
                int rs, stop;                 // check if terminating
                if ((stop = (rs = lockRunState()) & STOP) == 0) // 加锁成功且线程池未终止，则修改ctl
                    add = U.compareAndSwapLong(this, CTL, c, nc);
                unlockRunState(rs, rs & ~RSLOCK); // 解锁
                if (stop != 0) // 如果线程池已终止，则终止while循环
                    break;
                if (add) {
                    // ctl修改成功，创建新的Worker
                    createWorker();
                    break;
                }
            }
            // 重新读取ctl，如果依然可以新增线程且空闲线程数为0，则继续下一次for循环新增Worker
        } while (((c = ctl) & ADD_WORKER) != 0L && (int)c == 0);
    }

    /**
     * Callback from ForkJoinWorkerThread constructor to establish and
     * record its WorkQueue.
     *
     * <p>registerWorker是在ForkJoinWorkerThread的构造方法中调用的，通知线程池新增了一个Worker，会配套的创建一个关联的WorkQueue，
     * 将其保存在WorkQueue数组中，保存的位置是根据indexSeed计算出来的，是一个奇数，如果对应位置的WorkQueue非空，
     * 则遍历WorkQueue数组找到一个WorkQueue为空且索引是奇数的位置。注意新创建的WorkQueue的模式跟线程池的模式一致，FIFO或者LIFO，
     * 且scanState初始就是激活状态，其值等于该WorkQueue数组中的位置。
     *
     * @param wt the worker thread
     * @return the worker's queue
     */
    // 通知线程池新增一个WorkerThread
    final WorkQueue registerWorker(ForkJoinWorkerThread wt) {
        UncaughtExceptionHandler handler;
        // 设置Daemon为true
        wt.setDaemon(true);                           // configure thread
        if ((handler = ueh) != null)
            // 保存异常处理器
            wt.setUncaughtExceptionHandler(handler);
        WorkQueue w = new WorkQueue(this, wt);
        int i = 0;                                    // assign a pool index
        // 获取线程池的队列模式
        int mode = config & MODE_MASK;
        // 尝试获取锁，如果获取失败则阻塞当前线程
        int rs = lockRunState();
        try {
            WorkQueue[] ws; int n;                    // skip if no array
            if ((ws = workQueues) != null && (n = ws.length) > 0) {
                // 如果workQueues已初始化，indexSeed初始值为0
                int s = indexSeed += SEED_INCREMENT;  // unlikely to collide
                int m = n - 1;
                // 算出来的i肯定是一个奇数
                i = ((s << 1) | 1) & m;               // odd-numbered indices
                if (ws[i] != null) {                  // collision 如果对应索引的WorkQueue非空
                    int probes = 0;                   // step by approx half n
                    int step = (n <= 4) ? 2 : ((n >>> 1) & EVENMASK) + 2;
                    // 遍历数组找到一个WorkQueue为空的索引，因为step是一个偶数，i原来是一个奇数，所以最终i还是一个奇数
                    while (ws[i = (i + step) & m] != null) {
                        if (++probes >= n) {
                            // 原数组遍历了一遍，没有找到空闲的数组元素，则将其扩容一倍
                            // 将原数组的元素复制到新数组中
                            workQueues = ws = Arrays.copyOf(ws, n <<= 1);
                            m = n - 1;
                            probes = 0;
                        }
                    }
                }
                // 保存w
                w.hint = s;                           // use as random seed
                // 低16位保存w在WorkQueue数组中的位置，跟线程池的模式一致
                w.config = i | mode;
                w.scanState = i;                      // publication fence 初始就是激活状态
                ws[i] = w;
            }
        } finally {
            // 解锁，如果runState发生改变，则唤醒所有等待获取锁的线程
            unlockRunState(rs, rs & ~RSLOCK);
        }
        // 设置线程名
        wt.setName(workerNamePrefix.concat(Integer.toString(i >>> 1)));
        return w;
    }

    /**
     * Final callback from terminating worker, as well as upon failure
     * to construct or start a worker.  Removes record of worker from
     * array, and adjusts counts. If pool is shutting down, tries to
     * complete termination.
     *
     * <p>
     * deregisterWorker是在ForkJoinWorkerThread执行任务的过程中出现未捕获异常导致线程退出时或者创建Worker线程异常时调用的，
     * 会将关联的WorkQueue标记为已终止，将其steals属性累加到线程池的stealCounter属性中，取消掉所有未处理的任务，
     * 最后从WorkQueue数组中删除，将AC和TC都减1，如果有空闲的Worker线程则唤醒一个，如果没有且当前线程数小于parallelism则创建一个新线程。
     *
     * @param wt the worker thread, or null if construction failed
     * @param ex the exception causing failure, or null if none
     */
    // 通知线程池某个WorkerThread即将退出
    final void deregisterWorker(ForkJoinWorkerThread wt, Throwable ex) {
        WorkQueue w = null;
        if (wt != null && (w = wt.workQueue) != null) {
            WorkQueue[] ws;                           // remove index from array
            // 获取在数组中的位置
            int idx = w.config & SMASK;
            // 加锁
            int rs = lockRunState();
            if ((ws = workQueues) != null && ws.length > idx && ws[idx] == w)
                // ws不为空且对应索引的元素是目标WorkQueue w，将其置为null
                ws[idx] = null;
            // 解锁
            unlockRunState(rs, rs & ~RSLOCK);
        }
        long c;                                       // decrement counts
        // 将AC和TC都原子的减1，CAS失败则重试
        do {} while (!U.compareAndSwapLong
                     (this, CTL, c = ctl, ((AC_MASK & (c - AC_UNIT)) | // 获取最高的16位
                                           (TC_MASK & (c - TC_UNIT)) | // 获取紧挨着的16位
                                           (SP_MASK & c)))); // 获取低32位
        if (w != null) {
            w.qlock = -1;                             // ensure set 表明该WorkQueue已终止工作
            w.transferStealCount(this); // 将该WorkQueuen的steals累加到线程池的stealCounter属性中
            w.cancelAll();                            // cancel remaining tasks 取消掉所有未完成的任务
        }
        for (;;) {                                    // possibly replace
            WorkQueue[] ws; int m, sp;
            if (tryTerminate(false, false) || w == null || w.array == null ||
                (runState & STOP) != 0 || (ws = workQueues) == null ||
                (m = ws.length - 1) < 0)              // already terminating 如果线程池正在终止或者已经终止了
                break;
            if ((sp = (int)(c = ctl)) != 0) {         // wake up replacement 唤醒空闲的Worker线程
                if (tryRelease(c, ws[sp & m], AC_UNIT))
                    break;
            }
            // 没有空闲的线程
            else if (ex != null && (c & ADD_WORKER) != 0L) { // 如果是异常退出且线程总数小于parallelism
                tryAddWorker(c);                      // create replacement 增加一个新线程
                break;
            }
            else                                      // don't need replacement 不需要替换
                break;
        }
        if (ex == null)                               // help clean on way out 清空所有异常
            ForkJoinTask.helpExpungeStaleExceptions();
        else                                          // rethrow 重新抛出异常
            ForkJoinTask.rethrow(ex);
    }

    // Signalling

    /**
     * Tries to create or activate a worker if too few are active.
     *
     * @param ws the worker array to use to find signallees
     * @param q a WorkQueue --if non-null, don't retry if now empty
     */
    // 如果当前总的线程数小于parallelism，则signalWork会尝试创建新的Worker线程
    // 如果当前有空闲的Worker线程，则尝试唤醒一个，如果没有空闲的则直接返回
    final void signalWork(WorkQueue[] ws, WorkQueue q) {
        long c; int sp, i; WorkQueue v; Thread p;
        while ((c = ctl) < 0L) {                       // too few active ctl初始化时使用-parallelism，为负值表示活跃的线程数小于线程池初始化时设置的并行度要求
            if ((sp = (int)c) == 0) {                  // no idle workers 取低32位的值，如果为0，则活跃线程数等于总的线程数，即没有空闲的线程数
                if ((c & ADD_WORKER) != 0L)            // too few workers 不等于0，说明总的线程数小于parallelism属性，可以新增线程
                    tryAddWorker(c); // 尝试创建新线程
                break; // 只要没有空闲的线程，此处就break，终止while循环
            }
            // sp不等于0，即有空闲的线程，sp等于ctl的低32位
            // ctl的最低16位保存最近空闲的Worker线程关联的WorkQueue在数组中的索引
            if (ws == null)                            // unstarted/terminated 线程池未初始化或者已终止
                break;
            if (ws.length <= (i = sp & SMASK))         // terminated 线程池已终止
                break;
            if ((v = ws[i]) == null)                   // terminating 线程池在终止的过程中
                break;
            // sp加SS_SEQ，非INACTIVE就是最高位0，再加31个1
            // 此处将空闲WorkQueue的INACTIVE表示去除，变成正常的激活状态
            int vs = (sp + SS_SEQ) & ~INACTIVE;        // next scanState
            int d = sp - v.scanState;                  // screen CAS
            // AC加1，UC_MASK取高32位的值，SP_MASK取低32位的值
            // stackPred是v被标记成INACTIVE时ctl的低32位，可能保存着在v之前空闲的WorkQueue在数组中的索引
            long nc = (UC_MASK & (c + AC_UNIT)) | (SP_MASK & v.stackPred);
            if (d == 0 && U.compareAndSwapLong(this, CTL, c, nc)) {
                // d等于0说明WorkQueue v关联的Worker是最近才空闲的
                // 修改ctl属性成功
                v.scanState = vs;                      // activate v 将WorkQueue v标记成已激活
                if ((p = v.parker) != null)
                    U.unpark(p); // 唤醒等待的线程
                break;
            }
            // 如果d不等于0或者上述CAS修改失败，即已经有一个空闲Worker被其他线程给激活了
            if (q != null && q.base == q.top)          // no more work 任务队列为空，没有待执行的任务了，不需要激活空闲Worker或者创建新线程了
                break;
            // 如果if不成立则继续下一次for循环
        } // while循环结束
    }

    /**
     * Signals and releases worker v if it is top of idle worker
     * stack.  This performs a one-shot version of signalWork only if
     * there is (apparently) at least one idle worker.
     *
     * @param c incoming ctl value
     * @param v if non-null, a worker
     * @param inc the increment to active count (zero when compensating)
     * @return true if successful
     */
    // c就是当前的ctl，v表示空闲Worker线程关联的WorkQueue，inc就是AC_UNIT
    private boolean tryRelease(long c, WorkQueue v, long inc) {
        int sp = (int)c, vs = (sp + SS_SEQ) & ~INACTIVE; Thread p;
        if (v != null && v.scanState == sp) {          // v is at top of stack 如果v是最近被置为未激活的
            // AC加1，计算v被置为未激活时的ctl
            long nc = (UC_MASK & (c + inc)) | (SP_MASK & v.stackPred);
            if (U.compareAndSwapLong(this, CTL, c, nc)) {
                // 修改ctl成功，唤醒parker
                v.scanState = vs;
                if ((p = v.parker) != null)
                    U.unpark(p);
                return true;
            }
        }
        // 如果v不是最近空闲的则返回false
        return false;
    }

    // Scanning for tasks

    /**
     * Top-level runloop for workers, called by ForkJoinWorkerThread.run.
     *
     * <p>
     * runWorker是ForkJoinWorkerThread的run方法调用的，该方法是Worker线程执行任务的核心实现。
     *
     * <p>
     * 该方法首先调用scan方法，scan方法会随机的从某个WorkQueue中获取未执行的任务，如果该WorkQueue为null或者没有未执行的任务，则继续遍历下一个WorkQueue，
     * 如果所有WorkQueue都遍历了，且遍历了三遍还是没有获取待执行的任务，且这期间没有新增的任务提交任何一个WorkQueue中，
     * 则会将该WorkQueue标记为INACTIVE并将AC减1，然后返回null，进入awaitWork方法。
     *
     * <p>如果成功获取待执行的任务就调用runTask方法执行该任务，注意该方法不仅仅是执行该任务，还会将该WorkQueue中未执行的任务都执行完了，
     * 执行的过程中会将scanState的SCANNING标识去除，等所有任务都执行完成了再加上标识SCANNING。
     *
     * <p>scan方法涉及WorkQueue中的两个关键属性，scanState和stackPred，某个Worker线程刚创建时其关联的WorkQueue的scanState就是该WorkQueue在数组中的索引，
     * 是一个非负数，参考registerWorker方法。当Worker线程执行scan方法无法获取到待执行的任务，会将关联的WorkQueue标记成INACTIVE，
     * scanState属性的最高位变成1，其值变成一个负数，然后通过stackPred保存原来ctl属性的低32位的值，将变成负数的scanState写入ctl的低32位中并且将ctl中AC减1。
     *
     * <p>当signalWork方法唤醒空闲的Worker线程时，会根据ctl属性的低32位获取Worker线程关联的WorkQueue，将其scanState的INACTIVE标识去除，scanState变成一个非负数，
     * 将AC加1，将stackPred属性写入ctl的低32位中，即将ctl恢复到该WorkQueue被置为INACTIVE时的状态。
     *
     * <p>综上，ctl的低32位中保存着最近被置为INACTIVE的WorkQueue的索引，而该WorkQueue由保存着之前的ctl的低32位的值，据此可以不断向上遍历获取所有被置为INACTIVE状态的WorkQueue。
     *
     * <p>
     * 进入awaitWork方法，如果线程池准备关闭或者当前线程等待被激活超时，则返回false，终止外层的for循环，Worker线程退出，
     * 否则将当前线程阻塞指定的时间或者无期限阻塞，直到signalWork方法或者tryRelease方法将其唤醒。
     *
     * <p>
     * 参考awaitWork方法的实现可知，如果有多个Worker线程关联的WorkQueue依次进入此逻辑，则只有最后一个进入此逻辑的线程因为等待激活超时而退出，
     * 该线程退出后会唤醒之前的一个处于阻塞状态的WorkQueue，如果依然没有待执行的任务，则会继续等待并退出，直到最后所有线程退出，即ForkJoinPool中没有核心线程数的概念，
     * 如果长期没有待执行的任务，线程池中所有线程都会退出。
     *
     */
    final void runWorker(WorkQueue w) {
        // 初始化WorkQueue的任务数组
        w.growArray();                   // allocate queue
        // hint初始值就是累加后的indexSeed
        int seed = w.hint;               // initially holds randomization hint
        int r = (seed == 0) ? 1 : seed;  // avoid 0 for xorShift
        for (ForkJoinTask<?> t;;) {
            if ((t = scan(w, r)) != null)
                // 将t和w中所有的未处理任务都执行完，t有可能是w中的，也可能是其他WorkQueue中的
                w.runTask(t);
            // 如果scan返回null，说明所有的WorkQueue中都没有待执行的任务了，则调用awaitWork阻塞当前线程等待任务
            // 此时w已经被置为非激活状态
            // awaitWork返回false，则终止for循环，线程退出，返回true则继续遍历
            else if (!awaitWork(w, r))
                break;
            // 计算下一个随机数
            r ^= r << 13; r ^= r >>> 17; r ^= r << 5; // xorshift
        }
    }

    /**
     * Scans for and tries to steal a top-level task. Scans start at a
     * random location, randomly moving on apparent contention,
     * otherwise continuing linearly until reaching two consecutive
     * empty passes over all queues with the same checksum (summing
     * each base index of each queue, that moves on each steal), at
     * which point the worker tries to inactivate and then re-scans,
     * attempting to re-activate (itself or some other worker) if
     * finding a task; otherwise returning null to await work.  Scans
     * otherwise touch as little memory as possible, to reduce
     * disruption on other scanning threads.
     *
     * @param w the worker (via its WorkQueue)
     * @param r a random seed
     * @return a task, or null if none found
     */
    // scan方法从r对应的一个WorkQueue开始遍历，查找待处理的任务，如果对应的WorkQueue为空或者没有待处理的任务，则遍历下一个WorkQueue
    // 直到所有的WorkQueue都遍历了两遍还没有找到待处理的任务，则返回null
    // 遍历第一遍没有找到时，oldSum不等于checkSum，被赋值成checkSum，遍历第二遍时，如果此期间没有新增的任务,则checkSum不变，oldSum等于checkSum，
    // ss和scanState都变成负数，遍历第三遍时如果发现了一个待处理任务，则将oldSum和checkSum都置为0，如果还发现了一个待处理任务，
    // 则将最近休眠的WorkQueue，可能就是当前WorkQueue置为激活状态，并更新ss。
    // 如果第三遍遍历时还没有找到待处理的任务切期间没有新增的任务，则终止for循环，返回null，进入awaitWork方法。
    private ForkJoinTask<?> scan(WorkQueue w, int r) {
        WorkQueue[] ws; int m;
        if ((ws = workQueues) != null && (m = ws.length - 1) > 0 && w != null) { // workQueues已初始化
            int ss = w.scanState;                     // initially non-negative scanState的初始值就是该元素在数组中的索引，大于0
            for (int origin = r & m, k = origin, oldSum = 0, checkSum = 0;;) {
                WorkQueue q; ForkJoinTask<?>[] a; ForkJoinTask<?> t;
                int b, n; long c;
                if ((q = ws[k]) != null) { // 如果k对应的数组元素不为null
                    if ((n = (b = q.base) - q.top) < 0 &&
                        (a = q.array) != null) {      // non-empty 如果有待处理的任务
                        long i = (((a.length - 1) & b) << ASHIFT) + ABASE;
                        if ((t = ((ForkJoinTask<?>)
                                  U.getObjectVolatile(a, i))) != null &&
                            q.base == b) { // base未变更且base对应的数组元素不是null
                            if (ss >= 0) {
                                if (U.compareAndSwapObject(a, i, t, null)) {
                                    // 将i对应的元素置为null，base加1
                                    q.base = b + 1;
                                    if (n < -1)       // signal others 还有待处理的任务，唤醒空闲的Worker线程或者新增一个Worker线程
                                        signalWork(ws, q);
                                    return t;
                                }
                            }
                            // ss小于0，说明w由激活状态流转成非激活状态了
                            else if (oldSum == 0 &&   // try to activate oldSum等于0说明是第一轮遍历
                                     w.scanState < 0)
                                // 尝试激活最近一个被置为未激活的WorkQueue
                                tryRelease(c = ctl, ws[m & (int)c], AC_UNIT);
                        }
                        // ss小于0
                        // base对应的元素为null或者base发生变更，说明其他某个线程正在处理这个WorkQueue
                        if (ss < 0)                   // refresh
                            ss = w.scanState; // 更新ss，上一步tryRelease会将scanState变成大于0
                        r ^= r << 1; r ^= r >>> 3; r ^= r << 10;
                        // 重置k，遍历下一个元素，相当于重新调用scan方法，下一次for循环就命中上面的ss小于0时的else if分支，通过tryRelease方法将scanState变成一个非负数
                        origin = k = r & m;           // move and rescan
                        oldSum = checkSum = 0;
                        continue;
                    }
                    // 如果没有待处理的任务，checkSum将所有的WorkQueue的base值累加起来
                    checkSum += b;
                }
                // 如果k对应的数组元素为null，或者不为null但是没有待处理的任务
                // 会不断的k+1，往前遍历，再次等于origin时，相当于所有的WorkQueue都遍历了一遍
                if ((k = (k + 1) & m) == origin) {    // continue until stable
                    // ss大于等于0说明w还是激活状态
                    // 如果ss小于0且跟scanState相等，说明w已经从激活状态变成非激活了
                    if ((ss >= 0 || (ss == (ss = w.scanState))) &&
                        // 重新计算的checkSum没有改变，说明这期间没有新增任务
                        // 第一次进入此方法时，因为oldSum初始值为0，所以跟checkSum不等，只有第二次进入时才可能相等
                        oldSum == (oldSum = checkSum)) {
                        // ss小于0表示w已经是非激活了
                        // w.qlock小于0表示关联的Worker线程准备退出了
                        if (ss < 0 || w.qlock < 0)    // already inactive
                            break; // 终止for循环返回null
                        // ss大于0，其值就是w在数组中的索引
                        int ns = ss | INACTIVE;       // try to inactivate 尝试将其标记成不活跃的
                        // SP_MASK取低32位，UC_MASK取高32位，计算新的ctl，获取nc的低32位时的值就会等于ns
                        long nc = ((SP_MASK & ns) |
                                   (UC_MASK & ((c = ctl) - AC_UNIT))); // AC减1
                        w.stackPred = (int)c;         // hold prev stack top 保存上一次的ctl，取其低32位
                        U.putInt(w, QSCANSTATE, ns); // 修改w的scanState属性，再把WorkQueue数组遍历一遍后进入此分支，因为ss小于0而终止循环
                        if (U.compareAndSwapLong(this, CTL, c, nc)) // cas修改ctl属性
                            ss = ns;
                        else
                            w.scanState = ss;         // back out 如果cas修改失败，则恢复scanState
                    }
                    // checkSum重置为0，下一轮遍历时会重新计算
                    checkSum = 0;
                }
            } // for循环结束
        } // if结束
        return null;
    }

    /**
     * Possibly blocks worker w waiting for a task to steal, or
     * returns false if the worker should terminate.  If inactivating
     * w has caused the pool to become quiescent, checks for pool
     * termination, and, so long as this is not the only worker, waits
     * for up to a given duration.  On timeout, if ctl has not
     * changed, terminates the worker, which will in turn wake up
     * another worker to possibly repeat this process.
     *
     * @param w the calling worker
     * @param r a random seed (for spins)
     * @return false if the worker should terminate
     */
    // 进入此方法表示当前WorkQueue已经被标记成INACTIVE状态
    private boolean awaitWork(WorkQueue w, int r) {
        if (w == null || w.qlock < 0)                 // w is terminating w终止了
            return false;
        for (int pred = w.stackPred, spins = SPINS, ss;;) {
            if ((ss = w.scanState) >= 0) // w被激活了，终止for循环，返回true
                break;
            // w未激活
            else if (spins > 0) {
                // 生成随机数
                r ^= r << 6; r ^= r >>> 21; r ^= r << 7;
                if (r >= 0 && --spins == 0) {         // randomize spins 随机数大于等于0，则将spins减1，自旋
                    // 如果spins等于0
                    WorkQueue v; WorkQueue[] ws; int s, j; AtomicLong sc;
                    if (pred != 0 && (ws = workQueues) != null &&
                        (j = pred & SMASK) < ws.length && // j是上一个被置为未激活的WorkQueue的索引
                        (v = ws[j]) != null &&        // see if pred parking
                        (v.parker == null || v.scanState >= 0)) // 如果j对应的WorkQueue被激活了
                        spins = SPINS;                // continue spinning 继续自旋等待
                }
            }
            else if (w.qlock < 0)                     // recheck after spins 如果被终止
                return false;
            else if (!Thread.interrupted()) { // 线程未中断
                long c, prevctl, parkTime, deadline;
                // 获取当前获取的线程数，(c = ctl) >> AC_SHIFT算出来的是一个负值，加上parallelism属性就大于等于0
                int ac = (int)((c = ctl) >> AC_SHIFT) + (config & SMASK);
                if ((ac <= 0 && tryTerminate(false, false)) ||
                    (runState & STOP) != 0)           // pool terminating 如果线程池被终止
                    return false;
                if (ac <= 0 && ss == (int)c) {        // is last waiter w是最近一个被置为未激活状态的WorkQueue
                    // 计算原来的ctl
                    prevctl = (UC_MASK & (c + AC_UNIT)) | (SP_MASK & pred);
                    // 获取TC，总的线程数，当前实现下t最大为0，最小为-parallelism，不可能大于2
                    int t = (short)(c >>> TC_SHIFT);  // shrink excess spares
                    if (t > 2 && U.compareAndSwapLong(this, CTL, c, prevctl))
                        return false;                 // else use timed wait 如果修改ctl成功则返回false，让当前线程退出，即核心线程数为2，此处修改ctl将AC加1，在degisterWorker方法中会将AC减1
                    // 如果修改t小于等于2或者修改ctl失败
                    // 计算阻塞的时间，t最大就等于0，此时线程数等于parallelism属性，如果小于0说明线程数不足，此时parkTime的值就更大
                    parkTime = IDLE_TIMEOUT * ((t >= 0) ? 1 : 1 - t);
                    deadline = System.nanoTime() + parkTime - TIMEOUT_SLOP;
                }
                else
                    // 有新的WorkQueue被置为未激活状态，parkTime为0表示无期限等待，只能被唤醒
                    prevctl = parkTime = deadline = 0L;
                Thread wt = Thread.currentThread();
                U.putObject(wt, PARKBLOCKER, this);   // emulate LockSupport
                w.parker = wt;
                // 阻塞当前线程
                if (w.scanState < 0 && ctl == c)      // recheck before park 再次检查w的状态，ctl是否改变
                    U.park(false, parkTime); // 休眠指定的时间或者无期限休眠
                // 当前线程被唤醒
                U.putOrderedObject(w, QPARKER, null);
                U.putObject(wt, PARKBLOCKER, null);
                // 无期限等待被唤醒肯定是w被激活了
                if (w.scanState >= 0) // w被激活了，终止for循环返回true
                    break;
                // scanState依然小于0
                // 如果有多个Worker线程关联的WorkQueue依次进入此逻辑，则只有最后一个进入此逻辑的线程因为等待激活超时而退出，
                // 该线程退出后会唤醒之前的一个处于阻塞状态的WorkQueue，如果依然没有待执行的任务，则会继续退出，如此最后所有线程都会退出
                if (parkTime != 0L && ctl == c && // ctl等于c说明没有其他WorkeQueue被置为未激活状态
                    deadline - System.nanoTime() <= 0L && // 如果等待超时，返回false，让当前线程退出
                    U.compareAndSwapLong(this, CTL, c, prevctl))
                    return false;                     // shrink pool
                // 如果if不成立则继续下一次for循环
            }
        }
        return true;
    }

    // Joining tasks

    /**
     * Tries to steal and run tasks within the target's computation.
     * Uses a variant of the top-level algorithm, restricted to tasks
     * with the given task as ancestor: It prefers taking and running
     * eligible tasks popped from the worker's own queue (via
     * popCC). Otherwise it scans others, randomly moving on
     * contention or execution, deciding to give up based on a
     * checksum (via return codes frob pollAndExecCC). The maxTasks
     * argument supports external usages; internal calls use zero,
     * allowing unbounded steps (external calls trap non-positive
     * values).
     *
     * <p>
     * helpComplete和externalHelpComplete同样是阻塞当前线程等待任务执行完成，不过只适用于ForkJoinTask的特殊子类CountedCompleter
     *
     * @param w caller
     * @param maxTasks if non-zero, the maximum number of other tasks to run
     * @return task status on exit
     */
    // 会在指定的任务队列w或者任务数组中其他任务队列中查找task，如果找到则将其从队列中移除并执行
    final int helpComplete(WorkQueue w, CountedCompleter<?> task,
                           int maxTasks) {
        WorkQueue[] ws; int s = 0, m;
        if ((ws = workQueues) != null && (m = ws.length - 1) >= 0 && // workQueues已初始化
            task != null && w != null) {
            int mode = w.config;                 // for popCC
            // 计算一个随机数，第一个扫描的WorkQueue
            int r = w.hint ^ w.top;              // arbitrary seed for origin
            int origin = r & m;                  // first queue to scan
            int h = 1;                           // 1:ran, >1:contended, <0:hash
            for (int k = origin, oldSum = 0, checkSum = 0;;) {
                CountedCompleter<?> p; WorkQueue q;
                if ((s = task.status) < 0) // 如果任务已执行完成
                    break;
                if (h == 1 && (p = w.popCC(task, mode)) != null) {
                    p.doExec();                  // run local task 执行task任务，下一次for循环因为status小于0了会终止循环
                    if (maxTasks != 0 && --maxTasks == 0)
                        break; // 执行任务的次数达到最大值
                    origin = k;                  // reset
                    oldSum = checkSum = 0;
                }
                else {                           // poll other queues
                    if ((q = ws[k]) == null)
                        h = 0; // 置为0后就不会进入上面h==1的if分支了
                    // ws[k]不等于null
                    else if ((h = q.pollAndExecCC(task)) < 0)
                        checkSum += h;
                    if (h > 0) {
                        // h等于1表示查找并执行成功，将maxTasks减1
                        if (h == 1 && maxTasks != 0 && --maxTasks == 0)
                            break; // maxTasks达到最大值了
                        // h不等于1，表示查找失败，重新计算r，遍历下一个WorkQueue查找task
                        r ^= r << 13; r ^= r >>> 17; r ^= r << 5; // xorshift
                        origin = k = r & m;      // move and restart
                        oldSum = checkSum = 0;
                    }
                    // 如果h小于等于0，h应该是-1，表示ws[k]的base属性对应的Task不是CountedCompleter或者不包含目标任务task
                    // k加1，遍历下一个任务队列，再次等于origin说明所有的WorkQueue都遍历了一遍
                    else if ((k = (k + 1) & m) == origin) {
                        if (oldSum == (oldSum = checkSum))
                            break; // oldSum等于checkSum说明没有新的CountedCompleter提交到任务队列
                        checkSum = 0;
                    }
                }
            } // for循环结束
        }
        return s;
    }

    /**
     * Tries to locate and execute tasks for a stealer of the given
     * task, or in turn one of its stealers, Traces currentSteal ->
     * currentJoin links looking for a thread working on a descendant
     * of the given task and with a non-empty queue to steal back and
     * execute tasks from. The first call to this method upon a
     * waiting join will often entail scanning/search, (which is OK
     * because the joiner has nothing better to do), but this method
     * leaves hints in workers to speed up subsequent calls.
     *
     * @param w caller
     * @param task the task to join
     */
    // 如果task被其他某个线程偷走了，则帮助这个线程执行完任务
    private void helpStealer(WorkQueue w, ForkJoinTask<?> task) {
        WorkQueue[] ws = workQueues;
        int oldSum = 0, checkSum, m;
        if (ws != null && (m = ws.length - 1) >= 0 && w != null &&
            task != null) {
            do {                                       // restart point
                checkSum = 0;                          // for stability check
                ForkJoinTask<?> subtask;
                WorkQueue j = w, v;                    // v is subtask stealer
                descent: for (subtask = task; subtask.status >= 0; ) {
                    for (int h = j.hint | 1, k = 0, i; ; k += 2) {
                        if (k > m)                     // can't find stealer
                            // 所有WorkQueue都遍历过了，没有找到目标task则终止最外层的for循环，开始下一次的while循环
                            // 通过while循环判断任务是否执行完成，如果已完成则返回
                            break descent;
                        // h是一个奇数，k是一个偶数，h+k的结果就是一个奇数
                        // 遍历的WorkQueue都是跟Worker线程绑定的
                        if ((v = ws[i = (h + k) & m]) != null) {
                            if (v.currentSteal == subtask) {
                                // 如果找到目标task
                                j.hint = i;
                                break; // 终止内层for循环，进入到下面的for循环
                            }
                            checkSum += v.base;
                        }
                    }
                    // 找到偷走目标task的Worker线程关联的WorkQueue
                    for (;;) {                         // help v or descend
                        ForkJoinTask<?>[] a; int b;
                        checkSum += (b = v.base);
                        ForkJoinTask<?> next = v.currentJoin;
                        if (subtask.status < 0 || j.currentJoin != subtask ||
                            v.currentSteal != subtask) // stale 如果任务已经执行完成
                            break descent; // 终止最外层的for循环，开始下一次的while循环
                        if (b - v.top >= 0 || (a = v.array) == null) { // v中没有待执行的任务
                            if ((subtask = next) == null) // v.currentJoin为null，说明task可能已执行完成
                                break descent;
                            // v.currentJoin不为null，注意此时subtask被更新成v.currentJoin
                            // v关联的Worker线程在等待currentJoin任务执行完成才能执行currentSteal任务
                            // 所以这里需要向上遍历帮助所有currentJoin任务执行完成，才能最终执行currentSteal任务
                            j = v; // v对应的队列中没有待处理的任务
                            break; // 终止内层for循环，开始外层descent对应的for循环
                        }
                        // v中有待执行的任务
                        int i = (((a.length - 1) & b) << ASHIFT) + ABASE;
                        // 获取数组索引为i的元素
                        ForkJoinTask<?> t = ((ForkJoinTask<?>)
                                             U.getObjectVolatile(a, i));
                        if (v.base == b) { // base属性未发生变更
                            if (t == null)             // stale v的base属性发生变更了
                                break descent;
                            if (U.compareAndSwapObject(a, i, t, null)) {
                                // 将数组索引为i的元素修改为null
                                v.base = b + 1;
                                ForkJoinTask<?> ps = w.currentSteal;
                                int top = w.top;
                                do {
                                    // 修改w的currentSteal属性，w对应的worker线程来执行v对应的Worker线程未执行的任务
                                    U.putOrderedObject(w, QCURRENTSTEAL, t);
                                    t.doExec();        // clear local tasks too 执行t
                                } while (task.status >= 0 &&
                                         w.top != top &&
                                         (t = w.pop()) != null); // 执行w中的任务
                                // 恢复w的currentSteal属性
                                U.putOrderedObject(w, QCURRENTSTEAL, ps);
                                if (w.base != w.top) // w中添加了新任务
                                    return;            // can't further help
                            }
                        }
                    } // 第二个内层for循环
                } // for循环结束
            // 不断while循环，直到task执行完成，或者oldSum等于checkSum
            } while (task.status >= 0 && oldSum != (oldSum = checkSum));
        }
    }

    /**
     * Tries to decrement active count (sometimes implicitly) and
     * possibly release or create a compensating worker in preparation
     * for blocking. Returns false (retryable by caller), on
     * contention, detected staleness, instability, or termination.
     *
     * @param w caller
     */
    // 用于判断是否需要将当前线程阻塞，如果返回true且W非空，则会创建新的Worker线程代替当前阻塞的Worker线程继续执行w中其他待执行的任务
    private boolean tryCompensate(WorkQueue w) {
        boolean canBlock;
        WorkQueue[] ws; long c; int m, pc, sp;
        if (w == null || w.qlock < 0 ||           // caller terminating w终止了
            (ws = workQueues) == null || (m = ws.length - 1) <= 0 || // workQueues未初始化
            (pc = config & SMASK) == 0)           // parallelism disabled parallelism为0
            canBlock = false;
        else if ((sp = (int)(c = ctl)) != 0)      // release idle worker ctl的低32位不为0，尝试唤醒一个空闲的Worker线程
            // tryRelease返回true，表示成功唤醒一个Worker线程，可以替代当前线程继续执行任务
            // 所以canBlock返回true，让当前线程阻塞
            canBlock = tryRelease(c, ws[sp & m], 0L);
        else {
            // 没有空闲的Worker线程
            // 获取ac和tc
            int ac = (int)(c >> AC_SHIFT) + pc;
            int tc = (short)(c >> TC_SHIFT) + pc;
            int nbusy = 0;                        // validate saturation
            for (int i = 0; i <= m; ++i) {        // two passes of odd indices
                WorkQueue v;
                // ((i << 1) | 1)算出来的是一个奇数，即遍历Worker线程绑定的WorkQueue
                if ((v = ws[((i << 1) | 1) & m]) != null) {
                    if ((v.scanState & SCANNING) != 0)
                        break; // 如果正在扫描，则终止for循环
                    ++nbusy;
                }
            }
            if (nbusy != (tc << 1) || ctl != c)
                canBlock = false;                 // unstable or stale
            else if (tc >= pc && ac > 1 && w.isEmpty()) {
                // 没有待执行的任务了，需要休眠等待，将AC减1，修改ctl
                long nc = ((AC_MASK & (c - AC_UNIT)) |
                           (~AC_MASK & c));       // uncompensated
                canBlock = U.compareAndSwapLong(this, CTL, c, nc);
            }
            // w非空
            // 总线程数超标
            else if (tc >= MAX_CAP ||
                     (this == common && tc >= pc + commonMaxSpares))
                throw new RejectedExecutionException(
                    "Thread limit exceeded replacing blocked worker");
            else {                                // similar to tryAddWorker
                boolean add = false; int rs;      // CAS within lock
                // TC加1，createWorker方法中会新增TC
                // 因为当前线程会被阻塞，所以新增线程后AC不会加1
                long nc = ((AC_MASK & c) |
                           (TC_MASK & (c + TC_UNIT)));
                if (((rs = lockRunState()) & STOP) == 0) // 获取锁且线程池没有停止
                    add = U.compareAndSwapLong(this, CTL, c, nc); // 修改ctl，增加tc
                unlockRunState(rs, rs & ~RSLOCK); // 解锁
                // 如果cas修改ctl成功，则创建新的worker线程，该线程会在当前线程阻塞时继续执行WorkQueue中其他任务
                canBlock = add && createWorker(); // throws on exception
            }
        }
        return canBlock;
    }

    /**
     * Helps and/or blocks until the given task is done or timeout.
     *
     * <p>
     * 用于阻塞当前线程直到任务执行完成，如果该任务被其他某个线程偷走了，则会帮助其尽快的执行
     *
     * @param w caller
     * @param task the task
     * @param deadline for timed waits, if nonzero
     * @return task status on exit
     */
    // task本来是属于w的，阻塞当前线程等待task执行完成
    final int awaitJoin(WorkQueue w, ForkJoinTask<?> task, long deadline) {
        int s = 0;
        if (task != null && w != null) {
            // 获取等待的Task
            ForkJoinTask<?> prevJoin = w.currentJoin;
            // 将currentJoin修改成task
            U.putOrderedObject(w, QCURRENTJOIN, task);
            CountedCompleter<?> cc = (task instanceof CountedCompleter) ?
                (CountedCompleter<?>)task : null;
            for (;;) {
                if ((s = task.status) < 0) // 如果任务已执行完成
                    break;
                if (cc != null)
                    // 如果task是CountedCompleter实例
                    helpComplete(w, cc, 0);
                // 如果task不是CountedCompleter实例
                else if (w.base == w.top || // w中没有待执行的任务
                        w.tryRemoveAndExec(task)) // 尝试去移除并执行task，如果队列是空的则返回true
                    // task被其他某个线程从w中偷走了，帮助该线程执行完
                    helpStealer(w, task);
                if ((s = task.status) < 0)
                    break;
                long ms, ns;
                if (deadline == 0L)
                    ms = 0L;
                else if ((ns = deadline - System.nanoTime()) <= 0L)
                    break; // 等待超时
                else if ((ms = TimeUnit.NANOSECONDS.toMillis(ns)) <= 0L)
                    ms = 1L; // 超过最大值
                if (tryCompensate(w)) { // 如果需要阻塞
                    task.internalWait(ms); // 等待指定的时间
                    U.getAndAddLong(this, CTL, AC_UNIT); // 增加AC
                }
            } // for循环结束
            U.putOrderedObject(w, QCURRENTJOIN, prevJoin);
        }
        return s;
    }

    // Specialized scanning

    /**
     * Returns a (probably) non-empty steal queue, if one is found
     * during a scan, else null.  This method must be retried by
     * caller if, by the time it tries to use the queue, it is empty.
     */
    private WorkQueue findNonEmptyStealQueue() {
        WorkQueue[] ws; int m;  // one-shot version of scan loop
        int r = ThreadLocalRandom.nextSecondarySeed();
        if ((ws = workQueues) != null && (m = ws.length - 1) >= 0) {
            for (int origin = r & m, k = origin, oldSum = 0, checkSum = 0;;) {
                WorkQueue q; int b;
                if ((q = ws[k]) != null) {
                    if ((b = q.base) - q.top < 0)
                        return q; // 找到非空的任务队列
                    checkSum += b;
                }
                // k+1,遍历下一个WorkQueue
                // 再次等于origin说明所有WorkQueue都遍历了一遍
                // 第一次进入if分支，oldSum赋值，checkSum等于0，第二次进入if分支，如果checkSum不变说明
                // 这期间没有新的任务提交到任务队列中且所有队列都是空的，则终止for循环返回null
                if ((k = (k + 1) & m) == origin) {
                    if (oldSum == (oldSum = checkSum))
                        break;
                    checkSum = 0;
                }
            }
        }
        return null;
    }

    /**
     * Runs tasks until {@code isQuiescent()}. We piggyback on
     * active count ctl maintenance, but rather than blocking
     * when tasks cannot be found, we rescan until all others cannot
     * find tasks either.
     */
    // w是调用awaitQuiescence的Worker线程关联的任务队列
    final void helpQuiescePool(WorkQueue w) {
        ForkJoinTask<?> ps = w.currentSteal; // save context
        for (boolean active = true;;) {
            long c; WorkQueue q; ForkJoinTask<?> t; int b;
            w.execLocalTasks();     // run locals before each scan 执行完队列中所有任务
            if ((q = findNonEmptyStealQueue()) != null) {
                // 如果有包含待处理任务的队列
                if (!active) {      // re-establish active count
                    active = true;
                    U.getAndAddLong(this, CTL, AC_UNIT);
                }
                if ((b = q.base) - q.top < 0 && (t = q.pollAt(b)) != null) {
                    // 从q中偷一个任务出来执行，当前线程相当于一个获取线程
                    U.putOrderedObject(w, QCURRENTSTEAL, t);
                    t.doExec();
                    if (++w.nsteals < 0) // nsteals达到最大值
                        w.transferStealCount(this);
                }
            }
            // 所有队列都是空的，不包含待处理的任务
            // active为true
            else if (active) {      // decrement active count without queuing
                // AC减1
                long nc = (AC_MASK & ((c = ctl) - AC_UNIT)) | (~AC_MASK & c);
                if ((int)(nc >> AC_SHIFT) + (config & SMASK) <= 0)
                    break;          // bypass decrement-then-increment 如果AC为0
                if (U.compareAndSwapLong(this, CTL, c, nc))
                    active = false;
            }
            // active为false
            else if ((int)((c = ctl) >> AC_SHIFT) + (config & SMASK) <= 0 && // AC为0，修改ctl将AC加1
                     U.compareAndSwapLong(this, CTL, c, c + AC_UNIT))
                break;
        }
        // 重新恢复currentSteal属性
        U.putOrderedObject(w, QCURRENTSTEAL, ps);
    }

    /**
     * Gets and removes a local or stolen task for the given worker.
     *
     * @return a task, if available
     */
    final ForkJoinTask<?> nextTaskFor(WorkQueue w) {
        for (ForkJoinTask<?> t;;) {
            WorkQueue q; int b;
            if ((t = w.nextLocalTask()) != null)
                return t;
            if ((q = findNonEmptyStealQueue()) == null)
                return null;
            if ((b = q.base) - q.top < 0 && (t = q.pollAt(b)) != null)
                return t;
        }
    }

    /**
     * Returns a cheap heuristic guide for task partitioning when
     * programmers, frameworks, tools, or languages have little or no
     * idea about task granularity.  In essence, by offering this
     * method, we ask users only about tradeoffs in overhead vs
     * expected throughput and its variance, rather than how finely to
     * partition tasks.
     *
     * In a steady state strict (tree-structured) computation, each
     * thread makes available for stealing enough tasks for other
     * threads to remain active. Inductively, if all threads play by
     * the same rules, each thread should make available only a
     * constant number of tasks.
     *
     * The minimum useful constant is just 1. But using a value of 1
     * would require immediate replenishment upon each steal to
     * maintain enough tasks, which is infeasible.  Further,
     * partitionings/granularities of offered tasks should minimize
     * steal rates, which in general means that threads nearer the top
     * of computation tree should generate more than those nearer the
     * bottom. In perfect steady state, each thread is at
     * approximately the same level of computation tree. However,
     * producing extra tasks amortizes the uncertainty of progress and
     * diffusion assumptions.
     *
     * So, users will want to use values larger (but not much larger)
     * than 1 to both smooth over transient shortages and hedge
     * against uneven progress; as traded off against the cost of
     * extra task overhead. We leave the user to pick a threshold
     * value to compare with the results of this call to guide
     * decisions, but recommend values such as 3.
     *
     * When all threads are active, it is on average OK to estimate
     * surplus strictly locally. In steady-state, if one thread is
     * maintaining say 2 surplus tasks, then so are others. So we can
     * just use estimated queue length.  However, this strategy alone
     * leads to serious mis-estimates in some non-steady-state
     * conditions (ramp-up, ramp-down, other stalls). We can detect
     * many of these by further considering the number of "idle"
     * threads, that are known to have zero queued tasks, so
     * compensate by a factor of (#idle/#active) threads.
     */
    static int getSurplusQueuedTaskCount() {
        Thread t; ForkJoinWorkerThread wt; ForkJoinPool pool; WorkQueue q;
        if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)) {
            int p = (pool = (wt = (ForkJoinWorkerThread)t).pool).
                config & SMASK;
            int n = (q = wt.workQueue).top - q.base;
            int a = (int)(pool.ctl >> AC_SHIFT) + p;
            return n - (a > (p >>>= 1) ? 0 :
                        a > (p >>>= 1) ? 1 :
                        a > (p >>>= 1) ? 2 :
                        a > (p >>>= 1) ? 4 :
                        8);
        }
        return 0;
    }

    //  Termination

    /**
     * Possibly initiates and/or completes termination.
     *
     * @param now if true, unconditionally terminate, else only
     * if no work and no active workers
     * @param enable if true, enable shutdown when next possible
     * @return true if now terminating or terminated
     */
    // 尝试去终止线程池
    // now为true的时候，会无条件的终止，将所有WorkQueue中未执行的任务都取消掉，唤醒休眠的Worker线程，将正在执行的Worker线程标记为已中断，对应shutdownNow方法
    // enable为true，now为false，对应shutdown方法，不再接受新的任务，会等待WorkQueue未执行完成的任务都执行完
    // 两个都false时，如果此时runState小于0，即已经在关闭的过程中，则会尝试去关闭
    private boolean tryTerminate(boolean now, boolean enable) {
        int rs;
        if (this == common)                       // cannot shut down common线程池不能被关闭
            return false;
        if ((rs = runState) >= 0) { // 线程池正常运行
            if (!enable) // 如果enable为false
                return false;
            // 如果enable为true
            rs = lockRunState();                  // enter SHUTDOWN phase 获取锁，然后解锁修改state，加上SHUTDOWN标识
            unlockRunState(rs, (rs & ~RSLOCK) | SHUTDOWN);
        }
        // enable为true或者runState中有SHUTDOWN标识
        if ((rs & STOP) == 0) { // 如果状态不是STOP
            if (!now) {                           // check quiescence
                // now为false
                for (long oldSum = 0L;;) {        // repeat until stable
                    WorkQueue[] ws; WorkQueue w; int m, b; long c;
                    long checkSum = ctl;
                    if ((int)(checkSum >> AC_SHIFT) + (config & SMASK) > 0)
                        return false;             // still active workers 如果还有活跃的线程，返回false，等待线程执行完成
                    if ((ws = workQueues) == null || (m = ws.length - 1) <= 0) // 如果线程池未启动
                        break;                    // check queues workQueues未初始化或者长度为1
                    // 遍历所有的WorkQueue
                    for (int i = 0; i <= m; ++i) {
                        if ((w = ws[i]) != null) {
                            if ((b = w.base) != w.top || w.scanState >= 0 ||
                                w.currentSteal != null) { // 如果w中有未执行完成的任务
                                // 唤醒空闲的Worker线程
                                tryRelease(c = ctl, ws[m & (int)c], AC_UNIT);
                                return false;     // arrange for recheck
                            }
                            checkSum += b;
                            if ((i & 1) == 0) // 这类WorkQueue都不是跟Worker线程绑定的WorkerQueue
                                w.qlock = -1;     // try to disable external 如果i是偶数，将对应的WorkQueue的qlock置为-1，不再接受新的任务
                        }
                    }
                    // 所有的WorkQueue遍历了多遍，没有活跃的线程数了，也没有待执行的任务了，且checkSum稳定了，说明该退出的能退出的线程都退出了，则跳出for循环，将
                    // runState加上STOP
                    if (oldSum == (oldSum = checkSum))
                        break;
                }
            }
            // 如果now为true 或者上面break跳出for循环
            if ((runState & STOP) == 0) {
                rs = lockRunState();              // enter STOP phase 获取锁，然后解锁修改state，加上STOP
                unlockRunState(rs, (rs & ~RSLOCK) | STOP);
            }
        }
        // 状态已经是Stop，pass表示外层for循环的次数
        int pass = 0;                             // 3 passes to help terminate
        for (long oldSum = 0L;;) {                // or until done or stable
            WorkQueue[] ws; WorkQueue w; ForkJoinWorkerThread wt; int m;
            long checkSum = ctl;
            if ((short)(checkSum >>> TC_SHIFT) + (config & SMASK) <= 0 ||
                (ws = workQueues) == null || (m = ws.length - 1) <= 0) { // 如果所有线程都退出了
                if ((runState & TERMINATED) == 0) {
                    rs = lockRunState();          // done 修改状态，加上TERMINATED
                    unlockRunState(rs, (rs & ~RSLOCK) | TERMINATED);
                    synchronized (this) { notifyAll(); } // for awaitTermination 唤醒等待终止完成的线程
                }
                break;
            }
            // 还有未退出的线程，遍历所有的WorkQueue
            for (int i = 0; i <= m; ++i) {
                if ((w = ws[i]) != null) {
                    checkSum += w.base;
                    w.qlock = -1;                 // try to disable 置为-1，不再接受新的任务
                    if (pass > 0) {
                        w.cancelAll();            // clear queue 清空所有的任务
                        if (pass > 1 && (wt = w.owner) != null) {
                            if (!wt.isInterrupted()) {
                                try {             // unblock join 将关联的Worker线程标记为中断
                                    wt.interrupt();
                                } catch (Throwable ignore) {
                                }
                            }
                            if (w.scanState < 0)
                                // 唤醒休眠中的worker线程，被唤醒后发现线程池终止了会自动退出，线程退出会将对应的WorkQueue置为null
                                // 下一次for循环计算checkSum时值就变了
                                U.unpark(wt);     // wake up
                        }
                    }
                }
            }
            if (checkSum != oldSum) {             // unstable 重置oldSum，重新遍历
                oldSum = checkSum;
                pass = 0;
            }
            // checkSum等于oldSum，可能某个Worker线程在执行任务的过程中被长期阻塞了一直未退出，则此时pass会不断加1
            // 超过一定次数后则终止外层for循环
            else if (pass > 3 && pass > m)        // can't further help
                break;
            else if (++pass > 1) {                // try to dequeue pass加1，如果大于1
                long c; int j = 0, sp;            // bound attempts
                while (j++ <= m && (sp = (int)(c = ctl)) != 0)
                    tryRelease(c, ws[sp & m], AC_UNIT); // 唤醒休眠的Worker线程
            }
        } // for循环结束
        return true;
    }

    // External operations

    /**
     * Full version of externalPush, handling uncommon cases, as well
     * as performing secondary initialization upon the first
     * submission of the first task to the pool.  It also detects
     * first submission by an external thread and creates a new shared
     * queue if the one at index if empty or contended.
     *
     * @param task the task. Caller must ensure non-null.
     */
    private void externalSubmit(ForkJoinTask<?> task) {
        int r;                                    // initialize caller's probe
        if ((r = ThreadLocalRandom.getProbe()) == 0) { // 初始化调用线程的Probe
            ThreadLocalRandom.localInit();
            r = ThreadLocalRandom.getProbe();
        }
        for (;;) {
            WorkQueue[] ws; WorkQueue q; int rs, m, k;
            boolean move = false;
            if ((rs = runState) < 0) { // 如果线程池已关闭，则尝试终止，并抛出异常拒绝此次任务
                tryTerminate(false, false);     // help terminate
                throw new RejectedExecutionException();
            }
            else if ((rs & STARTED) == 0 ||     // initialize 如果线程池还未启动，未初始化
                     ((ws = workQueues) == null || (m = ws.length - 1) < 0)) {
                int ns = 0;
                rs = lockRunState(); // 加锁
                try {
                    if ((rs & STARTED) == 0) { // 再次校验未初始化
                        U.compareAndSwapObject(this, STEALCOUNTER, null,
                                               new AtomicLong()); // 初始化stealCounter属性
                        // create workQueues array with size a power of two
                        // 获取并行度，计算对应的数组长度
                        int p = config & SMASK; // ensure at least 2 slots
                        int n = (p > 1) ? p - 1 : 1;
                        // p是3，即4核，n为2时，计算的结果是8
                        // p是7，即8核，n为6时，计算的结果是16，算出来的n是大于2n的最小的2的整数次幂的值
                        n |= n >>> 1; n |= n >>> 2;  n |= n >>> 4;
                        n |= n >>> 8; n |= n >>> 16; n = (n + 1) << 1;
                        // 初始化workQueues
                        workQueues = new WorkQueue[n];
                        ns = STARTED;
                    }
                } finally {
                    unlockRunState(rs, (rs & ~RSLOCK) | ns);
                }
            }
            // 线程池已启动
            else if ((q = ws[k = r & m & SQMASK]) != null) {
                // 关联的WorkQueue不为null
                if (q.qlock == 0 && U.compareAndSwapInt(q, QLOCK, 0, 1)) { // 将WorkQueue加锁
                    ForkJoinTask<?>[] a = q.array;
                    int s = q.top;
                    boolean submitted = false; // initial submission or resizing
                    try {                      // locked version of push
                        if ((a != null && a.length > s + 1 - q.base) || // 如果任务数组有剩余空间
                            (a = q.growArray()) != null) { // 如果任务数组中没有剩余空间，则扩容
                            // 计算任务数组中保存的位置并保存
                            int j = (((a.length - 1) & s) << ASHIFT) + ABASE;
                            U.putOrderedObject(a, j, task);
                            U.putOrderedInt(q, QTOP, s + 1); // top属性加1
                            submitted = true;
                        }
                    } finally {
                        // 解锁
                        U.compareAndSwapInt(q, QLOCK, 1, 0);
                    }
                    if (submitted) { // 如果任务已经提交到WorkQueue中
                        signalWork(ws, q); // 新增Worker或者唤醒空闲的Worker
                        return;
                    }
                }
                // WorkQueue加锁失败
                move = true;                   // move on failure
            }
            // 关联的WorkQueue为null
            else if (((rs = runState) & RSLOCK) == 0) { // create new queue 线程池未加锁
                q = new WorkQueue(this, null);
                q.hint = r; // 使用当前线程的probe初始化hint
                q.config = k | SHARED_QUEUE; // 模式是共享的，即不属于某个特定的Worker，k表示该WorkQueue在数组中的位置
                q.scanState = INACTIVE;
                rs = lockRunState();           // publish index 加锁
                if (rs > 0 &&  (ws = workQueues) != null && // 线程池已启动且workQueues非空
                    k < ws.length && ws[k] == null) // 再次校验关联的WorkQueue为null
                    ws[k] = q;                 // else terminated 将新的WorkQueue保存起来
                unlockRunState(rs, rs & ~RSLOCK); // 解锁，下一次for循环就将任务保存到该WorkQueue中
            }
            else
                move = true;                   // move if busy
            if (move) // 如果WorkQueue加锁失败，则增加probe属性，下一次for循环则遍历下一个WorkQueue,即将该Task提交到其他的WorkQueue中
                r = ThreadLocalRandom.advanceProbe(r);
        } // for循环结束
    }

    /**
     * Tries to add the given task to a submission queue at
     * submitter's current queue. Only the (vastly) most common path
     * is directly handled in this method, while screening for need
     * for externalSubmit.
     *
     * @param task the task. Caller must ensure non-null.
     */
    final void externalPush(ForkJoinTask<?> task) {
        WorkQueue[] ws; WorkQueue q; int m;
        int r = ThreadLocalRandom.getProbe();
        int rs = runState; // runState的初始值为0
        if ((ws = workQueues) != null && (m = (ws.length - 1)) >= 0 && // 如果workQueues非空，该属性默认为null，说明线程池已初始化
            (q = ws[m & r & SQMASK]) != null && // 计算存放任务的WorkQueue,如果对应的WorkQueue非空
            r != 0 && rs > 0 && // r不等于0说明当前线程的probe属性已初始化，rs大于0说明线程池已初始化且是正常运行的
            U.compareAndSwapInt(q, QLOCK, 0, 1)) { // 对WorkQueue加锁
            ForkJoinTask<?>[] a; int am, n, s;
            if ((a = q.array) != null && // 保存任务的数组非空且有可用空间
                (am = a.length - 1) > (n = (s = q.top) - q.base)) {
                // 计算保存的位置并保存到数组中
                int j = ((am & s) << ASHIFT) + ABASE;
                U.putOrderedObject(a, j, task);
                U.putOrderedInt(q, QTOP, s + 1); // top属性加1
                U.putIntVolatile(q, QLOCK, 0); // 解锁
                if (n <= 1)
                    // 原任务队列是空的，此时新增了一个任务,则尝试新增Worker或者唤醒空闲的Worker
                    signalWork(ws, q);
                return;
            }
            // WorkQueue解锁
            U.compareAndSwapInt(q, QLOCK, 1, 0);
        }
        // 初始化调用线程的Probe，线程池和关联的WorkQueue
        // 如果已初始化，则将任务保存到WorkQueue中
        externalSubmit(task);
    }

    /**
     * Returns common pool queue for an external thread.
     */
    static WorkQueue commonSubmitterQueue() {
        ForkJoinPool p = common;
        int r = ThreadLocalRandom.getProbe();
        WorkQueue[] ws; int m;
        return (p != null && (ws = p.workQueues) != null &&
                (m = ws.length - 1) >= 0) ?
            ws[m & r & SQMASK] : null;
    }

    /**
     * Performs tryUnpush for an external submitter: Finds queue,
     * locks if apparently non-empty, validates upon locking, and
     * adjusts top. Each check can fail but rarely does.
     */
    // 如果task是上一次插入的且将其从队列成功移除，则返回true，否则返回false
    final boolean tryExternalUnpush(ForkJoinTask<?> task) {
        WorkQueue[] ws; WorkQueue w; ForkJoinTask<?>[] a; int m, s;
        int r = ThreadLocalRandom.getProbe();
        if ((ws = workQueues) != null && (m = ws.length - 1) >= 0 && // 线程池已初始化
            (w = ws[m & r & SQMASK]) != null && // 关联的WorkQueue非空
            (a = w.array) != null && (s = w.top) != w.base) { // 关联的WorkQueue包含有未执行的任务
            // j是上一次插入到数组中的索引
            long j = (((a.length - 1) & (s - 1)) << ASHIFT) + ABASE;
            if (U.compareAndSwapInt(w, QLOCK, 0, 1)) { // 队列加锁
                if (w.top == s && w.array == a && // top和array属性未变更
                    U.getObject(a, j) == task && // 如果上一次插入到数组中的任务就是task
                    U.compareAndSwapObject(a, j, task, null)) { // 成功将其修改为null
                    U.putOrderedInt(w, QTOP, s - 1); // top属性减1
                    U.putOrderedInt(w, QLOCK, 0); // 队列解锁
                    return true;
                }
                U.compareAndSwapInt(w, QLOCK, 1, 0); // 队列解锁
            }
        }
        return false;
    }

    /**
     * Performs helpComplete for an external submitter.
     */
    // externalHelpComplete基于helpComplete实现，会遍历WorkQueue数组找到目标task，然后执行该任务
    final int externalHelpComplete(CountedCompleter<?> task, int maxTasks) {
        WorkQueue[] ws; int n;
        int r = ThreadLocalRandom.getProbe();
        return ((ws = workQueues) == null || (n = ws.length) == 0) ? 0 :
            helpComplete(ws[(n - 1) & r & SQMASK], task, maxTasks);
    }

    // Exported methods

    // Constructors

    /**
     * Creates a {@code ForkJoinPool} with parallelism equal to {@link
     * java.lang.Runtime#availableProcessors}, using the {@linkplain
     * #defaultForkJoinWorkerThreadFactory default thread factory},
     * no UncaughtExceptionHandler, and non-async LIFO processing mode.
     *
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool() {
        this(Math.min(MAX_CAP, Runtime.getRuntime().availableProcessors()),
             defaultForkJoinWorkerThreadFactory, null, false);
    }

    /**
     * Creates a {@code ForkJoinPool} with the indicated parallelism
     * level, the {@linkplain
     * #defaultForkJoinWorkerThreadFactory default thread factory},
     * no UncaughtExceptionHandler, and non-async LIFO processing mode.
     *
     * @param parallelism the parallelism level
     * @throws IllegalArgumentException if parallelism less than or
     *         equal to zero, or greater than implementation limit
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool(int parallelism) {
        this(parallelism, defaultForkJoinWorkerThreadFactory, null, false);
    }

    /**
     * Creates a {@code ForkJoinPool} with the given parameters.
     *
     * @param parallelism the parallelism level. For default value,
     * use {@link java.lang.Runtime#availableProcessors}.
     * @param factory the factory for creating new threads. For default value,
     * use {@link #defaultForkJoinWorkerThreadFactory}.
     * @param handler the handler for internal worker threads that
     * terminate due to unrecoverable errors encountered while executing
     * tasks. For default value, use {@code null}.
     * @param asyncMode if true,
     * establishes local first-in-first-out scheduling mode for forked
     * tasks that are never joined. This mode may be more appropriate
     * than default locally stack-based mode in applications in which
     * worker threads only process event-style asynchronous tasks.
     * For default value, use {@code false}.
     * @throws IllegalArgumentException if parallelism less than or
     *         equal to zero, or greater than implementation limit
     * @throws NullPointerException if the factory is null
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool(int parallelism,
                        ForkJoinWorkerThreadFactory factory,
                        UncaughtExceptionHandler handler,
                        boolean asyncMode) {
        this(checkParallelism(parallelism),
             checkFactory(factory),
             handler,
             asyncMode ? FIFO_QUEUE : LIFO_QUEUE,
             "ForkJoinPool-" + nextPoolId() + "-worker-");
        checkPermission();
    }

    private static int checkParallelism(int parallelism) {
        if (parallelism <= 0 || parallelism > MAX_CAP)
            throw new IllegalArgumentException();
        return parallelism;
    }

    private static ForkJoinWorkerThreadFactory checkFactory
        (ForkJoinWorkerThreadFactory factory) {
        if (factory == null)
            throw new NullPointerException();
        return factory;
    }

    /**
     * Creates a {@code ForkJoinPool} with the given parameters, without
     * any security checks or parameter validation.  Invoked directly by
     * makeCommonPool.
     */
    private ForkJoinPool(int parallelism,
                         ForkJoinWorkerThreadFactory factory,
                         UncaughtExceptionHandler handler,
                         int mode,
                         String workerNamePrefix) {
        this.workerNamePrefix = workerNamePrefix;
        this.factory = factory;
        this.ueh = handler;
        // parallelism不能超过MAX_CAP，跟SMASK求且的结果不变
        this.config = (parallelism & SMASK) | mode;
        long np = (long)(-parallelism); // offset ctl counts
        // 左移48位后取高16位 和 左移32位后取第二个16位
        // 以parallelism是3为例，此时ctl是1111111111111101111111111111110100000000000000000000000000000000
        this.ctl = ((np << AC_SHIFT) & AC_MASK) | ((np << TC_SHIFT) & TC_MASK);
    }

    /**
     * Returns the common pool instance. This pool is statically
     * constructed; its run state is unaffected by attempts to {@link
     * #shutdown} or {@link #shutdownNow}. However this pool and any
     * ongoing processing are automatically terminated upon program
     * {@link System#exit}.  Any program that relies on asynchronous
     * task processing to complete before program termination should
     * invoke {@code commonPool().}{@link #awaitQuiescence awaitQuiescence},
     * before exit.
     *
     * @return the common pool instance
     * @since 1.8
     */
    public static ForkJoinPool commonPool() {
        // assert common != null : "static init error";
        return common;
    }

    // Execution methods

    /**
     * Performs the given task, returning its result upon completion.
     * If the computation encounters an unchecked Exception or Error,
     * it is rethrown as the outcome of this invocation.  Rethrown
     * exceptions behave in the same way as regular exceptions, but,
     * when possible, contain stack traces (as displayed for example
     * using {@code ex.printStackTrace()}) of both the current thread
     * as well as the thread actually encountering the exception;
     * minimally only the latter.
     *
     * @param task the task
     * @param <T> the type of the task's result
     * @return the task's result
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    // 将指定任务提交到任务队列中并阻塞当前线程等待任务执行完成
    public <T> T invoke(ForkJoinTask<T> task) {
        if (task == null)
            throw new NullPointerException();
        // 将新任务加入到任务队列中
        externalPush(task);
        // 等待任务执行完成
        return task.join();
    }

    /**
     * Arranges for (asynchronous) execution of the given task.
     *
     * @param task the task
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public void execute(ForkJoinTask<?> task) {
        if (task == null)
            throw new NullPointerException();
        // 将新任务加入到任务队列中
        externalPush(task);
    }

    // AbstractExecutorService methods

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public void execute(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<?> job;
        if (task instanceof ForkJoinTask<?>) // avoid re-wrap
            job = (ForkJoinTask<?>) task;
        else
            // 如果不是ForkJoinTask，则进行一层保证
            job = new ForkJoinTask.RunnableExecuteAction(task);
        // 将新任务加入到任务队列中
        externalPush(job);
    }

    /**
     * Submits a ForkJoinTask for execution.
     *
     * @param task the task to submit
     * @param <T> the type of the task's result
     * @return the task
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task) {
        if (task == null)
            throw new NullPointerException();
        // 将新任务加入到任务队列中
        externalPush(task);
        return task;
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        // 将Callable进行包装
        ForkJoinTask<T> job = new ForkJoinTask.AdaptedCallable<T>(task);
        // 将新任务加入到任务队列中
        externalPush(job);
        return job;
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        // 将Runnable给包装成ForkJoinTask
        ForkJoinTask<T> job = new ForkJoinTask.AdaptedRunnable<T>(task, result);
        externalPush(job);
        return job;
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public ForkJoinTask<?> submit(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<?> job;
        if (task instanceof ForkJoinTask<?>) // avoid re-wrap
            job = (ForkJoinTask<?>) task;
        else
            // 将Runnable给包装成ForkJoinTask
            job = new ForkJoinTask.AdaptedRunnableAction(task);
        externalPush(job);
        return job;
    }

    /**
     * @throws NullPointerException       {@inheritDoc}
     * @throws RejectedExecutionException {@inheritDoc}
     */
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        // In previous versions of this class, this method constructed
        // a task to run ForkJoinTask.invokeAll, but now external
        // invocation of multiple tasks is at least as efficient.
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());

        boolean done = false;
        try {
            for (Callable<T> t : tasks) {
                // Callable包装成ForkJoinTask
                ForkJoinTask<T> f = new ForkJoinTask.AdaptedCallable<T>(t);
                futures.add(f);
                externalPush(f);
            }
            for (int i = 0, size = futures.size(); i < size; i++)
                ((ForkJoinTask<?>)futures.get(i)).quietlyJoin(); // 等待任务执行完成
            done = true;
            return futures;
        } finally {
            if (!done)
                for (int i = 0, size = futures.size(); i < size; i++)
                    futures.get(i).cancel(false); // 出现异常取消掉剩余的未执行任务
        }
    }

    /**
     * Returns the factory used for constructing new workers.
     *
     * @return the factory used for constructing new workers
     */
    public ForkJoinWorkerThreadFactory getFactory() {
        return factory;
    }

    /**
     * Returns the handler for internal worker threads that terminate
     * due to unrecoverable errors encountered while executing tasks.
     *
     * @return the handler, or {@code null} if none
     */
    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return ueh;
    }

    /**
     * Returns the targeted parallelism level of this pool.
     *
     * @return the targeted parallelism level of this pool
     */
    public int getParallelism() {
        int par;
        return ((par = config & SMASK) > 0) ? par : 1;
    }

    /**
     * Returns the targeted parallelism level of the common pool.
     *
     * @return the targeted parallelism level of the common pool
     * @since 1.8
     */
    public static int getCommonPoolParallelism() {
        return commonParallelism;
    }

    /**
     * Returns the number of worker threads that have started but not
     * yet terminated.  The result returned by this method may differ
     * from {@link #getParallelism} when threads are created to
     * maintain parallelism when others are cooperatively blocked.
     *
     * @return the number of worker threads
     */
    public int getPoolSize() {
        return (config & SMASK) + (short)(ctl >>> TC_SHIFT);
    }

    /**
     * Returns {@code true} if this pool uses local first-in-first-out
     * scheduling mode for forked tasks that are never joined.
     *
     * @return {@code true} if this pool uses async mode
     */
    public boolean getAsyncMode() {
        return (config & FIFO_QUEUE) != 0;
    }

    /**
     * Returns an estimate of the number of worker threads that are
     * not blocked waiting to join tasks or for other managed
     * synchronization. This method may overestimate the
     * number of running threads.
     *
     * @return the number of worker threads
     */
    public int getRunningThreadCount() {
        int rc = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && w.isApparentlyUnblocked())
                    ++rc;
            }
        }
        return rc;
    }

    /**
     * Returns an estimate of the number of threads that are currently
     * stealing or executing tasks. This method may overestimate the
     * number of active threads.
     *
     * @return the number of active threads
     */
    public int getActiveThreadCount() {
        int r = (config & SMASK) + (int)(ctl >> AC_SHIFT);
        return (r <= 0) ? 0 : r; // suppress momentarily negative values
    }

    /**
     * Returns {@code true} if all worker threads are currently idle.
     * An idle worker is one that cannot obtain a task to execute
     * because none are available to steal from other threads, and
     * there are no pending submissions to the pool. This method is
     * conservative; it might not return {@code true} immediately upon
     * idleness of all threads, but will eventually become true if
     * threads remain inactive.
     *
     * @return {@code true} if all threads are currently idle
     */
    // 返回true，表示活跃的线程数为0，即所有的Worker线程目前都是空闲状态
    public boolean isQuiescent() {
        return (config & SMASK) + (int)(ctl >> AC_SHIFT) <= 0;
    }

    /**
     * Returns an estimate of the total number of tasks stolen from
     * one thread's work queue by another. The reported value
     * underestimates the actual total number of steals when the pool
     * is not quiescent. This value may be useful for monitoring and
     * tuning fork/join programs: in general, steal counts should be
     * high enough to keep threads busy, but low enough to avoid
     * overhead and contention across threads.
     *
     * @return the number of steals
     */
    public long getStealCount() {
        AtomicLong sc = stealCounter;
        long count = (sc == null) ? 0L : sc.get();
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.nsteals;
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the total number of tasks currently held
     * in queues by worker threads (but not including tasks submitted
     * to the pool that have not begun executing). This value is only
     * an approximation, obtained by iterating across all threads in
     * the pool. This method may be useful for tuning task
     * granularities.
     *
     * @return the number of queued tasks
     */
    // 获取跟Worker线程绑定的WorkQueue中包含的所有待处理任务总数
    public long getQueuedTaskCount() {
        long count = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            // 从i=1开始遍历，遍历所有奇数的WorkQueue，累加待处理的任务数
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the number of tasks submitted to this
     * pool that have not yet begun executing.  This method may take
     * time proportional to the number of submissions.
     *
     * @return the number of queued submissions
     */
    // 获取没有跟Worker线程绑定的WorkQueue中包含的所有待处理任务总数，这部分待处理任务是由Worker线程通过scan方法获取并处理的
    public int getQueuedSubmissionCount() {
        int count = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            // 从i=1开始遍历，遍历所有偶数的WorkQueue，累加待处理的任务数
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns {@code true} if there are any tasks submitted to this
     * pool that have not yet begun executing.
     *
     * @return {@code true} if there are any queued submissions
     */
    public boolean hasQueuedSubmissions() {
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            // 从i=1开始遍历，遍历所有偶数的WorkQueue，累加待处理的任务数
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && !w.isEmpty())
                    return true;
            }
        }
        return false;
    }

    /**
     * Removes and returns the next unexecuted submission if one is
     * available.  This method may be useful in extensions to this
     * class that re-assign work in systems with multiple pools.
     *
     * @return the next submission, or {@code null} if none
     */
    protected ForkJoinTask<?> pollSubmission() {
        WorkQueue[] ws; WorkQueue w; ForkJoinTask<?> t;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && (t = w.poll()) != null)
                    return t;
            }
        }
        return null;
    }

    /**
     * Removes all available unexecuted submitted and forked tasks
     * from scheduling queues and adds them to the given collection,
     * without altering their execution status. These may include
     * artificially generated or wrapped tasks. This method is
     * designed to be invoked only when the pool is known to be
     * quiescent. Invocations at other times may not remove all
     * tasks. A failure encountered while attempting to add elements
     * to collection {@code c} may result in elements being in
     * neither, either or both collections when the associated
     * exception is thrown.  The behavior of this operation is
     * undefined if the specified collection is modified while the
     * operation is in progress.
     *
     * @param c the collection to transfer elements into
     * @return the number of elements transferred
     */
    protected int drainTasksTo(Collection<? super ForkJoinTask<?>> c) {
        int count = 0;
        WorkQueue[] ws; WorkQueue w; ForkJoinTask<?> t;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; ++i) {
                if ((w = ws[i]) != null) {
                    while ((t = w.poll()) != null) {
                        c.add(t);
                        ++count;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Returns a string identifying this pool, as well as its state,
     * including indications of run state, parallelism level, and
     * worker and task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        // Use a single pass through workQueues to collect counts
        long qt = 0L, qs = 0L; int rc = 0;
        AtomicLong sc = stealCounter;
        long st = (sc == null) ? 0L : sc.get();
        long c = ctl;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; ++i) {
                if ((w = ws[i]) != null) {
                    int size = w.queueSize();
                    if ((i & 1) == 0)
                        qs += size;
                    else {
                        qt += size;
                        st += w.nsteals;
                        if (w.isApparentlyUnblocked())
                            ++rc;
                    }
                }
            }
        }
        int pc = (config & SMASK);
        int tc = pc + (short)(c >>> TC_SHIFT);
        int ac = pc + (int)(c >> AC_SHIFT);
        if (ac < 0) // ignore transient negative
            ac = 0;
        int rs = runState;
        String level = ((rs & TERMINATED) != 0 ? "Terminated" :
                        (rs & STOP)       != 0 ? "Terminating" :
                        (rs & SHUTDOWN)   != 0 ? "Shutting down" :
                        "Running");
        return super.toString() +
            "[" + level +
            ", parallelism = " + pc +
            ", size = " + tc +
            ", active = " + ac +
            ", running = " + rc +
            ", steals = " + st +
            ", tasks = " + qt +
            ", submissions = " + qs +
            "]";
    }

    /**
     * Possibly initiates an orderly shutdown in which previously
     * submitted tasks are executed, but no new tasks will be
     * accepted. Invocation has no effect on execution state if this
     * is the {@link #commonPool()}, and no additional effect if
     * already shut down.  Tasks that are in the process of being
     * submitted concurrently during the course of this method may or
     * may not be rejected.
     *
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public void shutdown() {
        checkPermission();
        tryTerminate(false, true);
    }

    /**
     * Possibly attempts to cancel and/or stop all tasks, and reject
     * all subsequently submitted tasks.  Invocation has no effect on
     * execution state if this is the {@link #commonPool()}, and no
     * additional effect if already shut down. Otherwise, tasks that
     * are in the process of being submitted or executed concurrently
     * during the course of this method may or may not be
     * rejected. This method cancels both existing and unexecuted
     * tasks, in order to permit termination in the presence of task
     * dependencies. So the method always returns an empty list
     * (unlike the case for some other Executors).
     *
     * @return an empty list
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public List<Runnable> shutdownNow() {
        checkPermission();
        tryTerminate(true, true);
        return Collections.emptyList();
    }

    /**
     * Returns {@code true} if all tasks have completed following shut down.
     *
     * @return {@code true} if all tasks have completed following shut down
     */
    public boolean isTerminated() {
        return (runState & TERMINATED) != 0;
    }

    /**
     * Returns {@code true} if the process of termination has
     * commenced but not yet completed.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, or are waiting for I/O,
     * causing this executor not to properly terminate. (See the
     * advisory notes for class {@link ForkJoinTask} stating that
     * tasks should not normally entail blocking operations.  But if
     * they do, they must abort them on interrupt.)
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        int rs = runState;
        return (rs & STOP) != 0 && (rs & TERMINATED) == 0;
    }

    /**
     * Returns {@code true} if this pool has been shut down.
     *
     * @return {@code true} if this pool has been shut down
     */
    public boolean isShutdown() {
        return (runState & SHUTDOWN) != 0;
    }

    /**
     * Blocks until all tasks have completed execution after a
     * shutdown request, or the timeout occurs, or the current thread
     * is interrupted, whichever happens first. Because the {@link
     * #commonPool()} never terminates until program shutdown, when
     * applied to the common pool, this method is equivalent to {@link
     * #awaitQuiescence(long, TimeUnit)} but always returns {@code false}.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if this executor terminated and
     *         {@code false} if the timeout elapsed before termination
     * @throws InterruptedException if interrupted while waiting
     */
    // 等待当前线程池终止
    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        if (Thread.interrupted()) // 如果线程被中断
            throw new InterruptedException();
        if (this == common) { // 如果是common线程池
            awaitQuiescence(timeout, unit);
            return false;
        }
        // 如果不是common线程池
        long nanos = unit.toNanos(timeout);
        if (isTerminated()) // 如果已中断
            return true;
        if (nanos <= 0L) // 等待的时间为0
            return false;
        // 计算等待的终止时间
        long deadline = System.nanoTime() + nanos;
        synchronized (this) {
            for (;;) {
                if (isTerminated()) // 已终止，返回true
                    return true;
                if (nanos <= 0L) // 等待超时
                    return false;
                long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
                // 执行等待
                wait(millis > 0L ? millis : 1L);
                nanos = deadline - System.nanoTime();
            }
        }
    }

    /**
     * If called by a ForkJoinTask operating in this pool, equivalent
     * in effect to {@link ForkJoinTask#helpQuiesce}. Otherwise,
     * waits and/or attempts to assist performing tasks until this
     * pool {@link #isQuiescent} or the indicated timeout elapses.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if quiescent; {@code false} if the
     * timeout elapsed.
     */
    // awaitQuiescence是等待common线程池终止的实现，会不断的从包含有未执行的任务的任务队列中获取待执行的任务并执行，直到所有的任务队列都为空，所有任务都执行完成。
    public boolean awaitQuiescence(long timeout, TimeUnit unit) {
        long nanos = unit.toNanos(timeout);
        ForkJoinWorkerThread wt;
        Thread thread = Thread.currentThread();
        if ((thread instanceof ForkJoinWorkerThread) &&
            (wt = (ForkJoinWorkerThread)thread).pool == this) {
            // 如果当前线程是common线程池中的Worker线程
            helpQuiescePool(wt.workQueue);
            return true;
        }
        // 如果不是common线程池中的Worker线程
        long startTime = System.nanoTime();
        WorkQueue[] ws;
        int r = 0, m;
        boolean found = true;
        while (!isQuiescent() // 有活跃的线程
                && (ws = workQueues) != null &&
               (m = ws.length - 1) >= 0) { // 线程池已初始化
            if (!found) { // 如果没有找到非空的任务队列
                if ((System.nanoTime() - startTime) > nanos)
                    return false; // 如果等待超时返回false
                Thread.yield(); // cannot block
            }
            found = false;
            for (int j = (m + 1) << 2; j >= 0; --j) {
                ForkJoinTask<?> t; WorkQueue q; int b, k;
                if ((k = r++ & m) <= m && k >= 0 && (q = ws[k]) != null &&
                    (b = q.base) - q.top < 0) { // 如果找到了非空的任务队列
                    found = true;
                    if ((t = q.pollAt(b)) != null) // 从中获取一个任务并执行
                        t.doExec();
                    break; // 终止for循环，继续外层的while循环
                }
            }
        }
        return true;
    }

    /**
     * Waits and/or attempts to assist performing tasks indefinitely
     * until the {@link #commonPool()} {@link #isQuiescent}.
     */
    // 调用awaitQuiescence方法终止common线程池
    static void quiesceCommonPool() {
        common.awaitQuiescence(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    /**
     * Interface for extending managed parallelism for tasks running
     * in {@link ForkJoinPool}s.
     *
     * <p>A {@code ManagedBlocker} provides two methods.  Method
     * {@link #isReleasable} must return {@code true} if blocking is
     * not necessary. Method {@link #block} blocks the current thread
     * if necessary (perhaps internally invoking {@code isReleasable}
     * before actually blocking). These actions are performed by any
     * thread invoking {@link ForkJoinPool#managedBlock(ManagedBlocker)}.
     * The unusual methods in this API accommodate synchronizers that
     * may, but don't usually, block for long periods. Similarly, they
     * allow more efficient internal handling of cases in which
     * additional workers may be, but usually are not, needed to
     * ensure sufficient parallelism.  Toward this end,
     * implementations of method {@code isReleasable} must be amenable
     * to repeated invocation.
     *
     * <p>For example, here is a ManagedBlocker based on a
     * ReentrantLock:
     *  <pre> {@code
     * class ManagedLocker implements ManagedBlocker {
     *   final ReentrantLock lock;
     *   boolean hasLock = false;
     *   ManagedLocker(ReentrantLock lock) { this.lock = lock; }
     *   public boolean block() {
     *     if (!hasLock)
     *       lock.lock();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return hasLock || (hasLock = lock.tryLock());
     *   }
     * }}</pre>
     *
     * <p>Here is a class that possibly blocks waiting for an
     * item on a given queue:
     *  <pre> {@code
     * class QueueTaker<E> implements ManagedBlocker {
     *   final BlockingQueue<E> queue;
     *   volatile E item = null;
     *   QueueTaker(BlockingQueue<E> q) { this.queue = q; }
     *   public boolean block() throws InterruptedException {
     *     if (item == null)
     *       item = queue.take();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return item != null || (item = queue.poll()) != null;
     *   }
     *   public E getItem() { // call after pool.managedBlock completes
     *     return item;
     *   }
     * }}</pre>
     */
    public static interface ManagedBlocker {
        /**
         * Possibly blocks the current thread, for example waiting for
         * a lock or condition.
         *
         * @return {@code true} if no additional blocking is necessary
         * (i.e., if isReleasable would return true)
         * @throws InterruptedException if interrupted while waiting
         * (the method is not required to do so, but is allowed to)
         */
        // 用于将当前线程阻塞
        boolean block() throws InterruptedException;

        /**
         * Returns {@code true} if blocking is unnecessary.
         * @return {@code true} if blocking is unnecessary
         */
        // true表示不需要阻塞了
        boolean isReleasable();
    }

    /**
     * Runs the given possibly blocking task.  When {@linkplain
     * ForkJoinTask#inForkJoinPool() running in a ForkJoinPool}, this
     * method possibly arranges for a spare thread to be activated if
     * necessary to ensure sufficient parallelism while the current
     * thread is blocked in {@link ManagedBlocker#block blocker.block()}.
     *
     * <p>This method repeatedly calls {@code blocker.isReleasable()} and
     * {@code blocker.block()} until either method returns {@code true}.
     * Every call to {@code blocker.block()} is preceded by a call to
     * {@code blocker.isReleasable()} that returned {@code false}.
     *
     * <p>If not running in a ForkJoinPool, this method is
     * behaviorally equivalent to
     *  <pre> {@code
     * while (!blocker.isReleasable())
     *   if (blocker.block())
     *     break;}</pre>
     *
     * If running in a ForkJoinPool, the pool may first be expanded to
     * ensure sufficient parallelism available during the call to
     * {@code blocker.block()}.
     *
     * @param blocker the blocker task
     * @throws InterruptedException if {@code blocker.block()} did so
     */
    public static void managedBlock(ManagedBlocker blocker)
        throws InterruptedException {
        ForkJoinPool p;
        ForkJoinWorkerThread wt;
        Thread t = Thread.currentThread();
        // 如果当前线程是ForkJoinWorkerThread
        if ((t instanceof ForkJoinWorkerThread) && // 如果是ForkJoinWorkerThread
            (p = (wt = (ForkJoinWorkerThread)t).pool) != null) {
            WorkQueue w = wt.workQueue;
            while (!blocker.isReleasable()) { // 如果需要阻塞
                if (p.tryCompensate(w)) { // 进一步判断是否需要阻塞
                    try {
                        // 让当前线程阻塞
                        do {} while (!blocker.isReleasable() &&
                                     !blocker.block());
                    } finally {
                        // 活跃的线程数加1
                        U.getAndAddLong(p, CTL, AC_UNIT);
                    }
                    break;
                }
            }
        }
        else {
            // 如果是普通的Thread
            // 不断循环直到可以终止等待
            do {} while (!blocker.isReleasable() &&
                         !blocker.block());
        }
    }

    // AbstractExecutorService overrides.  These rely on undocumented
    // fact that ForkJoinTask.adapt returns ForkJoinTasks that also
    // implement RunnableFuture.

    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new ForkJoinTask.AdaptedRunnable<T>(runnable, value);
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new ForkJoinTask.AdaptedCallable<T>(callable);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final int  ABASE;
    private static final int  ASHIFT;
    private static final long CTL;
    private static final long RUNSTATE;
    private static final long STEALCOUNTER;
    private static final long PARKBLOCKER;
    private static final long QTOP;
    private static final long QLOCK;
    private static final long QSCANSTATE;
    private static final long QPARKER;
    private static final long QCURRENTSTEAL;
    private static final long QCURRENTJOIN;

    static {
        // initialize field offsets for CAS etc
        // 获取属性的偏移量
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = ForkJoinPool.class;
            CTL = U.objectFieldOffset
                (k.getDeclaredField("ctl"));
            RUNSTATE = U.objectFieldOffset
                (k.getDeclaredField("runState"));
            STEALCOUNTER = U.objectFieldOffset
                (k.getDeclaredField("stealCounter"));
            Class<?> tk = Thread.class;
            PARKBLOCKER = U.objectFieldOffset
                (tk.getDeclaredField("parkBlocker"));
            Class<?> wk = WorkQueue.class;
            QTOP = U.objectFieldOffset
                (wk.getDeclaredField("top"));
            QLOCK = U.objectFieldOffset
                (wk.getDeclaredField("qlock"));
            QSCANSTATE = U.objectFieldOffset
                (wk.getDeclaredField("scanState"));
            QPARKER = U.objectFieldOffset
                (wk.getDeclaredField("parker"));
            QCURRENTSTEAL = U.objectFieldOffset
                (wk.getDeclaredField("currentSteal"));
            QCURRENTJOIN = U.objectFieldOffset
                (wk.getDeclaredField("currentJoin"));
            Class<?> ak = ForkJoinTask[].class;
            // 用于获取ForkJoinTask数组指定索引元素的偏移量
            ABASE = U.arrayBaseOffset(ak);
            int scale = U.arrayIndexScale(ak);
            if ((scale & (scale - 1)) != 0)
                throw new Error("data type scale not a power of two");
            ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
        } catch (Exception e) {
            throw new Error(e);
        }
        // DEFAULT_COMMON_MAX_SPARES的值是256
        commonMaxSpares = DEFAULT_COMMON_MAX_SPARES;
        defaultForkJoinWorkerThreadFactory =
            new DefaultForkJoinWorkerThreadFactory();
        modifyThreadPermission = new RuntimePermission("modifyThread");

        common = java.security.AccessController.doPrivileged
            (new java.security.PrivilegedAction<ForkJoinPool>() {
                public ForkJoinPool run() { return makeCommonPool(); }});
        // 获取common线程池的parallelism属性
        int par = common.config & SMASK; // report 1 even if threads disabled
        commonParallelism = par > 0 ? par : 1;
    }

    /**
     * Creates and returns the common pool, respecting user settings
     * specified via system properties.
     */
    // 创建common线程池
    private static ForkJoinPool makeCommonPool() {
        int parallelism = -1;
        ForkJoinWorkerThreadFactory factory = null;
        UncaughtExceptionHandler handler = null;
        try {  // ignore exceptions in accessing/parsing properties
            // 读取三个属性值
            String pp = System.getProperty("java.util.concurrent.ForkJoinPool.common.parallelism");
            String fp = System.getProperty("java.util.concurrent.ForkJoinPool.common.threadFactory");
            String hp = System.getProperty("java.util.concurrent.ForkJoinPool.common.exceptionHandler");
            // 根据属性配置初始化变量
            if (pp != null)
                parallelism = Integer.parseInt(pp);
            if (fp != null)
                factory = ((ForkJoinWorkerThreadFactory)ClassLoader.
                           getSystemClassLoader().loadClass(fp).newInstance());
            if (hp != null)
                handler = ((UncaughtExceptionHandler)ClassLoader.
                           getSystemClassLoader().loadClass(hp).newInstance());
        } catch (Exception ignore) {
        }
        if (factory == null) {
            if (System.getSecurityManager() == null)
                factory = defaultForkJoinWorkerThreadFactory;
            else // use security-managed default
                factory = new InnocuousForkJoinWorkerThreadFactory();
        }
        if (parallelism < 0 && // default 1 less than #cores 如果没有设置属性parallelism，则默认使用CPU核数减1，如果是单核的则为1
            (parallelism = Runtime.getRuntime().availableProcessors() - 1) <= 0)
            parallelism = 1;
        if (parallelism > MAX_CAP) // 如果配置的parallelism大于MAX_CAP，则为MAX_CAP，0x7fff，32767
            parallelism = MAX_CAP;
        // 注意common线程池的模式是LIFO，后进先出
        return new ForkJoinPool(parallelism, factory, handler, LIFO_QUEUE,
                                "ForkJoinPool.commonPool-worker-");
    }

    /**
     * Factory for innocuous worker threads
     */
    static final class InnocuousForkJoinWorkerThreadFactory
        implements ForkJoinWorkerThreadFactory {

        /**
         * An ACC to restrict permissions for the factory itself.
         * The constructed workers have no permissions set.
         */
        private static final AccessControlContext innocuousAcc;
        static {
            Permissions innocuousPerms = new Permissions();
            // 对应的RuntimePermission为modifyThread
            innocuousPerms.add(modifyThreadPermission);
            // 允许改写获取ContextClassLoader的方法
            innocuousPerms.add(new RuntimePermission(
                                   "enableContextClassLoaderOverride"));
            // 允许修改ThreadGroup
            innocuousPerms.add(new RuntimePermission(
                                   "modifyThreadGroup"));
            innocuousAcc = new AccessControlContext(new ProtectionDomain[] {
                    new ProtectionDomain(null, innocuousPerms)
                });
        }

        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return (ForkJoinWorkerThread.InnocuousForkJoinWorkerThread)
                java.security.AccessController.doPrivileged(
                    new java.security.PrivilegedAction<ForkJoinWorkerThread>() {
                    public ForkJoinWorkerThread run() {
                        return new ForkJoinWorkerThread.
                            InnocuousForkJoinWorkerThread(pool);
                    }}, innocuousAcc);
        }
    }

}
