package com.coolerpromc.coolerconfig.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.ConfigFormat;
import com.electronwill.nightconfig.core.io.ConfigParser;
import com.electronwill.nightconfig.core.io.ConfigWriter;
import com.electronwill.nightconfig.core.utils.WriterSupplier;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A Night-Config {@link ConfigFormat} implementation for JSON5.
 *
 * <p>Night-Config has no built-in JSON5 back-end, so CoolerConfig provides one here. JSON5
 * (<a href="https://json5.org/">json5.org</a>) is a strict superset of JSON that, most usefully
 * for a config file, permits comments. This format therefore reports {@link #supportsComments()
 * supportsComments() == true}, which lets CoolerConfig write the same per-entry and header
 * comments it writes for TOML and HOCON — something plain {@link ConfigFormat#JSON} cannot do.
 *
 * <p>The format is wired into the standard
 * {@link com.electronwill.nightconfig.core.file.CommentedFileConfig CommentedFileConfig}
 * machinery via {@link #instance()}; see
 * {@link ConfigManager#openFileConfig()}. Reading is handled by {@link Json5Parser}
 * (a lenient JSON5 parser) and writing by {@link Json5Writer} (pretty-printed JSON5 with
 * {@code //} comments).
 */
final class Json5Format implements ConfigFormat<CommentedConfig> {

    private static final Json5Format INSTANCE = new Json5Format();

    /**
     * Returns the singleton instance of this format.
     */
    static Json5Format instance() {
        return INSTANCE;
    }

    private Json5Format() {}

    @Override
    public ConfigWriter createWriter() {
        return new Json5Writer();
    }

    @Override
    public ConfigParser<CommentedConfig> createParser() {
        return new Json5Parser(this);
    }

    @Override
    public CommentedConfig createConfig(Supplier<Map<String, Object>> mapCreator) {
        return CommentedConfig.of(mapCreator, this);
    }

    @Override
    public boolean supportsComments() {
        return true;
    }

    /**
     * Writes {@code {}} into a newly created, still-empty config file, so that the first read of
     * a fresh file parses as an empty object rather than failing on a zero-byte input.
     *
     * <p>Night-Config 3.6.x hands over a {@link WriterSupplier} rather than an open
     * {@link Writer}, which makes closing the writer this method's job — mirroring
     * {@code JsonFormat}'s own implementation.
     */
    @Override
    public void initEmptyFile(WriterSupplier writerSupplier) throws IOException {
        try (Writer writer = writerSupplier.get()) {
            writer.write("{}");
        }
    }
}