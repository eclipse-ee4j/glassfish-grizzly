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

package org.glassfish.grizzly.http.server;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Simple session object.
 *
 * @author Jeanfrancois Arcand
 */
public class Session {

    /**
     * Largest shift {@link #setTimestamp(long)} applies to the monotonic access time, keeping the nanosecond arithmetic
     * in {@link #isExpired(long)} free of overflow.
     */
    private static final long MAX_TIMESTAMP_SHIFT_MILLIS = NANOSECONDS.toMillis(Long.MAX_VALUE / 4);

    /**
     * Cache attribute (thread safe)
     */
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * A session identifier
     */
    private String id = null;

    /**
     * Is this Session valid.
     */
    private volatile boolean isValid = true;

    /**
     * Is this session new.
     */
    private boolean isNew = true;

    /**
     * When this session was created.
     */
    private final long creationTime;

    /**
     * Timeout
     */
    private volatile long sessionTimeout = -1;

    /**
     * Creation time stamp.
     */
    private long timestamp = -1;

    /**
     * {@link System#nanoTime()} of the last access. Used for expiration, so that system clock changes neither shorten nor
     * extend the session.
     */
    private volatile long lastAccessedNanos;

    public Session() {
        this(null);
    }

    /**
     * Create a new session using a session identifier
     * 
     * @param id session identifier
     */
    public Session(String id) {
        this.id = id;
        lastAccessedNanos = System.nanoTime();
        creationTime = timestamp = System.currentTimeMillis();
    }

    /**
     * Is the current Session valid?
     * 
     * @return true if valid.
     */
    public boolean isValid() {
        return isValid;
    }

    /**
     * Set this object as validated.
     * 
     * @param isValid
     */
    public void setValid(boolean isValid) {
        this.isValid = isValid;
        if (!isValid) {
            timestamp = -1;
        }
    }

    /**
     * Returns <code>true</code> if the client does not yet know about the session or if the client chooses not to join the
     * session. For example, if the server used only cookie-based sessions, and the client had disabled the use of cookies,
     * then a session would be new on each request.
     *
     * @return <code>true</code> if the server has created a session, but the client has not yet joined
     */
    public boolean isNew() {
        return isNew;
    }

    /**
     * @return the session identifier for this session.
     */
    public String getIdInternal() {
        return id;
    }

    /**
     * Sets the session identifier for this session.
     * 
     * @param id
     */
    protected void setIdInternal(String id) {
        this.id = id;
    }

    /**
     * Add an attribute to this session.
     * 
     * @param key
     * @param value
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Return an attribute.
     *
     * @param key
     * @return an attribute
     */
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * Remove an attribute.
     * 
     * @param key
     * @return true if successful.
     */
    public Object removeAttribute(String key) {
        return attributes.remove(key);
    }

    /**
     * Return a {@link ConcurrentMap} of attributes.
     * 
     * @return the attributes associated with this session.
     */
    public ConcurrentMap<String, Object> attributes() {
        return attributes;
    }

    /**
     *
     * Returns the time when this session was created, measured in milliseconds since midnight January 1, 1970 GMT.
     *
     * @return a <code>long</code> specifying when this session was created, expressed in milliseconds since 1/1/1970 GMT
     */
    public long getCreationTime() {
        return creationTime;
    }

    /**
     * Return a long representing the maximum idle time (in milliseconds) a session can be.
     * 
     * @return a long representing the maximum idle time (in milliseconds) a session can be.
     */
    public long getSessionTimeout() {
        return sessionTimeout;
    }

    /**
     * Set a long representing the maximum idle time (in milliseconds) a session can be.
     * 
     * @param sessionTimeout a long representing the maximum idle time (in milliseconds) a session can be.
     */
    public void setSessionTimeout(long sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    /**
     * @return the timestamp when this session was accessed the last time
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Set the timestamp when this session was accessed the last time. The idle time used for expiration is shifted by the
     * same amount.
     * 
     * @param timestamp a long representing when the session was accessed the last time
     */
    public void setTimestamp(long timestamp) {
        final long nowMillis = System.currentTimeMillis();
        final long boundedTimestamp = Math.max(nowMillis - MAX_TIMESTAMP_SHIFT_MILLIS,
                Math.min(nowMillis + MAX_TIMESTAMP_SHIFT_MILLIS, timestamp));
        lastAccessedNanos = System.nanoTime() - MILLISECONDS.toNanos(nowMillis - boundedTimestamp);
        this.timestamp = timestamp;
    }

    /**
     * Updates the "last accessed" timestamp with the current time.
     * 
     * @return the time stamp
     */
    public long access() {
        final long localTimeStamp = System.currentTimeMillis();
        lastAccessedNanos = System.nanoTime();
        timestamp = localTimeStamp;
        isNew = false;

        return localTimeStamp;
    }

    /**
     * Returns <code>true</code> if this session has a positive {@link #getSessionTimeout() timeout} and has not been
     * accessed for longer than that. The idle time is measured with {@link System#nanoTime()}, so system clock changes do
     * not affect it.
     *
     * @param nowNanos the current {@link System#nanoTime()} value
     * @return <code>true</code> if the session has expired
     */
    public boolean isExpired(final long nowNanos) {
        final long timeout = sessionTimeout;
        return timeout > 0 && nowNanos - lastAccessedNanos > MILLISECONDS.toNanos(timeout);
    }
}
