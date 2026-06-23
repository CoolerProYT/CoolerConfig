package com.coolerpromc.coolerconfig.config;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * {@link DynamicOps} over plain Java types — {@link Map}, {@link List}, {@link String},
 * {@link Boolean} and boxed numbers.
 *
 * <p>This is a stand-in for {@code com.mojang.serialization.JavaOps}, which only exists in
 * DataFixerUpper 8.0.16 and later (Minecraft 1.20.5+). Minecraft 1.20.1 ships DFU 6.0.8, so
 * CoolerConfig carries its own copy and behaves identically on every supported version.
 *
 * <p>The tree it produces is exactly what Night-Config serialises, which is why codec-backed
 * entries round-trip through all four {@link ConfigFormat}s. Two properties matter to the rest
 * of the library:
 *
 * <ul>
 *   <li><b>Strict accessors.</b> {@link #getBooleanValue} accepts only a {@code Boolean} — it
 *       does not coerce numbers the way {@code JsonOps} does. {@link CoolerCodecs#PRIMITIVE}
 *       depends on that to tell {@code true} apart from {@code 1}.</li>
 *   <li><b>Encoding order is stable.</b> Maps are built as {@link LinkedHashMap}, so a given
 *       codec writes its fields in the same order on every run and re-saving a config produces
 *       no spurious diff. (The order is the one the codec emits fields in, which for
 *       {@code RecordCodecBuilder} groups of more than four fields is not the declaration order
 *       — that is a quirk of DataFixerUpper itself and {@code JsonOps} reorders identically.)</li>
 * </ul>
 *
 * <p>Numbers are stored as-is, so {@link #getNumberValue} hands back the original {@link Number}
 * and a {@code Codec.INT} field reads back as an {@code int} no matter which format wrote it.
 *
 * <p>Byte, int and long lists are not given a specialised representation: they fall back to the
 * {@link DynamicOps} defaults and become plain {@code List}s of numbers. That is deliberate —
 * the fastutil lists that DFU's own {@code JavaOps} produces are not types Night-Config can
 * write to a config file.
 */
public final class JavaOps implements DynamicOps<Object> {
    /** The single instance; this class holds no state. */
    public static final JavaOps INSTANCE = new JavaOps();

    private JavaOps() {}

    @Override
    public Object empty() {
        return null;
    }

    @Override
    public Object emptyMap() {
        return new LinkedHashMap<>();
    }

    @Override
    public Object emptyList() {
        return new ArrayList<>();
    }

    @Override
    public <U> U convertTo(DynamicOps<U> outOps, Object input) {
        if (input == null) return outOps.empty();
        if (input instanceof Map) return convertMap(outOps, input);
        if (input instanceof List) return convertList(outOps, input);
        if (input instanceof String value) return outOps.createString(value);
        if (input instanceof Boolean value) return outOps.createBoolean(value);
        if (input instanceof Byte value) return outOps.createByte(value);
        if (input instanceof Short value) return outOps.createShort(value);
        if (input instanceof Integer value) return outOps.createInt(value);
        if (input instanceof Long value) return outOps.createLong(value);
        if (input instanceof Float value) return outOps.createFloat(value);
        if (input instanceof Double value) return outOps.createDouble(value);
        if (input instanceof Number value) return outOps.createNumeric(value);
        throw new IllegalStateException("Don't know how to convert " + input);
    }

    @Override
    public DataResult<Number> getNumberValue(Object input) {
        if (input instanceof Number value) return DataResult.success(value);
        return DataResult.error(() -> "Not a number: " + input);
    }

    @Override
    public Object createNumeric(Number value) {
        return value;
    }

    @Override
    public Object createByte(byte value) {
        return value;
    }

    @Override
    public Object createShort(short value) {
        return value;
    }

    @Override
    public Object createInt(int value) {
        return value;
    }

    @Override
    public Object createLong(long value) {
        return value;
    }

    @Override
    public Object createFloat(float value) {
        return value;
    }

    @Override
    public Object createDouble(double value) {
        return value;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Strict: a number is <em>not</em> a boolean here, unlike the {@link DynamicOps} default.
     */
    @Override
    public DataResult<Boolean> getBooleanValue(Object input) {
        if (input instanceof Boolean value) return DataResult.success(value);
        return DataResult.error(() -> "Not a boolean: " + input);
    }

    @Override
    public Object createBoolean(boolean value) {
        return value;
    }

    @Override
    public DataResult<String> getStringValue(Object input) {
        if (input instanceof String value) return DataResult.success(value);
        return DataResult.error(() -> "Not a string: " + input);
    }

    @Override
    public Object createString(String value) {
        return value;
    }

    @Override
    public DataResult<Object> mergeToList(Object input, Object value) {
        if (input == null) {
            List<Object> result = new ArrayList<>(1);
            result.add(value);
            return DataResult.success(result);
        }
        if (input instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size() + 1);
            result.addAll(list);
            result.add(value);
            return DataResult.success(result);
        }
        return DataResult.error(() -> "Not a list: " + input);
    }

    @Override
    public DataResult<Object> mergeToList(Object input, List<Object> values) {
        if (input == null) return DataResult.success(new ArrayList<>(values));
        if (input instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size() + values.size());
            result.addAll(list);
            result.addAll(values);
            return DataResult.success(result);
        }
        return DataResult.error(() -> "Not a list: " + input);
    }

    @Override
    public DataResult<Object> mergeToMap(Object input, Object key, Object value) {
        if (input == null) {
            Map<Object, Object> result = new LinkedHashMap<>(1);
            result.put(key, value);
            return DataResult.success(result);
        }
        if (input instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>(map);
            result.put(key, value);
            return DataResult.success(result);
        }
        return DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public DataResult<Object> mergeToMap(Object input, Map<Object, Object> values) {
        if (input == null) return DataResult.success(new LinkedHashMap<>(values));
        if (input instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>(map);
            result.putAll(values);
            return DataResult.success(result);
        }
        return DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public DataResult<Object> mergeToMap(Object input, MapLike<Object> values) {
        if (input != null && !(input instanceof Map<?, ?>)) {
            return DataResult.error(() -> "Not a map: " + input);
        }
        Map<Object, Object> result = input == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>((Map<?, ?>) input);
        values.entries().forEach(entry -> result.put(entry.getFirst(), entry.getSecond()));
        return DataResult.success(result);
    }

    @Override
    public DataResult<Stream<Pair<Object, Object>>> getMapValues(Object input) {
        if (input instanceof Map<?, ?> map) return DataResult.success(mapEntries(map));
        return DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public DataResult<Consumer<BiConsumer<Object, Object>>> getMapEntries(Object input) {
        if (input instanceof Map<?, ?> map) return DataResult.success(map::forEach);
        return DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public DataResult<MapLike<Object>> getMap(Object input) {
        if (input instanceof Map<?, ?> map) {
            return DataResult.success(new MapLike<>() {
                @Nullable
                @Override
                public Object get(Object key) {
                    return map.get(key);
                }

                @Nullable
                @Override
                public Object get(String key) {
                    return map.get(key);
                }

                @Override
                public Stream<Pair<Object, Object>> entries() {
                    return mapEntries(map);
                }

                @Override
                public String toString() {
                    return "MapLike[" + map + "]";
                }
            });
        }
        return DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public Object createMap(Stream<Pair<Object, Object>> map) {
        Map<Object, Object> result = new LinkedHashMap<>();
        map.forEach(pair -> result.put(pair.getFirst(), pair.getSecond()));
        return result;
    }

    @Override
    public Object createMap(Map<Object, Object> map) {
        return new LinkedHashMap<>(map);
    }

    @Override
    public DataResult<Stream<Object>> getStream(Object input) {
        if (input instanceof List<?> list) return DataResult.success(list.stream().map(o -> o));
        return DataResult.error(() -> "Not a list: " + input);
    }

    @Override
    public DataResult<Consumer<Consumer<Object>>> getList(Object input) {
        if (input instanceof List<?> list) return DataResult.success(list::forEach);
        return DataResult.error(() -> "Not a list: " + input);
    }

    @Override
    public Object createList(Stream<Object> input) {
        return input.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    @Override
    public Object remove(Object input, String key) {
        if (input instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>(map);
            result.remove(key);
            return result;
        }
        return input;
    }

    @Override
    public RecordBuilder<Object> mapBuilder() {
        return new JavaMapBuilder(this);
    }

    @Override
    public String toString() {
        return "Java";
    }

    private static Stream<Pair<Object, Object>> mapEntries(Map<?, ?> input) {
        return input.entrySet().stream().map(e -> Pair.of(e.getKey(), e.getValue()));
    }

    /**
     * Record builder backed by a {@link LinkedHashMap}, so encoded fields keep the order the
     * codec declares them in and a repeated key overwrites rather than throwing (the behaviour
     * of DFU's own {@code JavaOps}; the {@link DynamicOps} default builder does neither).
     */
    private static final class JavaMapBuilder
            extends RecordBuilder.AbstractUniversalBuilder<Object, Map<Object, Object>> {
        JavaMapBuilder(DynamicOps<Object> ops) {
            super(ops);
        }

        @Override
        protected Map<Object, Object> initBuilder() {
            return new LinkedHashMap<>();
        }

        @Override
        protected Map<Object, Object> append(Object key, Object value, Map<Object, Object> builder) {
            builder.put(key, value);
            return builder;
        }

        @Override
        protected DataResult<Object> build(Map<Object, Object> builder, Object prefix) {
            return ops().mergeToMap(prefix, builder);
        }
    }
}
