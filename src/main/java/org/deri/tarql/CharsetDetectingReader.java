package org.deri.tarql;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;

import org.mozilla.intl.chardet.nsDetector;
import org.mozilla.intl.chardet.nsICharsetDetectionObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Reader} that wraps an {@link InputStream} and automatically takes
 * care of guessing the input stream's encoding, using the jchardet library.
 */
public class CharsetDetectingReader extends Reader {
	private static final Logger log = LoggerFactory.getLogger(CharsetDetectingReader.class);

	private static final int DEFAULT_BUFFER_SIZE = 1024;

	private final InputStream in;
	private final int bufferSize;

	private Reader reader;

	public CharsetDetectingReader(InputStream in) {
		this(in, DEFAULT_BUFFER_SIZE);
	}

	public CharsetDetectingReader(InputStream in, int bufferSize) {
		if (in == null) {
			throw new NullPointerException();
		}
		this.in = in;
		this.bufferSize = bufferSize;
	}

	@Override
	public int read(char[] cbuf, int off, int len) throws IOException {
		if (reader == null) {
			initializeReader();
		}
		return reader.read(cbuf, off, len);
	}

	@Override
	public void close() throws IOException {
		if (reader != null) {
			reader.close();
		} else {
			in.close();
		}
	}

	private void initializeReader() throws IOException {
		byte[] buffer = new byte[bufferSize];
		int bytesRead = in.read(buffer);

		if (bytesRead == -1) {
			reader = new InputStreamReader(
					new ByteArrayInputStream(new byte[0]), "US-ASCII");
			return;
		}

		final String[] detectedEncoding = new String[1];
		nsDetector detector = new nsDetector();

		detector.Init(new nsICharsetDetectionObserver() {
			@Override
			public void Notify(String encoding) {
				log.debug("Encoding detected: {}", encoding);
				detectedEncoding[0] = encoding;
			}
		});

		if (!detector.isAscii(buffer, bytesRead)) {
			detector.DoIt(buffer, bytesRead, false);
		}
		detector.DataEnd();

		String encoding = detectedEncoding[0];

		if (encoding == null) {
			String[] guesses = detector.getProbableCharsets();
			if (guesses.length > 0) {
				encoding = guesses[0];
			}
		}

		if (encoding == null) {
			encoding = "US-ASCII";
		}

		log.debug("Using encoding: {}", encoding);

		InputStream completeStream = new SequenceInputStream(
				new ByteArrayInputStream(buffer, 0, bytesRead),
				in);

		try {
			reader = new InputStreamReader(completeStream, encoding);
		} catch (UnsupportedEncodingException ex) {
			log.debug("Unsupported encoding {}, falling back to US-ASCII", encoding);
			reader = new InputStreamReader(completeStream, "US-ASCII");
		}
	}
}