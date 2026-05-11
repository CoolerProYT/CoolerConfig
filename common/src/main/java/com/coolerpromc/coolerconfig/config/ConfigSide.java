package com.coolerpromc.coolerconfig.config;

/**
 * Declares which physical game side a {@link ConfigSpec} is loaded on.
 *
 * <p>Set the side of a config via {@link ConfigBuilder#side(ConfigSide)} before calling
 * {@link ConfigBuilder#build()}. The default when the method is not called is {@link #COMMON}.
 *
 * <p><b>Physical side vs. logical side:</b> these constants refer to the <em>physical</em> JVM
 * process, not the logical server/client within a single-player session. In a single-player
 * world the physical client runs both the logical client and the logical (integrated) server,
 * so all three sides load their configs on that one process.
 *
 * <p>The mapping between side and automatic reload trigger is:
 * <ul>
 *   <li>{@link #SERVER} and {@link #COMMON} — reloaded by
 *       {@link ConfigRegistry#reloadForServer()}, which CoolerConfig calls on
 *       {@code SERVER_STARTING} / {@code ServerStartingEvent}.</li>
 *   <li>{@link #CLIENT} and {@link #COMMON} — reloaded by
 *       {@link ConfigRegistry#reloadForClient()}, which CoolerConfig calls on
 *       {@code CLIENT_STARTED} / {@code FMLClientSetupEvent}.</li>
 * </ul>
 */
public enum ConfigSide {

    /**
     * This config is only loaded on the physical client.
     *
     * <p>Typical use cases: HUD layout, keybind preferences, particle density, and other
     * purely visual or input-related settings that have no meaning on a headless server.
     *
     * <p>On a dedicated server this config is silently skipped — its entries retain their
     * default values and no file is created.
     */
    CLIENT,

    /**
     * This config is loaded on both the physical client and the dedicated server.
     *
     * <p>Typical use cases: game-balance values (damage multipliers, spawn rates, loot
     * tables) where the server is authoritative and the file must exist on whichever
     * machine runs the server process.
     *
     * <p>Note that on a remote multiplayer client this config still loads from the
     * <em>client</em> machine's config directory; it is not synced from the server.
     * If you need server-to-client sync, implement that separately.
     */
    SERVER,

    /**
     * This config is loaded on every physical side. This is the default.
     *
     * <p>Suitable for settings that are relevant regardless of whether the process is a
     * client, integrated server, or dedicated server — for example, feature toggles that
     * affect both rendering and server logic, or library-level tunables.
     */
    COMMON
}
