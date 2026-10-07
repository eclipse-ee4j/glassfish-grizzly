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

package org.glassfish.grizzly.http.server.sessionmanager;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.glassfish.grizzly.http.Cookie;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.server.Session;
import org.glassfish.grizzly.http.server.SessionManager;

import junit.framework.TestCase;

/**
 * A {@link SessionManager} outside of the Grizzly package must be able to implement
 * {@link SessionManager#changeSessionId(Request, Session)} for plain {@link Session} instances.
 */
public class CustomSessionManagerTest extends TestCase {

    private static final String COOKIE_NAME = "JSESSIONID";

    public void testChangeSessionIdWithCustomSessionManager() throws Exception {
        int port = 18890 + SecureRandom.getInstanceStrong().nextInt(1000);
        MapSessionManager sessionManager = new MapSessionManager();
        HttpServer server = HttpServer.createSimpleServer(null, port);
        NetworkListener listener = server.getListener("grizzly");
        listener.setSessionManager(sessionManager);
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                String body;
                if (request.getRequestURI().endsWith("/create")) {
                    Session session = request.getSession(true);
                    session.setAttribute("marker", "kept");
                    body = session.getIdInternal();
                } else if (request.getRequestURI().endsWith("/change")) {
                    Session session = request.getSession(false);
                    String oldId = request.changeSessionId();
                    body = oldId + "," + session.getIdInternal() + "," + request.getSession(false).getIdInternal();
                } else {
                    body = String.valueOf(request.getSession(false).getAttribute("marker"));
                }
                response.getWriter().write(body);
            }
        }, "/session");
        server.start();
        try {
            String oldId = get(port, "/session/create", null);
            assertSame(sessionManager.sessions.get(oldId), sessionManager.created);

            HttpURLConnection conn = open(port, "/session/change", oldId);
            String[] ids = read(conn).split(",");
            String newId = ids[1];
            assertEquals(oldId, ids[0]);
            assertFalse(oldId.equals(newId));
            assertEquals(newId, ids[2]);
            assertTrue(conn.getHeaderField("Set-Cookie"), conn.getHeaderField("Set-Cookie").startsWith(COOKIE_NAME + "=" + newId));

            assertNull(sessionManager.sessions.get(oldId));
            assertSame(sessionManager.created, sessionManager.sessions.get(newId));
            assertEquals("kept", get(port, "/session/get", newId));
        } finally {
            server.shutdownNow();
        }
    }

    private static String get(int port, String path, String sessionId) throws IOException {
        return read(open(port, path, sessionId));
    }

    private static HttpURLConnection open(int port, String path, String sessionId) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("http", "localhost", port, path).openConnection();
        conn.setReadTimeout(10_000);
        if (sessionId != null) {
            conn.setRequestProperty("Cookie", COOKIE_NAME + "=" + sessionId);
        }
        return conn;
    }

    private static String read(HttpURLConnection conn) throws IOException {
        assertEquals(200, conn.getResponseCode());
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Minimal session store that creates plain {@link Session} instances.
     */
    private static final class MapSessionManager implements SessionManager {

        final Map<String, Session> sessions = new ConcurrentHashMap<>();
        final AtomicLong ids = new AtomicLong(1000);
        volatile Session created;
        private String cookieName = COOKIE_NAME;

        @Override
        public Session getSession(Request request, String requestedSessionId) {
            return requestedSessionId == null ? null : sessions.get(requestedSessionId);
        }

        @Override
        public Session createSession(Request request) {
            Session session = new Session(String.valueOf(ids.incrementAndGet()));
            sessions.put(session.getIdInternal(), session);
            created = session;
            return session;
        }

        @Override
        public String changeSessionId(Request request, Session session) {
            String oldId = session.getIdInternal();
            String newId = String.valueOf(ids.incrementAndGet());
            session.setIdInternal(newId);
            sessions.remove(oldId);
            sessions.put(newId, session);
            return oldId;
        }

        @Override
        public void configureSessionCookie(Request request, Cookie cookie) {
            // nothing to configure
        }

        @Override
        public void setSessionCookieName(String name) {
            cookieName = name;
        }

        @Override
        public String getSessionCookieName() {
            return cookieName;
        }
    }
}
