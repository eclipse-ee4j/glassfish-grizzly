/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
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
package org.glassfish.grizzly.portunif;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.Callable;

public class TestUtils {

    private TestUtils() {
        // Prevent instantiation
    }

    /**
     * Repeatedly attempts to execute the given action. If the action throws an exception or assertion error,
     * it pauses and retries until the timeout is reached.
     *
     * @param action the task to execute
     * @param timeout the maximum time to keep retrying
     * @param sleep the duration to wait between attempts
     * @param <T> the type of the result
     * @return the result of the action if it succeeds before the timeout
     * @throws Exception if the timeout is exceeded, the last thrown exception is rethrown
     */
    @SuppressWarnings("SleepWhileInLoop")
    public static <T> T retryUntilSuccess(Callable<T> action, Duration timeout, Duration sleep) throws Exception {
        final long startTime = System.currentTimeMillis();
        Exception lastException = null;
        final long timeoutMillis = timeout.toMillis();
        final long sleepMillis = sleep.toMillis();

        while (System.currentTimeMillis() - startTime <= timeoutMillis) {
            try {
                return action.call();
            } catch (Exception t) {
                lastException = t;
            }
            Thread.sleep(sleepMillis);
        }

        throw lastException;
    }

    public static int findAvailableTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(
                0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
