/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation
 * Copyright (c) 2009, 2021 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.nio.transport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.nio.channels.SelectionKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.ConnectionProbe;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.localization.LogMessages;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.SelectorRunner;
import org.glassfish.grizzly.utils.Holder;

/**
 * {@link org.glassfish.grizzly.Connection} implementation for the {@link UDPNIOTransport}
 *
 * @author Alexey Stashok
 */
@SuppressWarnings("unchecked")
public class UDPNIOConnection extends NIOConnection {

    private static final Logger LOGGER = Grizzly.logger(UDPNIOConnection.class);

    private final Object multicastSync = new Object();
    private Map<InetAddress, Set<MembershipKey>> membershipKeysMap;

    Holder<SocketAddress> localSocketAddressHolder;
    Holder<SocketAddress> peerSocketAddressHolder;

    private int readBufferSize = -1;
    private int writeBufferSize = -1;

    public UDPNIOConnection(UDPNIOTransport transport, DatagramChannel channel) {
        super(transport);

        this.channel = channel;

        resetProperties();
    }

    public boolean isConnected() {
        return channel != null && ((DatagramChannel) channel).isConnected();
    }

    /**
     * Joins a multicast group to begin receiving all datagrams sent to the group. If this connection is currently a member
     * of the group on the given interface to receive all datagrams then this method call has no effect. Otherwise this
     * connection joins the requested group and channel's membership in not source-specific.
     *
     * A multicast connection may join several multicast groups, including the same group on more than one interface. An
     * implementation may impose a limit on the number of groups that may be joined at the same time.
     *
     * @param group The multicast address to join
     * @param networkInterface The network interface on which to join the group
     *
     * @throws IOException
     */
    public void join(final InetAddress group, final NetworkInterface networkInterface) throws IOException {
        join(group, networkInterface, null);
    }

    /**
     * Joins a multicast group to begin receiving datagrams sent to the group from a given source address. If this
     * connection is currently a member of the group on the given interface to receive datagrams from the given source
     * address then this method call has no effect. Otherwise this connection joins the group and depending on the source
     * parameter value (whether it's not null or null value) the connection's membership is or is not source-specific.
     *
     * Membership is cumulative and this method may be invoked again with the same group and interface to allow receiving
     * datagrams sent by other source addresses to the group.
     *
     * @param group The multicast address to join
     * @param networkInterface The network interface on which to join the group
     * @param source The source address
     *
     * @throws java.io.IOException
     */
    public void join(final InetAddress group, final NetworkInterface networkInterface, final InetAddress source) throws IOException {

        if (group == null) {
            throw new IllegalArgumentException("group parameter can't be null");
        }

        if (networkInterface == null) {
            throw new IllegalArgumentException("networkInterface parameter can't be null");
        }

        synchronized (multicastSync) {
            MembershipKey membershipKey = source == null ? ((DatagramChannel) channel).join(group, networkInterface) :
                    ((DatagramChannel) channel).join(group, networkInterface, source);

            if (membershipKeysMap == null) {
                membershipKeysMap = new HashMap<>();
            }

            Set<MembershipKey> keySet = membershipKeysMap.computeIfAbsent(group, k -> new HashSet<>());

            keySet.add(membershipKey);
        }
    }

    /**
     * Drops non-source specific membership in a multicast group. If this connection doesn't have non-source specific
     * membership in the group on the given interface to receive datagrams then this method call has no effect. Otherwise
     * this connection drops the group membership.
     *
     * @param group The multicast address to join
     * @param networkInterface The network interface on which to join the group
     *
     * @throws IOException
     */
    public void drop(final InetAddress group, final NetworkInterface networkInterface) throws IOException {
        drop(group, networkInterface, null);
    }

    /**
     * Drops membership in a multicast group. If the source parameter is null - this method call is equivalent to
     * {@link #drop(java.net.InetAddress, java.net.NetworkInterface)}.
     *
     * If the source parameter is not null and this connection doesn't have source specific membership in the group on the
     * given interface to receive datagrams then this method call has no effect. Otherwise this connection drops the source
     * specific group membership.
     *
     * @param group The multicast address to join
     * @param networkInterface The network interface on which to join the group
     * @param source The source address
     *
     * @throws IOException
     */
    public void drop(final InetAddress group, final NetworkInterface networkInterface, final InetAddress source) throws IOException {

        if (group == null) {
            throw new IllegalArgumentException("group parameter can't be null");
        }

        if (networkInterface == null) {
            throw new IllegalArgumentException("networkInterface parameter can't be null");
        }

        synchronized (multicastSync) {
            final Set<MembershipKey> keys;
            if (membershipKeysMap != null && (keys = membershipKeysMap.get(group)) != null) {
                for (final Iterator<MembershipKey> it = keys.iterator(); it.hasNext();) {
                    final MembershipKey key = it.next();

                    if (networkInterface.equals(key.networkInterface())) {
                        if (source == null && key.sourceAddress() == null || source != null && source.equals(key.sourceAddress())) {
                            key.drop();
                            it.remove();
                        }
                    }

                    if (keys.isEmpty()) {
                        membershipKeysMap.remove(group);
                    }
                }
            }
        }
    }

    /**
     * Drops all active membership in a multicast group. If this connection doesn't have any type of membership in the group
     * on the given interface to receive datagrams then this method call has no effect. Otherwise this connection drops all
     * types of the group membership.
     *
     * @param group The multicast address to join
     * @param networkInterface The network interface on which to join the group
     *
     * @throws IOException
     */
    public void dropAll(final InetAddress group, final NetworkInterface networkInterface) throws IOException {

        if (group == null) {
            throw new IllegalArgumentException("group parameter can't be null");
        }

        if (networkInterface == null) {
            throw new IllegalArgumentException("networkInterface parameter can't be null");
        }

        synchronized (multicastSync) {
            final Set<MembershipKey> keys;
            if (membershipKeysMap != null && (keys = membershipKeysMap.get(group)) != null) {
                for (final Iterator<MembershipKey> it = keys.iterator(); it.hasNext();) {
                    final MembershipKey key = it.next();

                    if (networkInterface.equals(key.networkInterface())) {
                        key.drop();
                        it.remove();
                    }
                }

                if (keys.isEmpty()) {
                    membershipKeysMap.remove(group);
                }
            }
        }
    }

    /**
     * Blocks multicast datagrams from the given source address.
     *
     * If this connection has non-source specific membership in the group on the given interface then this method blocks
     * multicast datagrams from the given source address. If the given source address is already blocked then this method
     * has no effect.
     *
     * @param group The multicast address to join
     * @param networkInterface The network interface on which to join the group
     * @param source The source address to block
     *
     * @throws IOException
     */
    public void block(final InetAddress group, final NetworkInterface networkInterface, final InetAddress source) throws IOException {

        if (group == null) {
            throw new IllegalArgumentException("group parameter can't be null");
        }

        if (networkInterface == null) {
            throw new IllegalArgumentException("networkInterface parameter can't be null");
        }

        synchronized (multicastSync) {
            final Set<MembershipKey> keys;
            if (membershipKeysMap != null && (keys = membershipKeysMap.get(group)) != null) {
                for (final MembershipKey key : keys) {
                    if (networkInterface.equals(key.networkInterface()) && key.sourceAddress() == null) {
                        key.block(source);
                    }
                }
            }
        }
    }

    /**
     * Unblocks multicast datagrams from the given source address.
     *
     * If this connection has non-source specific membership in the group on the given interface and specified source
     * address was previously blocked using
     * {@link #block(java.net.InetAddress, java.net.NetworkInterface, java.net.InetAddress)} method then this method
     * unblocks multicast datagrams from the given source address. If the given source address wasn't blocked then this
     * method has no effect.
     *
     * @param group The multicast address to join
     * @param networkInterface The network interface on which to join the group
     * @param source The source address to block
     *
     * @throws IOException
     */
    public void unblock(final InetAddress group, final NetworkInterface networkInterface, final InetAddress source) throws IOException {

        if (group == null) {
            throw new IllegalArgumentException("group parameter can't be null");
        }

        if (networkInterface == null) {
            throw new IllegalArgumentException("networkInterface parameter can't be null");
        }

        synchronized (multicastSync) {
            final Set<MembershipKey> keys;
            if (membershipKeysMap != null && (keys = membershipKeysMap.get(group)) != null) {
                for (final MembershipKey key : keys) {
                    if (networkInterface.equals(key.networkInterface()) && key.sourceAddress() == null) {
                        key.unblock(source);
                    }
                }
            }
        }
    }

    @Override
    protected void setSelectionKey(SelectionKey selectionKey) {
        super.setSelectionKey(selectionKey);
    }

    @Override
    protected void setSelectorRunner(SelectorRunner selectorRunner) {
        super.setSelectorRunner(selectorRunner);
    }

    protected boolean notifyReady() {
        return connectCloseSemaphoreUpdater.compareAndSet(this, null, NOTIFICATION_INITIALIZED);
    }

    /**
     * Returns the address of the endpoint this <tt>Connection</tt> is connected to, or <tt>null</tt> if it is unconnected.
     * 
     * @return the address of the endpoint this <tt>Connection</tt> is connected to, or <tt>null</tt> if it is unconnected.
     */
    @Override
    public SocketAddress getPeerAddress() {
        return peerSocketAddressHolder.get();
    }

    /**
     * Returns the local address of this <tt>Connection</tt>, or <tt>null</tt> if it is unconnected.
     * 
     * @return the local address of this <tt>Connection</tt>, or <tt>null</tt> if it is unconnected.
     */
    @Override
    public SocketAddress getLocalAddress() {
        return localSocketAddressHolder.get();
    }

    protected final void resetProperties() {
        if (channel != null) {
            setReadBufferSize(transport.getReadBufferSize());
            setWriteBufferSize(transport.getWriteBufferSize());

            final int transportMaxAsyncWriteQueueSize = transport.getAsyncQueueIO().getWriter().getMaxPendingBytesPerConnection();

            setMaxAsyncWriteQueueSize(
                    transportMaxAsyncWriteQueueSize == AsyncQueueWriter.AUTO_SIZE ? getWriteBufferSize() * 4 : transportMaxAsyncWriteQueueSize);

            localSocketAddressHolder = Holder.lazyHolder(new Supplier<SocketAddress>() {
                @Override
                public SocketAddress get() {
                    return ((DatagramChannel) channel).socket().getLocalSocketAddress();
                }
            });

            peerSocketAddressHolder = Holder.lazyHolder(new Supplier<SocketAddress>() {
                @Override
                public SocketAddress get() {
                    return ((DatagramChannel) channel).socket().getRemoteSocketAddress();
                }
            });
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getReadBufferSize() {
        if (readBufferSize >= 0) {
            return readBufferSize;
        }

        try {
            readBufferSize = ((DatagramChannel) channel).socket().getReceiveBufferSize();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, LogMessages.WARNING_GRIZZLY_CONNECTION_GET_READBUFFER_SIZE_EXCEPTION(), e);
            readBufferSize = 0;
        }

        return readBufferSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setReadBufferSize(final int readBufferSize) {
        if (readBufferSize > 0) {
            try {
                final int currentReadBufferSize = ((DatagramChannel) channel).socket().getReceiveBufferSize();
                if (readBufferSize > currentReadBufferSize) {
                    ((DatagramChannel) channel).socket().setReceiveBufferSize(readBufferSize);
                }

                this.readBufferSize = readBufferSize;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, LogMessages.WARNING_GRIZZLY_CONNECTION_SET_READBUFFER_SIZE_EXCEPTION(), e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getWriteBufferSize() {
        if (writeBufferSize >= 0) {
            return writeBufferSize;
        }

        try {
            writeBufferSize = ((DatagramChannel) channel).socket().getSendBufferSize();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, LogMessages.WARNING_GRIZZLY_CONNECTION_GET_WRITEBUFFER_SIZE_EXCEPTION(), e);
            writeBufferSize = 0;
        }

        return writeBufferSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setWriteBufferSize(int writeBufferSize) {
        if (writeBufferSize > 0) {
            try {
                final int currentSendBufferSize = ((DatagramChannel) channel).socket().getSendBufferSize();
                if (writeBufferSize > currentSendBufferSize) {
                    ((DatagramChannel) channel).socket().setSendBufferSize(writeBufferSize);
                }
                this.writeBufferSize = writeBufferSize;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, LogMessages.WARNING_GRIZZLY_CONNECTION_SET_WRITEBUFFER_SIZE_EXCEPTION(), e);
            }
        }
    }

    @Override
    protected void enableInitialOpRead() throws IOException {
        super.enableInitialOpRead();
    }

    /**
     * Method will be called, when the connection gets connected.
     * 
     * @throws IOException
     */
    protected final void onConnect() throws IOException {
        notifyProbesConnect(this);
    }

    /**
     * Method will be called, when some data was read on the connection
     */
    protected final void onRead(Buffer data, int size) {
        if (size > 0) {
            notifyProbesRead(this, data, size);
        }
        checkEmptyRead(size);
    }

    /**
     * Method will be called, when some data was written on the connection
     */
    protected final void onWrite(Buffer data, int size) {
        notifyProbesWrite(this, data, size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canWrite() {
        return transport.getWriter(this).canWrite(this);
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    @Override
    public boolean canWrite(int length) {
        return transport.getWriter(this).canWrite(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notifyCanWrite(final WriteHandler writeHandler) {
        transport.getWriter(this).notifyWritePossible(this, writeHandler);
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    @Override
    public void notifyCanWrite(WriteHandler handler, int length) {
        transport.getWriter(this).notifyWritePossible(this, handler);
    }

    /**
     * Set the monitoringProbes array directly.
     * 
     * @param monitoringProbes
     */
    void setMonitoringProbes(final ConnectionProbe[] monitoringProbes) {
        this.monitoringConfig.addProbes(monitoringProbes);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("UDPNIOConnection");
        sb.append("{localSocketAddress=").append(localSocketAddressHolder);
        sb.append(", peerSocketAddress=").append(peerSocketAddressHolder);
        sb.append('}');
        return sb.toString();
    }
}
