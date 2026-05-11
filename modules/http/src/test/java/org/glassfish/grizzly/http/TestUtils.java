/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
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
package org.glassfish.grizzly.http;

/**
 *
 * @author Ondro Mihalyi
 */
public class TestUtils {
    public static class SystemPropertyToggle {
        private final String propertyName;
        private final Boolean propertyEnabled;
        private final boolean enabledByDefault;
        private String previousValue;

        public SystemPropertyToggle(String propertyName, Boolean propertyEnabled, boolean enabledByDefault) {
            this.propertyName = propertyName;
            this.propertyEnabled = propertyEnabled;
            this.enabledByDefault = enabledByDefault;
        }

        public boolean isEnabled() {
            return propertyEnabled != null
                    ? propertyEnabled
                    : enabledByDefault;
        }

        public void set() {
            previousValue = System.getProperty(propertyName);
            setOrUnsetProperty(propertyEnabled == null
                    ? null
                    : String.valueOf(propertyEnabled.booleanValue()));
        }

        public void unset() {
            setOrUnsetProperty(previousValue);
        }

        private void setOrUnsetProperty(String value) {
            if (value == null) {
                System.getProperties().remove(propertyName);
            } else {
                System.setProperty(propertyName, value);
            }
        }
    }
}
