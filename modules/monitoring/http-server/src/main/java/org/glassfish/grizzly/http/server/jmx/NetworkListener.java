/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2010, 2017 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.server.jmx;

import org.glassfish.grizzly.Transport;
import org.glassfish.grizzly.http.HttpCodecFilter;
import org.glassfish.grizzly.http.KeepAlive;
import org.glassfish.grizzly.http.server.filecache.FileCache;
import org.glassfish.grizzly.http.server.HttpServerFilter;
import org.glassfish.grizzly.monitoring.jmx.JmxObject;
import org.glassfish.gmbal.Description;
import org.glassfish.gmbal.GmbalMBean;
import org.glassfish.gmbal.ManagedAttribute;
import org.glassfish.gmbal.ManagedObject;
import org.glassfish.grizzly.jmxbase.GrizzlyJmxManager;

/**
 * JMX management object for {@link org.glassfish.grizzly.http.server.NetworkListener}.
 *
 * @since 2.0
 */
@ManagedObject
@Description("The NetworkListener is an abstraction around the Transport (exposed as a child of this entity).")
public class NetworkListener extends JmxObject {

    private final org.glassfish.grizzly.http.server.NetworkListener listener;

    private FileCache currentFileCache;
    private Transport currentTransport;
    private KeepAlive currentKeepAlive;

    private Object fileCacheJmx;
    private Object transportJmx;
    private Object keepAliveJmx;

    private HttpServerFilter currentHttpServerFilter;
    private Object webServerFilterJmx;
    
    private HttpCodecFilter currentHttpCodecFilter;
    private Object httpCodecFilterJmx;

    private GrizzlyJmxManager mom;


    // ------------------------------------------------------------ Constructors


    public NetworkListener(org.glassfish.grizzly.http.server.NetworkListener listener) {

        this.listener = listener;

    }


    // -------------------------------------------------- Methods from JmxObject


    /**
     * {@inheritDoc}
     */
    @Override
    public String getJmxName() {
        return "NetworkListener";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected synchronized void onRegister(GrizzlyJmxManager mom, GmbalMBean bean) {
        this.mom = mom;
        rebuildSubTree();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected synchronized void onDeregister(GrizzlyJmxManager mom) {
        this.mom = null;
    }


    // ---------------------------------------------------------- Public Methods


    /**
     * @see org.glassfish.grizzly.http.server.NetworkListener#getName()
     */
    @ManagedAttribute(id="name")
    @Description("The logical name of the listener.")
    public String getName() {
        return listener.getName();
    }


    /**
     * @see org.glassfish.grizzly.http.server.NetworkListener#getHost()
     */
    @ManagedAttribute(id="host")
    @Description("The network host to which this listener is bound.")
    public String getHost() {
        return listener.getHost();
    }


    /**
     * @see org.glassfish.grizzly.http.server.NetworkListener#getPort()
     */
    @ManagedAttribute(id="port")
    @Description("The network port to which this listener is bound.")
    public int getPort() {
        return listener.getPort();
    }


    /**
     * @see org.glassfish.grizzly.http.KeepAlive#getIdleTimeoutInSeconds()
     */
    @ManagedAttribute(id="idle-timeout-in-seconds")
    @Description("The time, in seconds, to keep an inactive request alive.")
    public int getIdleTimeoutInSeconds() {
        return listener.getKeepAlive().getIdleTimeoutInSeconds();
    }


    /**
     * @see org.glassfish.grizzly.http.server.NetworkListener#isSecure()
     */
    @ManagedAttribute(id="secure")
    @Description("Indicates whether or not this listener is secured via SSL.")
    public boolean isSecure() {
        return listener.isSecure();
    }


    /**
     * @see org.glassfish.grizzly.http.server.NetworkListener#getMaxHttpHeaderSize()
     */
    @ManagedAttribute(id="max-http-header-size")
    @Description("The maximum size, in bytes, an HTTP request may be.")
    public int getMaxHttpHeaderSize() {
        return listener.getMaxHttpHeaderSize();
    }


    /**
     * @see org.glassfish.grizzly.http.server.NetworkListener#getMaxPendingBytes()
     */
    @ManagedAttribute(id="max-pending-bytes")
    @Description("The maximum size, in bytes, a connection may have waiting to be sent to the client.")
    public int getMaxPendingBytes() {
        return listener.getMaxPendingBytes();
    }


    /**
     * @see org.glassfish.grizzly.http.server.NetworkListener#getPendingBytesHardLimitMultiplier()
     */
    @ManagedAttribute(id = "pending-bytes-hard-limit-multiplier")
    @Description(
            "The multiplier applied to the per-connection maximum pending bytes to calculate the hard limit. If a write would exceed the hard limit, the write fails and the connection is closed.")
    public int getPendingBytesHardLimitMultiplier() {
        return listener.getPendingBytesHardLimitMultiplier();
    }


    /**
     * @see org.glassfish.grizzly.http.server.NetworkListener#isChunkingEnabled()
     */
    @ManagedAttribute(id="chunking-enabled")
    @Description("Flag indicating whether or not the http response body will be sent using the chunked transfer encoding.")
    public boolean isChunkingEnabled() {
        return listener.isChunkingEnabled();
    }


    /**
     * @see org.glassfish.grizzly.http.server.NetworkListener#isStarted()
     */
    @ManagedAttribute(id="started")
    @Description("Indicates whether or not this listener is started.")
    public boolean isStarted() {
        return listener.isStarted();
    }


    /**
     * @see org.glassfish.grizzly.http.server.NetworkListener#isPaused()
     */
    @Description("Indicates whether or not a started listener is actively processing requests.")
    @ManagedAttribute(id="paused")
    public boolean isPaused() {
        return listener.isPaused();
    }


    // ------------------------------------------------------- Protected Methods


    protected void rebuildSubTree() {

        final FileCache fileCache = listener.getFileCache();
        if (currentFileCache != fileCache) {
            if (currentFileCache != null) {
                mom.deregister(fileCacheJmx);

                currentFileCache = null;
                fileCacheJmx = null;
            }

            if (fileCache != null) {
                final Object jmx = fileCache
                        .getMonitoringConfig().createManagementObject();
                mom.register(this, jmx);
                currentFileCache = fileCache;
                fileCacheJmx = jmx;
            }
        }

        final Transport transport = listener.getTransport();
        if (currentTransport != transport) {
            if (currentTransport != null) {
                mom.deregister(transportJmx);

                currentTransport = null;
                transportJmx = null;
            }

            if (transport != null) {
                final Object jmx = transport
                        .getMonitoringConfig().createManagementObject();
                mom.register(this, jmx);
                currentTransport = transport;
                transportJmx = jmx;
            }
        }

        final KeepAlive keepAlive = listener.getKeepAlive();
        if (currentKeepAlive != keepAlive) {
            if (currentKeepAlive != null) {
                mom.deregister(keepAliveJmx);

                currentKeepAlive = null;
                keepAliveJmx = null;
            }

            if (transport != null) {
                final Object jmx = keepAlive
                        .getMonitoringConfig().createManagementObject();
                mom.register(this, jmx);
                currentKeepAlive = keepAlive;
                keepAliveJmx = jmx;
            }
        }

        final HttpServerFilter filter = listener.getHttpServerFilter();
        if (currentHttpServerFilter != filter) {
            if (currentHttpServerFilter != null) {
                mom.deregister(webServerFilterJmx);

                currentHttpServerFilter = null;
                webServerFilterJmx = null;
            }

            if (filter != null) {
                final Object jmx = filter
                        .getMonitoringConfig().createManagementObject();
                mom.register(this, jmx);
                currentHttpServerFilter = filter;
                webServerFilterJmx = jmx;
            }
        }

        final HttpCodecFilter codecFilter = listener.getHttpCodecFilter();
        if (currentHttpCodecFilter != codecFilter) {
            if (currentHttpCodecFilter != null) {
                mom.deregister(httpCodecFilterJmx);

                currentHttpCodecFilter = null;
                httpCodecFilterJmx = null;
            }

            if (codecFilter != null) {
                final Object jmx = codecFilter
                        .getMonitoringConfig().createManagementObject();
                mom.register(this, jmx);
                currentHttpCodecFilter = codecFilter;
                httpCodecFilterJmx = jmx;
            }
        }
        
    }

}
