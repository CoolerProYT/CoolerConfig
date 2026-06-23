package com.coolerpromc.coolerconfig.config;

import com.mojang.serialization.Codec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * public static ConfigSpec CONFIG;
 * public static ConfigValue<Boolean>    ENABLE;
 * public static ConfigValue<Integer>    COUNT;
 * public static ConfigValue<Double>     SCALE;
 * public static ConfigValue<String>     MODE;
 * public static ConfigValue<Difficulty> DIFFICULTY;
 *
 * public static void init() {
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
 * maps these to nested TOML tables / HOCON objects / JSON objects automatically. Entries appear
 * in the file in the order they were declared. Duplicate paths throw
 * {@link IllegalArgumentException} at declaration time.
 *
 * <h2>Defaults are validated eagerly</h2>
 * Every {@code define*} method checks its own default value against its own validator (or codec)
 * immediately and throws {@link IllegalArgumentException} if it fails — a default outside its
 * declared range, or a codec that cannot encode its default, is a programming error and is
 * reported at mod-init time rather than silently producing a config that can never hold a valid
 * value.
 *
 * <h2>Thread safety</h2>
 * {@code ConfigBuilder} is not thread-safe. Build specs during single-threaded mod
 * initialisation (inside {@code onInitialize} / your {@code @Mod} constructor).
 */
public final class ConfigBuilder {

    private final String modId;
    private final ConfigFormat format;
    private final Map<String, ConfigEntry<?>> entries = new LinkedHashMap<>();
    private ConfigSide side = ConfigSide.COMMON;
    private String headerComment = "";
    private boolean watchForChanges = false;
    private String customFileName = null;
    private boolean built = false;

    /**
     * Package-private constructor — obtain instances via
     * {@link ConfigSpec#builder(String, ConfigFormat)}.
     *
     * @param modId  base mod identifier; the file name is {@code <modId>-<side>.<ext>}, e.g.
     *               {@code "mymod"} with {@link ConfigSide#COMMON} → {@code mymod-common.toml}
     * @param format the serialisation format to use
     */
    ConfigBuilder(String modId, ConfigFormat format) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.format = Objects.requireNonNull(format, "format");
        if (modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
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
        this.side = Objects.requireNonNull(side, "side");
        return this;
    }

    /**
     * Sets a header comment written at the top of the config file.
     *
     * <p>Written for every format except {@link ConfigFormat#JSON}, which cannot represent
     * comments. TOML and HOCON render it as {@code #} lines; JSON5 as {@code //} lines.
     *
     * @param comment the header text; must not be {@code null}
     * @return {@code this} builder, for chaining
     */
    public ConfigBuilder comment(String comment) {
        this.headerComment = Objects.requireNonNull(comment, "comment");
        return this;
    }

    /**
     * Enables automatic hot-reload when the config file is modified on disk.
     *
     * <p>After {@link #build()} is called, a daemon {@link java.nio.file.WatchService} thread
     * is started for the config directory. Whenever the file is saved, the thread waits
     * 150 ms (to let editors finish writing), then re-reads values from disk and fires all
     * registered reload listeners.
     *
     * <p>Because reloads then happen off the main thread, any {@code ConfigValue} read by game
     * code may change value between two reads. Entry values are {@code volatile}, so each
     * individual read is safe; if you need a consistent snapshot across several entries, take a
     * copy in a {@linkplain ConfigSpec#addReloadListener(Runnable) reload listener}.
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
     * <p>The name may contain {@code /} to write into a subdirectory of the config folder
     * (e.g. {@code "mymod/client"} → {@code <config>/mymod/client.toml}); intermediate
     * directories are created automatically.
     *
     * <p>Mutually exclusive with {@link #suffix(String)} — the last call wins.
     *
     * @param fileName the literal base file name (without extension); must not be blank
     * @return {@code this} builder, for chaining
     */
    public ConfigBuilder fileName(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
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
     * @param suffix the custom suffix (without leading dash); must not be blank
     * @return {@code this} builder, for chaining
     */
    public ConfigBuilder suffix(String suffix) {
        Objects.requireNonNull(suffix, "suffix");
        if (suffix.isBlank()) {
            throw new IllegalArgumentException("suffix must not be blank");
        }
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
     * @param comment      human-readable description; written as a comment above the key
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
     * {@code defaultValue}. Night-Config reads TOML/HOCON integers as {@code Long}; the entry's
     * coercion logic narrows them to {@code int} before the range check.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or out of range
     * @param min          minimum accepted value (inclusive)
     * @param max          maximum accepted value (inclusive)
     * @param comment      human-readable description
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared, if
     *                                  {@code min > max}, or if {@code defaultValue} is outside
     *                                  {@code [min, max]}
     */
    public ConfigValue<Integer> defineInt(String path, int defaultValue, int min, int max, String comment) {
        checkRange(path, min <= max, min, max);
        return put(path, defaultValue, comment, v -> v instanceof Integer i && i >= min && i <= max);
    }

    /**
     * Declares a {@code long} entry with an inclusive range constraint and returns a typed handle.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or out of range
     * @param min          minimum accepted value (inclusive)
     * @param max          maximum accepted value (inclusive)
     * @param comment      human-readable description
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared, if
     *                                  {@code min > max}, or if {@code defaultValue} is outside
     *                                  {@code [min, max]}
     */
    public ConfigValue<Long> defineLong(String path, long defaultValue, long min, long max, String comment) {
        checkRange(path, min <= max, min, max);
        return put(path, defaultValue, comment, v -> v instanceof Long l && l >= min && l <= max);
    }

    /**
     * Declares a {@code double} entry with an inclusive range constraint and returns a typed handle.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or out of range
     * @param min          minimum accepted value (inclusive)
     * @param max          maximum accepted value (inclusive)
     * @param comment      human-readable description
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared, if
     *                                  {@code min > max}, or if {@code defaultValue} is outside
     *                                  {@code [min, max]}
     */
    public ConfigValue<Double> defineDouble(String path, double defaultValue, double min, double max, String comment) {
        checkRange(path, min <= max, min, max);
        return put(path, defaultValue, comment, v -> v instanceof Double d && d >= min && d <= max);
    }

    /**
     * Declares a {@code float} entry with an inclusive range constraint and returns a typed handle.
     *
     * <p>Note that config formats store floating-point numbers as doubles; the value is narrowed
     * back to {@code float} on read, so a value that is not exactly representable as a
     * {@code float} loses precision on the round trip.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or out of range
     * @param min          minimum accepted value (inclusive)
     * @param max          maximum accepted value (inclusive)
     * @param comment      human-readable description
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared, if
     *                                  {@code min > max}, or if {@code defaultValue} is outside
     *                                  {@code [min, max]}
     */
    public ConfigValue<Float> defineFloat(String path, float defaultValue, float min, float max, String comment) {
        checkRange(path, min <= max, min, max);
        return put(path, defaultValue, comment, v -> v instanceof Float f && f >= min && f <= max);
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
        Objects.requireNonNull(defaultValue, "defaultValue for '" + path + "'");
        // getDeclaringClass(), not getClass(): a constant with a body is an anonymous subclass of
        // the enum, so an identity check against getClass() would reject its own siblings.
        Class<E> type = defaultValue.getDeclaringClass();
        return put(path, defaultValue, comment, type::isInstance);
    }

    /**
     * Declares a {@code String} entry that accepts any string value and returns a typed handle.
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
     * @throws IllegalArgumentException if {@code path} has already been declared or if
     *                                  {@code defaultValue} fails {@code validator}
     */
    public ConfigValue<String> defineString(String path, String defaultValue, String comment, Predicate<Object> validator) {
        return put(path, defaultValue, comment, validator);
    }

    /**
     * Declares a {@code List} entry and returns a typed handle.
     *
     * <p>The validator accepts any {@link List}; element types are <em>not</em> checked. Use
     * {@link #defineList(String, List, String, Predicate)} to validate elements, which is
     * strongly recommended for anything a player can edit — Night-Config reads a TOML array as
     * {@code ArrayList<Object>}, so an unchecked list can hand your code an element of a type it
     * does not expect and blow up far away from the config.
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
     * Declares a {@code List} entry whose elements are individually validated, and returns a
     * typed handle.
     *
     * <p>The whole entry is rejected (and reset to {@code defaultValue}) if any single element
     * fails {@code elementValidator}:
     * <pre>{@code
     * ConfigValue<List<String>> blacklist = builder.defineList("general.blacklist",
     *         List.of("minecraft:dirt"), "Blacklisted item IDs",
     *         v -> v instanceof String s && s.contains(":"));
     * }</pre>
     *
     * @param path             dot-separated key path
     * @param defaultValue     value used when the key is absent, is not a list, or contains an
     *                         element that fails {@code elementValidator}
     * @param comment          human-readable description
     * @param elementValidator predicate applied to every element of the list
     * @param <T>              the element type
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared or if any
     *                                  element of {@code defaultValue} fails
     *                                  {@code elementValidator}
     */
    public <T> ConfigValue<List<T>> defineList(String path, List<T> defaultValue, String comment,
                                               Predicate<Object> elementValidator) {
        Objects.requireNonNull(elementValidator, "elementValidator for '" + path + "'");
        return put(path, defaultValue, comment,
                v -> v instanceof List<?> list && list.stream().allMatch(elementValidator));
    }

    /**
     * Declares a {@code Map<String, V>} entry and returns a typed handle.
     *
     * <p>In TOML files the map is written as its own table section (e.g.
     * {@code [general.weights]}). Values must be types the format can serialise directly
     * ({@code String}, {@code Integer}, {@code Long}, {@code Double}, {@code Boolean}).
     *
     * <p>Beware that integers read back as {@code Long} from TOML/HOCON but as {@code Integer}
     * from JSON/JSON5, so a direct {@code (int)} cast on a map value can throw. Prefer
     * {@link #defineCodec} with a record for structured data, or read numbers through
     * {@link Number}.
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
     * Declares an entry of any type with a fully custom validator and returns a typed handle.
     *
     * <p>Use this when none of the typed {@code define*} helpers fit your use case. The
     * {@code validator} predicate receives the value after {@link ConfigEntry}'s numeric and
     * enum coercion has been applied.
     *
     * @param path         dot-separated key path
     * @param defaultValue value used when the key is absent or fails the validator
     * @param comment      human-readable description
     * @param validator    predicate that returns {@code true} for acceptable values;
     *                     {@code null} accepts any non-null value
     * @param <T>          the Java type of the config value
     * @return a {@link ConfigValue} handle for reading and writing this entry
     * @throws IllegalArgumentException if {@code path} has already been declared or if
     *                                  {@code defaultValue} fails {@code validator}
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
     * <p>Encoding goes through {@link JavaOps}, which produces plain
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
     * Use {@link CoolerCodecs#PRIMITIVE_MAP} — its values may each independently be a boolean,
     * number, or string:
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
     * @throws IllegalArgumentException if {@code path} has already been declared or if
     *                                  {@code codec} cannot encode {@code defaultValue}
     * @see CoolerCodecs
     */
    public <T> ConfigValue<T> defineCodec(String path, Codec<T> codec, T defaultValue, String comment) {
        checkPath(path);
        Objects.requireNonNull(codec, "codec for '" + path + "'");
        Objects.requireNonNull(defaultValue, "defaultValue for '" + path + "'");

        codec.encodeStart(JavaOps.INSTANCE, defaultValue).error().ifPresent(err -> {
            throw new IllegalArgumentException("Default value for '" + path + "' cannot be encoded by its codec: " + err.message());
        });

        ConfigEntry<T> entry = new ConfigEntry<>(path, defaultValue, comment, codec);
        entries.put(path, entry);
        return new ConfigValue<>(entry);
    }

    /**
     * Inserts a new {@link ConfigEntry} into the ordered map and returns a {@link ConfigValue}
     * handle backed by it, after checking that the default value satisfies its own validator.
     *
     * @throws IllegalArgumentException if {@code path} is invalid or already present, or if
     *                                  {@code defaultValue} fails {@code validator}
     */
    private <T> ConfigValue<T> put(String path, T defaultValue, String comment, Predicate<Object> validator) {
        checkPath(path);
        Objects.requireNonNull(defaultValue, "defaultValue for '" + path + "'");

        if (validator != null && !validator.test(defaultValue)) {
            throw new IllegalArgumentException(
                    "Default value '" + defaultValue + "' for '" + path + "' fails its own validator");
        }

        ConfigEntry<T> entry = new ConfigEntry<>(path, defaultValue, comment, validator);
        entries.put(path, entry);
        return new ConfigValue<>(entry);
    }

    /**
     * Rejects paths that are duplicated, blank, or contain an empty segment. Night-Config splits
     * a path on {@code .}, so {@code "a..b"}, {@code ".a"}, and {@code "a."} would silently
     * produce a nameless table.
     */
    private void checkPath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("Config path must not be blank");
        }
        if (path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
            throw new IllegalArgumentException("Config path '" + path + "' has an empty segment");
        }
        if (entries.containsKey(path)) {
            throw new IllegalArgumentException("Duplicate config path: " + path);
        }
    }

    private static void checkRange(String path, boolean ordered, Object min, Object max) {
        if (!ordered) {
            throw new IllegalArgumentException("Invalid range for '" + path + "': min " + min + " > max " + max);
        }
    }

    /**
     * Builds the {@link ConfigSpec}, registers it with {@link ConfigRegistry}, and
     * performs the initial load from disk.
     *
     * <p>The config file name is derived as {@code <modId>-<side>.<ext>} unless overridden with
     * {@link #fileName(String)} or {@link #suffix(String)} — e.g. a builder with modId
     * {@code "mymod"} and side {@link ConfigSide#CLIENT} writes to {@code mymod-client.toml}.
     *
     * <p>If the file does not yet exist it is created with all default values and comments.
     * If it already exists, values are read and validated (invalid values are reset to
     * their defaults), and the file is always rewritten so that added fields, removed
     * fields, and updated comment text are reflected immediately. A file that cannot be parsed
     * at all is renamed to {@code <name>.<ext>.bak} and regenerated from defaults, so a typo in
     * a hand-edited config never prevents the game from starting.
     *
     * <p><b>Call this during mod initialisation</b> ({@code onInitialize} /
     * {@code @Mod} constructor), not in a {@code static} initialiser block — the config
     * directory provided by the platform helper is not available until the loader has
     * finished its own setup.
     *
     * @return the loaded {@link ConfigSpec}
     * @throws IllegalStateException if this builder has already been built, or if another spec
     *                               is already registered under the same file name
     */
    public ConfigSpec build() {
        if (built) {
            throw new IllegalStateException("This ConfigBuilder has already been built");
        }
        built = true;

        String specName = customFileName != null
                ? customFileName
                : modId + "-" + side.name().toLowerCase(Locale.ROOT);

        // Insertion order is load-bearing: it decides the key order written to the file. It must
        // survive into the spec, so copy into a LinkedHashMap rather than using Map.copyOf, whose
        // iteration order is unspecified and in practice reshuffles between JVM runs.
        Map<String, ConfigEntry<?>> ordered =
                Collections.unmodifiableMap(new LinkedHashMap<>(entries));

        ConfigSpec spec = new ConfigSpec(specName, format, side, ordered, headerComment, watchForChanges);
        spec.load();
        return spec;
    }
}
