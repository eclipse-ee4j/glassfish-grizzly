/*
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly;

import static junit.framework.Assert.assertEquals;

import java.io.IOException;
import java.nio.channels.SelectionKey;

import java.util.function.Supplier;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.monitoring.MonitoringConfig;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.SelectionKeyHandler;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestDefaults {

    @BeforeClass
    public static void init() {
        System.setProperty("org.glassfish.grizzly.DEFAULT_SELECTION_KEY_HANDLER", TestHandler.class.getName());
        System.setProperty("org.glassfish.grizzly.DEFAULT_MEMORY_MANAGER", TestManager.class.getName());
        System.setProperty("org.glassfish.grizzly.DEFAULT_ATTRIBUTE_BUILDER", TestBuilder.class.getName());

    }

    // ------------------------------------------------------------ Test Methods

    @Test
    public void testDefaults() throws Exception {

        assertEquals(SelectionKeyHandler.DEFAULT_SELECTION_KEY_HANDLER.getClass(), TestHandler.class);
        assertEquals(MemoryManager.DEFAULT_MEMORY_MANAGER.getClass(), TestManager.class);
        assertEquals(AttributeBuilder.DEFAULT_ATTRIBUTE_BUILDER.getClass(), TestBuilder.class);

    }

    // ---------------------------------------------------------- Nested Classes

    public static class TestHandler implements SelectionKeyHandler {
        @Override
        public void onKeyRegistered(SelectionKey key) {
        }

        @Override
        public void onKeyDeregistered(SelectionKey key) {
        }

        @Override
        public boolean onProcessInterest(SelectionKey key, int interest) throws IOException {
            return false;
        }

        @Override
        public void cancel(SelectionKey key) throws IOException {
        }

        @Override
        public NIOConnection getConnectionForKey(SelectionKey selectionKey) {
            return null;
        }

        @Override
        public void setConnectionForKey(NIOConnection connection, SelectionKey selectionKey) {
        }

        @Override
        public int ioEvent2SelectionKeyInterest(IOEvent ioEvent) {
            return 0;
        }

        @Override
        public IOEvent selectionKeyInterest2IoEvent(int selectionKeyInterest) {
            return null;
        }

        @Override
        public IOEvent[] getIOEvents(int interest) {
            return new IOEvent[0];
        }
    } // END TestHandler

    public static final class TestManager implements MemoryManager {

        @Override
        public Buffer allocate(int size) {
            return null;
        }

        @Override
        public Buffer allocateAtLeast(int size) {
            return null;
        }

        @Override
        public Buffer reallocate(Buffer oldBuffer, int newSize) {
            return null;
        }

        @Override
        public void release(Buffer buffer) {
        }

        @Override
        public boolean willAllocateDirect(int size) {
            return false;
        }

        @Override
        public MonitoringConfig getMonitoringConfig() {
            return null;
        }

    } // END TestManager

    public static final class TestBuilder implements AttributeBuilder {

        @Override
        public <T> Attribute<T> createAttribute(String name) {
            return null;
        }

        @Override
        public <T> Attribute<T> createAttribute(String name, T defaultValue) {
            return null;
        }

        @Override
        public <T> Attribute<T> createAttribute(String name, Supplier<T> initializer) {
            return null;
        }

        @Override
        public AttributeHolder createSafeAttributeHolder() {
            return null;
        }

        @Override
        public AttributeHolder createUnsafeAttributeHolder() {
            return null;
        }
    } // END TestBuilder
}
