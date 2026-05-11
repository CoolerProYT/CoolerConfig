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
    ConfigSpec(String name, ConfigFormat format, ConfigSide side, Map<String, ConfigEntry<?>> entries, String headerComment) {
        this.name = name;
        this.format = format;
        this.side = side;
        this.entries = entries;
        this.manager = new ConfigManager(this, headerComment);
        ConfigRegistry.register(this);
    }

    /**
     * Returns a new {@link ConfigBuilder} for the given file name and format.
     *
     * @param name   base file name without extension, e.g. {@code "mymod"};
     *               the full path will be {@code <configDir>/<name>.toml} or
     *               {@code <configDir>/<name>.conf}
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
     * Performs the initial load of this config from disk.
     *
     * <p>Called automatically by {@link ConfigBuilder#build()}. If the file does not exist
     * it is created with default values. If it exists, each entry is validated and any
     * invalid values are reset to their defaults (the file is then rewritten). Reload
     * listeners are <em>not</em> fired by this method.
     */
    public void load() {
        manager.load();
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
     * Re-reads all entries from disk and fires all registered reload listeners.
     *
     * <p>Invalid values found during reload are silently reset to their defaults and the
     * file is rewritten. This method is called by {@link ConfigRegistry#reloadForServer()}
     * and {@link ConfigRegistry#reloadForClient()} in response to lifecycle events, but can
     * also be invoked manually — for example from a {@code /reload} command handler.
     *
     * <p>Reload listeners run synchronously after the file has been parsed and all entry
     * values have been updated. If the config's {@link ConfigSide} does not match the
     * current physical side, this method does nothing.
     */
    public void reload() {
        manager.reload();
        reloadListeners.forEach(Runnable::run);
    }
}
