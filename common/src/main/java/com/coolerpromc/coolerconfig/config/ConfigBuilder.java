package com.coolerpromc.coolerconfig.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Fluent builder for creating a {@link ConfigSpec}.
 *
 * <p>Obtain an instance via {@link ConfigSpec#builder(String, ConfigFormat)}. Call the
 * {@code define*} methods to declare entries, then call {@link #build()} to create, register,
 * and load the spec.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public static final ConfigSpec CONFIG = ConfigSpec.builder("mymod", ConfigFormat.TOML)
 *         .side(ConfigSide.COMMON)
 *         .comment("MyMod configuration")
 *         .define("general.enable", true, "Enable all MyMod features")
 *         .defineInt("general.count", 10, 1, 100, "Spawn count per wave")
 *         .defineDouble("general.scale", 1.5, 0.1, 10.0, "Scale multiplier")
 *         .defineString("general.mode", "normal", "Operation mode")
 *         .build();
 * }</pre>
 *
 * <h2>Key paths</h2>
 * Paths use dots as section separators, e.g. {@code "section.subsection.key"}. Night-Config
 * maps these to nested TOML tables or HOCON objects automatically. Duplicate paths throw
 * {@link IllegalArgumentException} at build time.
 *
 * <h2>Thread safety</h2>
 * {@code ConfigBuilder} is not thread-safe. Build specs during single-threaded mod
 * initialisation (inside {@code onInitialize} / your {@code @Mod} constructor).
 */
public final class ConfigBuilder {

    private final String modId;
    private final ConfigFormat format;
    private ConfigSide side = ConfigSide.COMMON;
    private final Map<String, ConfigEntry<?>> entries = new LinkedHashMap<>();
    private String headerComment = "";

    /**
     * Package-private constructor — obtain instances via {@link ConfigSpec#builder(String, ConfigFormat)}.
     *
     * @param modId   file name without extension, e.g. {@code "mymod"} → {@code mymod.toml}
     * @param format the serialisation format to use
     */
    ConfigBuilder(String modId, ConfigFormat format) {
        this.modId = modId;
        this.format = format;
    }

    /**
     * Sets which physical side this config should be loaded on.
     *
     * <p>Defaults to {@link ConfigSide#COMMON} when not called.
     *
     * @param side the desired side; must not be {@code null}
     * @return {@code this} builder, for chaining
     * @see ConfigSide
     */
    public ConfigBuilder side(ConfigSide side) {
        this.side = side;
        return this;
    }

    /**
     * Sets a header comment written at the top of the config file.
     *
     * <p>Only effective for {@link ConfigFormat#TOML} files. The comment is rendered as a
     * block of {@code #}-prefixed lines before the first key.
     *
     * @param comment the header text; must not be {@code null}
     * @return {@code this} builder, for chaining
     */
    public ConfigBuilder comment(String comment) {
        this.headerComment = comment;
        return this;
    }

    /**
     * Declares a {@code boolean} entry.
     *
     * <p>The validator accepts only {@link Boolean} values; any other type found in the file
     * resets the entry to {@code defaultValue}.
     *
     * @param path         dot-separated key path, e.g. {@code "general.enable"}
     * @param defaultValue value used when the key is absent or invalid
     * @param comment      human-readable description; written as a comment in TOML files
     * @return {@code this} builder, for chaining
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public ConfigBuilder define(String path, boolean defaultValue, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof Boolean);
    }

    /**
     * Declares an {@code int} entry with an inclusive range constraint.
     *
     * <p>Values outside {@code [min, max]} are treated as invalid and reset to
     * {@code defaultValue}. Night-Config TOML integers are read as {@code Long}; the entry's
     * coercion logic converts them to {@code int} before the range check.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or out of range; must satisfy
     *                     {@code min <= defaultValue <= max}
     * @param min          minimum accepted value (inclusive)
     * @param max          maximum accepted value (inclusive)
     * @param comment      human-readable description
     * @return {@code this} builder, for chaining
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public ConfigBuilder defineInt(String path, int defaultValue, int min, int max, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof Integer i && i >= min && i <= max);
    }

    /**
     * Declares a {@code double} entry with an inclusive range constraint.
     *
     * <p>Values outside {@code [min, max]} are treated as invalid and reset to
     * {@code defaultValue}.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or out of range; must satisfy
     *                     {@code min <= defaultValue <= max}
     * @param min          minimum accepted value (inclusive)
     * @param max          maximum accepted value (inclusive)
     * @param comment      human-readable description
     * @return {@code this} builder, for chaining
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public ConfigBuilder defineDouble(String path, double defaultValue, double min, double max, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof Double d && d >= min && d <= max);
    }

    /**
     * Declares a {@code String} entry that accepts any non-null string value.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or the stored value is not a string
     * @param comment      human-readable description
     * @return {@code this} builder, for chaining
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public ConfigBuilder defineString(String path, String defaultValue, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof String);
    }

    /**
     * Declares a {@code String} entry with a custom validator.
     *
     * <p>Use this overload to restrict values to a known set, apply regex matching, or
     * perform any other string-level validation:
     * <pre>{@code
     * .defineString("mode", "normal", "Game mode",
     *         v -> v instanceof String s && Set.of("normal", "hard", "extreme").contains(s))
     * }</pre>
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or fails the validator
     * @param comment      human-readable description
     * @param validator    additional predicate; receives the already-coerced value
     * @return {@code this} builder, for chaining
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public ConfigBuilder defineString(String path, String defaultValue, String comment, Predicate<Object> validator) {
        return put(path, defaultValue, comment, validator);
    }

    /**
     * Declares a {@code List} entry.
     *
     * <p>The validator accepts any {@link List}; element types are not checked at this level.
     * Night-Config reads TOML arrays as {@code ArrayList<Object>}, so element access should
     * be done with explicit casts or via streams.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or the stored value is not a list
     * @param comment      human-readable description
     * @param <T>          the declared element type (not enforced at runtime)
     * @return {@code this} builder, for chaining
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public <T> ConfigBuilder defineList(String path, List<T> defaultValue, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof List<?>);
    }

    /**
     * Declares an entry of any type with a fully custom validator.
     *
     * <p>Use this when none of the typed {@code define*} helpers fit your use case. The
     * {@code validator} predicate receives the value after {@link ConfigEntry}'s numeric
     * coercion has been applied.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or fails the validator
     * @param comment      human-readable description
     * @param validator    predicate that returns {@code true} for acceptable values;
     *                     {@code null} accepts any value
     * @param <T>          the Java type of the config value
     * @return {@code this} builder, for chaining
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public <T> ConfigBuilder define(String path, T defaultValue, String comment, Predicate<Object> validator) {
        return put(path, defaultValue, comment, validator);
    }

    /**
     * Internal helper that inserts a new {@link ConfigEntry} into the ordered map.
     *
     * @throws IllegalArgumentException if {@code path} is already present
     */
    private <T> ConfigBuilder put(String path, T defaultValue, String comment, Predicate<Object> validator) {
        if (entries.containsKey(path)) {
            throw new IllegalArgumentException("Duplicate config path: " + path);
        }
        entries.put(path, new ConfigEntry<>(path, defaultValue, comment, validator));
        return this;
    }

    /**
     * Builds the {@link ConfigSpec}, registers it with {@link ConfigRegistry}, and
     * performs the initial load from disk.
     *
     * <p>If the config file does not yet exist it is created with all default values (and
     * comments for TOML). If it already exists, each entry is validated; invalid values
     * are reset to their defaults and the file is rewritten.
     *
     * <p><b>Call this during mod initialisation</b> ({@code onInitialize} /
     * {@code @Mod} constructor), not in a {@code static} initialiser block — the config
     * directory provided by the platform helper is not available until the loader has
     * finished its own setup.
     *
     * @return the immutable, loaded {@link ConfigSpec}
     */
    public ConfigSpec build() {
        ConfigSpec spec = new ConfigSpec(modId + "-" + side.toString().toLowerCase(Locale.ROOT), format, side, Map.copyOf(entries), headerComment);
        spec.load();
        return spec;
    }
}
