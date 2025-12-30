/*
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
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.glassfish.grizzly.Cacheable;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.ThreadCache;

/**
 * Simple thread-unsafe {@link Future} implementation.
 *
 * @see Future
 *
 * @author Alexey Stashok
 */
public final class UnsafeFutureImpl<R> implements FutureImpl<R> {

    private static final ThreadCache.CachedTypeIndex<UnsafeFutureImpl> CACHE_IDX = ThreadCache.obtainIndex(UnsafeFutureImpl.class, 4);

    /**
     * Construct {@link Future}.
     */
    @SuppressWarnings("unchecked")
    public static <R> UnsafeFutureImpl<R> create() {
        final UnsafeFutureImpl<R> future = ThreadCache.takeFromCache(CACHE_IDX);
        if (future != null) {
            return future;
        }

        return new UnsafeFutureImpl<>();
    }

    protected boolean isDone;

    protected boolean isCancelled;
    protected Throwable failure;

    protected Set<CompletionHandler<R>> completionHandlers;
    protected R result;

    protected int recycleMark;

    private UnsafeFutureImpl() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addCompletionHandler(final CompletionHandler<R> completionHandler) {
        if (isDone) {
            notifyCompletionHandler(completionHandler);
        } else {
            if (completionHandlers == null) {
                completionHandlers = new HashSet<>(2);
            }

            completionHandlers.add(completionHandler);
        }

    }

    /**
     * Get current result value without any blocking.
     *
     * @return current result value without any blocking.
     */
    @Override
    public R getResult() {
        return result;
    }

    /**
     * Set the result value and notify about operation completion.
     *
     * @param result the result value
     */
    @Override
    public void result(R result) {
        this.result = result;
        notifyHaveResult();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        isCancelled = true;
        notifyHaveResult();
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCancelled() {
        return isCancelled;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDone() {
        return isDone;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public R get() throws InterruptedException, ExecutionException {
        if (isDone) {
            if (isCancelled) {
                throw new CancellationException();
            } else if (failure != null) {
                throw new ExecutionException(failure);
            } else if (result != null) {
                return result;
            }
        }

        throw new ExecutionException(new IllegalStateException("Result is not ready"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public R get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return get();
    }

    /**
     * Notify about the failure, occurred during asynchronous operation execution.
     *
     * @param failure
     */
    @Override
    public void failure(Throwable failure) {
        this.failure = failure;
        notifyHaveResult();
    }

    /**
     * Notify blocked listeners threads about operation completion.
     */
    protected void notifyHaveResult() {
        if (recycleMark == 0) {
            isDone = true;
            notifyCompletionHandlers();
        } else {
            recycle(recycleMark == 2);
        }
    }

    /**
     * Notify registered {@link CompletionHandler}s about the result.
     */
    private void notifyCompletionHandlers() {
        if (completionHandlers != null) {
            for (CompletionHandler<R> completionHandler : completionHandlers) {
                notifyCompletionHandler(completionHandler);
            }

            completionHandlers = null;
        }
    }

    /**
     * Notify single {@link CompletionHandler} about the result.
     */
    private void notifyCompletionHandler(final CompletionHandler<R> completionHandler) {
        try {
            if (isCancelled) {
                completionHandler.cancelled();
            } else if (failure != null) {
                completionHandler.failed(failure);
            } else if (result != null) {
                completionHandler.completed(result);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void markForRecycle(boolean recycleResult) {
        if (isDone) {
            recycle(recycleResult);
        } else {
            recycleMark = 1 + (recycleResult ? 1 : 0);
        }
    }

    protected void reset() {
        completionHandlers = null;
        result = null;
        failure = null;
        isCancelled = false;
        isDone = false;
        recycleMark = 0;
    }

    @Override
    public void recycle(boolean recycleResult) {
        if (recycleResult && result != null && result instanceof Cacheable) {
            ((Cacheable) result).recycle();
        }

        reset();
        ThreadCache.putToCache(CACHE_IDX, this);
    }

    @Override
    public void recycle() {
        recycle(false);
    }
}
