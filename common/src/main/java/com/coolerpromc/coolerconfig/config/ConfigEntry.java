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
 * Night-Config deserialises TOML integers as {@code Long} and always uses {@code Double} for
 * floating-point numbers, regardless of the value's magnitude. To keep validator predicates
 * simple and consistent with the declared default type, {@link #set(Object)} and
 * {@link #validate(Object)} both pass the raw file value through {@link #coerce(Object)}
 * before testing the predicate. This means a {@code defineInt} entry whose validator checks
 * {@code v instanceof Integer} will still match a {@code Long} read from a TOML file.
 *
 * @param <T> the Java type of the config value (e.g. {@code Integer}, {@code Boolean},
 *            {@code String}, {@code List<String>})
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
     * Normalises numeric types to match the Java type of {@link #defaultValue}.
     *
     * <p>Night-Config deserialises TOML integers as {@code Long} and floating-point literals
     * as {@code Double}. Without coercion a validator written as
     * {@code v instanceof Integer} would never match a {@code Long} from a TOML file. This
     * method converts any {@link Number} to the exact numeric type expected by this entry so
     * that validators and callers always see a consistent type.
     *
     * @param rawValue the value as returned by Night-Config
     * @return the coerced value, or {@code rawValue} unchanged if it is not a {@link Number}
     *         or the default value is not a numeric type
     */
    private Object coerce(Object rawValue) {
        if (rawValue instanceof Number n) {
            if (defaultValue instanceof Integer) return n.intValue();
            if (defaultValue instanceof Long)    return n.longValue();
            if (defaultValue instanceof Double)  return n.doubleValue();
            if (defaultValue instanceof Float)   return n.floatValue();
        }
        return rawValue;
    }
}
