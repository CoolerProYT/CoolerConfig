package com.coolerpromc.coolerconfig.config;

import com.coolerpromc.coolerconfig.Constants;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A description of a config file together with its current in-memory values.
 *
 * <p>Create instances via the fluent {@link ConfigBuilder}:
 * <pre>{@code
 * ConfigBuilder builder = ConfigSpec.builder("mymod", ConfigFormat.TOML);
 * ConfigValue<Boolean> enable = builder.defineBoolean("general.enable", true, "Enable all features");
 * ConfigValue<Integer> count  = builder.defineInt("general.count", 10, 1, 100, "Spawn count");
 * ConfigSpec spec = builder.build();
 * }</pre>
 *
 * <p>After {@link ConfigBuilder#build()} returns, the spec is immediately loaded from disk
 * (or the file is created with default values) and registered with {@link ConfigRegistry}
 * so that CoolerConfig's lifecycle events can trigger automatic reloads.
 *
 * <h2>Reading and writing values</h2>
 * The preferred way is to hold the {@link ConfigValue} handles returned by the builder's
 * {@code define*} methods and call {@link ConfigValue#get()} / {@link ConfigValue#set} on them
 * — no string paths, no risk of typos. The typed getters on this class ({@link #getBoolean},
 * {@link #getInt}, etc.), {@link #set(String, Object)}, and {@link #entries()} remain available
 * for generic code, such as a config screen that enumerates a spec without knowing its entry
 * types ahead of time.
 *
 * <h2>Reload listeners</h2>
 * Register callbacks with {@link #addReloadListener(Runnable)} to be notified whenever the
 * config is reloaded from disk. Listeners fire at the end of every {@link #reload()} call but
 * <em>not</em> after the initial load performed by {@link ConfigBuilder#build()}.
 *
 * <h2>Thread safety</h2>
 * Reads ({@code get*}) are safe from any thread: entry values are held in {@code volatile}
 * fields, so a reload performed on the {@linkplain ConfigBuilder#watchForChanges() file-watcher}
 * thread is immediately visible to game threads. Writes ({@code set}, {@code save},
 * {@code reload}) are not synchronised against each other — drive them from a single thread,
 * normally the main server or client thread.
 */
public final class ConfigSpec {

    private final String name;
    private final ConfigFormat format;
    private final ConfigSide side;
    private final Map<String, ConfigEntry<?>> entries;
    private final ConfigManager manager;
    private final List<Runnable> reloadListeners = new CopyOnWriteArrayList<>();

    /**
     * Package-private constructor — use {@link ConfigSpec#builder(String, ConfigFormat)} and
     * {@link ConfigBuilder#build()} to obtain instances.
     *
     * @param name            base file name (without extension)
     * @param format          serialisation format
     * @param side            physical side this config is loaded on
     * @param entries         immutable, insertion-ordered map of path → entry
     * @param headerComment   file-level header comment; empty string if none
     * @param watchForChanges whether to hot-reload the spec when the file changes on disk
     */
    ConfigSpec(String name, ConfigFormat format, ConfigSide side, Map<String, ConfigEntry<?>> entries,
               String headerComment, boolean watchForChanges) {
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
     * @param modId  base mod identifier; the file name is derived by the builder as
     *               {@code <modId>-<side>.<ext>}, e.g. {@code "mymod"} with the default
     *               {@link ConfigSide#COMMON} side → {@code mymod-common.toml}. Override it with
     *               {@link ConfigBuilder#fileName(String)} or {@link ConfigBuilder#suffix(String)}.
     * @param format the file format to use
     * @return a fresh builder
     */
    public static ConfigBuilder builder(String modId, ConfigFormat format) {
        return new ConfigBuilder(modId, format);
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
     * Returns the absolute path of this config's file on disk, including its extension.
     *
     * <p>The file may not exist yet if this spec's {@link ConfigSide} is not loaded on the
     * current physical side.
     *
     * @return the resolved file path; never {@code null}
     */
    public Path getFilePath() { return manager.configPath(); }

    /**
     * Returns every entry in this spec, in declaration order.
     *
     * <p>Intended for generic tooling — a config screen can enumerate a spec and build a widget
     * per entry from {@link ConfigEntry#getType()}, {@link ConfigEntry#getComment()}, and
     * {@link ConfigEntry#getDefaultValue()}:
     * <pre>{@code
     * for (ConfigEntry<?> entry : CONFIG.entries()) {
     *     addWidget(entry.getPath(), entry.getType(), entry.get(), entry.getComment());
     * }
     * }</pre>
     *
     * @return an unmodifiable, insertion-ordered view of the entries; never {@code null}
     */
    public Collection<ConfigEntry<?>> entries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    /**
     * Package-private accessor used by {@link ConfigManager} to iterate over entries when
     * reading from and writing to the config file.
     */
    Map<String, ConfigEntry<?>> entryMap() { return entries; }

    /**
     * Registers a callback that fires at the end of every {@link #reload()} call.
     *
     * <p>Use listeners to recompute values that are derived from raw config entries so that
     * derived state stays in sync with the file:
     * <pre>{@code
     * public static int EFFECTIVE_RANGE;
     *
     * CONFIG.addReloadListener(() -> {
     *     EFFECTIVE_RANGE = PARTICLES.get() ? RANGE.get() : 0;
     * });
     * }</pre>
     *
     * <p>Listeners are called in registration order. An exception thrown by one listener is
     * logged and does not prevent the remaining listeners from running.
     *
     * @param listener the callback to run after each reload; must not be {@code null}
     * @return {@code this} spec, for fluent chaining
     */
    public ConfigSpec addReloadListener(Runnable listener) {
        reloadListeners.add(listener);
        return this;
    }

    /**
     * Unregisters a callback previously passed to {@link #addReloadListener(Runnable)}.
     *
     * @param listener the callback to remove
     * @return {@code true} if the listener was registered and has been removed
     */
    public boolean removeReloadListener(Runnable listener) {
        return reloadListeners.remove(listener);
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
        return (T) entry(path).get();
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
     * Returns the {@code float} value of the entry at {@code path}.
     *
     * @param path dot-separated key path
     * @return the current value
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public float getFloat(String path) { return get(path); }

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
     * Sets the in-memory value of the entry at {@code path}, reporting whether it was acceptable.
     *
     * <p>The value is validated exactly as if it had been read from disk. <b>A rejected value
     * leaves the entry unchanged</b> and returns {@code false}. Call {@link #save()} afterwards
     * to persist an accepted change to disk.
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
     * @return {@code true} if the value was accepted and stored, {@code false} if it failed
     *         validation and the entry was left untouched
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public boolean set(String path, Object value) {
        return entry(path).set(value);
    }

    /**
     * Returns the entry at {@code path}.
     *
     * @param path dot-separated key path
     * @return the entry; never {@code null}
     * @throws IllegalArgumentException if no entry exists at {@code path}
     */
    public ConfigEntry<?> entry(String path) {
        ConfigEntry<?> entry = entries.get(path);
        if (entry == null) throw new IllegalArgumentException("No config entry at path: " + path);
        return entry;
    }

    /**
     * Restores every entry in this spec to its default value.
     *
     * <p>Call {@link #save()} afterwards to persist the reset to disk. Reload listeners are
     * <em>not</em> fired.
     *
     * @return {@code this} spec, for fluent chaining
     */
    public ConfigSpec reset() {
        entries.values().forEach(ConfigEntry::reset);
        return this;
    }

    /**
     * Performs the initial load of this config from disk and starts the file watcher if one was
     * requested. Called exactly once, by {@link ConfigBuilder#build()}.
     */
    void load() {
        manager.load();
        manager.startWatcherIfEnabled();
    }

    /**
     * Writes the current in-memory values to disk, overwriting the file completely.
     *
     * <p>Use this after changing values programmatically — for example when applying edits from
     * a config screen. Reload listeners are <em>not</em> fired by this method.
     */
    public void save() {
        manager.save();
    }

    /**
     * Re-reads all entries from disk, rewrites the file, and fires all registered reload listeners.
     *
     * <p>Invalid values are reset to their defaults and logged at {@code WARN} level. The file is
     * always rewritten after reading so that added fields, removed fields, and updated comment
     * text stay in sync with the current spec. This method is called by
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
        fireReloadListeners();
    }

    /**
     * Called by the file-watcher thread. Uses {@link ConfigManager#reloadQuiet()} so that
     * the file is not unconditionally rewritten — only when a correction was needed. This
     * breaks the feedback loop that would otherwise occur when the rewrite triggers a new
     * {@code ENTRY_MODIFY} event.
     */
    void reloadWatch() {
        manager.reloadQuiet();
        fireReloadListeners();
    }

    /**
     * Runs every registered reload listener. A listener that throws is logged and skipped so that
     * one misbehaving mod cannot prevent the others from seeing the reload.
     */
    private void fireReloadListeners() {
        for (Runnable listener : reloadListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                Constants.LOG.error("Reload listener for config '{}' threw an exception", name, e);
            }
        }
    }

    @Override
    public String toString() {
        return "ConfigSpec[" + name + ", " + format + ", " + side + ", " + entries.size() + " entries]";
    }
}
