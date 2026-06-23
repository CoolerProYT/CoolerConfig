package com.coolerpromc.coolerconfig.config;

/**
 * A typed, path-free handle to a single entry inside a {@link ConfigSpec}.
 *
 * <p>Obtain instances from the {@code define*} methods on {@link ConfigBuilder}:
 * <pre>{@code
 * public static ConfigSpec CONFIG;
 * public static ConfigValue<Boolean> ENABLE;
 * public static ConfigValue<Integer> COUNT;
 *
 * public static void init() {
 *     ConfigBuilder builder = ConfigSpec.builder("mymod", ConfigFormat.TOML)
 *             .comment("MyMod configuration");
 *     ENABLE = builder.defineBoolean("general.enable", true,  "Enable all features");
 *     COUNT  = builder.defineInt    ("general.count",  10, 1, 100, "Spawn count");
 *     CONFIG = builder.build();
 * }
 * }</pre>
 *
 * <p>Then read and write via the handle — no string paths, no risk of typos:
 * <pre>{@code
 * boolean on = ENABLE.get();
 *
 * // in-game config screen
 * ENABLE.set(false);
 * COUNT.set(20);
 * CONFIG.save();
 * }</pre>
 *
 * @param <T> the Java type of the config value (e.g. {@code Boolean}, {@code Integer},
 *            {@code String}, {@code MyEnum})
 */
public final class ConfigValue<T> {

    private final ConfigEntry<T> entry;

    ConfigValue(ConfigEntry<T> entry) {
        this.entry = entry;
    }

    /**
     * Returns the current in-memory value of this entry.
     *
     * <p>Before the owning {@link ConfigSpec} is loaded this returns the default value.
     * After loading it reflects whatever was last read from disk or set via {@link #set}.
     *
     * @return the current value; never {@code null}
     */
    public T get() {
        return entry.get();
    }

    /**
     * Updates the in-memory value, reporting whether it was acceptable.
     *
     * <p>The value is validated exactly as if it had been read from the config file. <b>A
     * rejected value leaves the entry unchanged</b> and returns {@code false}; it does not
     * reset the entry to its default. Call {@link ConfigSpec#save()} afterwards to persist an
     * accepted change to disk.
     *
     * <pre>{@code
     * if (!MAX_ITEMS.set(userInput)) {
     *     showError("Value out of range");
     * }
     * }</pre>
     *
     * @param value the new value to set
     * @return {@code true} if the value was accepted and stored, {@code false} if it failed
     *         validation and the entry was left untouched
     */
    public boolean set(T value) {
        return entry.set(value);
    }

    /**
     * Restores this entry's default value. Call {@link ConfigSpec#save()} afterwards to persist
     * the reset to disk.
     */
    public void reset() {
        entry.reset();
    }

    /**
     * Returns the default value declared at build time.
     *
     * <p>This is the value the entry holds before loading, and the value it resets to
     * when the file contains an invalid entry.
     *
     * @return the default value; never {@code null}
     */
    public T getDefault() {
        return entry.getDefaultValue();
    }

    /**
     * Returns the dot-separated key path of this entry, e.g. {@code "general.maxItems"}.
     *
     * @return the key path; never {@code null}
     */
    public String getPath() {
        return entry.getPath();
    }

    /**
     * Returns the human-readable description declared at build time, or an empty string if none
     * was given. Useful as a tooltip in a config screen.
     *
     * @return the comment; never {@code null}
     */
    public String getComment() {
        return entry.getComment();
    }

    /**
     * Returns the underlying entry, for generic tooling that needs
     * {@link ConfigEntry#getType() type} metadata.
     *
     * @return the backing entry; never {@code null}
     */
    public ConfigEntry<T> getEntry() {
        return entry;
    }
}
