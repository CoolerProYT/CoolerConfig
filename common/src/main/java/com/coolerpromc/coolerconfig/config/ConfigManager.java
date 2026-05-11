package com.coolerpromc.coolerconfig.config;

import com.coolerpromc.coolerconfig.Constants;
import com.coolerpromc.coolerconfig.platform.Services;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Internal I/O layer that bridges a {@link ConfigSpec} to its backing file via Night-Config.
 *
 * <p>This class is package-private and not part of the public API. All interactions with the
 * Night-Config library are encapsulated here so that the public surface ({@link ConfigSpec},
 * {@link ConfigBuilder}, {@link ConfigEntry}) remains free of Night-Config types.
 *
 * <h2>File format dispatch</h2>
 * <ul>
 *   <li>{@link ConfigFormat#TOML} — uses {@link CommentedFileConfig} with insertion-order
 *       preservation so that entries appear in the file in the same order they were declared
 *       in the builder. Per-entry and file-level comments are written as TOML block comments.</li>
 *   <li>{@link ConfigFormat#HOCON} — uses a plain {@link FileConfig} backed by
 *       {@code HoconFormat.instance()}. Comments are not written programmatically for HOCON
 *       files.</li>
 * </ul>
 *
 * <h2>Load / reload / save semantics</h2>
 * <ul>
 *   <li>{@link #load()} — called once during {@link ConfigSpec#load()}. Creates the file with
 *       defaults if absent; validates and corrects entries if the file already exists.</li>
 *   <li>{@link #reload()} — re-reads the file and corrects entries; intended to pick up
 *       manual edits between game launches.</li>
 *   <li>{@link #save()} — replaces the file with current in-memory values; useful after
 *       programmatic value changes.</li>
 * </ul>
 *
 * <h2>Side filtering</h2>
 * All three methods call {@link #shouldLoad()} first. {@link ConfigSide#CLIENT} configs are
 * silently skipped when running on a dedicated server so that no empty file is created and
 * no warning appears in the server log.
 */
final class ConfigManager {

    private final ConfigSpec spec;
    private final String headerComment;
    private FileConfig fileConfig;

    /**
     * Creates a manager for the given spec.
     *
     * @param spec          the owning spec; used to read format, side, name, and entries
     * @param headerComment TOML file-level comment; written above all keys; empty string
     *                      if the builder's {@code comment()} was not called
     */
    ConfigManager(ConfigSpec spec, String headerComment) {
        this.spec = spec;
        this.headerComment = headerComment;
    }

    /**
     * Returns the absolute path of the config file on the current platform.
     *
     * <p>The directory is obtained from
     * {@link com.coolerpromc.coolerconfig.platform.services.IPlatformHelper#getConfigDirectory()},
     * and the extension is {@code .toml} for {@link ConfigFormat#TOML} or {@code .conf} for
     * {@link ConfigFormat#HOCON}.
     */
    private Path configPath() {
        String ext = spec.getFormat() == ConfigFormat.TOML ? ".toml" : ".conf";
        return Services.PLATFORM.getConfigDirectory().resolve(spec.getName() + ext);
    }

    /**
     * Opens (or re-opens) the Night-Config {@link FileConfig} for the spec's file path.
     *
     * <p>TOML specs use {@link CommentedFileConfig} with insertion-order preservation.
     * HOCON specs use a plain {@link FileConfig} backed by {@code HoconFormat}.
     *
     * @return a newly constructed, not-yet-loaded {@link FileConfig}
     */
    private FileConfig openFileConfig() {
        Path path = configPath();
        if (spec.getFormat() == ConfigFormat.TOML) {
            return CommentedFileConfig.builder(path)
                    .preserveInsertionOrder()
                    .build();
        } else {
            return FileConfig.builder(path, com.electronwill.nightconfig.hocon.HoconFormat.instance())
                    .build();
        }
    }

    /**
     * Performs the initial load of the config file.
     *
     * <p>If {@link #shouldLoad()} returns {@code false} the method exits immediately. Otherwise:
     * <ol>
     *   <li>The parent directory is created if it does not exist.</li>
     *   <li>The Night-Config handle is opened if it has not been opened before.</li>
     *   <li>If the file does not exist, defaults are applied and written to disk.</li>
     *   <li>If the file exists, it is read and {@link #correctAndFill()} validates each entry.
     *       If any entry was missing or invalid the file is rewritten with the corrected values.</li>
     * </ol>
     */
    void load() {
        if (!shouldLoad()) return;

        Path path = configPath();
        ensureDirectory(path);

        if (fileConfig == null) {
            fileConfig = openFileConfig();
        }

        if (!Files.exists(path)) {
            applyDefaults();
            writeToDisk();
        } else {
            fileConfig.load();
            boolean dirty = correctAndFill();
            if (dirty) writeToDisk();
        }
    }

    /**
     * Re-reads the config file from disk and corrects any invalid entries.
     *
     * <p>If {@link #shouldLoad()} returns {@code false} this is a no-op. If any entry was
     * missing or contained an invalid value, the file is rewritten with the corrected values
     * and a warning is logged for each corrected key.
     *
     * <p>Called by {@link ConfigSpec#reload()}, which subsequently fires the registered
     * reload listeners.
     */
    void reload() {
        if (!shouldLoad()) return;
        fileConfig.load();
        boolean dirty = correctAndFill();
        if (dirty) writeToDisk();
    }

    /**
     * Writes the current in-memory default values to disk, overwriting any existing file.
     *
     * <p>If {@link #shouldLoad()} returns {@code false} this is a no-op. If the Night-Config
     * handle has not been opened yet (e.g. because the file did not exist during {@link #load()}),
     * it is opened here before writing.
     */
    void save() {
        if (!shouldLoad()) return;
        if (fileConfig == null) {
            ensureDirectory(configPath());
            fileConfig = openFileConfig();
        }
        applyDefaults();
        writeToDisk();
    }

    /**
     * Returns {@code true} if this config should be loaded on the current physical side.
     *
     * <p>{@link ConfigSide#CLIENT} configs are skipped when the current process is a
     * dedicated server ({@code !isPhysicalClient()}). All other configs always load.
     * A {@code DEBUG}-level log line is emitted when a config is skipped.
     *
     * @return {@code false} only for {@link ConfigSide#CLIENT} configs on a dedicated server
     */
    private boolean shouldLoad() {
        if (spec.getSide() == ConfigSide.CLIENT && !Services.PLATFORM.isPhysicalClient()) {
            Constants.LOG.debug("[CoolerConfig] Skipping CLIENT config '{}' on dedicated server", spec.getName());
            return false;
        }
        return true;
    }

    /**
     * Serialises all current entry values (and optionally their comments) to the Night-Config
     * object and flushes it to disk.
     *
     * <p>For TOML configs the file-level header comment and each entry's per-key comment are
     * written via {@link CommentedFileConfig#setComment(String, String)}. For HOCON configs
     * only values are written (comments are not written programmatically).
     */
    private void writeToDisk() {
        if (spec.getFormat() == ConfigFormat.TOML && fileConfig instanceof CommentedFileConfig cf) {
            if (!headerComment.isEmpty()) {
                cf.setComment("", " " + headerComment);
            }
            for (ConfigEntry<?> entry : spec.getEntries().values()) {
                cf.set(entry.getPath(), entry.get());
                if (!entry.getComment().isEmpty()) {
                    cf.setComment(entry.getPath(), " " + entry.getComment());
                }
            }
        } else {
            for (ConfigEntry<?> entry : spec.getEntries().values()) {
                fileConfig.set(entry.getPath(), entry.get());
            }
        }
        fileConfig.save();
    }

    /**
     * Resets every entry's in-memory value to its {@link ConfigEntry#getDefaultValue() default}.
     *
     * <p>Called before writing a fresh file so that the on-disk representation always starts
     * from a clean, known state.
     */
    private void applyDefaults() {
        for (ConfigEntry<?> entry : spec.getEntries().values()) {
            entry.set(entry.getDefaultValue());
        }
    }

    /**
     * Validates every entry against the value found in the currently loaded Night-Config object
     * and updates the in-memory value accordingly.
     *
     * <p>For each entry:
     * <ul>
     *   <li>If the key is absent in the file, the entry is reset to its default and the file
     *       is marked dirty.</li>
     *   <li>If the value fails {@link ConfigEntry#validate(Object)} (after coercion), a
     *       {@code WARN}-level message is logged, the entry is reset to its default, and the
     *       file is marked dirty.</li>
     *   <li>Otherwise the in-memory value is updated from the file.</li>
     * </ul>
     *
     * @return {@code true} if at least one entry was missing or invalid and was reset,
     *         indicating that the file should be rewritten
     */
    private boolean correctAndFill() {
        boolean dirty = false;
        for (Map.Entry<String, ConfigEntry<?>> e : spec.getEntries().entrySet()) {
            String path = e.getKey();
            ConfigEntry<?> entry = e.getValue();
            Object raw = fileConfig.get(path);
            if (raw == null) {
                entry.set(entry.getDefaultValue());
                dirty = true;
            } else if (!entry.validate(raw)) {
                Constants.LOG.warn("[CoolerConfig] '{}': invalid value '{}', resetting to default '{}'",
                        path, raw, entry.getDefaultValue());
                entry.set(entry.getDefaultValue());
                dirty = true;
            } else {
                entry.set(raw);
            }
        }
        return dirty;
    }

    /**
     * Creates the parent directory of {@code filePath} and all missing ancestors.
     *
     * @param filePath the target config file path; the directory created is
     *                 {@code filePath.getParent()}
     * @throws RuntimeException wrapping any {@link IOException} if directory creation fails
     */
    private static void ensureDirectory(Path filePath) {
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create config directory: " + filePath.getParent(), e);
        }
    }
}
