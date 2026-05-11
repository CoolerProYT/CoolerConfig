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
 *   <tr><th>Constant</th><th>Extension</th><th>Comments written</th><th>Night-Config type</th></tr>
 *   <tr><td>{@link #TOML}</td><td>{@code .toml}</td><td>Yes</td><td>{@code CommentedFileConfig}</td></tr>
 *   <tr><td>{@link #HOCON}</td><td>{@code .conf}</td><td>No</td><td>{@code FileConfig + HoconFormat}</td></tr>
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
     * <p>Uses the Typesafe Config / HOCON back-end provided by Night-Config. HOCON syntax
     * ({@code //} and {@code #} comments, unquoted keys, etc.) is fully supported on read, but
     * CoolerConfig does not write programmatic comments when saving. Existing comments in the
     * file are not preserved across a rewrite.
     */
    HOCON
}
