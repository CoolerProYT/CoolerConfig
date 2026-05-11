package com.coolerpromc.coolerconfig.config;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry of every {@link ConfigSpec} created through CoolerConfig.
 *
 * <p>Specs register themselves automatically when {@link ConfigBuilder#build()} is called —
 * there is no need to call any method on this class explicitly. The registry is used by
 * CoolerConfig's built-in lifecycle hooks to trigger bulk reloads at the right moment in
 * the game's startup sequence.
 *
 * <h2>Automatic reloads</h2>
 * CoolerConfig hooks into both loaders' startup events and calls the appropriate reload
 * method on your behalf:
 * <ul>
 *   <li>{@link #reloadForServer()} is called on {@code SERVER_STARTING} (Fabric) /
 *       {@code ServerStartingEvent} (NeoForge).</li>
 *   <li>{@link #reloadForClient()} is called on {@code CLIENT_STARTED} (Fabric) /
 *       {@code FMLClientSetupEvent} (NeoForge).</li>
 * </ul>
 *
 * <h2>Manual use</h2>
 * You can call the reload methods yourself — for example from a {@code /reload} command
 * or a dev-mode keybind — without conflicting with the automatic hooks. You can also
 * iterate over all registered specs via {@link #getAll()} to build tooling (config GUI,
 * debug overlay, etc.).
 *
 * <h2>Thread safety</h2>
 * The internal spec list is a {@link CopyOnWriteArrayList}; concurrent reads are safe.
 * {@link #reloadForServer()} and {@link #reloadForClient()} are not synchronised with
 * respect to each other — call them from the main thread.
 */
public final class ConfigRegistry {

    private static final List<ConfigSpec> SPECS = new CopyOnWriteArrayList<>();

    /** Utility class — not instantiable. */
    private ConfigRegistry() {}

    /**
     * Adds {@code spec} to the registry. Called automatically by the {@link ConfigSpec}
     * constructor; there is no public equivalent.
     *
     * @param spec the spec to register; must not be {@code null}
     */
    static void register(ConfigSpec spec) {
        SPECS.add(spec);
    }

    /**
     * Returns an unmodifiable snapshot of all currently registered specs, in the order
     * they were built.
     *
     * @return an unmodifiable list; never {@code null}
     */
    public static List<ConfigSpec> getAll() {
        return Collections.unmodifiableList(SPECS);
    }

    /**
     * Reloads every {@link ConfigSide#SERVER} and {@link ConfigSide#COMMON} spec from disk.
     *
     * <p>{@link ConfigSide#CLIENT} specs are excluded because they are irrelevant to the
     * server process (and are skipped at the I/O level anyway). Each spec's registered
     * {@linkplain ConfigSpec#addReloadListener(Runnable) reload listeners} fire after the
     * file is re-read.
     *
     * <p>Called automatically by CoolerConfig when the server is about to start. Safe to
     * call again manually at any time — e.g. from a command that reloads configs without
     * restarting the server.
     */
    public static void reloadForServer() {
        SPECS.stream()
                .filter(s -> s.getSide() != ConfigSide.CLIENT)
                .forEach(ConfigSpec::reload);
    }

    /**
     * Reloads every {@link ConfigSide#CLIENT} and {@link ConfigSide#COMMON} spec from disk.
     *
     * <p>{@link ConfigSide#SERVER} specs are excluded because their values are controlled by
     * the server process (which handles its own reload via {@link #reloadForServer()}). Each
     * spec's registered {@linkplain ConfigSpec#addReloadListener(Runnable) reload listeners}
     * fire after the file is re-read.
     *
     * <p>Called automatically by CoolerConfig once the game client has finished initialising.
     * Safe to call again manually at any time — e.g. from a keybind or options screen.
     *
     * <p><b>Single-player note:</b> in a single-player world both {@link #reloadForServer()}
     * and {@link #reloadForClient()} are called (integrated server + client both start up),
     * so {@link ConfigSide#COMMON} specs are reloaded twice. This is harmless — the second
     * reload simply re-reads the same, already-correct file.
     */
    public static void reloadForClient() {
        SPECS.stream()
                .filter(s -> s.getSide() != ConfigSide.SERVER)
                .forEach(ConfigSpec::reload);
    }
}
