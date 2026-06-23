package com.coolerpromc.coolerconfig.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.ConfigFormat;
import com.electronwill.nightconfig.core.io.CharacterInput;
import com.electronwill.nightconfig.core.io.ConfigParser;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.core.io.ReaderInput;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A lenient <a href="https://json5.org/">JSON5</a> parser that produces Night-Config
 * {@link CommentedConfig} objects.
 *
 * <p>Night-Config ships only a strict JSON parser, so this class implements the JSON5 superset
 * directly on top of the {@link CharacterInput} abstraction. Comments are recognised and
 * skipped (their text is not retained — CoolerConfig always rewrites comments from the spec),
 * and the following JSON5 relaxations over plain JSON are accepted:
 *
 * <ul>
 *   <li>Line ({@code //}) and block ({@code /* *}{@code /}) comments anywhere whitespace is allowed.</li>
 *   <li>Trailing commas in objects and arrays.</li>
 *   <li>Single-quoted as well as double-quoted strings.</li>
 *   <li>Unquoted object keys (ASCII identifier characters plus {@code _} and {@code $}).</li>
 *   <li>Hexadecimal integers ({@code 0x1F}), a leading {@code +}, leading/trailing decimal
 *       points, and the literals {@code Infinity}, {@code -Infinity} and {@code NaN}.</li>
 *   <li>Extra string escapes ({@code \'}, {@code \v}, {@code \0}, {@code \xHH}) and line
 *       continuations (a backslash immediately before a newline).</li>
 * </ul>
 *
 * <p>Numbers are coerced to the same Java types as Night-Config's own JSON parser: an integer
 * that fits in an {@code int} becomes an {@link Integer}, a larger integer becomes a
 * {@link Long}, and anything with a fractional/exponent part (or {@code Infinity}/{@code NaN})
 * becomes a {@link Double}. This keeps {@link ConfigEntry} coercion identical across the JSON
 * and JSON5 formats.
 *
 * <p>Object keys are stored as single path elements (dots in a key are <em>not</em> treated as
 * nesting separators), matching Night-Config's JSON parser; nesting comes solely from nested
 * objects in the document.
 */
final class Json5Parser implements ConfigParser<CommentedConfig> {

    private final Json5Format format;

    /** Accumulates comment text encountered since the last consumed key, joined by newlines. */
    private final StringBuilder pendingComment = new StringBuilder();

    Json5Parser(Json5Format format) {
        this.format = format;
    }

    @Override
    public ConfigFormat<CommentedConfig> getFormat() {
        return format;
    }

    @Override
    public CommentedConfig parse(Reader reader) {
        CommentedConfig config = format.createConfig();
        parse(reader, config, ParsingMode.MERGE);
        return config;
    }

    @Override
    public void parse(Reader reader, Config destination, ParsingMode parsingMode) {
        pendingComment.setLength(0);
        CharacterInput input = new ReaderInput(reader);
        int first = nextSignificant(input);
        if (first == -1) {
            // Empty file (e.g. user cleared it): leave the destination empty so that
            // correctAndFill resets every entry to its default and rewrites a fresh file.
            return;
        }
        if (first != '{') {
            throw new ParsingException("Invalid first character for a JSON5 object: " + (char) first);
        }
        parsingMode.prepareParsing(destination);
        // Comments before the opening brace form the file-level header comment.
        String header = takeComment();
        if (header != null && destination instanceof CommentedConfig) {
            ((CommentedConfig) destination).setComment(Collections.singletonList(""), header);
        }
        parseObject(input, destination, parsingMode);
    }

    /**
     * Parses the body of an object, assuming the opening {@code '{'} has already been consumed.
     */
    private void parseObject(CharacterInput input, Config config, ParsingMode parsingMode) {
        int c = nextSignificant(input);
        if (c == '}') {
            pendingComment.setLength(0); // drop any trailing comments inside the object
            return; // empty object
        }
        while (true) {
            if (c == -1) {
                throw new ParsingException("Unexpected end of input inside an object");
            }
            String comment = takeComment(); // comments above this key belong to it
            String key = parseKey(input, (char) c);
            int sep = nextSignificant(input);
            if (sep != ':') {
                throw new ParsingException("Invalid key-value separator: " + asString(sep));
            }
            int valueFirst = nextSignificant(input);
            if (valueFirst == -1) {
                throw new ParsingException("Unexpected end of input, expected a value");
            }
            Object value = parseValue(input, (char) valueFirst, parsingMode, config);
            parsingMode.put(config, Collections.singletonList(key), value);
            if (comment != null && config instanceof CommentedConfig) {
                ((CommentedConfig) config).setComment(Collections.singletonList(key), comment);
            }

            int after = nextSignificant(input);
            if (after == '}') {
                pendingComment.setLength(0);
                return;
            }
            if (after != ',') {
                throw new ParsingException("Invalid value separator: " + asString(after));
            }
            c = nextSignificant(input); // allows a trailing comma (next may be '}')
            if (c == '}') {
                pendingComment.setLength(0);
                return;
            }
        }
    }

    /**
     * Parses an array body, assuming the opening {@code '['} has already been consumed.
     */
    private List<Object> parseArray(CharacterInput input, ParsingMode parsingMode, Config parent) {
        List<Object> list = new ArrayList<>();
        while (true) {
            int c = nextSignificant(input);
            if (c == ']') {
                pendingComment.setLength(0);
                return list; // empty array or trailing comma
            }
            if (c == -1) {
                throw new ParsingException("Unexpected end of input inside an array");
            }
            list.add(parseValue(input, (char) c, parsingMode, parent));
            int after = nextSignificant(input);
            if (after == ']') {
                pendingComment.setLength(0);
                return list;
            }
            if (after != ',') {
                throw new ParsingException("Invalid value separator: " + asString(after));
            }
        }
    }

    private Object parseValue(CharacterInput input, char first, ParsingMode parsingMode, Config parent) {
        switch (first) {
            case '{':
                Config sub = parent.createSubConfig();
                parseObject(input, sub, parsingMode);
                return sub;
            case '[':
                return parseArray(input, parsingMode, parent);
            case '"':
            case '\'':
                return parseString(input, first);
            default:
                return parseLiteral(input, first);
        }
    }

    /**
     * Parses an object key — either a quoted string or an unquoted identifier.
     */
    private String parseKey(CharacterInput input, char first) {
        if (first == '"' || first == '\'') {
            return parseString(input, first);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(first);
        while (true) {
            int r = input.read();
            if (r == -1) {
                break;
            }
            char ch = (char) r;
            if (isIdentifierPart(ch)) {
                sb.append(ch);
            } else {
                input.pushBack(ch);
                break;
            }
        }
        return sb.toString();
    }

    private String parseString(CharacterInput input, char quote) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = input.readChar();
            if (c == quote) {
                return sb.toString();
            }
            if (c == '\\') {
                appendEscape(input, sb);
            } else {
                sb.append(c);
            }
        }
    }

    private void appendEscape(CharacterInput input, StringBuilder sb) {
        char e = input.readChar();
        switch (e) {
            case '"':
            case '\'':
            case '\\':
            case '/':
                sb.append(e);
                break;
            case 'b': sb.append('\b'); break;
            case 'f': sb.append('\f'); break;
            case 'n': sb.append('\n'); break;
            case 'r': sb.append('\r'); break;
            case 't': sb.append('\t'); break;
            case 'v': sb.append('\u000B'); break;
            case '0': sb.append('\0'); break;
            case 'x': sb.append((char) Integer.parseInt(readChars(input, 2), 16)); break;
            case 'u': sb.append((char) Integer.parseInt(readChars(input, 4), 16)); break;
            case '\r': // line continuation: swallow an optional following '\n'
                if (input.peek() == '\n') {
                    input.read();
                }
                break;
            case '\n': // line continuation
                break;
            default:
                sb.append(e); // JSON5 allows escaping any character
        }
    }

    /**
     * Parses a non-quoted literal value: {@code true}, {@code false}, {@code null}, or a number.
     * The {@code first} character has already been read.
     */
    private Object parseLiteral(CharacterInput input, char first) {
        StringBuilder sb = new StringBuilder();
        sb.append(first);
        while (true) {
            int r = input.peek();
            if (r == -1) {
                break;
            }
            char ch = (char) r;
            if (isJson5Whitespace(ch) || ch == ',' || ch == '}' || ch == ']' || ch == '/') {
                break;
            }
            input.read();
            sb.append(ch);
        }
        String token = sb.toString();
        switch (token) {
            case "true":  return Boolean.TRUE;
            case "false": return Boolean.FALSE;
            case "null":  return null;
            default:      return parseNumber(token);
        }
    }

    private Object parseNumber(String token) {
        String s = token;
        boolean negative = false;
        if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
            negative = s.charAt(0) == '-';
            s = s.substring(1);
        }
        try {
            if (s.equals("Infinity")) {
                return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            if (s.equals("NaN")) {
                return Double.NaN;
            }
            if (s.startsWith("0x") || s.startsWith("0X")) {
                long hex = Long.parseLong(s.substring(2), 16);
                return narrow(negative ? -hex : hex);
            }
            if (s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0) {
                double d = Double.parseDouble(token);
                return d;
            }
            long l = Long.parseLong(token);
            return narrow(l);
        } catch (NumberFormatException ex) {
            throw new ParsingException("Invalid JSON5 value: " + token);
        }
    }

    /**
     * Returns an {@link Integer} when the value fits, otherwise a {@link Long} — matching the
     * behaviour of Night-Config's JSON parser.
     */
    private static Object narrow(long l) {
        int small = (int) l;
        return (l == small) ? (Object) small : (Object) l;
    }

    /**
     * Reads the next significant character, skipping whitespace and both styles of comment.
     * Comment text is accumulated into {@link #pendingComment}.
     *
     * @return the character code, or {@code -1} at end of input
     */
    private int nextSignificant(CharacterInput input) {
        while (true) {
            int r = input.read();
            if (r == -1) {
                return -1;
            }
            char c = (char) r;
            if (isJson5Whitespace(c)) {
                continue;
            }
            if (c == '/') {
                int n = input.read();
                if (n == '/') {
                    appendComment(collectLineComment(input));
                    continue;
                }
                if (n == '*') {
                    appendComment(collectBlockComment(input));
                    continue;
                }
                throw new ParsingException("Unexpected '/' outside of a comment");
            }
            return r;
        }
    }

    private String collectLineComment(CharacterInput input) {
        StringBuilder sb = new StringBuilder();
        int r;
        while ((r = input.read()) != -1) {
            if (r == '\n' || r == '\r') {
                break;
            }
            sb.append((char) r);
        }
        return sb.toString();
    }

    private String collectBlockComment(CharacterInput input) {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        int r;
        while ((r = input.read()) != -1) {
            if (prev == '*' && r == '/') {
                sb.setLength(sb.length() - 1); // drop the trailing '*'
                return sb.toString();
            }
            sb.append((char) r);
            prev = r;
        }
        throw new ParsingException("Unterminated block comment");
    }

    /** Appends one comment line/block to the pending buffer, separating entries with newlines. */
    private void appendComment(String text) {
        if (pendingComment.length() > 0) {
            pendingComment.append('\n');
        }
        pendingComment.append(text);
    }

    /** Returns the accumulated comment (clearing it), or {@code null} if none. */
    private String takeComment() {
        if (pendingComment.length() == 0) {
            return null;
        }
        String s = pendingComment.toString();
        pendingComment.setLength(0);
        return s;
    }

    private static String readChars(CharacterInput input, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(input.readChar());
        }
        return sb.toString();
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean isJson5Whitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f'
                || c == '\u000B' || c == '\u00A0' || c == '\uFEFF' || Character.isWhitespace(c);
    }

    private static String asString(int c) {
        return c == -1 ? "<end of input>" : String.valueOf((char) c);
    }
}