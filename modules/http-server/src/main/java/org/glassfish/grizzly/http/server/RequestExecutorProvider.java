/*
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.server;

import java.util.concurrent.Executor;

import org.glassfish.grizzly.threadpool.Threads;

/**
 * An implementation of this interface will be responsible for executing user's code in
 * {@link HttpHandler#service(org.glassfish.grizzly.http.server.Request, org.glassfish.grizzly.http.server.Response)}
 * and notifying {@link ReadHandler}, {@link WriteHandler} registered by the user.
 *
 * @author Alexey Stashok
 */
public interface RequestExecutorProvider {

    /**
     * Returns the {@link Executor} to execute user's code associated with the {@link Request} processing.
     *
     * @param request {@link Request}
     * @return the {@link Executor} to execute user's code associated with the {@link Request} processing, or <tt>null</tt>
     * if the {@link Request} has to be executed on the current {@link Thread}
     */
    Executor getExecutor(final Request request);

    /**
     * The {@link RequestExecutorProvider} implementation, which always returns <tt>null</tt> to force the user code to be
     * executed on the current {@link Thread}.
     */
    class SameThreadProvider implements RequestExecutorProvider {

        @Override
        public Executor getExecutor(final Request request) {
            return null;
        }
    }

    /**
     * The {@link RequestExecutorProvider} implementation, which checks if the current {@link Thread} is a service
     * {@link Thread} (see {@link Threads#isService()}). If the current {@link Thread} is a service {@link Thread} - the
     * implementation returns a worker thread pool associated with the {@link Request}, or, if the current {@link Thread} is
     * not a service {@link Thread} - <tt>null</tt> will be returned to force the user code to be executed on the current
     * {@link Thread}.
     */
    class WorkerThreadProvider implements RequestExecutorProvider {

        @Override
        public Executor getExecutor(final Request request) {
            if (!Threads.isService()) {
                return null; // Execute in the current thread
            }

            return request.getContext().getConnection().getTransport().getWorkerThreadPool();
        }
    }
}
