package com.coolerpromc.coolerconfig.config;

/**
 * A typed, path-free handle to a single entry inside a {@link ConfigSpec}.
 *
 * <p>Obtain instances from the {@code define*} methods on {@link ConfigBuilder}:
 * <pre>{@code
 * private static final ConfigSpec CONFIG;
 * public  static final ConfigValue<Boolean> ENABLE;
 * public  static final ConfigValue<Integer> COUNT;
 *
 * static {
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
     * Updates the in-memory value of this entry.
     *
     * <p>The value is coerced and validated the same way as values read from disk; an
     * invalid value silently falls back to the default. Call {@link ConfigSpec#save()}
     * afterwards to persist the change to disk.
     *
     * @param value the new value to set
     */
    public void set(T value) {
        entry.set(value);
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
}
