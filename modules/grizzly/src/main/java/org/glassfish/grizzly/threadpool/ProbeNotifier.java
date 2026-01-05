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

package org.glassfish.grizzly.threadpool;

/**
 * Utility class, which has notification methods for different {@link ThreadPoolProbe} events.
 *
 * @author Alexey Stashok
 */
final class ProbeNotifier {
    /**
     * Notify registered {@link ThreadPoolProbe}s about the "thread pool started" event.
     *
     * @param threadPool the {@link ThreadPoolInfo} being monitored
     */
    static void notifyThreadPoolStarted(final ThreadPoolInfo threadPool) {

        final ThreadPoolProbe[] probes = threadPool.getMonitoringConfig().getProbesUnsafe();
        if (probes != null) {
            for (ThreadPoolProbe probe : probes) {
                probe.onThreadPoolStartEvent(threadPool);
            }
        }
    }

    /**
     * Notify registered {@link ThreadPoolProbe}s about the "thread pool stopped" event.
     *
     * @param threadPool the {@link ThreadPoolInfo} being monitored
     */
    static void notifyThreadPoolStopped(final ThreadPoolInfo threadPool) {

        final ThreadPoolProbe[] probes = threadPool.getMonitoringConfig().getProbesUnsafe();
        if (probes != null) {
            for (ThreadPoolProbe probe : probes) {
                probe.onThreadPoolStopEvent(threadPool);
            }
        }
    }

    /**
     * Notify registered {@link ThreadPoolProbe}s about the "thread allocated" event.
     *
     * @param threadPool the {@link ThreadPoolInfo} being monitored
     * @param thread the thread that has been allocated
     */
    static void notifyThreadAllocated(final ThreadPoolInfo threadPool, final Thread thread) {

        final ThreadPoolProbe[] probes = threadPool.getMonitoringConfig().getProbesUnsafe();
        if (probes != null) {
            for (ThreadPoolProbe probe : probes) {
                probe.onThreadAllocateEvent(threadPool, thread);
            }
        }
    }

    /**
     * Notify registered {@link ThreadPoolProbe}s about the "thread released" event.
     *
     * @param threadPool the {@link ThreadPoolInfo} being monitored
     * @param thread the thread that has been allocated
     */
    static void notifyThreadReleased(final ThreadPoolInfo threadPool, final Thread thread) {

        final ThreadPoolProbe[] probes = threadPool.getMonitoringConfig().getProbesUnsafe();
        if (probes != null) {
            for (ThreadPoolProbe probe : probes) {
                probe.onThreadReleaseEvent(threadPool, thread);
            }
        }
    }

    /**
     * Notify registered {@link ThreadPoolProbe}s about the "max number of threads reached" event.
     *
     * @param threadPool the {@link ThreadPoolInfo} being monitored
     * @param maxNumberOfThreads the maximum number of threads allowed in the {@link ThreadPoolInfo}
     */
    static void notifyMaxNumberOfThreads(final ThreadPoolInfo threadPool, final int maxNumberOfThreads) {

        final ThreadPoolProbe[] probes = threadPool.getMonitoringConfig().getProbesUnsafe();
        if (probes != null) {
            for (ThreadPoolProbe probe : probes) {
                probe.onMaxNumberOfThreadsEvent(threadPool, maxNumberOfThreads);
            }
        }
    }

    /**
     * Notify registered {@link ThreadPoolProbe}s about the "task queued" event.
     *
     * @param threadPool the {@link ThreadPoolInfo} being monitored
     * @param task a unit of work to be processed
     */
    static void notifyTaskQueued(final ThreadPoolInfo threadPool, final Runnable task) {

        final ThreadPoolProbe[] probes = threadPool.getMonitoringConfig().getProbesUnsafe();
        if (probes != null) {
            for (ThreadPoolProbe probe : probes) {
                probe.onTaskQueueEvent(threadPool, task);
            }
        }
    }

    /**
     * Notify registered {@link ThreadPoolProbe}s about the "task dequeued" event.
     *
     * @param threadPool the {@link ThreadPoolInfo} being monitored
     * @param task a unit of work to be processed
     */
    static void notifyTaskDequeued(final ThreadPoolInfo threadPool, final Runnable task) {

        final ThreadPoolProbe[] probes = threadPool.getMonitoringConfig().getProbesUnsafe();
        if (probes != null) {
            for (ThreadPoolProbe probe : probes) {
                probe.onTaskDequeueEvent(threadPool, task);
            }
        }
    }

    /**
     * Notify registered {@link ThreadPoolProbe}s about the "task cancelled" event.
     *
     * @param threadPool the {@link ThreadPoolInfo} being monitored
     * @param task a unit of work to be processed
     */
    static void notifyTaskCancelled(final ThreadPoolInfo threadPool, final Runnable task) {

        final ThreadPoolProbe[] probes = threadPool.getMonitoringConfig().getProbesUnsafe();
        if (probes != null) {
            for (ThreadPoolProbe probe : probes) {
                probe.onTaskCancelEvent(threadPool, task);
            }
        }
    }

    /**
     * Notify registered {@link ThreadPoolProbe}s about the "task completed" event.
     *
     * @param threadPool the {@link ThreadPoolInfo} being monitored
     * @param task a unit of work to be processed
     */
    static void notifyTaskCompleted(final ThreadPoolInfo threadPool, final Runnable task) {

        final ThreadPoolProbe[] probes = threadPool.getMonitoringConfig().getProbesUnsafe();
        if (probes != null) {
            for (ThreadPoolProbe probe : probes) {
                probe.onTaskCompleteEvent(threadPool, task);
            }
        }
    }

    /**
     * Notify registered {@link ThreadPoolProbe}s about the "task queue overflow" event.
     *
     * @param threadPool the {@link ThreadPoolInfo} being monitored
     */
    static void notifyTaskQueueOverflow(final ThreadPoolInfo threadPool) {

        final ThreadPoolProbe[] probes = threadPool.getMonitoringConfig().getProbesUnsafe();
        if (probes != null) {
            for (ThreadPoolProbe probe : probes) {
                probe.onTaskQueueOverflowEvent(threadPool);
            }
        }
    }
}
