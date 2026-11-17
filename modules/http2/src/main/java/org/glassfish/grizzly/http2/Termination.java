/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
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

/**
 *
 */
public class Termination {

    static final Termination IN_FIN_TERMINATION = new Termination(TerminationType.FIN, "End of input", false);

    static final Termination OUT_FIN_TERMINATION = new Termination(TerminationType.FIN, "The output stream has been closed", false);

    static final String CLOSED_BY_PEER_STRING = "Closed by peer";

    static final Termination LOCAL_CLOSE_TERMINATION = new Termination(TerminationType.LOCAL_CLOSE, "Closed locally", true);

    static final Termination PEER_CLOSE_TERMINATION = new Termination(TerminationType.PEER_CLOSE, CLOSED_BY_PEER_STRING, true);

    static final Termination RESET_TERMINATION = new Termination(TerminationType.RST, "Reset by peer", false);

    static final Termination LOCAL_RESET_TERMINATION = new Termination(TerminationType.RST, "Reset locally", false);

    static final Termination UNEXPECTED_FRAME_TERMINATION = new Termination(TerminationType.LOCAL_CLOSE, "Unexpected HTTP/2 frame", false);

    static final Termination FRAME_TOO_LARGE_TERMINATION = new Termination(TerminationType.LOCAL_CLOSE, "HTTP/2 frame sent by peer is too large", false);

    static final String HTTP2_PUSH_ENABLED = "http2-push-enabled";

    enum TerminationType {
        FIN, RST, LOCAL_CLOSE, PEER_CLOSE
    }

    private final TerminationType type;
    private final String description;
    private final boolean sessionClosed;

    public Termination(final TerminationType type, final String description, final boolean sessionClosed) {
        this.type = type;
        this.description = description;
        this.sessionClosed = sessionClosed;
    }

    public TerminationType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSessionClosed() {
        return sessionClosed;
    }

    public void doTask() {
    }
}
