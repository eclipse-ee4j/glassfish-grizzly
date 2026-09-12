/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.http.Cookie;
import org.glassfish.grizzly.http.server.util.Globals;

/**
 * Default {@link SessionManager} implementation.
 */
public class DefaultSessionManager implements SessionManager {

    /**
     * @return <tt>DefaultSessionManager</tt> singleton
     */
    public static SessionManager instance() {
        return LazyHolder.INSTANCE;
    }

    // Lazy initialization of DefaultSessionManager
    private static class LazyHolder {
        private static final DefaultSessionManager INSTANCE = new DefaultSessionManager();
    }

    /**
     * Not Good. We need a better mechanism. TODO: Move Session Management out of here
     */
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Session ids must not be predictable from previously issued ones.
     */
    private final Random rnd = new SecureRandom();

    private String sessionCookieName = Globals.SESSION_COOKIE_NAME;

    /**
     * Scheduled Thread that clean the cache every XX seconds.
     */
    private final ScheduledThreadPoolExecutor sessionExpirer = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            final Thread t = new Thread(r, "Grizzly-HttpSession-Expirer");
            t.setDaemon(true);
            return t;
        }
    });

    {
        sessionExpirer.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                final long nowNanos = System.nanoTime();
                Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
                Map.Entry<String, Session> entry;
                while (iterator.hasNext()) {
                    entry = iterator.next();
                    final Session session = entry.getValue();

                    if (!session.isValid() || session.isExpired(nowNanos)) {
                        session.setValid(false);
                        iterator.remove();
                    }
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private DefaultSessionManager() {
    }

    @Override
    public Session getSession(final Request request, String requestedSessionId) {

        if (requestedSessionId != null) {
            final Session session = sessions.get(requestedSessionId);
            if (session != null && session.isValid()) {
                if (!session.isExpired(System.nanoTime())) {
                    return session;
                }

                // Expired since the last sweep
                session.setValid(false);
                sessions.remove(requestedSessionId, session);
            }
        }

        return null;

    }

    @Override
    public Session createSession(final Request request) {
        final Session session = new Session();

        String requestedSessionId;
        do {
            requestedSessionId = String.valueOf(generateRandomLong());
            session.setIdInternal(requestedSessionId);
        } while (sessions.putIfAbsent(requestedSessionId, session) != null);

        return session;
    }

    @Override
    public String changeSessionId(final Request request, final Session session) {
        final String oldSessionId = session.getIdInternal();
        final String newSessionId = String.valueOf(generateRandomLong());

        session.setIdInternal(newSessionId);

        sessions.remove(oldSessionId);
        sessions.put(newSessionId, session);
        return oldSessionId;
    }

    @Override
    public void configureSessionCookie(final Request request, final Cookie cookie) {
    }

    @Override
    public void setSessionCookieName(final String name) {
        if (name != null && !name.isEmpty()) {
            sessionCookieName = name;
        }
    }

    @Override
    public String getSessionCookieName() {
        return sessionCookieName;
    }

    /**
     * Returns pseudorandom positive long value.
     */
    private long generateRandomLong() {
        return rnd.nextLong() & 0x7FFFFFFFFFFFFFFFL;
    }
}
