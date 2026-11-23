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

package org.glassfish.grizzly.http2;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.memory.ByteBufferManager;
import org.glassfish.grizzly.memory.Buffers;
import org.junit.Test;

/**
 * Regression test for https://github.com/eclipse-ee4j/glassfish-grizzly/issues/2397:
 * when the session output queue is closed while a record is pending, a later
 * {@code flushOutputQueue()} must not log "UNEXPECTED NULL RECORD"
 * (TaskQueue spaceInBytes vs. record-count desync).
 */
public class Http2SessionOutputSinkTest {

    @Test
    public void noUnexpectedNullRecordWarningAfterCloseWithPendingRecord() throws Exception {
        final Http2Session session = mock(Http2Session.class);
        doReturn(0).when(session).getDefaultConnectionWindowSize();
        // a writable connection, so that a post-close flush would actually poll the (drained) queue
        final Connection<?> connection = mock(Connection.class);
        doReturn(true).when(connection).canWrite();
        doReturn(connection).when(session).getConnection();
        final Http2Stream stream = mock(Http2Stream.class);
        doReturn(1).when(stream).getId();

        final Http2SessionOutputSink sink = new Http2SessionOutputSink(session);

        final List<String> messages = new ArrayList<>();
        final Handler handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        final Logger logger = Logger.getLogger(Http2SessionOutputSink.class.getName());
        logger.addHandler(handler);
        try {
            final Buffer data = Buffers.wrap(new ByteBufferManager(), new byte[45]);
            sink.writeDataDownStream(stream, null, data, null, null, false);
            sink.close();
            sink.onPeerWindowUpdate(1024);
        } finally {
            logger.removeHandler(handler);
        }

        for (final String message : messages) {
            assertFalse("UNEXPECTED NULL RECORD must not be logged after close, but was: " + message,
                    message.contains("UNEXPECTED NULL RECORD"));
        }
    }
}
