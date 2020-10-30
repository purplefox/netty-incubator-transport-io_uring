/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubator.channel.uring;

import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.channel.unix.Errors;
import io.netty.channel.unix.FileDescriptor;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

public final class IOUringEventLoop extends SingleThreadEventLoop implements IOUringCompletionQueueCallback, IOUringCompletionQueueSimpleCallback {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(IOUringEventLoop.class);

    private final long eventfdReadBuf = PlatformDependent.allocateMemory(8);

    private final IntObjectMap<AbstractIOUringChannel> channels = new IntObjectHashMap<AbstractIOUringChannel>(4096);
    private final RingBuffer ringBuffer;

    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);
    private final FileDescriptor eventfd;

    // The maximum number of bytes for an InetAddress / Inet6Address
    private final byte[] inet4AddressArray = new byte[4];
    private final byte[] inet6AddressArray = new byte[16];

    private long prevDeadlineNanos = NONE;
    private boolean pendingWakeup;

    private final AtomicLong opSeq = new AtomicLong();
    private final Map<Long, MappingVal> resultMappings = new HashMap<>();
    private Thread thread;

    IOUringEventLoop(IOUringEventLoopGroup parent, Executor executor, int ringSize, int iosqeAsyncThreshold,
                     RejectedExecutionHandler rejectedExecutionHandler, EventLoopTaskQueueFactory queueFactory) {
        super(parent, executor, false, newTaskQueue(queueFactory), newTaskQueue(queueFactory),
                rejectedExecutionHandler);
        // Ensure that we load all native bits as otherwise it may fail when try to use native methods in IovArray
        IOUring.ensureAvailability();

        ringBuffer = Native.createRingBuffer(ringSize, iosqeAsyncThreshold, this);

        eventfd = Native.newBlockingEventFd();
        logger.trace("New EventLoop: {}", this.toString());
    }

    private static Queue<Runnable> newTaskQueue(
            EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    void add(AbstractIOUringChannel ch) {
        logger.trace("Add Channel: {} ", ch.socket.intValue());
        int fd = ch.socket.intValue();

        if (channels.put(fd, ch) == null) {
            ringBuffer.ioUringSubmissionQueue().incrementHandledFds();
        }
    }

    void remove(AbstractIOUringChannel ch) {
        logger.trace("Remove Channel: {}", ch.socket.intValue());
        int fd = ch.socket.intValue();

        AbstractIOUringChannel old = channels.remove(fd);
        if (old != null) {
            ringBuffer.ioUringSubmissionQueue().decrementHandledFds();

            if (old != ch) {
                // The Channel mapping was already replaced due FD reuse, put back the stored Channel.
                channels.put(fd, old);

                // If we found another Channel in the map that is mapped to the same FD the given Channel MUST be
                // closed.
                assert !ch.isOpen();
            }
        }
    }

    private void closeAll() {
        logger.trace("CloseAll IOUringEvenloop");
        // Using the intermediate collection to prevent ConcurrentModificationException.
        // In the `close()` method, the channel is deleted from `channels` map.
        AbstractIOUringChannel[] localChannels = channels.values().toArray(new AbstractIOUringChannel[0]);

        for (AbstractIOUringChannel ch : localChannels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    @Override
    protected void run() {
        final IOUringCompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();
        final IOUringSubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();

        thread = Thread.currentThread();

        // Lets add the eventfd related events before starting to do any real work.
        addEventFdRead(submissionQueue);

        for (;;) {
            try {

                // Prepare to block wait
                long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                if (curDeadlineNanos == -1L) {
                    curDeadlineNanos = NONE; // nothing on the calendar
                }
                nextWakeupNanos.set(curDeadlineNanos);

                // Only submit a timeout if there are no tasks to process and do a blocking operation
                // on the completionQueue.
                try {
                    if (!hasTasks()) {
                        if (curDeadlineNanos != prevDeadlineNanos) {
                            prevDeadlineNanos = curDeadlineNanos;
                            submissionQueue.addTimeout(deadlineToDelayNanos(curDeadlineNanos), (short) 0);
                        }

                        // Check there were any completion events to process
                        if (!completionQueue.hasCompletions()) {
                            // Block if there is nothing to process after this try again to call process(....)
                            submissionQueue.submitAndWait();
                        }
                    }
                } finally {
                    if (nextWakeupNanos.get() == AWAKE || nextWakeupNanos.getAndSet(AWAKE) == AWAKE) {
                        pendingWakeup = true;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }

            // Avoid blocking for as long as possible - loop until available work exhausted
            boolean maybeMoreWork = true;
            do {
                try {
                    // CQE processing can produce tasks, and new CQEs could arrive while
                    // processing tasks. So run both on every iteration and break when
                    // they both report that nothing was done (| means always run both).
                    maybeMoreWork = completionQueue.processSimple(this) != 0 | runAllTasks();
                } catch (Throwable t) {
                    handleLoopException(t);
                }
                // Always handle shutdown even if the loop processing threw an exception
                try {
                    if (isShuttingDown()) {
                        closeAll();
                        if (confirmShutdown()) {
                            return;
                        }
                        if (!maybeMoreWork) {
                            maybeMoreWork = hasTasks() || completionQueue.hasCompletions();
                        }
                    }
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            } while (maybeMoreWork);
        }
    }

    /**
     * Visible only for testing!
     */
    void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the io_uring event loop", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void checkThread() {
        if (Thread.currentThread() != thread) {
            throw new IllegalStateException("Called on wrong thread, expected " + thread + " got " + Thread.currentThread());
        }
    }

    // We indirect through here so we can make the original data into a value from a sequence
    // so we can support user operations too which might otherwise have clashing data values
    boolean enqueueSqe(byte op, int flags, int rwFlags, int fd,
                       long bufferAddress, int length, long offset, short data) {
        long opNumber = opSeq.getAndIncrement();
        checkThread();
        if (resultMappings.containsKey(opNumber)) {
            throw new IllegalStateException("Already got mapping for " + opNumber);
        }
        long userData = UserData.encode(fd, op, data);
        resultMappings.put(opNumber, new MappingVal(userData));
        return ringBuffer.ioUringSubmissionQueue().enqueueSqeInternal(op, flags, rwFlags, fd, bufferAddress, length, offset,
                opNumber);
    }

    public boolean addUserWrite(int fd, long bufferAddress, int pos, int limit, CompletableFuture<Integer> future) {
        long opNumber = createOp(future);
        return ringBuffer.ioUringSubmissionQueue().addWriteDirect(fd, bufferAddress, pos, limit, opNumber);
    }

    public boolean addUserFsync(int fd, int flags, CompletableFuture<Integer> future) {
        long opNumber = createOp(future);
        return ringBuffer.ioUringSubmissionQueue().addFsync(fd, flags, opNumber);
    }

    public boolean addUserSyncFileRange(int fd, long offset, int length, int flags, CompletableFuture<Integer> future) {
        long opNumber = createOp(future);
        return ringBuffer.ioUringSubmissionQueue().addSyncFileRange(fd, offset, length, flags, opNumber);
    }

    private long createOp(CompletableFuture<Integer> future) {
        long opNumber = opSeq.getAndIncrement();
        checkThread();
        if (resultMappings.containsKey(opNumber)) {
            throw new IllegalStateException("Already got mapping for " + opNumber);
        }
        resultMappings.put(opNumber, new MappingVal(future));
        return opNumber;
    }

    @Override
    public void handle(int fd, int res, int flags, byte op, short data) {

        if (op == Native.IORING_OP_READ && eventfd.intValue() == fd) {
            pendingWakeup = false;
            addEventFdRead(ringBuffer.ioUringSubmissionQueue());
        } else if (op == Native.IORING_OP_TIMEOUT) {
            if (res == Native.ERRNO_ETIME_NEGATIVE) {
                prevDeadlineNanos = NONE;
            }
        } else {
            // Remaining events should be channel-specific
            final AbstractIOUringChannel channel = channels.get(fd);
            if (channel == null) {
                return;
            }
            if (op == Native.IORING_OP_READ || op == Native.IORING_OP_ACCEPT || op == Native.IORING_OP_RECVMSG) {
                handleRead(channel, res, data);
            } else if (op == Native.IORING_OP_WRITEV ||
                    op == Native.IORING_OP_WRITE || op == Native.IORING_OP_SENDMSG) {
                handleWrite(channel, res, data);
            } else if (op == Native.IORING_OP_POLL_ADD) {
                handlePollAdd(channel, res, data);
            } else if (op == Native.IORING_OP_POLL_REMOVE) {
                if (res == Errors.ERRNO_ENOENT_NEGATIVE) {
                    logger.trace("IORING_POLL_REMOVE not successful");
                } else if (res == 0) {
                    logger.trace("IORING_POLL_REMOVE successful");
                }
                channel.clearPollFlag(data);
                if (!channel.ioScheduled()) {
                    // We cancelled the POLL ops which means we are done and should remove the mapping.
                    remove(channel);
                    return;
                }
            } else if (op == Native.IORING_OP_CONNECT) {
                handleConnect(channel, res);
            }
            channel.ioUringUnsafe().processDelayedClose();
        }

    }

    @Override
    public void handle(int res, int flags, long userData) {
        checkThread();
        MappingVal val = resultMappings.remove(userData);
        if (val == null) {
            throw new IllegalStateException("Cannot find data mapping " + userData);
        }
        if (val.future != null) {
            val.future.complete(res);
        } else {
            UserData.decode(res, flags, val.data, this);
        }

    }

    private void handleRead(AbstractIOUringChannel channel, int res, int data) {
        channel.ioUringUnsafe().readComplete(res, data);
    }

    private void handleWrite(AbstractIOUringChannel channel, int res, int data) {
        channel.ioUringUnsafe().writeComplete(res, data);
    }

    private void handlePollAdd(AbstractIOUringChannel channel, int res, int pollMask) {
        if ((pollMask & Native.POLLOUT) != 0) {
            channel.ioUringUnsafe().pollOut(res);
        }
        if ((pollMask & Native.POLLIN) != 0) {
            channel.ioUringUnsafe().pollIn(res);
        }
        if ((pollMask & Native.POLLRDHUP) != 0) {
            channel.ioUringUnsafe().pollRdHup(res);
        }
    }

    private void addEventFdRead(IOUringSubmissionQueue submissionQueue) {
        submissionQueue.addRead(eventfd.intValue(), eventfdReadBuf, 0, 8, (short) 0);
    }

    private void handleConnect(AbstractIOUringChannel channel, int res) {
        channel.ioUringUnsafe().connectComplete(res);
    }

    @Override
    protected void cleanup() {
        if (pendingWakeup) {
            // Another thread is in the process of writing to the eventFd. We must wait to
            // receive the corresponding CQE before closing it or else the fd int may be
            // reassigned by the kernel in the meantime.
            IOUringCompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();
            IOUringCompletionQueueCallback callback = new IOUringCompletionQueueCallback() {
                @Override
                public void handle(int fd, int res, int flags, byte op, short data) {
                    if (op == Native.IORING_OP_READ && eventfd.intValue() == fd) {
                        pendingWakeup = false;
                    }
                }
            };
            completionQueue.process(callback);
            while (pendingWakeup) {
                completionQueue.ioUringWaitCqe();
                completionQueue.process(callback);
            }
        }
        try {
            eventfd.close();
        } catch (IOException e) {
            logger.warn("Failed to close the event fd.", e);
        }
        ringBuffer.close();
        PlatformDependent.freeMemory(eventfdReadBuf);
    }

    public RingBuffer getRingBuffer() {
        return ringBuffer;
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            // write to the evfd which will then wake-up epoll_wait(...)
            Native.eventFdWrite(eventfd.intValue(), 1L);
        }
    }

    /**
     * {@code byte[]} that can be used as temporary storage to encode the ipv4 address
     */
    byte[] inet4AddressArray() {
        return inet4AddressArray;
    }

    /**
     * {@code byte[]} that can be used as temporary storage to encode the ipv6 address
     */
    byte[] inet6AddressArray() {
        return inet6AddressArray;
    }

    private static class MappingVal {
        final long data;
        final CompletableFuture<Integer> future;

        public MappingVal(long data) {
            this.data = data;
            this.future = null;
        }

        public MappingVal(CompletableFuture<Integer> future) {
            this.future = future;
            this.data = -1;
        }
    }

}
