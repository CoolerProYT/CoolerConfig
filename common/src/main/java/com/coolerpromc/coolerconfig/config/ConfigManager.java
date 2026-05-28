package com.coolerpromc.coolerconfig.config;

import com.coolerpromc.coolerconfig.Constants;
import com.coolerpromc.coolerconfig.platform.Services;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Internal I/O layer that bridges a {@link ConfigSpec} to its backing file via Night-Config.
 *
 * <p>This class is package-private and not part of the public API. All interactions with the
 * Night-Config library are encapsulated here so that the public surface ({@link ConfigSpec},
 * {@link ConfigBuilder}, {@link ConfigEntry}) remains free of Night-Config types.
 *
 * <h2>File format dispatch</h2>
 * Both formats use {@link CommentedFileConfig} so comments are always written.
 * <ul>
 *   <li>{@link ConfigFormat#TOML} — format inferred from the {@code .toml} extension;
 *       insertion order is preserved; comments use {@code #} prefix.</li>
 *   <li>{@link ConfigFormat#HOCON} — format supplied explicitly via {@code HoconFormat.instance()}
 *       because Night-Config does not auto-detect {@code .conf}; comments use {@code #} prefix,
 *       which is valid HOCON syntax.</li>
 * </ul>
 *
 * <h2>Load / reload / save semantics</h2>
 * <ul>
 *   <li>{@link #load()} — called once during {@link ConfigSpec#load()}. Creates the file with
 *       defaults if absent. If the file exists, values are read, validated, and corrected;
 *       the file is then always rewritten so that added fields, removed fields, and updated
 *       comments are reflected on disk.</li>
 *   <li>{@link #reload()} — re-reads the file, corrects entries, and always rewrites; intended
 *       to pick up manual edits between game launches while keeping the file canonical.</li>
 *   <li>{@link #save()} — replaces the file with current in-memory values; useful after
 *       programmatic value changes.</li>
 * </ul>
 *
 * <h2>Why the file is always rewritten</h2>
 * After reading values from disk into {@link ConfigEntry} objects, the in-memory Night-Config
 * object is cleared with {@link com.electronwill.nightconfig.core.Config#clear()} before
 * {@link #writeToDisk()} is called. This guarantees three properties on every load or reload:
 * <ol>
 *   <li><b>New fields</b> — entries added to the spec since the file was last written are
 *       written with their default value.</li>
 *   <li><b>Removed fields</b> — entries deleted from the spec are no longer in the file.</li>
 *   <li><b>Updated comments</b> — comment text is always taken from the current spec, never
 *       from a stale file.</li>
 * </ol>
 *
 * <h2>Side filtering</h2>
 * All three methods call {@link #shouldLoad()} first. {@link ConfigSide#CLIENT} configs are
 * silently skipped when running on a dedicated server so that no empty file is created and
 * no warning appears in the server log.
 */
final class ConfigManager {

    private final ConfigSpec spec;
    private final String headerComment;
    private final boolean watchForChanges;
    private CommentedFileConfig fileConfig;

    /**
     * Creates a manager for the given spec.
     *
     * @param spec          the owning spec; used to read format, side, name, and entries
     * @param headerComment file-level header comment; written above all keys; empty string
     *                      if the builder's {@code comment()} was not called
     */
    ConfigManager(ConfigSpec spec, String headerComment, boolean watchForChanges) {
        this.spec = spec;
        this.headerComment = headerComment;
        this.watchForChanges = watchForChanges;
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
     * Opens the Night-Config {@link CommentedFileConfig} for the spec's file path.
     *
     * <p>Both formats use {@link CommentedFileConfig} so that per-entry and header comments
     * are written for all file types. TOML infers its format from the {@code .toml} extension;
     * HOCON must be specified explicitly via {@code HoconFormat.instance()} because Night-Config
     * does not auto-detect {@code .conf} files.
     *
     * @return a newly constructed, not-yet-loaded {@link CommentedFileConfig}
     */
    private CommentedFileConfig openFileConfig() {
        Path path = configPath();
        if (spec.getFormat() == ConfigFormat.TOML) {
            return CommentedFileConfig.builder(path)
                    .preserveInsertionOrder()
                    .build();
        } else {
            return CommentedFileConfig.builder(path, com.electronwill.nightconfig.hocon.HoconFormat.instance())
                    .preserveInsertionOrder()
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
     *   <li>If the file does not exist, defaults are applied and the file is written.</li>
     *   <li>If the file exists, values are read into the entries via {@link #correctAndFill()},
     *       the in-memory config is then {@link com.electronwill.nightconfig.core.Config#clear()
     *       cleared} to remove any stale keys, and the file is always rewritten so that added
     *       fields, removed fields, and updated comment text are reflected immediately.</li>
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
        } else {
            fileConfig.load();
            correctAndFill();
            fileConfig.clear();
        }
        writeToDisk();
    }

    /**
     * Re-reads the config file from disk, corrects any invalid entries, and rewrites the file.
     *
     * <p>If {@link #shouldLoad()} returns {@code false} this is a no-op. The file is always
     * rewritten after reading so that stale keys are removed, new keys are added, and comment
     * text is kept in sync with the current spec.
     *
     * <p>Called by {@link ConfigSpec#reload()}, which subsequently fires the registered
     * reload listeners.
     */
    void reload() {
        if (!shouldLoad()) return;
        fileConfig.load();
        correctAndFill();
        fileConfig.clear();
        writeToDisk();
    }

    /**
     * Re-reads the config file from disk and updates in-memory values, but only rewrites the
     * file if at least one value was invalid and had to be corrected.
     *
     * <p>Used exclusively by the file-watcher path. Skipping the unconditional rewrite breaks
     * the feedback loop where {@link #writeToDisk()} would generate a new {@code ENTRY_MODIFY}
     * event and cause the watcher to fire again indefinitely.
     */
    void reloadQuiet() {
        if (!shouldLoad()) return;
        fileConfig.load();
        boolean corrected = correctAndFill();
        if (corrected) {
            fileConfig.clear();
            writeToDisk();
        }
    }

    /**
     * Writes the current in-memory entry values to disk, overwriting any existing file.
     *
     * <p>If {@link #shouldLoad()} returns {@code false} this is a no-op. If the Night-Config
     * handle has not been opened yet (e.g. because {@link #load()} was skipped on this side),
     * it is opened here before writing.
     *
     * <p>Unlike {@link #load()} and {@link #reload()}, this method does <em>not</em> clear the
     * Night-Config object before writing. The Night-Config object is either fresh (null case
     * above) or was already canonicalised by the last load/reload; stale keys are therefore
     * not re-introduced.
     */
    void save() {
        if (!shouldLoad()) return;
        if (fileConfig == null) {
            ensureDirectory(configPath());
            fileConfig = openFileConfig();
        }
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
     * Serialises all current entry values and their comments to the Night-Config object and
     * flushes it to disk.
     *
     * <p>Writes only the entries declared in the spec — callers are responsible for calling
     * {@link com.electronwill.nightconfig.core.Config#clear()} on {@link #fileConfig} before
     * this method when stale keys must be removed. The file-level header comment (if set via
     * {@link ConfigBuilder#comment(String)}) is written at the top of the file, followed by
     * per-key comments above each entry. Both TOML and HOCON use the {@code #} prefix.
     */
    private void writeToDisk() {
        if (!headerComment.isEmpty()) {
            fileConfig.setComment("", " " + headerComment);
        }
        for (ConfigEntry<?> entry : spec.getEntries().values()) {
            fileConfig.set(entry.getPath(), toWritable(entry.get()));
            if (!entry.getComment().isEmpty()) {
                fileConfig.setComment(entry.getPath(), " " + entry.getComment());
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
     * Reads values from the currently loaded Night-Config object into the {@link ConfigEntry}
     * objects, correcting invalid or missing values to their defaults.
     *
     * <p>For each spec entry:
     * <ul>
     *   <li>If the key is absent from the file (newly added field), the entry keeps its
     *       default value — no warning is emitted.</li>
     *   <li>If the value fails {@link ConfigEntry#validate(Object)} (after coercion), a
     *       {@code WARN}-level message is logged and the entry is reset to its default.</li>
     *   <li>Otherwise the in-memory value is updated from the file.</li>
     * </ul>
     *
     * <p>Keys present in the file but absent from the spec (removed fields) are ignored here;
     * they are eliminated by the caller clearing {@link #fileConfig} before
     * {@link #writeToDisk()}.
     */
    private boolean correctAndFill() {
        boolean corrected = false;
        for (Map.Entry<String, ConfigEntry<?>> e : spec.getEntries().entrySet()) {
            String path = e.getKey();
            ConfigEntry<?> entry = e.getValue();
            Object raw = fileConfig.get(path);
            if (raw instanceof UnmodifiableConfig subConfig && entry.getDefaultValue() instanceof Map<?, ?>) {
                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                for (UnmodifiableConfig.Entry entry1 : subConfig.entrySet()) {
                    map.put(entry1.getKey(), entry1.getValue());
                }
                raw = map;
            }
            if (raw == null) {
                entry.set(entry.getDefaultValue());
            } else if (!entry.validate(raw)) {
                Constants.LOG.warn("[CoolerConfig] '{}': invalid value '{}', resetting to default '{}'",
                        path, raw, entry.getDefaultValue());
                entry.set(entry.getDefaultValue());
                corrected = true;
            } else {
                entry.set(raw);
            }
        }
        return corrected;
    }

    /**
     * Starts a daemon {@link WatchService} thread for the config file if
     * {@code watchForChanges} was enabled on the builder.
     *
     * <p>The thread polls the config directory every 2 seconds. When it detects that this
     * specific file was modified, it sleeps 150 ms (debounce — editors often write in two
     * steps) then calls {@link ConfigSpec#reload()}, which re-reads values from disk and
     * fires all registered reload listeners.
     *
     * <p>If the config should not load on the current side (e.g. a CLIENT config on a
     * dedicated server) this method does nothing.
     */
    void startWatcherIfEnabled() {
        if (!watchForChanges || !shouldLoad()) return;
        Path configFile = configPath();
        Thread thread = new Thread(() -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                configFile.getParent().register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);
                Constants.LOG.debug("[CoolerConfig] Watching '{}' for changes", configFile.getFileName());
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watcher.poll(2, TimeUnit.SECONDS);
                    if (key == null) continue;
                    boolean relevant = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (configFile.getFileName().equals(event.context())) {
                            relevant = true;
                        }
                    }
                    key.reset();
                    if (relevant) {
                        Thread.sleep(150);
                        spec.reloadWatch();
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                Constants.LOG.warn("[CoolerConfig] File watcher for '{}' failed: {}", spec.getName(), e.getMessage());
            }
        }, "CoolerConfig-Watcher-" + spec.getName());
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Converts a Java value to a type that Night-Config's TOML/HOCON writer can serialize.
     *
     * <ul>
     *   <li>{@link Enum} constants are stored as their {@link Enum#name() name} string.</li>
     *   <li>{@link Map} values are converted to an in-memory {@link CommentedConfig} sub-table
     *       so the TOML/HOCON writer sees a native config object rather than a raw Java Map,
     *       which it cannot serialize.</li>
     *   <li>All other values are returned unchanged.</li>
     * </ul>
     */
    private static Object toWritable(Object value) {
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Map<?, ?> map) {
            CommentedConfig sub = CommentedConfig.inMemory();
            map.forEach((k, v) -> sub.set(k.toString(), v));
            return sub;
        }
        return value;
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
