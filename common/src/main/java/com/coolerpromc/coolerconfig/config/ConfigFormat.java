package com.coolerpromc.coolerconfig.config;

/**
 * The file format used to serialise a {@link ConfigSpec}.
 *
 * <p>Pass one of these constants to {@link ConfigSpec#builder(String, ConfigFormat)} when
 * creating a new config. The format controls both the file extension that CoolerConfig
 * writes and the Night-Config back-end used for reading and writing.
 *
 * <table>
 *   <caption>Format characteristics</caption>
 *   <tr><th>Constant</th><th>Extension</th><th>Comments written</th><th>Comment syntax</th></tr>
 *   <tr><td>{@link #TOML}</td><td>{@code .toml}</td><td>Yes</td><td>{@code # comment}</td></tr>
 *   <tr><td>{@link #HOCON}</td><td>{@code .conf}</td><td>Yes</td><td>{@code # comment}</td></tr>
 * </table>
 */
public enum ConfigFormat {

    /**
     * Tom's Obvious Minimal Language ({@code .toml}).
     *
     * <p>Entry comments set via {@link ConfigBuilder#defineInt(String, int, int, int, String)} (and
     * similar) are written as TOML inline comments above each key. A file-level header comment is
     * written when {@link ConfigBuilder#comment(String)} is called.
     */
    TOML,

    /**
     * Human-Optimized Config Object Notation ({@code .conf}).
     *
     * <p>Uses the Typesafe Config / HOCON back-end provided by Night-Config via
     * {@code HoconFormat.instance()}. HOCON syntax ({@code //} and {@code #} comments,
     * unquoted keys, object merging, etc.) is fully supported on read. CoolerConfig writes
     * per-entry and file-level header comments using the {@code #} prefix, which is valid
     * HOCON syntax. Note that existing hand-written comments in the file are not preserved
     * across a rewrite triggered by validation correction.
     */
    HOCON
}
