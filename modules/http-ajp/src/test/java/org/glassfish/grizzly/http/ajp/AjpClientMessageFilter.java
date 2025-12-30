/*
 * Copyright (c) 2011, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.ajp;

import java.io.IOException;

import java.util.function.Supplier;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;

/**
 * Client side AJP filter, which deserializes AJP message by parsing magic bytes and message length.
 *
 * @author Alexey Stashok
 */
public class AjpClientMessageFilter extends BaseFilter {
    private final Attribute<AjpClientMessageFilter.ParsingState> parsingStateAttribute = Grizzly.DEFAULT_ATTRIBUTE_BUILDER
            .createAttribute(AjpClientMessageFilter.class + ".parsingStateAttribute", new Supplier<ParsingState>() {

                @Override
                public AjpClientMessageFilter.ParsingState get() {
                    return new AjpClientMessageFilter.ParsingState();
                }
            });

    @Override
    public NextAction handleRead(final FilterChainContext ctx) throws IOException {
        final Buffer buffer = ctx.getMessage();
        final Connection connection = ctx.getConnection();

        final AjpClientMessageFilter.ParsingState parsingState = parsingStateAttribute.get(connection);

        // Have we read the AJP message header?
        if (!parsingState.isHeaderParsed) {
            if (buffer.remaining() < AjpConstants.H_SIZE) {
                return ctx.getStopAction(buffer);
            }

            final int start = buffer.position();

            final byte markA = buffer.get(start);
            final byte markB = buffer.get(start + 1);

            if (markA != 'A' || markB != 'B') {
                throw new IllegalStateException("Unexpected mark=" + markA + markB);
            }

            parsingState.length = buffer.getShort(start + 2);
            parsingState.isHeaderParsed = true;

            if (parsingState.length + AjpConstants.H_SIZE > AjpConstants.MAX_PACKET_SIZE) {
                throw new IllegalStateException(
                        "The message is too large. " + (parsingState.length + AjpConstants.H_SIZE) + ">" + AjpConstants.MAX_PACKET_SIZE);
            }
        }

        // Do we have the entire content?
        if (buffer.remaining() < AjpConstants.H_SIZE + parsingState.length) {
            return ctx.getStopAction(buffer);
        }

        // Message is ready

        final int start = buffer.position();

        // Split off the remainder
        final Buffer remainder = buffer.split(start + parsingState.length + AjpConstants.H_SIZE);

        parsingState.parsed();

        // Invoke the next filter
        return ctx.getInvokeAction(remainder.hasRemaining() ? remainder : null);
    }

    static final class ParsingState {
        boolean isHeaderParsed;
        int length;

        void parsed() {
            isHeaderParsed = false;
            length = 0;
        }

        void reset() {
            isHeaderParsed = false;
            length = 0;
        }
    }
}
