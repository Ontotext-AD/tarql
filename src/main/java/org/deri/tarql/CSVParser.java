package org.deri.tarql;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;

import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.util.iterator.ClosableIterator;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;


/**
 * Parses a CSV file presented as a {@link Reader}, and delivers
 * results as an iterator of {@link Binding}s. Also provides
 * access to the variable names (which may come from row 1 or
 * could be auto-generated).
 * <p>
 * Adds a <code>ROWNUM</code> column with the number of the
 * row.
 */
public class CSVParser implements ClosableIterator<Binding> {

	public static String getColumnName(int i) {
		String var = "";
		do {
			var = alphabet.charAt(i % alphabet.length()) + var;
			i = i / alphabet.length() - 1;
		} while (i >= 0);
		return var;
	}

	private final static String alphabet = "abcdefghijklmnopqrstuvwxyz";

	private final String commentPrefix;
	private final Reader reader;
	private final boolean varsFromHeader;
	private final char delimiter;
	private final Character quote;
	private final Character escape;
	private final List<Var> vars = new ArrayList<Var>();
	private int rownum;

	private Binding binding;
	private CSVReader csv;

	/**
	 * @param reader
	 *            Reader over the contents of a CSV file
	 * @param varsFromHeader
	 *            If true, use values of first row as column names
	 * @param delimiter
	 *            The delimiter character to use for separating entries (e.g., ',' or ';' or '\t'), or <code>null</code> for default
	 * @param quote
	 *            The quote character used to quote values (typically double or single quote), or <code>null</code> for default
	 * @param escape
	 *            The escape character for quotes and delimiters, or <code>null</code> for none 
	 * @throws IOException if an I/O error occurs while reading from the input
	 */
	public CSVParser(Reader reader, boolean varsFromHeader, Character delimiter,
					 Character quote, Character escape) throws IOException {
		this(reader, varsFromHeader, delimiter, quote, escape, null);
	}

	public CSVParser(Reader reader, boolean varsFromHeader, Character delimiter,
					 Character quote, Character escape, String commentPrefix)
			throws IOException {
		this.reader = reader;
		this.varsFromHeader = varsFromHeader;
		this.delimiter = delimiter == null ? ',' : delimiter;
		// OpenCSV insists on a quote character
		this.quote = quote == null ? '\0' : quote;
		// OpenCSV insists on an escape character
		this.escape = escape == null ? '\0' : escape;
		this.commentPrefix = commentPrefix;
		init();

	}

	private Var toVar(String s) {
		if (s == null)
			return null;
		/* SPARQL 1.1 VAR Gramar ?
		 VARNAME	  ::=  	( PN_CHARS_U | [0-9] ) ( PN_CHARS_U | [0-9] | #x00B7 | [#x0300-#x036F] | [#x203F-#x2040] )*
		 PN_CHARS_U	  ::=  	PN_CHARS_BASE | '_'
		 PN_CHARS_BASE	  ::=  	[A-Z] | [a-z] | [#x00C0-#x00D6] | [#x00D8-#x00F6] | [#x00F8-#x02FF] | [#x0370-#x037D] | [#x037F-#x1FFF] | [#x200C-#x200D] | [#x2070-#x218F] | [#x2C00-#x2FEF] | [#x3001-#xD7FF] | [#xF900-#xFDCF] | [#xFDF0-#xFFFD] | [#x10000-#xEFFFF]
			I've omitted UTF-16 character range #x10000-#xEFFFF.
		*/

		String PN_CHARS_BASE = "A-Za-z\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02FF\u0370-\u037D\u037F-\u1FFF\u200C-\u200D\u2070-\u218F\u2C00-\u2FEF\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD";
		String pattern = PN_CHARS_BASE + "0-9\u00B7\u0300-\u036F\u203F-\u2040";

		s = s.trim().replaceAll("[^" + pattern + "]", "_").replace(":", "");

		if ("".equals(s))
			return null;
		return Var.alloc(s);
	}

	private boolean isEmpty(String[] row) {
		for (int i = 0; i < row.length; i++) {
			if (!isUnboundValue(row[i]))
				return false;
		}
		return true;
	}

	/**
	 * Checks whether a string taken from a CSV cell is considered an unbound
	 * SPARQL value
	 */
	private boolean isUnboundValue(String value) {
		return value == null || value.matches("\\s*");
	}

	private Binding toBinding(String[] row) {
		BindingBuilder builder = BindingBuilder.create();

		for (int i = 0; i < row.length; i++) {
			if (isUnboundValue(row[i])) {
				continue;
			}

			builder.add(
					getVar(i),
					NodeFactory.createLiteralString(sanitizeString(row[i]))
			);
		}

		// Add current row number as ?ROWNUM
		builder.add(
				TarqlQuery.ROWNUM,
				NodeFactory.createLiteralDT(
						Integer.toString(rownum),
						XSDDatatype.XSDinteger
				)
		);

		return builder.build();
	}

	/**
	 * Remove/replace weird characters known to cause problems in RDF toolkits.
	 */
	private String sanitizeString(String s) {
		// ASCII 10h, "Data Link Escape", causes parse failure in Turtle
		// in Virtuoso 7.0.0
		return s.replace((char) 0x10, (char) 0xFFFD);
	}

	private Var getVar(int column) {
		if (vars.size() < column) {
			getVar(column - 1);
		}
		if (vars.size() == column) {
			Var var = Var.alloc(getColumnName(column));
			while (vars.contains(var)) {
				var = Var.alloc("_" + var.getName());
			}
			vars.add(var);
		}
		return vars.get(column);
	}

	@Override
	public boolean hasNext() {
		return binding != null;
	}

	@Override
	public Binding next() {
		Binding current = binding;
		binding = null;
		String[] row;
		try {
			while ((row = csv.readNext()) != null) {
				// Skip rows without data
				if (isEmpty(row))
					continue;
				binding = toBinding(row);
				rownum++;
				break;
			}
		} catch (IOException e) {
			throw new TarqlException(e);
		}
		return current;
	}

	@Override
	public void remove() {
		throw new UnsupportedOperationException(
				"Remove is not supported. It is a read-only iterator");
	}

	@Override
	public void close() {
		try {
			csv.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public List<Var> getVars() {
		List<Var> varsWithRowNum = new ArrayList<Var>(vars);
		varsWithRowNum.add(TarqlQuery.ROWNUM);
		return varsWithRowNum;
	}
	private Reader commentFilteringReader() {
		if (commentPrefix == null || commentPrefix.isEmpty()) {
			return reader;
		}

		return new Reader() {
			private final java.io.PushbackReader input =
					new java.io.PushbackReader(reader, commentPrefix.length() + 2);
			private boolean inQuotes = false;
			private boolean atLineStart = true;

			@Override
			public int read(char[] cbuf, int off, int len) throws IOException {
				if (len == 0) {
					return 0;
				}

				int count = 0;

				while (count < len) {
					int ch = readNext();

					if (ch == -1) {
						return count == 0 ? -1 : count;
					}

					cbuf[off + count] = (char) ch;
					count++;
				}

				return count;
			}

			private int readNext() throws IOException {
				while (true) {
					if (atLineStart && !inQuotes && startsWithCommentPrefix()) {
						skipCommentLine();
						continue;
					}

					int ch = input.read();
					if (ch == -1) {
						return -1;
					}

					if (ch == quote && quote != '\0') {
						if (inQuotes) {
							int next = input.read();

							if (next == quote) {
								input.unread(next);
							} else {
								inQuotes = false;

								if (next != -1) {
									input.unread(next);
								}
							}
						} else {
							inQuotes = true;
						}
					}

					if (ch == '\n' || ch == '\r') {
						atLineStart = true;
					} else {
						atLineStart = false;
					}

					return ch;
				}
			}

			private boolean startsWithCommentPrefix() throws IOException {
				char[] prefix = new char[commentPrefix.length()];
				int read = 0;

				while (read < prefix.length) {
					int ch = input.read();

					if (ch == -1) {
						break;
					}

					prefix[read++] = (char) ch;
				}

				for (int i = read - 1; i >= 0; i--) {
					input.unread(prefix[i]);
				}

				if (read != prefix.length) {
					return false;
				}

				return commentPrefix.equals(new String(prefix));
			}

			private void skipCommentLine() throws IOException {
				int ch;

				while ((ch = input.read()) != -1) {
					if (ch == '\n') {
						atLineStart = true;
						return;
					}

					if (ch == '\r') {
						int next = input.read();

						if (next != '\n' && next != -1) {
							input.unread(next);
						}

						atLineStart = true;
						return;
					}
				}
			}

			@Override
			public void close() throws IOException {
				input.close();
			}
		};
	}
	private void init() throws IOException {
		String[] row;
		csv = new CSVReaderBuilder(commentFilteringReader()).withCSVParser(
				new CSVParserBuilder()
						.withSeparator(delimiter)
						.withQuoteChar(quote)
						.withEscapeChar(escape)
						.build())
				.build();
		if (varsFromHeader) {
			while ((row = csv.readNext()) != null) {
				boolean foundValidColumnName = false;
				for (int i = 0; i < row.length; i++) {
					if (toVar(row[i]) == null)
						continue;
					foundValidColumnName = true;
				}
				// If row was empty or didn't contain anything usable
				// as column name, then try next row
				if (!foundValidColumnName)
					continue;
				for (int i = 0; i < row.length; i++) {
					Var var = toVar(row[i]);
					if (var == null || vars.contains(var)
							|| var.equals(TarqlQuery.ROWNUM)) {
						getVar(i);
					} else {
						vars.add(var);
					}
				}
				break;
			}
		}
		rownum = 1;
		next();
	}
}
