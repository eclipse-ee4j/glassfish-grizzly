/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.http2.frames;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.http.util.BufferChunk;
import org.glassfish.grizzly.http.util.ByteChunk;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.memory.MemoryManager;

/**
 * TODO: Need to implement handling of per-setting flags.
 */
public class SettingsFrame extends Http2Frame {
    private static final Logger LOGGER = Grizzly.logger(SettingsFrame.class);

    private static final char[] CA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final int[] IA = new int[256];
    static {
        Arrays.fill(IA, -1);
        for (int i = 0, iS = CA.length; i < iS; i++) {
            IA[CA[i]] = i;
        }
    }

    private static final ThreadCache.CachedTypeIndex<SettingsFrame> CACHE_IDX = ThreadCache.obtainIndex(SettingsFrame.class, 8);

    private static final String[] OPTION_TEXT = { "HEADER_TABLE_SIZE", "ENABLE_PUSH", "MAX_CONCURRENT_STREAMS", "INITIAL_WINDOW_SIZE", "MAX_FRAME_SIZE",
            "MAX_HEADER_LIST_SIZE" };

    public static final int TYPE = 4;

    public static final byte ACK_FLAG = 0x01;

    static final Map<Integer, String> FLAG_NAMES_MAP = new HashMap<>(2);

    static {
        FLAG_NAMES_MAP.put((int) ACK_FLAG, "ACK");
    }

    public static final int MAX_DEFINED_SETTINGS = 6;
    /*
     * Values defined by SETTINGS are the index in settingsSlots for their respective values.
     */
    public static final int SETTINGS_HEADER_TABLE_SIZE = 1;
    public static final int SETTINGS_ENABLE_PUSH = 2;
    public static final int SETTINGS_MAX_CONCURRENT_STREAMS = 3;
    public static final int SETTINGS_INITIAL_WINDOW_SIZE = 4;
    public static final int SETTINGS_MAX_FRAME_SIZE = 5;
    public static final int SETTINGS_MAX_HEADER_LIST_SIZE = 6;

    private int numberOfSettings;

    private final Setting[] settings = new Setting[MAX_DEFINED_SETTINGS];

    // ------------------------------------------------------------ Constructors

    private SettingsFrame() {
        for (int i = 0; i < MAX_DEFINED_SETTINGS; i++) {
            settings[i] = new Setting();
        }
    }

    // ---------------------------------------------------------- Public Methods

    static SettingsFrame create() {
        SettingsFrame frame = ThreadCache.takeFromCache(CACHE_IDX);
        if (frame == null) {
            frame = new SettingsFrame();
        }
        return frame;
    }

    public static SettingsFrame fromBuffer(final int flags, final int streamId, final Buffer frameBuffer) {

        SettingsFrame frame = create();
        frame.setStreamId(streamId);
        frame.setFlags(flags);
        frame.setFrameBuffer(frameBuffer);
        if (frameBuffer.remaining() % 6 == 0) {
            while (frameBuffer.hasRemaining()) {
                frame.addSetting(frameBuffer.getShort(), frameBuffer.getInt());
            }
        } else {
            frame.numberOfSettings = -1;
        }

        return frame;
    }

    @Override
    public int getType() {
        return TYPE;
    }

    public static SettingsFrame fromBase64Uri(final DataChunk src) {
        if (src.getType() == DataChunk.Type.Bytes) {
            final ByteChunk bc = src.getByteChunk();
            return parseBase64Uri(bc.getBuffer(), bc.getStart(), bc.getEnd());
        } else if (src.getType() == DataChunk.Type.Buffer) {
            final BufferChunk bc = src.getBufferChunk();
            return parseBase64Uri(bc.getBuffer(), bc.getStart(), bc.getEnd());
        }

        return parseBase64Uri(src.toString());
    }

    private static SettingsFrame parseBase64Uri(final byte[] bytes, int offs, final int end) {
        final SettingsFrame frame = new SettingsFrame();

        while (offs < end) {
            frame.addBase64UriSetting(IA[bytes[offs++]], IA[bytes[offs++]], IA[bytes[offs++]], IA[bytes[offs++]], IA[bytes[offs++]], IA[bytes[offs++]],
                    IA[bytes[offs++]], IA[bytes[offs++]]);
        }

        return frame;
    }

    private static SettingsFrame parseBase64Uri(final Buffer buffer, int offs, final int end) {
        final SettingsFrame frame = new SettingsFrame();

        while (offs < end) {
            frame.addBase64UriSetting(IA[buffer.get(offs++)], IA[buffer.get(offs++)], IA[buffer.get(offs++)], IA[buffer.get(offs++)], IA[buffer.get(offs++)],
                    IA[buffer.get(offs++)], IA[buffer.get(offs++)], IA[buffer.get(offs++)]);
        }

        return frame;
    }

    private static SettingsFrame parseBase64Uri(final String s) {
        final SettingsFrame frame = new SettingsFrame();

        int offs = 0;
        final int end = s.length();

        while (offs < end) {
            frame.addBase64UriSetting(IA[s.charAt(offs++)], IA[s.charAt(offs++)], IA[s.charAt(offs++)], IA[s.charAt(offs++)], IA[s.charAt(offs++)],
                    IA[s.charAt(offs++)], IA[s.charAt(offs++)], IA[s.charAt(offs++)]);
        }

        return frame;
    }

    public static SettingsFrameBuilder builder() {
        return new SettingsFrameBuilder();
    }

    public boolean isAck() {
        return isFlagSet(ACK_FLAG);
    }

    public int getNumberOfSettings() {
        return numberOfSettings;
    }

    public Setting getSettingByIndex(final int idx) {
        return idx >= 0 && idx < numberOfSettings ? settings[idx] : null;
    }

    public String toBase64Uri() {
        if (numberOfSettings == 0) {
            return "";
        }

        final StringBuilder sb = new StringBuilder(numberOfSettings * 8);
        for (int i = 0; i < numberOfSettings; i++) {
            final int id = settings[i].id;
            final int value = settings[i].value;

            threeBytesToBase64Uri(id >> 8 & 0xFF, id & 0xFF, value >>> 24, sb);

            threeBytesToBase64Uri(value >> 16 & 0xFF, value >> 8 & 0xFF, value & 0xFF, sb);
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("SettingsFrame {").append(headerToString()).append(", numberOfSettings=").append(numberOfSettings);
        if (numberOfSettings > 0) {
            sb.append(", [");
            for (int i = 0; i < numberOfSettings; i++) {
                sb.append(' ');
                sb.append(OPTION_TEXT[settings[i].id - 1]).append('=').append(settings[i].value);
            }
        }
        sb.append(" ]}");
        return sb.toString();
    }

    @Override
    protected int calcLength() {
        if (numberOfSettings == -1) {
            // invalid settings frame
            return frameBuffer.remaining();
        }
        return numberOfSettings * 6;
    }

    public String getSettingNameById(final int id) {
        return OPTION_TEXT[id - 1];
    }

    // -------------------------------------------------- Methods from Cacheable

    @Override
    public void recycle() {
        if (DONT_RECYCLE) {
            return;
        }

        numberOfSettings = 0;
        super.recycle();
        ThreadCache.putToCache(CACHE_IDX, this);
    }

    // -------------------------------------------------- Methods from Http2Frame

    @Override
    public Buffer toBuffer(final MemoryManager memoryManager) {

        final Buffer buffer = memoryManager.allocate(FRAME_HEADER_SIZE + numberOfSettings * 6);

        serializeFrameHeader(buffer);

        if (numberOfSettings > 0) {
            for (int i = 0; i < numberOfSettings; i++) {
                final Setting setting = settings[i];

                buffer.putShort((short) setting.id);
                buffer.putInt(setting.value);
            }
        }

        buffer.trim();
        return buffer;
    }

    @Override
    protected Map<Integer, String> getFlagNamesMap() {
        return FLAG_NAMES_MAP;
    }

    // ------------------------------------------------------- Private Methods

    private void threeBytesToBase64Uri(final int b1, final int b2, final int b3, final StringBuilder to) {
        to.append(CA[b1 >>> 2]).append(CA[(b1 & 3) << 4 | b2 >>> 4]).append(CA[(b2 & 15) << 2 | b3 >>> 6]).append(CA[b3 & 63]);
    }

    private void addBase64UriSetting(final int b1, final int b2, final int b3, final int b4, final int b5, final int b6, final int b7, final int b8) {

        if (b1 == -1 || b2 == -1 || b3 == -1 || b4 == -1 || b5 == -1 || b6 == -1 || b7 == -1 || b8 == -1) {
            throw new IllegalStateException("Unknown base64uri character");
        }

        final int tmp1 = b1 << 18 | b2 << 12 | b3 << 6 | b4;
        final int tmp2 = b5 << 18 | b6 << 12 | b7 << 6 | b8;

        final int setting = tmp1 >> 8;
        final int value = (tmp1 & 0xFF) << 24 | tmp2;

        addSetting(setting, value);
    }

    private void addSetting(final int settingId, final int value) {
        if (settingId > 0 && settingId <= MAX_DEFINED_SETTINGS) {
            final int oldIdx = idx(settingId);
            if (oldIdx != -1) {
                numberOfSettings--; // we will remove the old value and add a new one
                if (oldIdx < numberOfSettings) {
                    // shift settings by one
                    final Setting oldSetting = settings[oldIdx];
                    System.arraycopy(settings, oldIdx + 1, settings, oldIdx, numberOfSettings - oldIdx);
                    settings[numberOfSettings] = oldSetting;
                }
            }

            final Setting storedSetting = settings[numberOfSettings++];
            storedSetting.id = settingId;
            storedSetting.value = value;
        } else if (LOGGER.isLoggable(Level.FINE)) {
            // Settings received from the peer: unknown ones must be ignored (RFC 9113, section 6.5.2), and a single
            // SETTINGS frame can carry thousands of them, so they are only logged at FINE.
            LOGGER.log(Level.FINE, "Setting {0} is unknown and will be ignored", settingId);
        }
    }

    private int idx(final int settingId) {
        for (int i = 0; i < numberOfSettings; i++) {
            if (settings[i].id == settingId) {
                return i;
            }
        }

        return -1;
    }
    // ---------------------------------------------------------- Nested Classes

    public static class SettingsFrameBuilder extends Http2FrameBuilder<SettingsFrameBuilder> {

        private int numberOfSettings;
        private final Setting[] settings = new Setting[MAX_DEFINED_SETTINGS];

        // -------------------------------------------------------- Constructors

        protected SettingsFrameBuilder() {
        }

        // ------------------------------------------------------ Public Methods

        public SettingsFrameBuilder setting(final int settingId, final int value) {
            if (settingId > 0 && settingId <= MAX_DEFINED_SETTINGS) {
                final Setting settingContainer;
                final int oldIdx = idx(settingId);
                if (oldIdx != -1) {
                    numberOfSettings--; // we will remove the old value and add a new one
                    if (oldIdx < numberOfSettings) {
                        // shift settings by one
                        final Setting oldSetting = settings[oldIdx];
                        System.arraycopy(settings, oldIdx + 1, settings, oldIdx, numberOfSettings - oldIdx);
                        settings[numberOfSettings++] = oldSetting;
                        settingContainer = oldSetting;
                    } else {
                        settingContainer = settings[numberOfSettings++];
                    }
                } else {
                    settingContainer = new Setting();
                    settings[numberOfSettings++] = settingContainer;
                }

                settingContainer.id = settingId;
                settingContainer.value = value;
            } else {
                LOGGER.log(Level.WARNING, "Setting {0} is unknown and will be ignored", settingId);
            }

            return this;
        }

        public SettingsFrameBuilder setAck() {
            setFlag(ACK_FLAG);
            return this;
        }

        @Override
        public SettingsFrame build() {
            final SettingsFrame frame = SettingsFrame.create();
            setHeaderValuesTo(frame);

            for (int i = 0; i < numberOfSettings; i++) {
                frame.addSetting(settings[i].id, settings[i].value);
            }

            return frame;
        }

        private int idx(final int settingId) {
            for (int i = 0; i < numberOfSettings; i++) {
                if (settings[i].id == settingId) {
                    return i;
                }
            }

            return -1;
        }

        // --------------------------------------- Methods from Http2FrameBuilder

        @Override
        protected SettingsFrameBuilder getThis() {
            return this;
        }

    } // END SettingsFrameBuilder

    public static final class Setting {
        private int id;
        private int value;

        private Setting() {
        }

        public int getId() {
            return id;
        }

        public int getValue() {
            return value;
        }
    }
}
