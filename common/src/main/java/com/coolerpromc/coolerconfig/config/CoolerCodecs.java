package com.coolerpromc.coolerconfig.config;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import java.util.List;
import java.util.Map;

/**
 * Ready-made {@link Codec}s for shapes that config files commonly need but that Mojang's
 * built-in codecs do not cover.
 *
 * <p>Pass these to {@link ConfigBuilder#defineCodec}.
 *
 * @see ConfigBuilder#defineCodec
 */
public final class CoolerCodecs {

    private CoolerCodecs() {}

    /**
     * A codec for a single value of <em>any</em> primitive config type — {@code Boolean},
     * {@code Number}, or {@code String} — preserving whichever one it actually is.
     *
     * <p>Use it when a value's type is not known ahead of time. Decoding tries boolean, then
     * number, then string, and fails on anything else (a nested table or array), which means an
     * invalid entry is rejected rather than silently stored.
     *
     * <h4>Numbers decode as {@link Number}</h4>
     * Because the declared type is {@code Object}, numeric values arrive as whatever the file
     * format produced — TOML and HOCON yield {@code Long} for integers, JSON and JSON5 narrow to
     * {@code Integer}. Always read them through {@link Number}, never with a direct cast:
     * <pre>{@code
     * int count = ((Number) map.get("count")).intValue();   // correct
     * int count = (int) map.get("count");                   // ClassCastException on TOML
     * }</pre>
     * If you know a value's type ahead of time, prefer a record plus
     * {@code RecordCodecBuilder} — that gives you real {@code int}/{@code double} fields with no
     * casting at all.
     *
     * <p><b>Note:</b> this codec assumes an ops implementation whose accessors are strict about
     * types, which is true of the {@link com.mojang.serialization.JavaOps} that CoolerConfig uses
     * internally. Under {@code JsonOps}, whose {@code getBooleanValue} coerces numbers, a number
     * would decode as a boolean.
     */
    public static final Codec<Object> PRIMITIVE = new Codec<>() {
        @Override
        public <T> DataResult<Pair<Object, T>> decode(DynamicOps<T> ops, T input) {
            DataResult<Boolean> asBoolean = ops.getBooleanValue(input);
            if (asBoolean.result().isPresent()) {
                return DataResult.success(Pair.of(asBoolean.result().get(), ops.empty()));
            }
            DataResult<Number> asNumber = ops.getNumberValue(input);
            if (asNumber.result().isPresent()) {
                return DataResult.success(Pair.of(asNumber.result().get(), ops.empty()));
            }
            DataResult<String> asString = ops.getStringValue(input);
            if (asString.result().isPresent()) {
                return DataResult.success(Pair.of(asString.result().get(), ops.empty()));
            }
            return DataResult.error(() -> "Not a boolean, number, or string: " + input);
        }

        @Override
        public <T> DataResult<T> encode(Object input, DynamicOps<T> ops, T prefix) {
            if (input instanceof Boolean b) return ops.mergeToPrimitive(prefix, ops.createBoolean(b));
            if (input instanceof Number n)  return ops.mergeToPrimitive(prefix, ops.createNumeric(n));
            if (input instanceof String s)  return ops.mergeToPrimitive(prefix, ops.createString(s));
            return DataResult.error(() -> "Not a boolean, number, or string: " + input);
        }

        @Override
        public String toString() {
            return "Primitive";
        }
    };

    /**
     * A codec for a {@code Map<String, Object>} whose values may each independently be a
     * boolean, number, or string.
     *
     * <p>This is the codec-backed replacement for {@link ConfigBuilder#defineMap} when the map's
     * values are heterogeneous:
     * <pre>{@code
     * ConfigValue<Map<String, Object>> STUFF = builder.defineCodec("general.stuff",
     *         CoolerCodecs.PRIMITIVE_MAP,
     *         Map.of("enabled", true, "scale", 1.5, "count", 10, "label", "hello"),
     *         "Mixed settings");
     * }</pre>
     *
     * <p>Numeric values come back as {@link Number} — see {@link #PRIMITIVE} for why.
     */
    public static final Codec<Map<String, Object>> PRIMITIVE_MAP =
            Codec.unboundedMap(Codec.STRING, PRIMITIVE);

    /**
     * A codec for a {@code List<Object>} whose elements may each independently be a boolean,
     * number, or string.
     *
     * <p>Elements come back as {@link Number} where numeric — see {@link #PRIMITIVE}.
     */
    public static final Codec<List<Object>> PRIMITIVE_LIST = PRIMITIVE.listOf();
}
