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

package org.glassfish.grizzly.servlet;

import org.glassfish.grizzly.http.Cookie;
import org.glassfish.grizzly.http.server.DefaultSessionManager;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Session;
import org.glassfish.grizzly.http.server.SessionManager;
import org.glassfish.grizzly.http.server.util.Globals;

/**
 * The Servlet-aware {@link SessionManager} implementation.
 */
public class ServletSessionManager implements SessionManager {
    /**
     * @return <tt>DefaultSessionManager</tt> singleton
     */
    public static SessionManager instance() {
        return LazyHolder.INSTANCE;
    }

    // Lazy initialization of ServletSessionManager
    private static class LazyHolder {
        private static final ServletSessionManager INSTANCE = new ServletSessionManager();
    }

    private final SessionManager defaultManager = DefaultSessionManager.instance();

    private String sessionCookieName = Globals.SESSION_COOKIE_NAME;

    private ServletSessionManager() {
    }

    @Override
    public Session getSession(final Request request, final String requestedSessionId) {
        return defaultManager.getSession(request, requestedSessionId);
    }

    @Override
    public Session createSession(final Request request) {
        return defaultManager.createSession(request);
    }

    @Override
    public String changeSessionId(final Request request, final Session session) {
        return defaultManager.changeSessionId(request, session);
    }

    @Override
    public void configureSessionCookie(final Request request, final Cookie cookie) {
        defaultManager.configureSessionCookie(request, cookie);

        final HttpServletRequestImpl servletRequest = ServletHandler.getServletRequest(request);

        assert servletRequest != null;

        final jakarta.servlet.SessionCookieConfig cookieConfig = servletRequest.getContextImpl().getSessionCookieConfig();

        if (cookieConfig.getDomain() != null) {
            cookie.setDomain(cookieConfig.getDomain());
        }
        if (cookieConfig.getPath() != null) {
            cookie.setPath(cookieConfig.getPath());
        }
        if (cookieConfig.getComment() != null) {
            cookie.setVersion(1);
            cookie.setComment(cookieConfig.getComment());
        }

        cookie.setSecure(cookieConfig.isSecure());
        cookie.setHttpOnly(cookieConfig.isHttpOnly());
        cookie.setMaxAge(cookieConfig.getMaxAge());
        cookieConfig.getAttributes().forEach((name, value) -> {
            if (!Cookie.isReservedAttribute(name)) {
                cookie.setAttribute(name, value);
            }
        });
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
}
