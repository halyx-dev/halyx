package org.kamranzafar.cirrus.storage;

import org.kamranzafar.cirrus.error.DataIntegrityException;
import org.kamranzafar.cirrus.storage.spi.ChecksumCalculator;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** Checks downloaded bytes against an expected checksum when the stream reaches its end. */
final class VerifyingInputStream extends FilterInputStream {

    private final ChecksumAlgorithm algorithm;
    private final String expected;
    private final ChecksumCalculator calculator;
    private final String provider;
    private boolean verified;

    VerifyingInputStream(InputStream in, ChecksumAlgorithm algorithm, String expected, String provider) {
        super(in);
        this.algorithm = algorithm;
        this.expected = expected;
        this.calculator = new ChecksumCalculator(Set.of(algorithm));
        this.provider = provider;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b < 0) {
            verify();
        } else {
            calculator.update(new byte[] {(byte) b}, 0, 1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n < 0) {
            verify();
        } else {
            calculator.update(b, off, n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        throw new IOException("Skipping is not supported while validating a checksum");
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    private void verify() {
        if (verified) {
            return;
        }
        verified = true;
        String actual = calculator.result().get(algorithm).orElseThrow();
        if (!actual.equals(expected)) {
            throw new DataIntegrityException("Downloaded content failed " + algorithm + " validation: expected "
                    + expected + ", computed " + actual, null, provider, null);
        }
    }
}
