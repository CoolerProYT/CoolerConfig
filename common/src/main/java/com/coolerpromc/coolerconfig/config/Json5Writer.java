package com.coolerpromc.coolerconfig.config;

import com.electronwill.nightconfig.core.UnmodifiableCommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.CharacterOutput;
import com.electronwill.nightconfig.core.io.ConfigWriter;
import com.electronwill.nightconfig.core.io.WriterOutput;
import com.electronwill.nightconfig.core.io.WritingException;
import com.electronwill.nightconfig.core.utils.FakeUnmodifiableCommentedConfig;
import com.electronwill.nightconfig.core.utils.StringUtils;

import java.io.Writer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static com.electronwill.nightconfig.core.NullObject.NULL_OBJECT;

/**
 * Writes a Night-Config configuration as pretty-printed <a href="https://json5.org/">JSON5</a>.
 *
 * <p>The output is valid JSON (and therefore valid JSON5) with two additions that JSON5 permits
 * but plain JSON does not:
 * <ul>
 *   <li>A file-level header comment (taken from the config's root comment) written as
 *       {@code //} lines before the opening brace.</li>
 *   <li>Per-entry comments written as {@code //} lines immediately above each key.</li>
 * </ul>
 *
 * <p>Comment handling mirrors {@code HoconWriter}: the comment prefix ({@code //}) is written
 * directly followed by the stored comment text, which CoolerConfig stores with a single leading
 * space (see {@link ConfigManager#writeToDisk()}). Multi-line comments are split into one
 * {@code //} line each. Values, strings, and keys are always emitted in canonical
 * double-quoted JSON form so the file round-trips cleanly through {@link Json5Parser}.
 */
final class Json5Writer implements ConfigWriter {

    private static final char[] NULL_CHARS = {'n', 'u', 'l', 'l'};
    private static final char[] TRUE_CHARS = {'t', 'r', 'u', 'e'};
    private static final char[] FALSE_CHARS = {'f', 'a', 'l', 's', 'e'};
    private static final char[] EMPTY_OBJECT = {'{', '}'}, EMPTY_ARRAY = {'[', ']'};
    private static final char[] COMMENT_PREFIX = {'/', '/'};
    private static final char[] NEWLINE = System.lineSeparator().toCharArray();
    private static final char[] INDENT = {' ', ' '};

    private static final char[] TO_ESCAPE = {'"', '\\', '\n', '\r', '\t', '\b', '\f'};
    private static final char[] ESCAPED = {'"', '\\', 'n', 'r', 't', 'b', 'f'};

    private int indentLevel;

    @Override
    public void write(UnmodifiableConfig config, Writer writer) {
        indentLevel = 0;
        CharacterOutput output = new WriterOutput(writer);
        UnmodifiableCommentedConfig commented = config instanceof UnmodifiableCommentedConfig
                ? (UnmodifiableCommentedConfig) config
                : new FakeUnmodifiableCommentedConfig(config);

        String header = commented.getComment("");
        if (header != null && !header.isEmpty()) {
            writeComment(header, output);
        }
        writeObject(commented, output);
        output.write(NEWLINE);
    }

    private void writeObject(UnmodifiableCommentedConfig config, CharacterOutput output) {
        if (config.isEmpty()) {
            output.write(EMPTY_OBJECT);
            return;
        }
        output.write('{');
        output.write(NEWLINE);
        indentLevel++;
        Iterator<? extends UnmodifiableCommentedConfig.Entry> it = config.entrySet().iterator();
        while (it.hasNext()) {
            UnmodifiableCommentedConfig.Entry entry = it.next();
            String comment = entry.getComment();
            if (comment != null && !comment.isEmpty()) {
                writeComment(comment, output);
            }
            writeIndent(output);
            writeString(entry.getKey(), output);
            output.write(':');
            output.write(' ');
            writeValue(entry.getValue(), output);
            if (it.hasNext()) {
                output.write(',');
            }
            output.write(NEWLINE);
        }
        indentLevel--;
        writeIndent(output);
        output.write('}');
    }

    private void writeValue(Object v, CharacterOutput output) {
        if (v == null || v == NULL_OBJECT) {
            output.write(NULL_CHARS);
        } else if (v instanceof CharSequence) {
            writeString((CharSequence) v, output);
        } else if (v instanceof Enum) {
            writeString(((Enum<?>) v).name(), output);
        } else if (v instanceof Number) {
            output.write(v.toString());
        } else if (v instanceof Boolean) {
            output.write((boolean) v ? TRUE_CHARS : FALSE_CHARS);
        } else if (v instanceof UnmodifiableCommentedConfig) {
            writeObject((UnmodifiableCommentedConfig) v, output);
        } else if (v instanceof UnmodifiableConfig) {
            writeObject(new FakeUnmodifiableCommentedConfig((UnmodifiableConfig) v), output);
        } else if (v instanceof Collection) {
            writeArray((Collection<?>) v, output);
        } else if (v instanceof Object[]) {
            writeArray(Arrays.asList((Object[]) v), output);
        } else {
            throw new WritingException("Unsupported value type: " + v.getClass());
        }
    }

    private void writeArray(Collection<?> collection, CharacterOutput output) {
        if (collection.isEmpty()) {
            output.write(EMPTY_ARRAY);
            return;
        }
        output.write('[');
        output.write(NEWLINE);
        indentLevel++;
        Iterator<?> it = collection.iterator();
        while (it.hasNext()) {
            writeIndent(output);
            writeValue(it.next(), output);
            if (it.hasNext()) {
                output.write(',');
            }
            output.write(NEWLINE);
        }
        indentLevel--;
        writeIndent(output);
        output.write(']');
    }

    private void writeString(CharSequence s, CharacterOutput output) {
        output.write('"');
        int length = s.length();
        for (int i = 0; i < length; i++) {
            char c = s.charAt(i);
            int escapeIndex = indexOf(TO_ESCAPE, c);
            if (escapeIndex != -1) {
                output.write('\\');
                output.write(ESCAPED[escapeIndex]);
            } else if (c < 0x20) {
                output.write(unicodeEscape(c));
            } else {
                output.write(c);
            }
        }
        output.write('"');
    }

    private void writeComment(String comment, CharacterOutput output) {
        for (String line : StringUtils.splitLines(comment)) {
            writeIndent(output);
            output.write(COMMENT_PREFIX);
            output.write(line);
            output.write(NEWLINE);
        }
    }

    private void writeIndent(CharacterOutput output) {
        for (int i = 0; i < indentLevel; i++) {
            output.write(INDENT);
        }
    }

    private static char[] unicodeEscape(char c) {
        return new char[] {'\\', 'u',
                hexDigit((c >> 12) & 0xF), hexDigit((c >> 8) & 0xF),
                hexDigit((c >> 4) & 0xF), hexDigit(c & 0xF)};
    }

    private static char hexDigit(int nibble) {
        return (char) (nibble < 10 ? '0' + nibble : 'a' + (nibble - 10));
    }

    private static int indexOf(char[] array, char c) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
