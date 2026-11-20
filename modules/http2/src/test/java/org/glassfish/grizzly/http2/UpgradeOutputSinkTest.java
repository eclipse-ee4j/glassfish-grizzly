/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class UpgradeOutputSinkTest {

    @Test
    public void closeDoesNotFailAndMarksTheSinkClosed() {
        final UpgradeOutputSink sink = new UpgradeOutputSink(null);

        sink.close();

        assertTrue(sink.isClosed());
    }

    @Test
    public void terminationTaskIsRunOnlyOnce() {
        final UpgradeOutputSink sink = new UpgradeOutputSink(null);
        final AtomicInteger counter = new AtomicInteger();
        final Termination termination = new Termination(Termination.TerminationType.PEER_CLOSE, "test", true) {
            @Override
            public void doTask() {
                counter.incrementAndGet();
            }
        };

        sink.terminate(termination);
        sink.terminate(termination);
        sink.close();

        assertEquals(1, counter.get());
        assertTrue(sink.isClosed());
    }
}
