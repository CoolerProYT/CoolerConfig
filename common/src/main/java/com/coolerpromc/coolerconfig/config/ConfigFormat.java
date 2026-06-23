package com.coolerpromc.coolerconfig.config;

/**
 * The file format used to serialise a {@link ConfigSpec}.
 *
 * <p>Pass one of these constants to {@link ConfigSpec#builder(String, ConfigFormat)} when
 * creating a new config. The format controls both the file extension CoolerConfig writes and the
 * back-end used to read and write it.
 *
 * <table border="1">
 *   <caption>Format characteristics</caption>
 *   <tr><th>Constant</th><th>Extension</th><th>Comments written</th><th>Comment syntax</th></tr>
 *   <tr><td>{@link #TOML}</td> <td>{@code .toml}</td> <td>Yes</td><td>{@code # comment}</td></tr>
 *   <tr><td>{@link #HOCON}</td><td>{@code .conf}</td> <td>Yes</td><td>{@code # comment}</td></tr>
 *   <tr><td>{@link #JSON}</td> <td>{@code .json}</td> <td>No</td> <td>N/A</td></tr>
 *   <tr><td>{@link #JSON5}</td><td>{@code .json5}</td><td>Yes</td><td>{@code // comment}</td></tr>
 * </table>
 *
 * <p>{@link #TOML} is the conventional choice for a Minecraft mod and the one to reach for unless
 * you have a specific reason not to.
 */
public enum ConfigFormat {

    /**
     * Tom's Obvious Minimal Language ({@code .toml}).
     *
     * <p>Entry comments and the file-level header comment are written as {@code #} lines. This is
     * the format both loaders use for their own configs.
     */
    TOML(".toml"),

    /**
     * Human-Optimized Config Object Notation ({@code .conf}).
     *
     * <p>Uses the Typesafe Config / HOCON back-end provided by Night-Config. HOCON syntax
     * ({@code //} and {@code #} comments, unquoted keys, object merging, etc.) is fully supported
     * on read. CoolerConfig writes per-entry and header comments using the {@code #} prefix.
     *
     * <p><b>Caveat:</b> HOCON cannot round-trip a map <em>key</em> containing a {@code .} — see
     * {@link ConfigBuilder#defineCodec}. Prefer TOML, JSON, or JSON5 if your map keys may contain
     * dots.
     */
    HOCON(".conf"),

    /**
     * JavaScript Object Notation ({@code .json}).
     *
     * <p>Uses Night-Config's JSON back-end with pretty-printing. JSON cannot represent comments,
     * so any comment passed to {@link ConfigBuilder} is silently dropped for this format — which
     * makes it a poor choice for a file a player is expected to edit by hand. Prefer
     * {@link #JSON5} if you want JSON syntax <em>and</em> comments.
     */
    JSON(".json"),

    /**
     * JSON5 ({@code .json5}).
     *
     * <p><a href="https://json5.org/">JSON5</a> is a strict superset of JSON that permits
     * comments, trailing commas, single quotes, and unquoted keys. Night-Config has no built-in
     * JSON5 back-end, so CoolerConfig supplies its own.
     *
     * <p>Unlike {@link #JSON}, this format <b>does</b> write comments: entry comments are written
     * as {@code //} lines above each key, and the file-level header at the top. Output is
     * pretty-printed and remains valid plain JSON; on read, the full range of JSON5 syntax is
     * accepted, so hand-edited files using JSON5 features are parsed correctly.
     */
    JSON5(".json5");

    private final String extension;

    ConfigFormat(String extension) {
        this.extension = extension;
    }

    /**
     * Returns the file extension this format writes, including the leading dot
     * (e.g. {@code ".toml"}).
     *
     * @return the extension; never {@code null}
     */
    public String getExtension() {
        return extension;
    }
}
