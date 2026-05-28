package com.coolerpromc.coolerconfig.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An immutable description of a config file together with its current in-memory values.
 *
 * <p>Create instances via the fluent {@link ConfigBuilder}:
 * <pre>{@code
 * public static final ConfigSpec CONFIG = ConfigSpec.builder("mymod", ConfigFormat.TOML)
 *         .define("general.enable", true, "Enable all features")
 *         .defineInt("general.count", 10, 1, 100, "Spawn count")
 *         .build();
 * }</pre>
 *
 * <p>After {@link ConfigBuilder#build()} returns, the spec is immediately loaded from disk
 * (or the file is created with default values) and registered with {@link ConfigRegistry}
 * so that CoolerConfig's lifecycle events can trigger automatic reloads.
 *
 * <h2>Reading values</h2>
 * Use the typed getters — {@link #getBoolean(String)}, {@link #getInt(String)},
 * {@link #getDouble(String)}, {@link #getString(String)}, {@link #getList(String)} — or the
 * generic {@link #get(String)} if you need to cast the value yourself.
 *
 * <h2>Reload listeners</h2>
 * Register callbacks with {@link #addReloadListener(Runnable)} to be notified whenever the
 * config is reloaded from disk. Listeners are fired at the end of every {@link #reload()}
 * call but <em>not</em> after the initial {@link #load()}.
 *
 * <h2>Thread safety</h2>
 * Reads ({@code get*}) are safe from any thread after the initial load. Writes ({@code reload},
 * {@code save}) are not synchronised — call them from the main server or client thread.
 */
public final class ConfigSpec {

    private final String name;
    private final ConfigFormat format;
    private final ConfigSide side;
    private final Map<String, ConfigEntry<?>> entries;
    private final ConfigManager manager;
    private final List<Runnable> reloadListeners = new ArrayList<>();

    /**
     * Package-private constructor — use {@link ConfigSpec#builder(String, ConfigFormat)} and
     * {@link ConfigBuilder#build()} to obtain instances.
     *
     * @param name          base file name (without extension)
     * @param format        serialisation format
     * @param side          physical side this config is loaded on
     * @param entries       immutable, insertion-ordered map of path → entry
     * @param headerComment TOML file-level header comment; empty string if none
     */
    ConfigSpec(String name, ConfigFormat format, ConfigSide side, Map<String, ConfigEntry<?>> entries, String headerComment, boolean watchForChanges) {
        this.name = name;
        this.format = format;
        this.side = side;
        this.entries = entries;
        this.manager = new ConfigManager(this, headerComment, watchForChanges);
        ConfigRegistry.register(this);
    }

    /**
     * Returns a new {@link ConfigBuilder} for the given mod ID and format.
     *
     * @param name   base mod identifier; the actual file name is derived by the builder as
     *               {@code <name>-<side>.toml} or {@code <name>-<side>.conf}, e.g.
     *               {@code "mymod"} with the default {@link ConfigSide#COMMON} side →
     *               {@code mymod-common.toml}
     * @param format the file format to use
     * @return a fresh builder
     */
    public static ConfigBuilder builder(String name, ConfigFormat format) {
        return new ConfigBuilder(name, format);
    }

    /**
     * Returns the base file name (without extension) used to locate this config on disk.
     *
     * @return the config name; never {@code null}
     */
    public String getName() { return name; }

    /**
     * Returns the serialization format of this config.
     *
     * @return the format; never {@code null}
     */
    public ConfigFormat getFormat() { return format; }

    /**
     * Returns the physical side on which this config is loaded.
     *
     * @return the side; never {@code null}
     */
    public ConfigSide getSide() { return side; }

    /**
     * Package-private accessor used by {@link ConfigManager} to iterate over entries when
     * reading from and writing to the config file.
     */
    Map<String, ConfigEntry<?>> getEntries() { return entries; }

    /**
     * Registers a callback that fires at the end of every {@link #reload()} call.
     *
     * <p>Use listeners to recompute values that are derived from raw config entries so that
     * derived state stays in sync with the file:
     * <pre>{@code
     * public static int EFFECTIVE_RANGE;
     *
     * static {
     *     CONFIG.addReloadListener(() -> {
     *         EFFECTIVE_RANGE = CONFIG.getBoolean("display.particles")
     *                 ? CONFIG.getInt("display.range") : 0;
     *     });
     * }
     * }</pre>
     *
     * <p>Listeners are called in registration order. Exceptions thrown by a listener
     * propagate to the caller of {@link #reload()} and may skip subsequent listeners.
     *
     * @param listener the callback to run after each reload; must not be {@code null}
     * @return {@code this} spec, for fluent chaining
     */
    public ConfigSpec addReloadListener(Runnable listener) {
        reloadListeners.add(listener);
        return this;
    }

    /**
     * Returns the current value of the entry at {@code path}, cast to {@code T}.
     *
     * <p>Prefer the typed helpers ({@link #getBoolean}, {@link #getInt}, etc.) over this
     * method to avoid unchecked cast warnings at the call site.
     *
     * @param path dot-separated key path, e.g. {@code "general.enable"}
     * @param <T>  the expected return type
     * @return the current value; never {@code null}
     * @throws IllegalArgumentException if no entry exists at {@code path}
     * @throws ClassCastException       if the stored type does not match {@code T}
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String path) {
        ConfigEntry<T> entry = (ConfigEntry<T>) entries.get(path);
        if (entry == null) throw new IllegalArgumentException("No config entry at path: " + path);
        return entry.get();
    }

    /**
     * Returns the {@code boolean} value of the entry at {@code path}.
     *
     * @param path dot-separated key path
     * @return the current value
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public boolean getBoolean(String path) { return get(path); }

    /**
     * Returns the {@code int} value of the entry at {@code path}.
     *
     * @param path dot-separated key path
     * @return the current value
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public int getInt(String path) { return get(path); }

    /**
     * Returns the {@code long} value of the entry at {@code path}.
     *
     * @param path dot-separated key path
     * @return the current value
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public long getLong(String path) { return get(path); }

    /**
     * Returns the {@code double} value of the entry at {@code path}.
     *
     * @param path dot-separated key path
     * @return the current value
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public double getDouble(String path) { return get(path); }

    /**
     * Returns the {@code String} value of the entry at {@code path}.
     *
     * @param path dot-separated key path
     * @return the current value; never {@code null}
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public String getString(String path) { return get(path); }

    /**
     * Returns the {@code List} value of the entry at {@code path}.
     *
     * @param path dot-separated key path
     * @param <T>  the declared element type (not enforced at runtime)
     * @return the current list; never {@code null}
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public <T> List<T> getList(String path) { return get(path); }

    /**
     * Returns the enum value of the entry at {@code path}.
     *
     * @param path dot-separated key path
     * @param <E>  the enum type
     * @return the current value; never {@code null}
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public <E extends Enum<E>> E getEnum(String path) { return get(path); }

    /**
     * Returns the {@code Map<String, V>} value of the entry at {@code path}.
     *
     * @param path dot-separated key path
     * @param <V>  the map value type
     * @return the current map; never {@code null}
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public <V> Map<String, V> getMap(String path) { return get(path); }

    /**
     * Sets the in-memory value of the entry at {@code path}.
     *
     * <p>The value is coerced and validated the same way as values read from disk; if it
     * fails validation the entry silently resets to its default. Call {@link #save()}
     * afterwards to persist the change to disk.
     *
     * <p>Typical usage from an in-game config screen:
     * <pre>{@code
     * CONFIG.set("general.enable", false);
     * CONFIG.set("general.count", 20);
     * CONFIG.save();
     * }</pre>
     *
     * @param path  dot-separated key path, e.g. {@code "general.enable"}
     * @param value the new value
     * @return {@code this} spec, for fluent chaining
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public ConfigSpec set(String path, Object value) {
        ConfigEntry<?> entry = entries.get(path);
        if (entry == null) throw new IllegalArgumentException("No config entry at path: " + path);
        entry.set(value);
        return this;
    }

    /**
     * Performs the initial load of this config from disk.
     *
     * <p>Called automatically by {@link ConfigBuilder#build()}. If the file does not exist
     * it is created with all default values and comments. If it exists, each entry is read
     * and validated (invalid values are reset to their defaults), then the file is always
     * rewritten so that added fields, removed fields, and updated comment text are reflected
     * immediately. Reload listeners are <em>not</em> fired by this method.
     */
    public void load() {
        manager.load();
        manager.startWatcherIfEnabled();
    }

    /**
     * Writes the current in-memory values to disk, overwriting the file completely.
     *
     * <p>This is useful when you want to programmatically update entries and persist the
     * change without waiting for the next server/client restart. Reload listeners are
     * <em>not</em> fired by this method.
     */
    public void save() {
        manager.save();
    }

    /**
     * Re-reads all entries from disk, rewrites the file, and fires all registered reload listeners.
     *
     * <p>Invalid values are silently reset to their defaults and logged at {@code WARN} level.
     * The file is always rewritten after reading so that added fields, removed fields, and
     * updated comment text stay in sync with the current spec. This method is called by
     * {@link ConfigRegistry#reloadForServer()} and {@link ConfigRegistry#reloadForClient()} in
     * response to lifecycle events, but can also be invoked manually — for example from a
     * {@code /reload} command handler.
     *
     * <p>Reload listeners run synchronously after the file has been parsed and all entry
     * values have been updated. If the config's {@link ConfigSide} does not match the
     * current physical side, this method does nothing.
     */
    public void reload() {
        manager.reload();
        reloadListeners.forEach(Runnable::run);
    }

    /**
     * Called by the file-watcher thread. Uses {@link ConfigManager#reloadQuiet()} so that
     * the file is not unconditionally rewritten — only when a correction was needed. This
     * breaks the feedback loop that would otherwise occur when the rewrite triggers a new
     * {@code ENTRY_MODIFY} event.
     */
    void reloadWatch() {
        manager.reloadQuiet();
        reloadListeners.forEach(Runnable::run);
    }
}
