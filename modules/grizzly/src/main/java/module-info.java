/*
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2018 Payara Services Ltd.
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

module org.glassfish.grizzly {
    
    exports org.glassfish.grizzly;
    exports org.glassfish.grizzly.asyncqueue;
    exports org.glassfish.grizzly.attributes;
    exports org.glassfish.grizzly.compression.lzma;
    exports org.glassfish.grizzly.compression.lzma.impl;
    exports org.glassfish.grizzly.compression.lzma.impl.lz;
    exports org.glassfish.grizzly.compression.lzma.impl.rangecoder;
    exports org.glassfish.grizzly.compression.zip;
    exports org.glassfish.grizzly.filterchain;
    exports org.glassfish.grizzly.impl;
    exports org.glassfish.grizzly.jmxbase;
    exports org.glassfish.grizzly.localization;
    exports org.glassfish.grizzly.memory;
    exports org.glassfish.grizzly.monitoring;
    exports org.glassfish.grizzly.nio;
    exports org.glassfish.grizzly.nio.tmpselectors;
    exports org.glassfish.grizzly.nio.transport;
    exports org.glassfish.grizzly.ssl;
    exports org.glassfish.grizzly.strategies;
    exports org.glassfish.grizzly.streams;
    exports org.glassfish.grizzly.threadpool;
    exports org.glassfish.grizzly.utils;
    exports org.glassfish.grizzly.utils.conditions;
    
    opens org.glassfish.grizzly;
    opens org.glassfish.grizzly.asyncqueue;
    opens org.glassfish.grizzly.attributes;
    opens org.glassfish.grizzly.compression.lzma;
    opens org.glassfish.grizzly.compression.lzma.impl;
    opens org.glassfish.grizzly.compression.lzma.impl.lz;
    opens org.glassfish.grizzly.compression.lzma.impl.rangecoder;
    opens org.glassfish.grizzly.compression.zip;
    opens org.glassfish.grizzly.filterchain;
    opens org.glassfish.grizzly.impl;
    opens org.glassfish.grizzly.jmxbase;
    opens org.glassfish.grizzly.localization;
    opens org.glassfish.grizzly.memory;
    opens org.glassfish.grizzly.monitoring;
    opens org.glassfish.grizzly.nio;
    opens org.glassfish.grizzly.nio.tmpselectors;
    opens org.glassfish.grizzly.nio.transport;
    opens org.glassfish.grizzly.ssl;
    opens org.glassfish.grizzly.strategies;
    opens org.glassfish.grizzly.streams;
    opens org.glassfish.grizzly.threadpool;
    opens org.glassfish.grizzly.utils;
    opens org.glassfish.grizzly.utils.conditions;
    
    requires java.logging;
}
