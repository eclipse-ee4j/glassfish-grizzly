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

package org.glassfish.grizzly.threadpool;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.monitoring.MonitoringAware;
import org.glassfish.grizzly.monitoring.MonitoringConfig;

/**
 *
 * @author gustav trede
 */
public class GrizzlyExecutorService extends AbstractExecutorService implements MonitoringAware<ThreadPoolProbe> {

    private final Object statelock = new Object();
    private volatile AbstractThreadPool pool;
    protected volatile ThreadPoolConfig config;

    /**
     *
     * @return {@link GrizzlyExecutorService}
     */
    public static GrizzlyExecutorService createInstance() {
        return createInstance(ThreadPoolConfig.defaultConfig());
    }

    /**
     *
     * @param cfg {@link ThreadPoolConfig}
     * @return {@link GrizzlyExecutorService}
     */
    public static GrizzlyExecutorService createInstance(ThreadPoolConfig cfg) {
        return new GrizzlyExecutorService(cfg);
    }

    protected GrizzlyExecutorService(ThreadPoolConfig config) {
        setImpl(config);
    }

    protected final void setImpl(ThreadPoolConfig cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("config is null");
        }

        cfg = cfg.copy();

        if (cfg.getMemoryManager() == null) {
            cfg.setMemoryManager(MemoryManager.DEFAULT_MEMORY_MANAGER);
        }

        final Queue<Runnable> queue = cfg.getQueue();
        if ((queue == null || queue instanceof BlockingQueue) && (cfg.getCorePoolSize() < 0 || cfg.getCorePoolSize() == cfg.getMaxPoolSize())) {

            this.pool = cfg.getQueueLimit() < 0 ? new FixedThreadPool(cfg) : new QueueLimitedThreadPool(cfg);
        } else {
            this.pool = new SyncThreadPool(cfg);
        }

        this.config = cfg;
    }

    /**
     * Sets the {@link ThreadPoolConfig}
     * 
     * @param config
     * @return returns {@link GrizzlyExecutorService}
     */
    public GrizzlyExecutorService reconfigure(ThreadPoolConfig config) {
        synchronized (statelock) {
            // TODO: only create new pool if old one can't be runtime config
            // for the needed state change(s).
            final AbstractThreadPool oldpool = this.pool;
            if (config.getQueue() == oldpool.getQueue()) {
                config.setQueue(null);
            }

            setImpl(config);
            AbstractThreadPool.drain(oldpool.getQueue(), this.pool.getQueue());
            oldpool.shutdown();
        }
        return this;
    }

    /**
     *
     * @return config - {@link ThreadPoolConfig}
     */
    public ThreadPoolConfig getConfiguration() {
        return config.copy();
    }

    @Override
    public void shutdown() {
        pool.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return pool.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return pool.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return pool.isTerminated();
    }

    @Override
    public void execute(Runnable r) {
        pool.execute(r);
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return pool.awaitTermination(timeout, unit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MonitoringConfig<ThreadPoolProbe> getMonitoringConfig() {
        return pool.getMonitoringConfig();
    }
}
