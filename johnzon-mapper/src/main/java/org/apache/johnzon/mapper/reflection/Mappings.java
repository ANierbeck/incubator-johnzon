/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.johnzon.mapper.reflection;

import org.apache.johnzon.mapper.Converter;
import org.apache.johnzon.mapper.JohnzonConverter;
import org.apache.johnzon.mapper.JohnzonIgnore;
import org.apache.johnzon.mapper.JohnzonVirtualObject;
import org.apache.johnzon.mapper.JohnzonVirtualObjects;
import org.apache.johnzon.mapper.access.AccessMode;

import java.beans.ConstructorProperties;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Arrays.asList;

public class Mappings {
    public static class ClassMapping {
        public final Class<?> clazz;
        public final Map<String, Getter> getters;
        public final Map<String, Setter> setters;
        public final Constructor<?> constructor;
        public final boolean constructorHasArguments;
        public final String[] constructorParameters;
        public final Converter<?>[] constructorParameterConverters;
        public final Type[] constructorParameterTypes;

        protected ClassMapping(final Class<?> clazz,
                               final Map<String, Getter> getters, final Map<String, Setter> setters,
                               final boolean acceptHiddenConstructor, final boolean useConstructor) {
            this.clazz = clazz;
            this.getters = getters;
            this.setters = setters;
            this.constructor = findConstructor(acceptHiddenConstructor, useConstructor);

            this.constructorHasArguments = this.constructor != null && this.constructor.getGenericParameterTypes().length > 0;
            if (this.constructorHasArguments) {
                this.constructorParameterTypes = this.constructor.getGenericParameterTypes();

                this.constructorParameters = new String[this.constructor.getGenericParameterTypes().length];
                final ConstructorProperties constructorProperties = this.constructor.getAnnotation(ConstructorProperties.class);
                System.arraycopy(constructorProperties.value(), 0, this.constructorParameters, 0, this.constructorParameters.length);

                this.constructorParameterConverters = new Converter<?>[this.constructor.getGenericParameterTypes().length];
                for (int i = 0; i < this.constructorParameters.length; i++) {
                    for (final Annotation a : this.constructor.getParameterAnnotations()[i]) {
                        if (a.annotationType() == JohnzonConverter.class) {
                            try {
                                this.constructorParameterConverters[i] = JohnzonConverter.class.cast(a).value().newInstance();
                            } catch (final Exception e) {
                                throw new IllegalArgumentException(e);
                            }
                        }
                    }
                }
            } else {
                this.constructorParameterTypes = null;
                this.constructorParameters = null;
                this.constructorParameterConverters = null;
            }
        }

        private Constructor<?> findConstructor(final boolean acceptHiddenConstructor, final boolean useConstructor) {
            Constructor<?> found = null;
            for (final Constructor<?> c : clazz.getDeclaredConstructors()) {
                if (c.getParameterTypes().length == 0) {
                    if (!Modifier.isPublic(c.getModifiers()) && acceptHiddenConstructor) {
                        c.setAccessible(true);
                    }
                    found = c;
                    if (!useConstructor) {
                        break;
                    }
                } else if (c.getAnnotation(ConstructorProperties.class) != null) {
                    found = c;
                    break;
                }
            }
            if (found != null) {
                return found;
            }
            try {
                return clazz.getConstructor();
            } catch (final NoSuchMethodException e) {
                return null; // readOnly class
            }
        }
    }

    public static class CollectionMapping {
        public final Class<?> raw;
        public final Type arg;
        public final boolean primitive;

        public CollectionMapping(final boolean primitive, final Class<?> collectionType, final Type fieldArgType) {
            this.raw = collectionType;
            this.arg = fieldArgType;
            this.primitive = primitive;
        }
    }

    public static class Getter {
        public final AccessMode.Reader reader;
        public final int version;
        public final Converter<Object> converter;
        public final boolean primitive;
        public final boolean array;
        public final boolean map;
        public final boolean collection;

        public Getter(final AccessMode.Reader reader,
                      final boolean primitive, final boolean array,
                      final boolean collection, final boolean map,
                      final Converter<Object> converter, final int version) {
            this.reader = reader;
            this.converter = converter;
            this.version = version;
            this.array = array;
            this.map = map && converter == null;
            this.collection = collection;
            this.primitive = primitive;
        }
    }

    public static class Setter {
        public final AccessMode.Writer writer;
        public final int version;
        public final Type paramType;
        public final Converter<?> converter;
        public final boolean primitive;
        public final boolean array;

        public Setter(final AccessMode.Writer writer, final boolean primitive, final boolean array,
                      final Type paramType, final Converter<?> converter, final int version) {
            this.writer = writer;
            this.paramType = paramType;
            this.converter = converter;
            this.version = version;
            this.primitive = primitive;
            this.array = array;
        }
    }

    private static final JohnzonParameterizedType VIRTUAL_TYPE = new JohnzonParameterizedType(Map.class, String.class, Object.class);

    protected final ConcurrentMap<Type, ClassMapping> classes = new ConcurrentHashMap<Type, ClassMapping>();
    protected final ConcurrentMap<Type, CollectionMapping> collections = new ConcurrentHashMap<Type, CollectionMapping>();
    protected final Comparator<String> fieldOrdering;
    private final boolean supportHiddenConstructors;
    private final boolean supportConstructors;
    private final AccessMode accessMode;
    private final int version;

    public Mappings(final Comparator<String> attributeOrder, final AccessMode accessMode,
                    final boolean supportHiddenConstructors, final boolean supportConstructors,
                    final int version) {
        this.fieldOrdering = attributeOrder;
        this.accessMode = accessMode;
        this.supportHiddenConstructors = supportHiddenConstructors;
        this.supportConstructors = supportConstructors;
        this.version = version;
    }

    public <T> CollectionMapping findCollectionMapping(final ParameterizedType genericType) {
        CollectionMapping collectionMapping = collections.get(genericType);
        if (collectionMapping == null) {
            collectionMapping = createCollectionMapping(genericType);
            if (collectionMapping == null) {
                return null;
            }
            final CollectionMapping existing = collections.putIfAbsent(genericType, collectionMapping);
            if (existing != null) {
                collectionMapping = existing;
            }
        }
        return collectionMapping;
    }

    private <T> CollectionMapping createCollectionMapping(final ParameterizedType aType) {
        final Type[] fieldArgTypes = aType.getActualTypeArguments();
        final Type raw = aType.getRawType();
        if (fieldArgTypes.length == 1 && Class.class.isInstance(raw)) {
            final Class<?> r = Class.class.cast(raw);
            final Class<?> collectionType;
            if (List.class.isAssignableFrom(r)) {
                collectionType = List.class;
            }else if (SortedSet.class.isAssignableFrom(r)) {
                collectionType = SortedSet.class;
            } else if (Set.class.isAssignableFrom(r)) {
                collectionType = Set.class;
            } else if (Queue.class.isAssignableFrom(r)) {
                collectionType = Queue.class;
            } else if (Collection.class.isAssignableFrom(r)) {
                collectionType = Collection.class;
            } else {
                return null;
            }

            final CollectionMapping mapping = new CollectionMapping(isPrimitive(fieldArgTypes[0]), collectionType, fieldArgTypes[0]);
            collections.putIfAbsent(aType, mapping);
            return mapping;
        }
        return null;
    }

    // has JSon API a method for this type
    public static boolean isPrimitive(final Type type) {
        if (type == String.class) {
            return true;
        } else if (type == char.class || type == Character.class) {
            return true;
        } else if (type == long.class || type == Long.class) {
            return true;
        } else if (type == int.class || type == Integer.class
                || type == byte.class || type == Byte.class
                || type == short.class || type == Short.class) {
            return true;
        } else if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            return true;
        } else if (type == boolean.class || type == Boolean.class) {
            return true;
        } else if (type == BigDecimal.class) {
            return true;
        } else if (type == BigInteger.class) {
            return true;
        }
        return false;
    }

    public ClassMapping getClassMapping(final Type clazz) {
        return classes.get(clazz);
    }

    public ClassMapping findOrCreateClassMapping(final Type clazz) {
        ClassMapping classMapping = classes.get(clazz);
        if (classMapping == null) {
            if (!Class.class.isInstance(clazz) || Map.class.isAssignableFrom(Class.class.cast(clazz))) {
                return null;
            }

            classMapping = createClassMapping(Class.class.cast(clazz));
            final ClassMapping existing = classes.putIfAbsent(clazz, classMapping);
            if (existing != null) {
                classMapping = existing;
            }
        }
        return classMapping;
    }

    private ClassMapping createClassMapping(final Class<?> clazz) {
        final Map<String, Getter> getters = newOrderedMap();
        final Map<String, Setter> setters = newOrderedMap();

        final Map<String, AccessMode.Reader> readers = accessMode.findReaders(clazz);
        final Map<String, AccessMode.Writer> writers = accessMode.findWriters(clazz);

        final Collection<String> virtualFields = new HashSet<String>();
        {
            final JohnzonVirtualObjects virtualObjects = clazz.getAnnotation(JohnzonVirtualObjects.class);
            if (virtualObjects != null) {
                for (final JohnzonVirtualObject virtualObject : virtualObjects.value()) {
                    handleVirtualObject(virtualFields, virtualObject, getters, setters, readers, writers);
                }
            }

            final JohnzonVirtualObject virtualObject = clazz.getAnnotation(JohnzonVirtualObject.class);
            if (virtualObject != null) {
                handleVirtualObject(virtualFields, virtualObject, getters, setters, readers, writers);
            }
        }

        for (final Map.Entry<String, AccessMode.Reader> reader : readers.entrySet()) {
            final String key = reader.getKey();
            if (virtualFields.contains(key)) {
                continue;
            }
            addGetterIfNeeded(getters, key, reader.getValue());
        }

        for (final Map.Entry<String, AccessMode.Writer> writer : writers.entrySet()) {
            final String key = writer.getKey();
            if (virtualFields.contains(key)) {
                continue;
            }
            addSetterIfNeeded(setters, key, writer.getValue());
        }
        return new ClassMapping(clazz, getters, setters, supportHiddenConstructors, supportConstructors);
    }

    private <T> Map<String, T> newOrderedMap() {
        return fieldOrdering != null ? new TreeMap<String, T>(fieldOrdering) : new HashMap<String, T>();
    }

    private void addSetterIfNeeded(final Map<String, Setter> setters,
                                   final String key,
                                   final AccessMode.Writer value) {
        final JohnzonIgnore writeIgnore = value.getAnnotation(JohnzonIgnore.class);
        if (writeIgnore == null || writeIgnore.minVersion() >= 0) {
            if (key.equals("metaClass")) {
                return;
            }
            final Type param = value.getType();
            final Class<?> returnType = Class.class.isInstance(param) ? Class.class.cast(param) : null;
            final Setter setter = new Setter(
                    value, isPrimitive(param), returnType != null && returnType.isArray(), param,
                    findConverter(value), writeIgnore != null ? writeIgnore.minVersion() : -1);
            setters.put(key, setter);
        }
    }

    private void addGetterIfNeeded(final Map<String, Getter> getters,
                                   final String key,
                                   final AccessMode.Reader value) {
        final JohnzonIgnore readIgnore = value.getAnnotation(JohnzonIgnore.class);
        if (readIgnore == null || readIgnore.minVersion() >= 0) {
            final Class<?> returnType = Class.class.isInstance(value.getType()) ? Class.class.cast(value.getType()) : null;
            final ParameterizedType pt = ParameterizedType.class.isInstance(value.getType()) ? ParameterizedType.class.cast(value.getType()) : null;
            final Getter getter = new Getter(value, isPrimitive(returnType),
                    returnType != null && returnType.isArray(),
                    (pt != null && Collection.class.isAssignableFrom(Class.class.cast(pt.getRawType())))
                            || (returnType != null && Collection.class.isAssignableFrom(returnType)),
                    (pt != null && Map.class.isAssignableFrom(Class.class.cast(pt.getRawType())))
                            || (returnType != null && Map.class.isAssignableFrom(returnType)),
                    findConverter(value),
                    readIgnore != null ? readIgnore.minVersion() : -1);
            getters.put(key, getter);
        }
    }

    // idea is quite trivial, simulate an object with a Map<String, Object>
    private void handleVirtualObject(final Collection<String> virtualFields,
                                     final JohnzonVirtualObject o,
                                     final Map<String, Getter> getters,
                                     final Map<String, Setter> setters,
                                     final Map<String, AccessMode.Reader> readers,
                                     final Map<String, AccessMode.Writer> writers) {
        final String[] path = o.path();
        if (path.length < 1) {
            throw new IllegalArgumentException("@JohnzonVirtualObject need a path");
        }

        // add them to ignored fields
        for (final JohnzonVirtualObject.Field f : o.fields()) {
            virtualFields.add(f.value());
        }

        // build "this" model
        final Map<String, Getter> objectGetters = newOrderedMap();
        final Map<String, Setter> objectSetters = newOrderedMap();

        for (final JohnzonVirtualObject.Field f : o.fields()) {
            final String name = f.value();
            if (f.read()) {
                final AccessMode.Reader reader = readers.get(name);
                if (reader != null) {
                    addGetterIfNeeded(objectGetters, name, reader);
                }
            }
            if (f.write()) {
                final AccessMode.Writer writer = writers.get(name);
                if (writer != null) {
                    addSetterIfNeeded(objectSetters, name, writer);
                }
            }
        }

        final String key = path[0];

        final Getter getter = getters.get(key);
        final MapBuilderReader newReader = new MapBuilderReader(objectGetters, path, version);
        getters.put(key, new Getter(getter == null ? newReader : new CompositeReader(getter.reader, newReader), false, false, false, true, null, -1));

        final Setter newSetter = setters.get(key);
        final MapUnwrapperWriter newWriter = new MapUnwrapperWriter(objectSetters, path);
        setters.put(key, new Setter(newSetter == null ? newWriter : new CompositeWriter(newSetter.writer, newWriter), false, false, VIRTUAL_TYPE, null, -1));
    }

    private static Converter findConverter(final AccessMode.DecoratedType method) {
        Converter converter = null;
        if (method.getAnnotation(JohnzonConverter.class) != null) {
            try {
                converter = method.getAnnotation(JohnzonConverter.class).value().newInstance();
            } catch (final Exception e) {
                throw new IllegalArgumentException(e);
            }
        }
        return converter;
    }

    private static class MapBuilderReader implements AccessMode.Reader {
        private final Map<String, Getter> getters;
        private final Map<String, Object> template;
        private final String[] paths;
        private final int version;

        public MapBuilderReader(final Map<String, Getter> objectGetters, final String[] paths, final int version) {
            this.getters = objectGetters;
            this.paths = paths;
            this.template = new LinkedHashMap<String, Object>();
            this.version = version;

            Map<String, Object> last = this.template;
            for (int i = 1; i < paths.length; i++) {
                final Map<String, Object> newLast = new LinkedHashMap<String, Object>();
                last.put(paths[i], newLast);
                last = newLast;
            }
        }

        @Override
        public Object read(final Object instance) {
            final Map<String, Object> map = new LinkedHashMap<String, Object>(template);
            Map<String, Object> nested = map;
            for (int i = 1; i < paths.length; i++) {
                nested = Map.class.cast(nested.get(paths[i]));
            }
            for (final Map.Entry<String, Getter> g : getters.entrySet()) {
                final Mappings.Getter getter = g.getValue();
                final Object value = getter.reader.read(instance);
                final Object val = value == null || getter.converter == null ? value : getter.converter.toString(value);
                if (val == null) {
                    continue;
                }
                if (getter.version >= 0 && version >= getter.version) {
                    continue;
                }

                nested.put(g.getKey(), val);
            }
            return map;
        }

        @Override
        public Type getType() {
            return VIRTUAL_TYPE;
        }

        @Override
        public <T extends Annotation> T getAnnotation(final Class<T> clazz) {
            throw new UnsupportedOperationException("getAnnotation shouldn't get called for virtual fields");
        }
    }

    private static class MapUnwrapperWriter implements AccessMode.Writer {
        private final Map<String, Setter> writers;
        private final Map<String, Class<?>> componentTypes;
        private final String[] paths;

        public MapUnwrapperWriter(final Map<String, Setter> writers, final String[] paths) {
            this.writers = writers;
            this.paths = paths;
            this.componentTypes = new HashMap<String, Class<?>>();

            for (final Map.Entry<String, Setter> setter : writers.entrySet()) {
                if (setter.getValue().array) {
                    componentTypes.put(setter.getKey(), Class.class.cast(setter.getValue().paramType).getComponentType());
                }
            }
        }

        @Override
        public void write(final Object instance, final Object value) {
            Map<String, Object> nested = null;
            for (final String path : paths) {
                nested = Map.class.cast(nested == null ? value : nested.get(path));
                if (nested == null) {
                    return;
                }
            }

            for (final Map.Entry<String, Setter> setter : writers.entrySet()) {
                final Setter setterValue = setter.getValue();
                final String key = setter.getKey();
                final Object rawValue = nested.get(key);
                Object val = value == null || setterValue.converter == null ?
                        rawValue : Converter.class.cast(setterValue.converter).toString(rawValue);
                if (val == null) {
                    continue;
                }

                if (setterValue.array && Collection.class.isInstance(val)) {
                    final Collection<?> collection = Collection.class.cast(val);
                    final Object[] array = (Object[]) Array.newInstance(componentTypes.get(key), collection.size());
                    val = collection.toArray(array);
                }

                final AccessMode.Writer setterMethod = setterValue.writer;
                setterMethod.write(instance, val);
            }
        }

        @Override
        public Type getType() {
            return VIRTUAL_TYPE;
        }

        @Override
        public <T extends Annotation> T getAnnotation(final Class<T> clazz) {
            throw new UnsupportedOperationException("getAnnotation shouldn't get called for virtual fields");
        }
    }

    private static class CompositeReader implements AccessMode.Reader {
        private final AccessMode.Reader[] delegates;

        public CompositeReader(final AccessMode.Reader... delegates) {
            final Collection<AccessMode.Reader> all = new LinkedList<AccessMode.Reader>();
            for (final AccessMode.Reader r : delegates) {
                if (CompositeReader.class.isInstance(r)) {
                    all.addAll(asList(CompositeReader.class.cast(r).delegates));
                } else {
                    all.add(r);
                }
            }
            this.delegates = all.toArray(new AccessMode.Reader[all.size()]);
        }

        @Override
        public Object read(final Object instance) {
            final Map<String, Object> map = new LinkedHashMap<String, Object>();
            for (final AccessMode.Reader reader : delegates) {
                final Map<String, Object> readerMap = (Map<String, Object>) reader.read(instance);
                for (final Map.Entry<String, Object> entry :readerMap.entrySet()) {
                    final Object o = map.get(entry.getKey());
                    if (o == null) {
                        map.put(entry.getKey(), entry.getValue());
                    } else  if (Map.class.isInstance(o)) {
                        // TODO
                    } else {
                        throw new IllegalStateException(entry.getKey() + " is ambiguous");
                    }
                }
            }
            return map;
        }

        @Override
        public Type getType() {
            return VIRTUAL_TYPE;
        }

        @Override
        public <T extends Annotation> T getAnnotation(final Class<T> clazz) {
            throw new UnsupportedOperationException("getAnnotation shouldn't get called for virtual fields");
        }
    }

    private static class CompositeWriter implements AccessMode.Writer {
        private final AccessMode.Writer[] delegates;

        public CompositeWriter(final AccessMode.Writer... writers) {
            final Collection<AccessMode.Writer> all = new LinkedList<AccessMode.Writer>();
            for (final AccessMode.Writer r : writers) {
                if (CompositeWriter.class.isInstance(r)) {
                    all.addAll(asList(CompositeWriter.class.cast(r).delegates));
                } else {
                    all.add(r);
                }
            }
            this.delegates = all.toArray(new AccessMode.Writer[all.size()]);
        }

        @Override
        public void write(final Object instance, final Object value) {
            for (final AccessMode.Writer w : delegates) {
                w.write(instance, value);
            }
        }

        @Override
        public Type getType() {
            return VIRTUAL_TYPE;
        }

        @Override
        public <T extends Annotation> T getAnnotation(final Class<T> clazz) {
            throw new UnsupportedOperationException("getAnnotation shouldn't get called for virtual fields");
        }
    }
}
