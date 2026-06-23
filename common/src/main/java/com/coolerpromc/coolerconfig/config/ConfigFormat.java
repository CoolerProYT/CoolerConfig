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
 *   <tr><td>{@link #JSON}</td><td>{@code .json}</td><td>No</td><td>N/A</td></tr>
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
    HOCON,

    /**
     * JavaScript Object Notation ({@code .json}).
     *
     * <p>Uses Night-Config's JSON back-end with pretty-printing ({@code JsonFormat.fancyInstance()}).
     * JSON does not support comments; any comments defined via {@link ConfigBuilder} are silently
     * ignored for this format.
     */
    JSON,

    /**
     * JSON5 ({@code .json5}).
     *
     * <p>JSON5 (<a href="https://json5.org/">json5.org</a>) is a strict superset of JSON that
     * permits comments, trailing commas, single quotes, and unquoted keys. Night-Config has no
     * built-in JSON5 back-end, so CoolerConfig supplies its own ({@code Json5Format}).
     *
     * <p>Unlike {@link #JSON}, this format <b>does</b> write comments: entry comments defined via
     * {@link ConfigBuilder#defineInt(String, int, int, int, String)} (and similar) are written as
     * {@code //} lines above each key, and the file-level header from {@link ConfigBuilder#comment(String)}
     * is written at the top. Output is pretty-printed and remains valid plain JSON; on read, the
     * full range of JSON5 syntax is accepted, so hand-edited files using JSON5 features are parsed
     * correctly.
     */
    JSON5
}
