package com.coolerpromc.coolerconfig.config;

import com.coolerpromc.coolerconfig.Constants;
import com.mojang.serialization.Codec;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A single typed key-value pair inside a {@link ConfigSpec}.
 *
 * <p>Instances are created exclusively by {@link ConfigBuilder}. Most code should read and write
 * values through the {@link ConfigValue} handle returned by the {@code define*} methods rather
 * than touching entries directly. Entries are exposed publicly (via {@link ConfigSpec#entries()})
 * for generic tooling — a config screen that enumerates a spec without knowing its types ahead of
 * time can use {@link #getType()}, {@link #getComment()}, and {@link #getDefaultValue()} to build
 * widgets.
 *
 * <h2>Type coercion</h2>
 * Raw values from Night-Config do not always match the declared Java type, so
 * {@link #set(Object)} and {@link #validate(Object)} both pass the raw value through
 * {@code coerce} before the validator predicate is tested:
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
 * <h2>Thread safety</h2>
 * The current value is held in a {@code volatile} field. Reads from any thread always observe the
 * most recently written value, which matters because
 * {@linkplain ConfigBuilder#watchForChanges() file-watched} specs are reloaded on a background
 * daemon thread while game threads are reading.
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
    private volatile T value;

    /**
     * Creates a new validator-backed entry. Package-private — use {@link ConfigBuilder} instead.
     *
     * @param path         dot-separated key path, e.g. {@code "general.maxItems"}
     * @param defaultValue value used when the file is absent or contains an invalid entry
     * @param comment      human-readable description written as a comment in the config file;
     *                     may be empty but must not be {@code null}
     * @param validator    predicate run against the coerced file value; {@code null} means
     *                     any value of a compatible type is accepted
     */
    ConfigEntry(String path, T defaultValue, String comment, Predicate<Object> validator) {
        this.path = Objects.requireNonNull(path, "path");
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue for '" + path + "'");
        this.comment = Objects.requireNonNull(comment, "comment for '" + path + "'");
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
        this.path = Objects.requireNonNull(path, "path");
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue for '" + path + "'");
        this.comment = Objects.requireNonNull(comment, "comment for '" + path + "'");
        this.validator = null;
        this.codec = Objects.requireNonNull(codec, "codec for '" + path + "'");
        this.value = defaultValue;
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
     * contains a value that fails validation.
     *
     * @return the default value; never {@code null}
     */
    public T getDefaultValue() {
        return defaultValue;
    }

    /**
     * Returns the human-readable description of this entry, written as a comment above the key
     * in every format except {@link ConfigFormat#JSON}. Empty string if no comment was provided.
     *
     * @return the comment string; never {@code null}
     */
    public String getComment() {
        return comment;
    }

    /**
     * Returns the runtime class of this entry's value, derived from its default.
     *
     * <p>Intended for generic tooling such as a config screen that picks a widget per entry:
     * <pre>{@code
     * for (ConfigEntry<?> entry : spec.entries()) {
     *     if (entry.getType() == Boolean.class) addToggle(entry);
     *     else if (Enum.class.isAssignableFrom(entry.getType())) addDropdown(entry);
     *     // ...
     * }
     * }</pre>
     *
     * @return the value's class; never {@code null}
     */
    @SuppressWarnings("unchecked")
    public Class<? extends T> getType() {
        return (Class<? extends T>) defaultValue.getClass();
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
     * Updates the in-memory value, reporting whether the new value was acceptable.
     *
     * <p>The value is validated exactly as if it had been read from the config file: for
     * validator-backed entries it is coerced and tested against the validator; for codec-backed
     * entries it is tested by attempting to encode it. <b>A rejected value leaves the entry
     * unchanged</b> and returns {@code false} — unlike a rejected value read from disk, which
     * resets the entry to its default.
     *
     * <p>Call {@link ConfigSpec#save()} afterwards to persist an accepted change to disk.
     *
     * @param newValue the new value to set
     * @return {@code true} if the value was accepted and stored, {@code false} if it failed
     *         validation and the entry was left untouched
     */
    @SuppressWarnings("unchecked")
    public boolean set(Object newValue) {
        if (newValue == null) return false;
        if (codec != null) {
            try {
                // The cast is unchecked, so a wrong-typed value reaches the codec and blows up
                // inside a field getter rather than returning a failed DataResult.
                if (codec.encodeStart(JavaOps.INSTANCE, (T) newValue).result().isEmpty()) return false;
            } catch (ClassCastException e) {
                return false;
            }
            this.value = (T) newValue;
            return true;
        }
        Object coerced = coerce(newValue);
        if (validator != null && !validator.test(coerced)) return false;
        this.value = (T) coerced;
        return true;
    }

    /**
     * Restores this entry's {@linkplain #getDefaultValue() default value}.
     *
     * <p>Call {@link ConfigSpec#save()} afterwards to persist the reset to disk.
     */
    public void reset() {
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
     * Updates the in-memory value from a raw value read out of the config file.
     *
     * <p>{@link ConfigManager} hands over a plain-Java view of the file value ({@code Map},
     * {@code List}, or a boxed primitive — never a Night-Config type). Codec-backed entries
     * decode that tree with {@link JavaOps}; a decode failure logs a warning and resets the
     * entry to its default. Validator-backed entries coerce and store without re-validating —
     * {@link ConfigManager} has already called {@link #validate(Object)} on the same value.
     *
     * @param rawValue the plain-Java value read from the file
     */
    @SuppressWarnings("unchecked")
    void setRaw(Object rawValue) {
        if (codec != null) {
            this.value = codec.parse(JavaOps.INSTANCE, rawValue)
                    .resultOrPartial(err -> Constants.LOG.warn("'{}': failed to decode value: {}", path, err))
                    .orElse(defaultValue);
            return;
        }
        this.value = (T) coerce(rawValue);
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
        if (rawValue == null) return false;
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
     * @return the encodable value, or an empty optional if a codec-backed value failed to encode
     */
    Optional<Object> encode() {
        if (codec == null) return Optional.of(value);
        return codec.encodeStart(JavaOps.INSTANCE, value)
                .resultOrPartial(err -> Constants.LOG.warn("'{}': failed to encode value: {}", path, err))
                .map(o -> (Object) o);
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
