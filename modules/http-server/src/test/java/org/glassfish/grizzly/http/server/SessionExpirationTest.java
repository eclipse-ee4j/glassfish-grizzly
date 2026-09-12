/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Session expiration is based on {@link System#nanoTime()}, so it is not affected by system clock changes.
 */
public class SessionExpirationTest {

    private static final long TIMEOUT_MILLIS = 10_000;
    private static final long TIMEOUT_NANOS = MILLISECONDS.toNanos(TIMEOUT_MILLIS);

    @Test
    public void expiresOnlyAfterIdleTimeExceedsTimeout() {
        final long before = System.nanoTime();
        final Session session = new Session();
        final long after = System.nanoTime();
        session.setSessionTimeout(TIMEOUT_MILLIS);

        assertFalse(session.isExpired(before + TIMEOUT_NANOS));
        assertTrue(session.isExpired(after + TIMEOUT_NANOS + 1));
    }

    @Test
    public void accessRestartsIdleTime() {
        final Session session = new Session();
        session.setSessionTimeout(TIMEOUT_MILLIS);

        final long beforeAccess = System.nanoTime();
        session.access();

        assertFalse(session.isExpired(beforeAccess + TIMEOUT_NANOS));
    }

    @Test
    public void neverExpiresWithoutPositiveTimeout() {
        final Session session = new Session();
        final long farFuture = System.nanoTime() + Long.MAX_VALUE / 2;

        assertFalse(session.isExpired(farFuture));
        session.setSessionTimeout(0);
        assertFalse(session.isExpired(farFuture));
    }

    @Test
    public void setTimestampShiftsIdleTime() {
        final Session session = new Session();
        session.setSessionTimeout(TIMEOUT_MILLIS);

        session.setTimestamp(System.currentTimeMillis() - 2 * TIMEOUT_MILLIS);
        assertTrue(session.isExpired(System.nanoTime()));

        session.setTimestamp(System.currentTimeMillis());
        assertFalse(session.isExpired(System.nanoTime()));
    }

    @Test
    public void setTimestampWithExtremeValuesDoesNotOverflow() {
        final Session session = new Session();
        session.setSessionTimeout(TIMEOUT_MILLIS);

        session.setTimestamp(-1);
        assertTrue(session.isExpired(System.nanoTime()));

        session.setTimestamp(Long.MIN_VALUE);
        assertTrue(session.isExpired(System.nanoTime()));

        session.setTimestamp(Long.MAX_VALUE);
        assertFalse(session.isExpired(System.nanoTime()));
    }

    @Test
    public void defaultSessionManagerDoesNotReturnExpiredSessionBeforeSweep() {
        final SessionManager manager = DefaultSessionManager.instance();
        final Session session = manager.createSession(null);
        session.setSessionTimeout(TIMEOUT_MILLIS);
        final String id = session.getIdInternal();

        assertSame(session, manager.getSession(null, id));

        session.setTimestamp(System.currentTimeMillis() - 2 * TIMEOUT_MILLIS);

        assertNull(manager.getSession(null, id));
        assertFalse(session.isValid());
    }
}
