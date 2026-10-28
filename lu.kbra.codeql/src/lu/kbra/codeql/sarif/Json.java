package lu.kbra.codeql.sarif;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small, dependency-free JSON reader for CodeQL's SARIF 2.1 output. */
public final class Json {
	private final String input;
	private int pos;

	private Json(final String input) {
		this.input = input;
	}

	public static Object parse(final String source) {
		final Json reader = new Json(source);
		final Object result = reader.value();
		reader.space();
		if (reader.pos != reader.input.length()) {
			reader.fail("Trailing content");
		}
		return result;
	}

	private Object value() {
		this.space();
		if (this.pos >= this.input.length()) {
			throw new IllegalArgumentException("Unexpected end of JSON");
		}
		final char c = this.input.charAt(this.pos);
		switch (c) {
		case '{' -> {
			return this.object();
		}
		case '[' -> {
			return this.array();
		}
		case '"' -> {
			return this.string();
		}
		case 't' -> {
			return this.literal("true", Boolean.TRUE);
		}
		case 'f' -> {
			return this.literal("false", Boolean.FALSE);
		}
		case 'n' -> {
			return this.literal("null", null);
		}
		default -> {
		}
		}
		if (c == '-' || c >= '0' && c <= '9') {
			return this.number();
		}
		throw this.fail("Unexpected character");
	}

	private Map<String, Object> object() {
		final Map<String, Object> result = new LinkedHashMap<>();
		this.pos++;
		this.space();
		if (this.take('}')) {
			return result;
		}
		do {
			this.space();
			if (this.pos >= this.input.length() || this.input.charAt(this.pos) != '"') {
				throw this.fail("Expected object key");
			}
			final String key = this.string();
			this.space();
			this.expect(':');
			result.put(key, this.value());
			this.space();
			if (this.take('}')) {
				return result;
			}
			this.expect(',');
		} while (true);
	}

	private List<Object> array() {
		final List<Object> result = new ArrayList<>();
		this.pos++;
		this.space();
		if (this.take(']')) {
			return result;
		}
		do {
			result.add(this.value());
			this.space();
			if (this.take(']')) {
				return result;
			}
			this.expect(',');
		} while (true);
	}

	private String string() {
		this.expect('"');
		final StringBuilder b = new StringBuilder();
		while (this.pos < this.input.length()) {
			final char c = this.input.charAt(this.pos++);
			if (c == '"') {
				return b.toString();
			}
			if (c == '\\') {
				if (this.pos >= this.input.length()) {
					throw this.fail("Invalid escape");
				}
				final char escaped = this.input.charAt(this.pos++);
				switch (escaped) {
				case '"':
				case '\\':
				case '/':
					b.append(escaped);
					break;
				case 'b':
					b.append('\b');
					break;
				case 'f':
					b.append('\f');
					break;
				case 'n':
					b.append('\n');
					break;
				case 'r':
					b.append('\r');
					break;
				case 't':
					b.append('\t');
					break;
				case 'u':
					if (this.pos + 4 > this.input.length()) {
						throw this.fail("Invalid Unicode escape");
					}
					try {
						b.append((char) Integer.parseInt(this.input.substring(this.pos, this.pos + 4), 16));
					} catch (final NumberFormatException e) {
						throw this.fail("Invalid Unicode escape");
					}
					this.pos += 4;
					break;
				default:
					throw this.fail("Invalid escape");
				}
			} else {
				if (c < 0x20) {
					throw this.fail("Control character in string");
				}
				b.append(c);
			}
		}
		throw this.fail("Unterminated string");
	}

	private Object number() {
		final int from = this.pos;
		if (this.take('-')) {
			/* sign */ }
		this.digits();
		if (this.take('.')) {
			this.digits();
		}
		if (this.take('e') || this.take('E')) {
			if (!this.take('+')) {
				this.take('-');
			}
			this.digits();
		}
		final String number = this.input.substring(from, this.pos);
		try {
			return Double.valueOf(number);
		} catch (final NumberFormatException e) {
			throw this.fail("Invalid number");
		}
	}

	private void digits() {
		final int from = this.pos;
		while (this.pos < this.input.length() && Character.isDigit(this.input.charAt(this.pos))) {
			this.pos++;
		}
		if (from == this.pos) {
			throw this.fail("Expected digit");
		}
	}

	private Object literal(final String word, final Object result) {
		if (!this.input.startsWith(word, this.pos)) {
			throw this.fail("Unexpected literal");
		}
		this.pos += word.length();
		return result;
	}

	private void space() {
		while (this.pos < this.input.length()) {
			final char c = this.input.charAt(this.pos);
			if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
				break;
			}
			this.pos++;
		}
	}

	private boolean take(final char c) {
		if (this.pos < this.input.length() && this.input.charAt(this.pos) == c) {
			this.pos++;
			return true;
		}
		return false;
	}

	private void expect(final char c) {
		if (!this.take(c)) {
			throw this.fail("Expected '" + c + "'");
		}
	}

	private IllegalArgumentException fail(final String text) {
		return new IllegalArgumentException(text + " at character " + this.pos);
	}

	private static final Map<String, Object> EMPTY = Map.of();

	@SuppressWarnings("unchecked")
	public static Map<String, Object> map(final Object value) {
		return value instanceof Map<?, ?> ? (Map<String, Object>) value : Json.EMPTY;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(final Object value) {
		return value instanceof List<?> ? (List<Object>) value : List.of();
	}

	public static String str(final Object value) {
		return value instanceof final String s ? s : "";
	}

	public static int integer(final Object value, final int fallback) {
		return value instanceof final Number n ? n.intValue() : fallback;
	}
}
