/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.halyx.spring.data.document.repository;

import org.springframework.data.repository.query.parser.Part;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The property a query part refers to. Spring Data 4.1 moved {@code PropertyPath} from
 * {@code org.springframework.data.mapping} to {@code org.springframework.data.core}, so code compiled against one
 * can't link against the other; these look the methods up by name, which both versions share.
 */
final class PropertyPaths {

    private static final MethodHandle PROPERTY;
    private static final MethodHandle SEGMENT;
    private static final MethodHandle HAS_NEXT;
    private static final MethodHandle DOT_PATH;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            var getProperty = Part.class.getMethod("getProperty");
            Class<?> path = getProperty.getReturnType();
            PROPERTY = lookup.unreflect(getProperty).asType(MethodType.methodType(Object.class, Part.class));
            SEGMENT = lookup.findVirtual(path, "getSegment", MethodType.methodType(String.class))
                    .asType(MethodType.methodType(String.class, Object.class));
            HAS_NEXT = lookup.findVirtual(path, "hasNext", MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
            DOT_PATH = lookup.findVirtual(path, "toDotPath", MethodType.methodType(String.class))
                    .asType(MethodType.methodType(String.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PropertyPaths() {
    }

    /** The first segment of the part's property, such as {@code customerId}. */
    static String segment(Part part) {
        try {
            return (String) SEGMENT.invokeExact(property(part));
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /** Whether the part's property is nested, such as {@code address.city}. */
    static boolean nested(Part part) {
        try {
            return (boolean) HAS_NEXT.invokeExact(property(part));
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    static String dotPath(Part part) {
        try {
            return (String) DOT_PATH.invokeExact(property(part));
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    private static Object property(Part part) throws Throwable {
        return (Object) PROPERTY.invokeExact(part);
    }

    private static RuntimeException rethrow(Throwable e) {
        if (e instanceof RuntimeException runtime) {
            return runtime;
        }
        if (e instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(e);
    }
}
