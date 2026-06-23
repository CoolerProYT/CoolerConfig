package com.coolerpromc.coolerconfig.config;

import com.coolerpromc.coolerconfig.Constants;
import com.coolerpromc.coolerconfig.platform.Services;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.core.io.WritingException;
import com.electronwill.nightconfig.hocon.HoconFormat;
import com.electronwill.nightconfig.json.JsonFormat;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal I/O layer that bridges a {@link ConfigSpec} to its backing file via Night-Config.
 *
 * <p>This class is package-private and not part of the public API. All interactions with the
 * Night-Config library are encapsulated here so that the public surface ({@link ConfigSpec},
 * {@link ConfigBuilder}, {@link ConfigEntry}) stays free of Night-Config types.
 *
 * <h2>Load / reload / save semantics</h2>
 * <ul>
 *   <li>{@link #load()} — called once from {@link ConfigBuilder#build()}. Creates the file with
 *       defaults if absent. If the file exists, values are read, validated, and corrected;
 *       the file is then always rewritten so that added fields, removed fields, and updated
 *       comments are reflected on disk.</li>
 *   <li>{@link #reload()} — re-reads the file, corrects entries, and always rewrites; picks up
 *       manual edits while keeping the file canonical.</li>
 *   <li>{@link #reloadQuiet()} — as {@link #reload()}, but only rewrites when a value actually
 *       had to be corrected. Used by the file watcher to avoid a self-triggering write loop.</li>
 *   <li>{@link #save()} — replaces the file with current in-memory values; useful after
 *       programmatic value changes.</li>
 * </ul>
 *
 * <h2>Why the file is always rewritten</h2>
 * After reading values from disk into {@link ConfigEntry} objects, the in-memory Night-Config
 * object is cleared before {@link #writeToDisk()} is called. This guarantees three properties on
 * every load or reload:
 * <ol>
 *   <li><b>New fields</b> — entries added to the spec since the file was last written are
 *       written with their default value.</li>
 *   <li><b>Removed fields</b> — entries deleted from the spec are no longer in the file.</li>
 *   <li><b>Updated comments</b> — comment text is always taken from the current spec, never
 *       from a stale file.</li>
 * </ol>
 *
 * <h2>Malformed files</h2>
 * A config a player has hand-edited into invalid syntax must never stop the game from starting.
 * Every parse is therefore guarded: on a {@link ParsingException} the broken file is moved aside
 * to {@code <name>.<ext>.bak} and regenerated from defaults, with an error logged that points at
 * the backup.
 *
 * <h2>Side filtering</h2>
 * Every entry point calls {@link #shouldLoad()} first. {@link ConfigSide#CLIENT} configs are
 * silently skipped when running on a dedicated server so that no empty file is created and
 * no warning appears in the server log.
 */
final class ConfigManager {

    private final ConfigSpec spec;
    private final String headerComment;
    private final boolean watchForChanges;
    private final AtomicBoolean watcherStarted = new AtomicBoolean();
    private FileConfig fileConfig;

    /**
     * Creates a manager for the given spec.
     *
     * @param spec            the owning spec; used to read format, side, name, and entries
     * @param headerComment   file-level header comment; written above all keys; empty string if
     *                        the builder's {@code comment()} was not called
     * @param watchForChanges whether to hot-reload the spec when the file changes on disk
     */
    ConfigManager(ConfigSpec spec, String headerComment, boolean watchForChanges) {
        this.spec = spec;
        this.headerComment = headerComment;
        this.watchForChanges = watchForChanges;
    }

    /**
     * Returns the absolute path of the config file on the current platform: the loader's config
     * directory plus the spec's name and its format's extension.
     */
    Path configPath() {
        return Services.PLATFORM.getConfigDirectory()
                .resolve(spec.getName() + spec.getFormat().getExtension());
    }

    /**
     * Opens the Night-Config handle for the spec's file path.
     *
     * <p>Every format except {@link ConfigFormat#JSON} uses {@link CommentedFileConfig} so that
     * per-entry and header comments are written. TOML is the only format Night-Config infers from
     * the file extension; the others must be supplied explicitly.
     *
     * <p>Handles are built {@code sync()}: Night-Config writes asynchronously by default, which
     * would let {@link #save()} return before the bytes reach the disk — so a {@code save()}
     * followed by a {@code reload()} could read back the previous contents, and a write issued
     * shortly before the JVM exits could be lost entirely. Config files are a few kilobytes, so
     * there is nothing to gain by writing them off-thread.
     *
     * @return a newly constructed, not-yet-loaded handle
     */
    private FileConfig openFileConfig() {
        Path path = configPath();
        return switch (spec.getFormat()) {
            case TOML -> CommentedFileConfig.builder(path)
                    .preserveInsertionOrder().sync().build();
            case HOCON -> CommentedFileConfig.builder(path, HoconFormat.instance())
                    .preserveInsertionOrder().sync().build();
            case JSON5 -> CommentedFileConfig.builder(path, Json5Format.instance())
                    .preserveInsertionOrder().sync().build();
            case JSON -> FileConfig.builder(path, JsonFormat.fancyInstance())
                    .preserveInsertionOrder().sync().build();
        };
    }

    /**
     * Performs the initial load of the config file: creates it from defaults if absent, otherwise
     * reads and corrects it, then rewrites it so the on-disk file matches the current spec.
     */
    void load() {
        if (!shouldLoad()) return;

        ensureDirectory(configPath());
        if (fileConfig == null) {
            fileConfig = openFileConfig();
        }
        readAndRewrite(true);
    }

    /**
     * Re-reads the config file from disk, corrects any invalid entries, and rewrites the file.
     *
     * <p>Called by {@link ConfigSpec#reload()}, which subsequently fires the reload listeners.
     */
    void reload() {
        if (!shouldLoad()) return;
        if (fileConfig == null) {
            load();
            return;
        }
        readAndRewrite(true);
    }

    /**
     * Re-reads the config file and updates in-memory values, but only rewrites the file if at
     * least one value was invalid and had to be corrected.
     *
     * <p>Used exclusively by the file-watcher path. Skipping the unconditional rewrite breaks
     * the feedback loop in which {@link #writeToDisk()} would generate a new {@code ENTRY_MODIFY}
     * event and cause the watcher to fire again indefinitely.
     */
    void reloadQuiet() {
        if (!shouldLoad()) return;
        if (fileConfig == null) {
            load();
            return;
        }
        readAndRewrite(false);
    }

    /**
     * The shared body of {@link #load()}, {@link #reload()}, and {@link #reloadQuiet()}.
     *
     * <p>Reads the file into the entries and writes it back out. A file that does not exist is
     * created from defaults. A file that cannot be parsed is backed up and replaced with the
     * defaults rather than propagating the exception, which at {@link #load()} time would abort
     * mod initialisation and take the game down with it.
     *
     * @param alwaysRewrite {@code true} to rewrite the file even when every value was already
     *                      valid — this is what prunes removed keys, adds new keys, and refreshes
     *                      comments. {@code false} rewrites only after a correction, so that the
     *                      file watcher does not retrigger itself on its own write.
     */
    private void readAndRewrite(boolean alwaysRewrite) {
        Path path = configPath();
        boolean corrected;

        if (!Files.exists(path)) {
            applyDefaults();
            corrected = true;
        } else {
            try {
                fileConfig.load();
            } catch (ParsingException e) {
                backupCorruptFile(path, e);
                applyDefaults();
                fileConfig.clear();
                writeToDisk();
                return;
            }
            corrected = correctAndFill();
            fileConfig.clear();
        }

        if (alwaysRewrite || corrected) {
            writeToDisk();
        }
    }

    /**
     * Moves an unparseable config file aside so that it is not silently destroyed by the
     * regenerated defaults that replace it.
     */
    private void backupCorruptFile(Path path, ParsingException cause) {
        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        try {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            Constants.LOG.error("Config '{}' could not be parsed ({}). The broken file has been moved to '{}' "
                            + "and regenerated with default values.",
                    path.getFileName(), cause.getMessage(), backup.getFileName());
        } catch (IOException io) {
            Constants.LOG.error("Config '{}' could not be parsed ({}) and the broken file could not be backed up ({}). "
                            + "It will be overwritten with default values.",
                    path.getFileName(), cause.getMessage(), io.getMessage());
        }
    }

    /**
     * Writes the current in-memory entry values to disk, overwriting any existing file.
     *
     * <p>If the Night-Config handle has not been opened yet (e.g. because {@link #load()} was
     * skipped on this side), it is opened here before writing. Unlike the read paths, this method
     * does not clear the handle first: it is either fresh, or was already canonicalised by the
     * last load/reload, so stale keys cannot be reintroduced.
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
     * <p>{@link ConfigSide#CLIENT} configs are skipped on a dedicated server. All other configs
     * always load.
     */
    private boolean shouldLoad() {
        if (spec.getSide() == ConfigSide.CLIENT && !Services.PLATFORM.isPhysicalClient()) {
            Constants.LOG.debug("Skipping CLIENT config '{}' on dedicated server", spec.getName());
            return false;
        }
        return true;
    }

    /**
     * Serialises all current entry values and their comments into the Night-Config object and
     * flushes it to disk.
     *
     * <p>Writes only the entries declared in the spec — callers clear {@link #fileConfig} first
     * when stale keys must be removed. The file-level header comment (if any) is written at the
     * top of the file, followed by per-key comments above each entry. JSON is the one format that
     * cannot carry comments; there the handle is not a {@link CommentedConfig} and comments are
     * skipped.
     */
    private void writeToDisk() {
        CommentedConfig commented = fileConfig instanceof CommentedConfig c ? c : null;
        if (commented != null && !headerComment.isEmpty()) {
            commented.setComment("", " " + headerComment);
        }
        for (ConfigEntry<?> entry : spec.entryMap().values()) {
            Optional<Object> encoded = entry.encode();
            if (encoded.isEmpty()) {
                continue; // codec-backed entry whose value failed to encode; encode() already warned
            }
            Object writable = toWritable(encoded.get());
            if (spec.getFormat() == ConfigFormat.HOCON) {
                warnAboutHoconDottedKeys(entry.getPath(), writable);
            }
            fileConfig.set(entry.getPath(), writable);
            if (commented != null && !entry.getComment().isEmpty()) {
                commented.setComment(entry.getPath(), " " + entry.getComment());
            }
        }
        try {
            fileConfig.save();
            prependHeaderComment();
        } catch (WritingException e) {
            Constants.LOG.error("Failed to write config '{}': {}", spec.getName(), e.getMessage(), e);
        }
    }

    /**
     * Writes the file-level header comment above the first key, for the formats whose writer cannot
     * do it itself.
     *
     * <p>Night-Config's TOML and HOCON writers only emit comments attached to a key that actually
     * exists; a comment set on the root path is attached to a key named {@code ""}, which is never
     * written, so the header is silently dropped. There is no root-comment concept to hook into, so
     * the lines are prepended to the finished file instead.
     *
     * <p>{@link ConfigFormat#JSON5} needs no help — {@link Json5Writer} reads the root comment and
     * places it before the opening brace. {@link ConfigFormat#JSON} cannot carry comments at all.
     */
    private void prependHeaderComment() {
        if (headerComment.isEmpty()) return;
        if (spec.getFormat() != ConfigFormat.TOML && spec.getFormat() != ConfigFormat.HOCON) return;

        Path path = configPath();
        try {
            StringBuilder header = new StringBuilder();
            for (String line : headerComment.split("\r?\n", -1)) {
                header.append("# ").append(line).append(System.lineSeparator());
            }
            header.append(System.lineSeparator());
            Files.writeString(path, header + Files.readString(path));
        } catch (IOException e) {
            Constants.LOG.warn("Could not write the header comment of config '{}': {}",
                    spec.getName(), e.getMessage());
        }
    }

    /**
     * Warns about map keys that HOCON cannot round-trip.
     *
     * <p>HOCON reads an unquoted {@code .} in a key as a path separator, and Night-Config's HOCON
     * writer only quotes keys containing one of a fixed set of special characters — a set that
     * does <em>not</em> include {@code .}. A key such as {@code mymod.enabled} is therefore
     * written bare, re-parsed as a nested object {@code mymod { enabled = ... }} on the next load,
     * and then fails validation — silently resetting the whole entry (and every other key in the
     * same map) to its default.
     *
     * <p>The gap is in the upstream writer, so the key cannot be quoted from here. Warning loudly
     * beats losing the value quietly. Keys containing {@code :} (such as {@code minecraft:stone})
     * are fine — the writer does quote those.
     */
    private static void warnAboutHoconDottedKeys(String entryPath, Object value) {
        if (value instanceof UnmodifiableConfig config) {
            for (UnmodifiableConfig.Entry child : config.entrySet()) {
                if (child.getKey().indexOf('.') >= 0) {
                    Constants.LOG.warn("'{}': the map key '{}' contains a '.', which HOCON reads as a path "
                                    + "separator. This entry will reset to its default on the next load. "
                                    + "Use TOML, JSON, or JSON5 for maps with dotted keys.",
                            entryPath, child.getKey());
                }
                warnAboutHoconDottedKeys(entryPath, child.getValue());
            }
        } else if (value instanceof List<?> list) {
            for (Object element : list) {
                warnAboutHoconDottedKeys(entryPath, element);
            }
        }
    }

    /**
     * Resets every entry's in-memory value to its default, so that a freshly written file always
     * starts from a clean, known state.
     */
    private void applyDefaults() {
        spec.entryMap().values().forEach(ConfigEntry::reset);
    }

    /**
     * Reads values from the currently loaded Night-Config object into the {@link ConfigEntry}
     * objects, correcting invalid or missing values to their defaults.
     *
     * <p>For each spec entry:
     * <ul>
     *   <li>If the key is absent from the file (a newly added field), the entry resets to its
     *       default and the file is flagged for rewriting so the key appears on disk.</li>
     *   <li>If the value fails {@link ConfigEntry#validate(Object)}, a {@code WARN} is logged and
     *       the entry resets to its default.</li>
     *   <li>Otherwise the in-memory value is updated from the file.</li>
     * </ul>
     *
     * <p>Keys present in the file but absent from the spec (removed fields) are ignored here;
     * the caller clears {@link #fileConfig} before {@link #writeToDisk()}, which drops them.
     *
     * @return {@code true} if any entry had to be corrected or was missing from the file, meaning
     *         the on-disk file is out of sync with the spec and should be rewritten
     */
    private boolean correctAndFill() {
        boolean corrected = false;
        for (Map.Entry<String, ConfigEntry<?>> e : spec.entryMap().entrySet()) {
            String path = e.getKey();
            ConfigEntry<?> entry = e.getValue();
            Object raw = toJava(fileConfig.get(path));
            if (raw == null) {
                entry.reset();
                corrected = true;
            } else if (!entry.validate(raw)) {
                Constants.LOG.warn("'{}': invalid value '{}', resetting to default '{}'",
                        path, raw, entry.getDefaultValue());
                entry.reset();
                corrected = true;
            } else {
                entry.setRaw(raw);
            }
        }
        return corrected;
    }

    /**
     * Recursively converts a value read from Night-Config into a pure-Java tree.
     *
     * <p>Night-Config models TOML tables / HOCON objects / JSON objects as
     * {@link UnmodifiableConfig}, which neither {@link ConfigEntry}'s validators nor
     * {@link JavaOps} understand. This flattens the whole tree to
     * {@link LinkedHashMap} (preserving key order), {@link ArrayList}, and boxed primitives —
     * the exact shape {@code JavaOps} decodes from, and the shape {@code defineMap} /
     * {@code defineList} entries expect.
     *
     * <p>Recursion matters: a codec for a record containing a nested record produces a config
     * inside a config, and only the outermost level would otherwise be converted.
     *
     * @param value a value straight out of {@link #fileConfig}, possibly {@code null}
     * @return the equivalent plain-Java value, or {@code null} if {@code value} was {@code null}
     */
    private static Object toJava(Object value) {
        if (value instanceof UnmodifiableConfig config) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            for (UnmodifiableConfig.Entry entry : config.entrySet()) {
                map.put(entry.getKey(), toJava(entry.getValue()));
            }
            return map;
        }
        if (value instanceof List<?> list) {
            List<Object> converted = new ArrayList<>(list.size());
            for (Object element : list) {
                converted.add(toJava(element));
            }
            return converted;
        }
        return value;
    }

    /**
     * Recursively converts a pure-Java value into types Night-Config's writers can serialize.
     *
     * <p>This is the inverse of {@link #toJava(Object)}. It is applied to the output of
     * {@link ConfigEntry#encode()}, which for codec-backed entries is already a tree of
     * {@link Map} / {@link List} / boxed primitives produced by
     * {@link JavaOps}.
     *
     * <ul>
     *   <li>{@link Enum} constants are stored as their {@link Enum#name() name} string.</li>
     *   <li>{@link Map} values become an in-memory {@link CommentedConfig} sub-table, because the
     *       writers cannot serialize a raw Java {@code Map}. Values are converted recursively so
     *       that nested objects (a record inside a record) survive.</li>
     *   <li>{@link List} elements are converted recursively, so a list of objects becomes a list
     *       of config sub-tables (a TOML array-of-tables / JSON array of objects).</li>
     *   <li>All other values are returned unchanged.</li>
     * </ul>
     */
    private static Object toWritable(Object value) {
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Map<?, ?> map) {
            CommentedConfig sub = CommentedConfig.inMemory();
            // Pass the key as a single-element path: the String overload of set() splits on '.',
            // which would explode a key like "mymod.item" into nested tables.
            map.forEach((k, v) -> sub.set(List.of(String.valueOf(k)), toWritable(v)));
            return sub;
        }
        if (value instanceof List<?> list) {
            List<Object> converted = new ArrayList<>(list.size());
            for (Object element : list) {
                converted.add(toWritable(element));
            }
            return converted;
        }
        return value;
    }

    /**
     * Starts a daemon {@link WatchService} thread for the config file if
     * {@code watchForChanges} was enabled on the builder.
     *
     * <p>The thread polls the config directory every 2 seconds. When it detects that this
     * specific file was modified, it sleeps 150 ms (debounce — editors often write in two
     * steps) then re-reads values and fires the spec's reload listeners.
     *
     * <p>Does nothing if the config should not load on the current side (e.g. a CLIENT config on
     * a dedicated server), or if a watcher has already been started for this spec.
     */
    void startWatcherIfEnabled() {
        if (!watchForChanges || !shouldLoad()) return;
        if (!watcherStarted.compareAndSet(false, true)) return;

        Path configFile = configPath();
        Thread thread = new Thread(() -> watchLoop(configFile), "CoolerConfig-Watcher-" + spec.getName());
        thread.setDaemon(true);
        thread.start();
    }

    private void watchLoop(Path configFile) {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            configFile.getParent().register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);
            Constants.LOG.debug("Watching '{}' for changes", configFile.getFileName());

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
                    Thread.sleep(150); // debounce: editors commonly write in two steps
                    try {
                        spec.reloadWatch();
                    } catch (Exception e) {
                        Constants.LOG.warn("Failed to reload '{}', keeping the values already in memory: {}",
                                spec.getName(), e.getMessage());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Constants.LOG.warn("File watcher for '{}' stopped: {}", spec.getName(), e.getMessage());
        }
    }

    /**
     * Creates the parent directory of {@code filePath} and all missing ancestors.
     *
     * @throws IllegalStateException wrapping any {@link IOException} if directory creation fails
     */
    private static void ensureDirectory(Path filePath) {
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create config directory: " + filePath.getParent(), e);
        }
    }
}
