package com.coolerpromc.coolerconfig.config;

import com.mojang.serialization.Codec;

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
 * private static final ConfigSpec CONFIG;
 * public  static final ConfigValue<Boolean>    ENABLE     ;
 * public  static final ConfigValue<Integer>    COUNT      ;
 * public  static final ConfigValue<Double>     SCALE      ;
 * public  static final ConfigValue<String>     MODE       ;
 * public  static final ConfigValue<Difficulty> DIFFICULTY ;
 *
 * static {
 *     ConfigBuilder builder = ConfigSpec.builder("mymod", ConfigFormat.TOML)
 *             .side(ConfigSide.COMMON)
 *             .comment("MyMod configuration");
 *     ENABLE     = builder.defineBoolean("general.enable",     true,              "Enable all MyMod features");
 *     COUNT      = builder.defineInt    ("general.count",      10, 1, 100,        "Spawn count per wave");
 *     SCALE      = builder.defineDouble ("general.scale",      1.5, 0.1, 10.0,    "Scale multiplier");
 *     MODE       = builder.defineString ("general.mode",       "normal",          "Operation mode");
 *     DIFFICULTY = builder.defineEnum   ("general.difficulty", Difficulty.NORMAL, "Difficulty level");
 *     CONFIG     = builder.build();
 * }
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
    private boolean watchForChanges = false;
    private String customFileName = null;

    /**
     * Package-private constructor — obtain instances via {@link ConfigSpec#builder(String, ConfigFormat)}.
     *
     * @param modId  base mod identifier; the actual file name is
     *               {@code <modId>-<side>.toml/.conf}, e.g. {@code "mymod"} with
     *               {@link ConfigSide#COMMON} → {@code mymod-common.toml}
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
     * <p>Effective for both {@link ConfigFormat#TOML} and {@link ConfigFormat#HOCON} files.
     * The comment is rendered as {@code #}-prefixed lines before the first key.
     *
     * @param comment the header text; must not be {@code null}
     * @return {@code this} builder, for chaining
     */
    public ConfigBuilder comment(String comment) {
        this.headerComment = comment;
        return this;
    }

    /**
     * Enables automatic hot-reload when the config file is modified on disk.
     *
     * <p>After {@link #build()} is called, a daemon {@link java.nio.file.WatchService} thread
     * is started for the config directory. Whenever the file is saved, the thread waits
     * 150 ms (to let editors finish writing), then calls {@link ConfigSpec#reload()}, which
     * re-reads values from disk and fires all registered reload listeners.
     *
     * <p>The watcher thread is a daemon — it stops automatically when the JVM exits.
     *
     * @return {@code this} builder, for chaining
     */
    public ConfigBuilder watchForChanges() {
        this.watchForChanges = true;
        return this;
    }

    /**
     * Overrides the entire base file name used on disk.
     *
     * <p>By default the file is written as {@code <modId>-<side>.<ext>}, e.g.
     * {@code mymod-common.toml}. Calling this method replaces that base name with
     * {@code fileName} verbatim — no {@code modId} prefix is added, no {@code -side}
     * suffix is appended. The file extension is still derived from the {@link ConfigFormat}.
     *
     * <pre>{@code
     * ConfigSpec.builder("mymod", ConfigFormat.TOML)
     *         .fileName("mymod-perf")
     *         .build();  // → <config>/mymod-perf.toml
     * }</pre>
     *
     * <p>Mutually exclusive with {@link #suffix(String)} — the last call wins.
     *
     * @param fileName the literal base file name (without extension); must not be {@code null} or empty
     * @return {@code this} builder, for chaining
     */
    public ConfigBuilder fileName(String fileName) {
        this.customFileName = fileName;
        return this;
    }

    /**
     * Overrides only the suffix portion of the default file name.
     *
     * <p>By default the file is written as {@code <modId>-<side>.<ext>}, e.g.
     * {@code mymod-common.toml}. Calling this method replaces the {@code -<side>} portion
     * with {@code -<suffix>} while keeping the {@code <modId>-} prefix and the
     * {@link ConfigFormat}-derived extension.
     *
     * <pre>{@code
     * ConfigSpec.builder("mymod", ConfigFormat.TOML)
     *         .suffix("graphics")
     *         .build();  // → <config>/mymod-graphics.toml
     * }</pre>
     *
     * <p>The {@link ConfigSide} set via {@link #side(ConfigSide)} still controls runtime
     * load behaviour (e.g. CLIENT specs being skipped on a dedicated server) — only the
     * on-disk file name is affected.
     *
     * <p>Mutually exclusive with {@link #fileName(String)} — the last call wins.
     *
     * @param suffix the custom suffix (without leading dash); must not be {@code null} or empty
     * @return {@code this} builder, for chaining
     */
    public ConfigBuilder suffix(String suffix) {
        this.customFileName = modId + "-" + suffix;
        return this;
    }

    /**
     * Declares a {@code boolean} entry and returns a typed handle to it.
     *
     * <p>The validator accepts only {@link Boolean} values; any other type found in the file
     * resets the entry to {@code defaultValue}.
     *
     * @param path         dot-separated key path, e.g. {@code "general.enable"}
     * @param defaultValue value used when the key is absent or invalid
     * @param comment      human-readable description; written as a comment in TOML files
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public ConfigValue<Boolean> defineBoolean(String path, boolean defaultValue, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof Boolean);
    }

    /**
     * Declares an {@code int} entry with an inclusive range constraint and returns a typed handle.
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
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public ConfigValue<Integer> defineInt(String path, int defaultValue, int min, int max, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof Integer i && i >= min && i <= max);
    }

    /**
     * Declares a {@code double} entry with an inclusive range constraint and returns a typed handle.
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
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public ConfigValue<Double> defineDouble(String path, double defaultValue, double min, double max, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof Double d && d >= min && d <= max);
    }

    /**
     * Declares a {@code long} entry with an inclusive range constraint and returns a typed handle.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or out of range; must satisfy
     *                     {@code min <= defaultValue <= max}
     * @param min          minimum accepted value (inclusive)
     * @param max          maximum accepted value (inclusive)
     * @param comment      human-readable description
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public ConfigValue<Long> defineLong(String path, long defaultValue, long min, long max, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof Long l && l >= min && l <= max);
    }

    /**
     * Declares an enum entry and returns a typed handle.
     *
     * <p>The enum constant is stored in the config file as its {@link Enum#name() name} string.
     * On load, the string is converted back to the matching constant. Unrecognised names reset
     * the entry to {@code defaultValue}.
     *
     * <pre>{@code
     * ConfigValue<Difficulty> diff = builder.defineEnum("general.difficulty", Difficulty.NORMAL, "Difficulty level");
     * }</pre>
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or the stored string cannot be
     *                     matched to a constant
     * @param comment      human-readable description
     * @param <E>          the enum type
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public <E extends Enum<E>> ConfigValue<E> defineEnum(String path, E defaultValue, String comment) {
        return put(path, defaultValue, comment, v -> v != null && v.getClass() == defaultValue.getClass());
    }

    /**
     * Declares a {@code Map<String, V>} entry and returns a typed handle.
     *
     * <p>In TOML files the map is written as an inline table section (e.g.
     * {@code [general.weights]}). Values must be Night-Config-compatible primitives
     * ({@code String}, {@code Integer}, {@code Long}, {@code Double}, {@code Boolean}).
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or the stored value is not a map
     * @param comment      human-readable description
     * @param <V>          the value type of the map
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public <V> ConfigValue<Map<String, V>> defineMap(String path, Map<String, V> defaultValue, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof Map<?, ?>);
    }

    /**
     * Declares a {@code String} entry that accepts any non-null string value and returns a typed handle.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or the stored value is not a string
     * @param comment      human-readable description
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public ConfigValue<String> defineString(String path, String defaultValue, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof String);
    }

    /**
     * Declares a {@code String} entry with a custom validator and returns a typed handle.
     *
     * <p>Use this overload to restrict values to a known set, apply regex matching, or
     * perform any other string-level validation:
     * <pre>{@code
     * ConfigValue<String> mode = builder.defineString("mode", "normal", "Game mode",
     *         v -> v instanceof String s && Set.of("normal", "hard", "extreme").contains(s));
     * }</pre>
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or fails the validator
     * @param comment      human-readable description
     * @param validator    additional predicate; receives the already-coerced value
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public ConfigValue<String> defineString(String path, String defaultValue, String comment, Predicate<Object> validator) {
        return put(path, defaultValue, comment, validator);
    }

    /**
     * Declares a {@code List} entry and returns a typed handle.
     *
     * <p>The validator accepts any {@link List}; element types are not checked at this level.
     * Night-Config reads TOML arrays as {@code ArrayList<Object>}, so element access should
     * be done with explicit casts or via streams.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or the stored value is not a list
     * @param comment      human-readable description
     * @param <T>          the declared element type (not enforced at runtime)
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public <T> ConfigValue<List<T>> defineList(String path, List<T> defaultValue, String comment) {
        return put(path, defaultValue, comment, v -> v instanceof List<?>);
    }

    /**
     * Declares an entry of any type with a fully custom validator and returns a typed handle.
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
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared
     */
    public <T> ConfigValue<T> define(String path, T defaultValue, String comment, Predicate<Object> validator) {
        return put(path, defaultValue, comment, validator);
    }

    /**
     * Declares an entry serialised by a Mojang {@link Codec} and returns a typed handle.
     *
     * <p>This is the general-purpose escape hatch for structured data: records, nested objects,
     * lists of objects, and maps with heterogeneous values. The codec is used for both
     * directions — encoding on save and decoding on load — and <em>is</em> the entry's
     * validator: a value that fails to decode logs a warning and resets to {@code defaultValue}.
     *
     * <p>Encoding goes through {@link com.mojang.serialization.JavaOps}, which produces plain
     * {@code Map}/{@code List}/boxed-primitive trees. Those are exactly the types Night-Config
     * serialises, so codec entries work identically in <b>all four</b> {@link ConfigFormat}s —
     * TOML, HOCON, JSON, and JSON5.
     *
     * <h4>Structured objects</h4>
     * <pre>{@code
     * record Boss(String name, int health, double scale) {
     *     static final Codec<Boss> CODEC = RecordCodecBuilder.create(i -> i.group(
     *             Codec.STRING.fieldOf("name").forGetter(Boss::name),
     *             Codec.INT.fieldOf("health").forGetter(Boss::health),
     *             Codec.DOUBLE.fieldOf("scale").forGetter(Boss::scale)
     *     ).apply(i, Boss::new));
     * }
     *
     * ConfigValue<Boss> BOSS = builder.defineCodec("boss", Boss.CODEC,
     *         new Boss("Ender Dragon", 200, 1.0), "Boss settings");
     * }</pre>
     *
     * <h4>Map with mixed primitive values</h4>
     * Use {@link CoolerCodecs#PRIMITIVE} as the value codec — it accepts any boolean, number,
     * or string and preserves the original type:
     * <pre>{@code
     * ConfigValue<Map<String, Object>> STUFF = builder.defineCodec("stuff",
     *         CoolerCodecs.PRIMITIVE_MAP,
     *         Map.of("enabled", true, "scale", 1.5, "count", 10, "label", "hi"),
     *         "Mixed settings");
     * }</pre>
     *
     * <h4>Number widening</h4>
     * Unlike {@link #defineMap}, codec entries do <em>not</em> suffer the TOML {@code Long}
     * widening problem: {@code JavaOps.getNumberValue} yields a raw {@link Number}, so a
     * {@code Codec.INT} field reads back as an {@code int} no matter which format wrote it.
     *
     * <h4>HOCON caveat: map keys containing a dot</h4>
     * {@link ConfigFormat#HOCON} cannot round-trip a <em>map key</em> that contains a {@code .},
     * because HOCON reads an unquoted dot as a path separator and Night-Config's HOCON writer does
     * not quote such keys. The entry re-parses as a nested object and resets to its default on the
     * next load (a warning is logged when it is written). Keys containing {@code :} — including
     * resource locations like {@code minecraft:stone} — are fine. TOML, JSON, and JSON5 handle
     * every key shape correctly. This affects only map <em>keys</em>, never record field names or
     * the dotted {@code path} of the entry itself.
     *
     * @param path         dot-separated key path
     * @param codec        codec used to encode and decode this entry; must not be {@code null}
     * @param defaultValue value used when the key is absent or fails to decode
     * @param comment      human-readable description
     * @param <T>          the Java type of the config value
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared
     * @see CoolerCodecs
     */
    public <T> ConfigValue<T> defineCodec(String path, Codec<T> codec, T defaultValue, String comment) {
        if (entries.containsKey(path)) {
            throw new IllegalArgumentException("Duplicate config path: " + path);
        }
        ConfigEntry<T> entry = new ConfigEntry<>(path, defaultValue, comment, codec);
        entries.put(path, entry);
        return new ConfigValue<>(entry);
    }

    /**
     * Internal helper that inserts a new {@link ConfigEntry} into the ordered map and returns
     * a {@link ConfigValue} handle backed by that entry.
     *
     * @throws IllegalArgumentException if {@code path} is already present
     */
    private <T> ConfigValue<T> put(String path, T defaultValue, String comment, Predicate<Object> validator) {
        if (entries.containsKey(path)) {
            throw new IllegalArgumentException("Duplicate config path: " + path);
        }
        ConfigEntry<T> entry = new ConfigEntry<>(path, defaultValue, comment, validator);
        entries.put(path, entry);
        return new ConfigValue<>(entry);
    }

    /**
     * Builds the {@link ConfigSpec}, registers it with {@link ConfigRegistry}, and
     * performs the initial load from disk.
     *
     * <p>The config file name is derived as {@code <modId>-<side>.toml} (or {@code .conf}),
     * e.g. a builder with modId {@code "mymod"} and side {@link ConfigSide#CLIENT} writes to
     * {@code mymod-client.toml}.
     *
     * <p>If the file does not yet exist it is created with all default values and comments.
     * If it already exists, values are read and validated (invalid values are reset to
     * their defaults), and the file is always rewritten so that added fields, removed
     * fields, and updated comment text are reflected immediately.
     *
     * <p><b>Call this during mod initialisation</b> ({@code onInitialize} /
     * {@code @Mod} constructor), not in a {@code static} initialiser block — the config
     * directory provided by the platform helper is not available until the loader has
     * finished its own setup.
     *
     * @return the immutable, loaded {@link ConfigSpec}
     */
    public ConfigSpec build() {
        String specName = customFileName != null ? customFileName : modId + "-" + side.toString().toLowerCase(Locale.ROOT);
        ConfigSpec spec = new ConfigSpec(specName, format, side, Map.copyOf(entries), headerComment, watchForChanges);
        spec.load();
        return spec;
    }
}
