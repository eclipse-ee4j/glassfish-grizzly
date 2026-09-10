/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.grizzly.impl;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

import org.glassfish.grizzly.CompletionHandler;

/**
 * Safe {@link FutureImpl} implementation.
 * <p/>
 * (Based on the JDK {@link java.util.concurrent.FutureTask})
 *
 * @see Future
 */
@SuppressWarnings("deprecation")
public class SafeFutureImpl<R> implements FutureImpl<R> {

    private final Object chSync = new Object();
    private Set<CompletionHandler<R>> completionHandlers;

    /**
     * {@inheritDoc}
     */
    @Override
    public void addCompletionHandler(final CompletionHandler<R> completionHandler) {
        if (isDone()) {
            notifyCompletionHandler(completionHandler);
        } else {
            synchronized (chSync) {
                if (!isDone()) {
                    if (completionHandlers == null) {
                        completionHandlers = new HashSet<>(2);
                    }

                    completionHandlers.add(completionHandler);

                    return;
                }
            }

            notifyCompletionHandler(completionHandler);
        }
    }

    /**
     * Construct {@link SafeFutureImpl}.
     */
    @SuppressWarnings("unchecked")
    public static <R> SafeFutureImpl<R> create() {
        return new SafeFutureImpl<>();
    }

    /**
     * Creates <tt>SafeFutureImpl</tt>
     */
    @SuppressWarnings("unchecked")
    public SafeFutureImpl() {
        sync = new Sync();
    }

    /**
     * Set the result value and notify about operation completion.
     *
     * @param result the result value
     */
    @Override
    public void result(R result) {
        sync.innerSet(result);
    }

    /**
     * Notify about the failure, occurred during asynchronous operation execution.
     *
     * @param failure
     */
    @Override
    public void failure(Throwable failure) {
        sync.innerSetException(failure);
    }

    @Override
    public void markForRecycle(boolean recycleResult) {
    }

    @Override
    public void recycle(boolean recycleResult) {
    }

    @Override
    public void recycle() {
    }

    @Override
    public R getResult() {
        if (isDone()) {
            try {
                return get();
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    /**
     * The method is called when this <tt>SafeFutureImpl</tt> is marked as completed. Subclasses can override this method.
     */
    protected void onComplete() {
    }

    /**
     * Notify registered {@link CompletionHandler}s about the result.
     */
    private void notifyCompletionHandlers() {

        assert isDone();

        final Set<CompletionHandler<R>> localSet;
        synchronized (chSync) {
            if (completionHandlers == null) {
                return;
            }

            localSet = completionHandlers;
            completionHandlers = null;
        }

        final boolean isCancelled = isCancelled();
        final R result = sync.result;
        final Throwable error = sync.exception;

        for (Iterator<CompletionHandler<R>> it = localSet.iterator(); it.hasNext();) {
            final CompletionHandler<R> completionHandler = it.next();
            it.remove();
            try {
                if (isCancelled) {
                    completionHandler.cancelled();
                } else if (error != null) {
                    completionHandler.failed(error);
                } else {
                    completionHandler.completed(result);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Notify single {@link CompletionHandler} about the result.
     */
    private void notifyCompletionHandler(final CompletionHandler<R> completionHandler) {
        if (isCancelled()) {
            completionHandler.cancelled();
        } else {
            try {
                final R result = get();

                try {
                    completionHandler.completed(result);
                } catch (Exception ignored) {
                }
            } catch (ExecutionException e) {
                completionHandler.failed(e.getCause());
            } catch (Exception e) {
                completionHandler.failed(e);
            }
        }
    }

    // FROM FUTURETASK =========================================================

    /**
     * Synchronization control for FutureTask
     */
    private final Sync sync;

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCancelled() {
        return sync.innerIsCancelled();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDone() {
        return sync.ranOrCancelled();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return sync.innerCancel(mayInterruptIfRunning);
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    @Override
    public R get() throws InterruptedException, ExecutionException {
        return sync.innerGet();
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    @Override
    public R get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return sync.innerGet(unit.toNanos(timeout));
    }

    /**
     * Protected method invoked when this task transitions to state <tt>isDone</tt> (whether normally or via cancellation).
     * The default implementation does nothing. Subclasses may override this method to invoke completion callbacks or
     * perform bookkeeping. Note that you can query status inside the implementation of this method to determine whether
     * this task has been cancelled.
     */
    protected void done() {
        notifyCompletionHandlers();
        onComplete();
    }

    /**
     * Synchronization control for FutureTask. Note that this must be a non-static inner class in order to invoke the
     * protected <tt>done</tt> method. For clarity, all inner class support methods are same as outer, prefixed with
     * "inner".
     * <p/>
     * Uses AQS sync state to represent run status
     */
    private final class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = -7828117401763700385L;

        /**
         * State value representing that task is ready to run
         */
        private static final int READY = 0;
        /**
         * State value representing that result/exception is being set
         */
        private static final int RESULT = 1;
        /**
         * State value representing that task ran
         */
        private static final int RAN = 2;
        /**
         * State value representing that task was cancelled
         */
        private static final int CANCELLED = 3;

        /**
         * The result to return from get()
         */
        private R result;
        /**
         * The exception to throw from get()
         */
        private Throwable exception;

        private boolean ranOrCancelled() {
            final int state = getState();
            return state == RAN || state == CANCELLED;
        }

        /**
         * Implements AQS base acquire to succeed if ran or cancelled
         */
        @Override
        protected int tryAcquireShared(int ignore) {
            return ranOrCancelled() ? 1 : -1;
        }

        /**
         * Implements AQS base release to always signal after setting final done status by nulling runner thread.
         */
        @Override
        protected boolean tryReleaseShared(int ignore) {
            return true;
        }

        boolean innerIsCancelled() {
            return getState() == CANCELLED;
        }

        R innerGet() throws InterruptedException, ExecutionException {
            acquireSharedInterruptibly(0);
            if (getState() == CANCELLED) {
                throw new CancellationException();
            }
            if (exception != null) {
                throw new ExecutionException(exception);
            }
            return result;
        }

        R innerGet(long nanosTimeout) throws InterruptedException, ExecutionException, TimeoutException {
            if (!tryAcquireSharedNanos(0, nanosTimeout)) {
                throw new TimeoutException();
            }
            if (getState() == CANCELLED) {
                throw new CancellationException();
            }
            if (exception != null) {
                throw new ExecutionException(exception);
            }
            return result;
        }

        void innerSet(R v) {
            if (compareAndSetState(READY, RESULT)) {
                result = v;
                setState(RAN);
                releaseShared(0);
                done();
            }
        }

        void innerSetException(Throwable t) {
            if (compareAndSetState(READY, RESULT)) {
                exception = t;
                setState(RAN);
                releaseShared(0);
                done();
            }
        }

        boolean innerCancel(boolean mayInterruptIfRunning) {
            if (compareAndSetState(READY, CANCELLED)) {
                releaseShared(0);
                done();
                return true;
            }

            return false;
        }
    }
}
