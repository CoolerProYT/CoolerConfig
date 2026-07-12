package com.coolerpromc.coolerconfig.config;

import com.coolerpromc.coolerconfig.Constants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JavaOps;

import java.util.function.Predicate;

/**
 * A single typed key-value pair inside a {@link ConfigSpec}.
 *
 * <p>Instances are created exclusively by {@link ConfigBuilder} and are not part of the public
 * API — callers read values through the typed getters on {@link ConfigSpec} (e.g.
 * {@link ConfigSpec#getInt(String)}).
 *
 * <h2>Type coercion</h2>
 * Raw values from Night-Config do not always match the declared Java type, so
 * {@link #set(Object)} and {@link #validate(Object)} both pass the raw value through
 * {@link #coerce(Object)} before the validator predicate is tested:
 * <ul>
 *   <li><b>Numeric:</b> TOML integers arrive as {@code Long} and floats as {@code Double};
 *       coercion narrows them to the exact numeric type of the default value so that a
 *       {@code defineInt} validator written as {@code v instanceof Integer} still matches.</li>
 *   <li><b>Enum:</b> enum entries are stored in the file as the constant's {@link Enum#name()
 *       name} string. Coercion converts that string back to the matching constant via
 *       {@link Enum#valueOf}; an unrecognised name is passed through unchanged so the
 *       validator can reject it and trigger a reset to the default.</li>
 *   <li><b>Map:</b> Night-Config represents TOML tables and HOCON objects as internal
 *       {@code UnmodifiableConfig} objects. Map coercion is handled by {@link ConfigManager}
 *       before the value reaches this class.</li>
 * </ul>
 *
 * <h2>Codec-backed entries</h2>
 * An entry declared via {@link ConfigBuilder#defineCodec} carries a {@link Codec} instead of a
 * validator predicate. For such entries the codec <em>is</em> the validator: values read from
 * disk are decoded with {@link JavaOps} (which speaks plain {@code Map}/{@code List}/boxed
 * primitives — exactly the shape {@link ConfigManager} hands over), and a decode failure resets
 * the entry to its default. Because {@code JavaOps.getNumberValue} returns a raw {@link Number},
 * codecs handle the TOML {@code Long}/{@code Double} widening automatically — a
 * {@code Codec.INT} field reads back as an {@code int} regardless of the file format.
 *
 * @param <T> the Java type of the config value (e.g. {@code Integer}, {@code Boolean},
 *            {@code String}, {@code List<String>}, {@code MyEnum}, or any codec-backed type)
 */
public final class ConfigEntry<T> {

    private final String path;
    private final T defaultValue;
    private final String comment;
    private final Predicate<Object> validator;
    private final Codec<T> codec;
    private T value;

    /**
     * Creates a new validator-backed entry. Package-private — use {@link ConfigBuilder} instead.
     *
     * @param path         dot-separated key path, e.g. {@code "general.maxItems"}
     * @param defaultValue value used when the file is absent or contains an invalid entry
     * @param comment      human-readable description written as a comment in TOML files;
     *                     may be empty but must not be {@code null}
     * @param validator    predicate run against the coerced file value; {@code null} means
     *                     any value of a compatible type is accepted
     */
    ConfigEntry(String path, T defaultValue, String comment, Predicate<Object> validator) {
        this.path = path;
        this.defaultValue = defaultValue;
        this.comment = comment;
        this.validator = validator;
        this.codec = null;
        this.value = defaultValue;
    }

    /**
     * Creates a new codec-backed entry. Package-private — use
     * {@link ConfigBuilder#defineCodec} instead.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the file is absent or the stored value fails to decode
     * @param comment      human-readable description
     * @param codec        the codec used to serialise and deserialise this entry
     */
    ConfigEntry(String path, T defaultValue, String comment, Codec<T> codec) {
        this.path = path;
        this.defaultValue = defaultValue;
        this.comment = comment;
        this.validator = null;
        this.codec = codec;
        this.value = defaultValue;
    }

    /**
     * Returns {@code true} if this entry serialises through a {@link Codec} rather than a
     * validator predicate.
     */
    boolean hasCodec() {
        return codec != null;
    }

    /**
     * Returns the dot-separated key path of this entry, e.g. {@code "general.maxItems"}.
     *
     * @return the key path; never {@code null}
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns the value that this entry falls back to when the config file is absent or
     * contains a value that fails {@link #validate(Object)}.
     *
     * @return the default value; never {@code null}
     */
    public T getDefaultValue() {
        return defaultValue;
    }

    /**
     * Returns the human-readable description of this entry. Used as a comment above the
     * key when writing TOML files. Empty string if no comment was provided.
     *
     * @return the comment string; never {@code null}
     */
    public String getComment() {
        return comment;
    }

    /**
     * Returns the current in-memory value of this entry.
     *
     * <p>Before the owning {@link ConfigSpec} is loaded for the first time this returns
     * the {@link #getDefaultValue() default value}. After loading it reflects whatever was
     * read from disk (or the default if the file was absent / contained an invalid value).
     *
     * @return the current value; never {@code null}
     */
    public T get() {
        return value;
    }

    /**
     * Updates the in-memory value directly, bypassing file I/O.
     *
     * <p>Use this for in-game config screens. The value is expected to already be of this
     * entry's Java type {@code T}. For validator-backed entries it is coerced and tested
     * against the validator; for codec-backed entries it is checked by attempting to encode
     * it. Values that fail either check silently fall back to the default. Call
     * {@link ConfigSpec#save()} afterwards to persist the change to disk.
     *
     * <p>This is <em>not</em> the method used to load values from disk — see
     * {@link #setRaw(Object)}.
     *
     * @param rawValue the new value to set
     */
    @SuppressWarnings("unchecked")
    public void set(Object rawValue) {
        if (codec != null) {
            // A typed value is expected here; round-trip it through the codec to validate.
            this.value = codec.encodeStart(JavaOps.INSTANCE, (T) rawValue).result().isPresent()
                    ? (T) rawValue
                    : defaultValue;
            return;
        }
        Object coerced = coerce(rawValue);
        if (validator == null || validator.test(coerced)) {
            this.value = (T) coerced;
        } else {
            this.value = defaultValue;
        }
    }

    /**
     * Updates the in-memory value from a raw value read out of the config file.
     *
     * <p>{@link ConfigManager} hands over a plain-Java view of the file value ({@code Map},
     * {@code List}, or a boxed primitive — never a Night-Config type). Codec-backed entries
     * decode that tree with {@link JavaOps}; a decode failure logs a warning and resets the
     * entry to its default. Validator-backed entries fall through to {@link #set(Object)}.
     *
     * @param rawValue the plain-Java value read from the file
     */
    void setRaw(Object rawValue) {
        if (codec != null) {
            this.value = codec.parse(JavaOps.INSTANCE, rawValue)
                    .resultOrPartial(err -> Constants.LOG.warn(
                            "[CoolerConfig] '{}': failed to decode value: {}", path, err))
                    .orElse(defaultValue);
            return;
        }
        set(rawValue);
    }

    /**
     * Returns {@code true} if the given raw file value is acceptable for this entry.
     *
     * <p>For codec-backed entries this means "decodes cleanly"; for validator-backed entries
     * it means "passes the validator after coercion". {@link ConfigManager} uses this to decide
     * whether a value read from disk should be kept or reset to the default.
     *
     * @param rawValue the plain-Java value as read from the file
     * @return {@code true} if the value is valid for this entry
     */
    boolean validate(Object rawValue) {
        if (codec != null) {
            return codec.parse(JavaOps.INSTANCE, rawValue).result().isPresent();
        }
        Object coerced = coerce(rawValue);
        return validator == null || validator.test(coerced);
    }

    /**
     * Returns this entry's current value in the plain-Java shape that should be written to the
     * config file.
     *
     * <p>Codec-backed entries are encoded with {@link JavaOps}, producing a tree of {@code Map},
     * {@code List}, and boxed primitives that {@link ConfigManager} converts to Night-Config
     * types. All other entries return their value unchanged.
     *
     * @return the encodable value, or {@code null} if a codec-backed value failed to encode
     */
    Object encode() {
        if (codec == null) return value;
        return codec.encodeStart(JavaOps.INSTANCE, value)
                .resultOrPartial(err -> Constants.LOG.warn(
                        "[CoolerConfig] '{}': failed to encode value: {}", path, err))
                .orElse(null);
    }

    /**
     * Normalises the raw file value to the Java type expected by this entry.
     *
     * <ul>
     *   <li><b>Numeric:</b> any {@link Number} is narrowed to the exact type of
     *       {@link #defaultValue} ({@code int}, {@code long}, {@code double}, or
     *       {@code float}) so validators and callers always see a consistent type.</li>
     *   <li><b>Enum:</b> if the default is an enum constant and the raw value is a
     *       {@code String}, {@link Enum#valueOf} is attempted. On success the matching
     *       constant is returned; on failure the original string is returned unchanged
     *       so the validator can reject it and trigger a reset to the default.</li>
     * </ul>
     *
     * @param rawValue the value as returned by Night-Config (or from {@link ConfigManager})
     * @return the coerced value, or {@code rawValue} unchanged if no coercion applies
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object coerce(Object rawValue) {
        if (rawValue instanceof Number n) {
            if (defaultValue instanceof Integer) return n.intValue();
            if (defaultValue instanceof Long)    return n.longValue();
            if (defaultValue instanceof Double)  return n.doubleValue();
            if (defaultValue instanceof Float)   return n.floatValue();
        }
        if (defaultValue instanceof Enum<?> e && rawValue instanceof String s) {
            try {
                return Enum.valueOf((Class<Enum>) e.getDeclaringClass(), s);
            } catch (IllegalArgumentException ignored) {
                return rawValue; // unknown name; validator will reject and reset to default
            }
        }
        return rawValue;
    }
}
