/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.attributes.DefaultAttributeBuilder;
import org.glassfish.grizzly.attributes.IndexedAttributeHolder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Testing {@link Attribute}s.
 *
 * @author Alexey Stashok
 */
@RunWith(Parameterized.class)
public class AttributesTest {

    @Parameterized.Parameters
    public static Collection<Object[]> isSafe() {
        return Arrays.asList(new Object[][] { { Boolean.FALSE }, { Boolean.TRUE } });
    }

    private final boolean isSafe;

    public AttributesTest(final boolean isSafe) {
        this.isSafe = isSafe;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAttributes() {
        AttributeBuilder builder = new DefaultAttributeBuilder();
        AttributeHolder holder = isSafe ? builder.createSafeAttributeHolder() : builder.createUnsafeAttributeHolder();

        final int attrCount = 10;

        final Attribute[] attrs = new Attribute[attrCount];

        for (int i = 0; i < attrCount; i++) {
            attrs[i] = builder.createAttribute("attribute-" + i);
        }

        // set values
        for (int i = 0; i < attrCount; i++) {
            attrs[i].set(holder, "value-" + i);
        }

        // check values
        for (int i = 0; i < attrCount; i++) {
            assertTrue(attrs[i].isSet(holder));
            assertEquals("value-" + i, attrs[i].get(holder));
        }

        assertNotNull(attrs[0].remove(holder));
        assertFalse(attrs[0].isSet(holder));
        assertNull(attrs[0].remove(holder));
        assertNull(attrs[0].get(holder));

        assertNotNull(attrs[attrCount - 1].remove(holder));
        assertFalse(attrs[attrCount - 1].isSet(holder));
        assertNull(attrs[attrCount - 1].remove(holder));
        assertNull(attrs[attrCount - 1].get(holder));

        final Set<String> attrNames = holder.getAttributeNames();
        assertEquals(attrCount - 2, attrNames.size());

        for (int i = 1; i < attrCount - 1; i++) {
            assertTrue(attrNames.contains(attrs[i].name()));
        }
    }

    @Test
    public void testAttributeGetWithNullaryFunctionOnEmptyHolder() {
        AttributeBuilder builder = new DefaultAttributeBuilder();
        AttributeHolder holder = isSafe ? builder.createSafeAttributeHolder() : builder.createUnsafeAttributeHolder();

        assertEquals("default", holder.getAttribute("attribute", () -> "default"));

        final Attribute<String> attr = builder.createAttribute("attribute", new Supplier<String>() {
            @Override
            public String get() {
                return "default";
            }
        });

        assertNull(attr.peek(holder));
        assertEquals("default", attr.get(holder));
        assertTrue(attr.isSet(holder));
        assertEquals("default", attr.peek(holder));
    }

    @Test
    public void testAttributeGetWithoutInitializerOnEmptyHolder() {
        AttributeBuilder builder = new DefaultAttributeBuilder();
        AttributeHolder holder = isSafe ? builder.createSafeAttributeHolder() : builder.createUnsafeAttributeHolder();

        assertNull(holder.getAttribute("attribute"));

        final Attribute<String> attr = builder.createAttribute("attribute");

        assertNull(attr.peek(holder));
        assertNull(attr.get(holder));
        assertFalse(attr.isSet(holder));
    }

    @Test
    public void testAttributesCopy() {
        final AttributeBuilder builder = new DefaultAttributeBuilder();
        final AttributeHolder holder =
                isSafe ? builder.createSafeAttributeHolder() : builder.createUnsafeAttributeHolder();

        final int attrCount = 100;

        final Attribute[] attrs = new Attribute[attrCount];
        IntStream.range(0, attrCount).forEach(i -> attrs[i] = builder.createAttribute("attribute-" + i));

        Arrays.stream(attrs).forEach(attr -> {
            final String value = "value-" + attr.toString();
            attr.set(holder, value);
            assertEquals(value, attr.get(holder));
        });

        // copy from the same type of holder
        final AttributeHolder copiedHolder =
                isSafe ? builder.createSafeAttributeHolder() : builder.createUnsafeAttributeHolder();
        copiedHolder.copyFrom(holder);
        isSameAttributeHolder(attrs, holder, copiedHolder);

        // copy to the same type of holder
        final AttributeHolder copyToHolder =
                isSafe ? builder.createSafeAttributeHolder() : builder.createUnsafeAttributeHolder();
        holder.copyTo(copyToHolder);
        isSameAttributeHolder(attrs, holder, copyToHolder);

        // copy to another type of holder
        final AttributeHolder copyToAnotherHolder =
                !isSafe ? builder.createSafeAttributeHolder() : builder.createUnsafeAttributeHolder();
        holder.copyTo(copyToAnotherHolder);
        isSameAttributeHolder(attrs, holder, copyToAnotherHolder);

        // copy from another type of holder
        final AttributeHolder copiedHolder2 =
                isSafe ? builder.createSafeAttributeHolder() : builder.createUnsafeAttributeHolder();
        copiedHolder2.copyFrom(copyToAnotherHolder);
        isSameAttributeHolder(attrs, copyToAnotherHolder, copiedHolder2);
    }

    private static void isSameAttributeHolder(final Attribute[] attrs, final AttributeHolder expected,
                                              final AttributeHolder actual) {
        assertEquals(expected.getAttributeNames(), actual.getAttributeNames());
        Arrays.stream(attrs).forEach(attr -> {
            assertEquals(attr.get(expected), attr.get(actual));
        });
    }

    @Test
    public void testAttributesInMultiThreads() {
        if (!isSafe) {
            return;
        }
        final AttributeBuilder builder = new DefaultAttributeBuilder();
        final AttributeHolder holder = builder.createSafeAttributeHolder();

        final int attrCount = 100;
        final int numberOfTryCountPerThread = 100;

        final Attribute[] attrs = new Attribute[attrCount];
        IntStream.range(0, attrCount).forEach(i -> attrs[i] = builder.createAttribute("attribute-" + i));

        // Each thread tries to set/get/remove each attribute multiple times.
        Arrays.stream(attrs).parallel().forEach(attr -> IntStream.range(0, numberOfTryCountPerThread).forEach(i -> {
            final String value = "value-" + i;
            attr.set(holder, value);
            assertEquals(attr.toString(), value, attr.get(holder));
            assertEquals(attr.toString(), value, attr.remove(holder));
            assertNull(attr.toString(), attr.get(holder));
        }));
    }

    @Test
    @Ignore("Performance test")
    public void testAttributesForPerformance() {
        if (!isSafe) {
            return;
        }
        final AttributeBuilder builder = new DefaultAttributeBuilder();
        final AttributeHolder holder = builder.createSafeAttributeHolder();
        final AttributeHolder holder2 = new IndexedAttributeHolder(builder); // for comparison
        final AttributeHolder holder3 = builder.createUnsafeAttributeHolder(); // for comparison

        final int attrCount = 100;
        final int numberOfTryCountPerThread = 20000;
        final int repeatCount = 2;

        final Attribute[] attrs = new Attribute[attrCount];
        IntStream.range(0, attrCount).forEach(i -> attrs[i] = builder.createAttribute("attribute-" + i));

        for (int i = 0; i < repeatCount; i++) {
            System.out.println("Performance test iteration #" + (i + 1));
            long timeElapsed = getMeasureTimeInMillis(attrs, holder, numberOfTryCountPerThread);
            System.out.println("Time elapsed for Attributes test1 with " + attrs.length + " attributes and " +
                               numberOfTryCountPerThread + " tries per attribute: " + timeElapsed + " ms");
            timeElapsed = getMeasureTimeInMillis(attrs, holder2, numberOfTryCountPerThread);
            System.out.println("Time elapsed for Attributes test2 with " + attrs.length + " attributes and " +
                               numberOfTryCountPerThread + " tries per attribute: " + timeElapsed + " ms");
            timeElapsed = getMeasureTimeInMillis(attrs, holder3, numberOfTryCountPerThread);
            System.out.println("Time elapsed for Attributes test3 with " + attrs.length + " attributes and " +
                               numberOfTryCountPerThread + " tries per attribute: " + timeElapsed + " ms");
        }
    }

    private static long getMeasureTimeInMillis(final Attribute[] attrs, final AttributeHolder holder,
                                               final int numberOfTryCountPerThread) {
        final Instant start = Instant.now();
        // Each thread tries to set/get/remove each attribute multiple times.
        final CompletableFuture[] futures = Arrays.stream(attrs).map(attr -> CompletableFuture.runAsync(
                () -> IntStream.range(0, numberOfTryCountPerThread).forEach(i -> {
                    final String value = "value-" + i;
                    attr.set(holder, value);
                    Object result = attr.get(holder);
                    //assertEquals(attr.toString(), value, result);
                    result = attr.remove(holder);
                    //assertEquals(attr.toString(), value, result);
                    result = attr.get(holder);
                    //assertNull(attr.toString(), result);
                }))).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futures).orTimeout(30, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            fail(e.getMessage());
        }
        final Instant finish = Instant.now();
        return Duration.between(start, finish).toMillis();
    }
}
