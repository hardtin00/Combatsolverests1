package com.combatsolver1.runtime;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Locale;

/** Captures bytes from the exact FileHandle stream used by STS1's Continue loader. */
public final class NativeReadEvidence {
    private NativeReadEvidence() { }

    public static InputStream wrap(Transaction transaction, String actualPath, InputStream input) {
        return transaction == null ? input : transaction.wrap(actualPath, input);
    }

    public interface Decoder {
        String decode(String raw) throws Exception;
    }

    public static final class CapturedSave {
        public final String path;
        public final byte[] bytes;
        public final Object saveObject;

        private CapturedSave(String path, byte[] bytes, Object saveObject) {
            this.path = path;
            this.bytes = bytes.clone();
            this.saveObject = saveObject;
        }
    }

    /** One transaction is scoped to CardCrawlGame.loadPlayerSave and cannot use unrelated reads. */
    public static final class Transaction {
        private final String mainPath;
        private final String backupPath;
        private CaptureStream pendingStream;
        private String pendingPath = "";
        private String lastObservedPath = "";
        private Candidate returnedCandidate;
        private CapturedSave parsedSave;
        private String failure = "native save read was not captured";

        public Transaction(String mainPath, String backupPath) {
            this.mainPath = normalize(mainPath);
            this.backupPath = normalize(backupPath);
        }

        /** Returns the original stream unchanged for unrelated paths or absent transactions. */
        public synchronized InputStream wrap(String actualPath, InputStream input) {
            lastObservedPath = actualPath == null ? "" : actualPath;
            if (input == null || !isExpectedPath(actualPath)) {
                if (!lastObservedPath.isEmpty()) failure = "native source path mismatch: actual="
                        + actualPath + " expected=" + mainPath + " or " + backupPath;
                return input;
            }
            CaptureStream capture = new CaptureStream(input);
            pendingStream = capture;
            pendingPath = actualPath;
            returnedCandidate = null;
            parsedSave = null;
            failure = "native stream has not completed";
            return capture;
        }

        /** Called by the postfix of the exact private loadSaveString(String) overload. */
        public synchronized void returned(String nativeDecodedText, Decoder decoder) {
            CaptureStream capture = pendingStream;
            String path = pendingPath;
            pendingStream = null;
            pendingPath = "";
            if (capture == null) {
                returnedCandidate = null;
                failure = lastObservedPath.isEmpty() ? "native loaded bytes unavailable" : "native source path mismatch";
                return;
            }
            if (nativeDecodedText == null) {
                returnedCandidate = null;
                failure = "native loaded text unavailable";
                return;
            }
            try {
                byte[] bytes = capture.completedBytes();
                String raw = new String(bytes, Charset.defaultCharset());
                String decoded = decoder.decode(raw);
                if (!nativeDecodedText.equals(decoded)) {
                    returnedCandidate = null;
                    failure = "native read/decode mismatch: path=" + path + " bytes=" + bytes.length;
                    return;
                }
                returnedCandidate = new Candidate(path, bytes);
                failure = "native read verified; SaveFile parse not yet confirmed";
            } catch (Throwable t) {
                returnedCandidate = null;
                failure = t.getMessage() == null ? "native stream was incomplete" : t.getMessage();
            }
        }

        /** Called only after the matching private loadSaveFile(String) returned a SaveFile. */
        public synchronized void sealSuccessfulLoad(Object saveObject) {
            Candidate candidate = returnedCandidate;
            returnedCandidate = null;
            if (candidate == null || saveObject == null) {
                failure = "native load transaction mismatch: successful loader has no matching captured read";
                return;
            }
            parsedSave = new CapturedSave(candidate.path, candidate.bytes, saveObject);
            failure = "";
        }

        public synchronized boolean hasParsedSave(Object expectedObject) {
            return parsedSave != null && parsedSave.saveObject == expectedObject;
        }

        public synchronized CapturedSave parsedSave() { return parsedSave; }
        public synchronized String failure() { return failure; }

        private boolean isExpectedPath(String path) {
            String normalized = normalize(path);
            return !normalized.isEmpty() && (normalized.equals(mainPath) || normalized.equals(backupPath));
        }
    }

    private static final class Candidate {
        final String path;
        final byte[] bytes;
        Candidate(String path, byte[] bytes) { this.path = path; this.bytes = bytes; }
    }

    private static final class CaptureStream extends FilterInputStream {
        private static final int MAX_CAPTURE_BYTES = 16 * 1024 * 1024;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private boolean eof;
        private boolean closed;
        private boolean incomplete;

        CaptureStream(InputStream input) { super(input); }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value < 0) eof = true;
            else if (captured.size() < MAX_CAPTURE_BYTES) captured.write(value);
            else incomplete = true;
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            if (count < 0) eof = true;
            else if (count > 0) {
                if ((long) captured.size() + count <= MAX_CAPTURE_BYTES) captured.write(bytes, offset, count);
                else incomplete = true;
            }
            return count;
        }

        @Override public long skip(long count) throws IOException {
            if (count > 0) incomplete = true;
            return in.skip(count);
        }

        @Override public synchronized void mark(int limit) {
            incomplete = true;
            in.mark(limit);
        }

        @Override public synchronized void reset() throws IOException {
            incomplete = true;
            in.reset();
        }

        @Override public void close() throws IOException {
            try { in.close(); }
            finally { closed = true; }
        }

        byte[] completedBytes() throws IOException {
            if (incomplete || !eof || !closed || captured.size() == 0) {
                throw new IOException("native stream incomplete (eof=" + eof + ", closed=" + closed
                        + ", bytes=" + captured.size() + ")");
            }
            return captured.toByteArray();
        }
    }

    public static String normalize(String path) {
        if (path == null) return "";
        String result = path.replace('\\', '/').trim();
        while (result.startsWith("./")) result = result.substring(2);
        return result.toLowerCase(Locale.ROOT);
    }
}
