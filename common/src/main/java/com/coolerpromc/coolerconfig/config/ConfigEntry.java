package com.coolerpromc.coolerconfig.config;

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
 * @param <T> the Java type of the config value (e.g. {@code Integer}, {@code Boolean},
 *            {@code String}, {@code List<String>}, {@code MyEnum})
 */
public final class ConfigEntry<T> {

    private final String path;
    private final T defaultValue;
    private final String comment;
    private final Predicate<Object> validator;
    private T value;

    /**
     * Creates a new entry. Package-private — use {@link ConfigBuilder} instead.
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
     * Updates the in-memory value from a raw object read from the config file.
     *
     * <p>The raw value is first passed through {@link #coerce(Object)} to normalise numeric
     * types, then tested against the validator. If coercion produces a value that passes
     * validation the entry is updated; otherwise it silently falls back to the default.
     *
     * @param rawValue the object read from the Night-Config file, or a typed value to set
     *                 directly
     */
    @SuppressWarnings("unchecked")
    void set(Object rawValue) {
        Object coerced = coerce(rawValue);
        if (validator == null || validator.test(coerced)) {
            this.value = (T) coerced;
        } else {
            this.value = defaultValue;
        }
    }

    /**
     * Returns {@code true} if the given raw value, after coercion, passes this entry's
     * validator predicate.
     *
     * <p>{@link ConfigManager} uses this to decide whether a value read from disk is
     * acceptable or should be reset to the default.
     *
     * @param rawValue the object as read from the Night-Config file
     * @return {@code true} if the value is valid for this entry
     */
    boolean validate(Object rawValue) {
        Object coerced = coerce(rawValue);
        return validator == null || validator.test(coerced);
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
