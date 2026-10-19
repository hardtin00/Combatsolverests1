package com.combatsolver1.runtime;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Captures bytes from the exact FileHandle stream used by STS1's Continue loader. */
public final class NativeReadEvidence {
    private NativeReadEvidence() { }

    /** Invalid Continue evidence never suppresses the independent fresh-autosave path. */
    public static boolean useNativeLoadedSave(boolean nativeLoadObserved, boolean verifiedSourceAvailable) {
        return nativeLoadObserved && verifiedSourceAvailable;
    }

    public static InputStream wrap(Transaction transaction, String actualPath, InputStream input) {
        return transaction == null ? input : transaction.wrap(actualPath, input);
    }

    /** Keeps FileHandle.read Prefix/Postfix contexts paired even if another read nests inside. */
    public static final class ReadScopes {
        private final ThreadLocal<Deque<ReadScope>> scopes = new ThreadLocal<>();

        public void begin(Transaction transaction, String actualPath) {
            Deque<ReadScope> stack = scopes.get();
            if (stack == null) {
                stack = new ArrayDeque<>();
                scopes.set(stack);
            }
            stack.push(new ReadScope(transaction, actualPath));
        }

        public InputStream finish(InputStream input) {
            Deque<ReadScope> stack = scopes.get();
            if (stack == null || stack.isEmpty()) return input;
            ReadScope scope = stack.pop();
            if (stack.isEmpty()) scopes.remove();
            if (scope.transaction == null) return input;
            if (scope.path == null || scope.path.isEmpty()) {
                scope.transaction.pathUnavailable();
                return input;
            }
            return scope.transaction.wrap(scope.path, input);
        }

        public void clear() { scopes.remove(); }
    }

    /** Isolates each native loadSaveString(path) call from other calls in the same Continue load. */
    public static final class LoadSaveCalls {
        private final AtomicLong nextId = new AtomicLong();
        private final ThreadLocal<Deque<LoadSaveCall>> calls = new ThreadLocal<>();

        public LoadSaveCall begin(String primaryPath, String backupPath) {
            Deque<LoadSaveCall> stack = calls.get();
            if (stack == null) {
                stack = new ArrayDeque<>();
                calls.set(stack);
            }
            LoadSaveCall call = new LoadSaveCall(nextId.incrementAndGet(), primaryPath, backupPath);
            stack.push(call);
            return call;
        }

        public LoadSaveCall current() {
            Deque<LoadSaveCall> stack = calls.get();
            return stack == null || stack.isEmpty() ? null : stack.peek();
        }

        public LoadSaveCall complete(String returnedText, Decoder decoder) {
            Deque<LoadSaveCall> stack = calls.get();
            if (stack == null || stack.isEmpty()) return null;
            LoadSaveCall call = stack.pop();
            if (stack.isEmpty()) calls.remove();
            call.returnedTextLength = returnedText == null ? -1 : returnedText.length();
            call.transaction.returned(returnedText, decoder);
            return call;
        }

        public void clear() { calls.remove(); }
    }

    public static final class LoadSaveCall {
        public final long id;
        public final Transaction transaction;
        private String streamPath = "";
        private int returnedTextLength = -1;

        private LoadSaveCall(long id, String primaryPath, String backupPath) {
            this.id = id;
            this.transaction = new Transaction(primaryPath, backupPath);
        }

        public void observeStreamPath(String path) { this.streamPath = path == null ? "" : path; }
        public String streamPath() { return streamPath; }
        public int returnedTextLength() { return returnedTextLength; }
    }

    private static final class ReadScope {
        final Transaction transaction;
        final String path;
        ReadScope(Transaction transaction, String path) {
            this.transaction = transaction;
            this.path = path;
        }
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
        private String failure = "native FileHandle.read hook was not observed in active load transaction";

        public Transaction(String mainPath, String backupPath) {
            this.mainPath = normalize(mainPath);
            this.backupPath = normalize(backupPath);
        }

        /** Returns the original stream unchanged for unrelated paths or absent transactions. */
        public synchronized InputStream wrap(String actualPath, InputStream input) {
            lastObservedPath = actualPath == null ? "" : actualPath;
            if (input == null || !isExpectedPath(actualPath)) {
                if (input == null) failure = "native FileHandle.read returned a null stream";
                else if (!lastObservedPath.isEmpty()) failure = "native source path mismatch: actual="
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
                if (lastObservedPath.isEmpty()) {
                    failure = "native loadSaveString returned without an observed FileHandle.read stream";
                } else if (!isExpectedPath(lastObservedPath)) {
                    failure = "native FileHandle.read source path mismatch: actual=" + lastObservedPath
                            + " expected=" + mainPath + " or " + backupPath;
                }
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
                    failure = "native read/decode mismatch: path=" + path + " bytes=" + bytes.length
                            + mismatchDetails(nativeDecodedText, decoded);
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
            if (candidate == null) {
                if (failure == null || failure.isEmpty()) {
                    failure = "native SaveFile parse completed without a verified native read";
                }
                return;
            }
            if (saveObject == null) {
                failure = "native FileHandle read was verified but successful loader returned no SaveFile object";
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

        private synchronized void pathUnavailable() {
            failure = "native FileHandle.read path could not be resolved";
        }

        private boolean isExpectedPath(String path) {
            String normalized = normalize(path);
            return !normalized.isEmpty() && (normalized.equals(mainPath) || normalized.equals(backupPath));
        }

        private static String mismatchDetails(String nativeText, String capturedText) {
            int limit = Math.min(nativeText.length(), capturedText.length());
            int offset = 0;
            while (offset < limit && nativeText.charAt(offset) == capturedText.charAt(offset)) offset++;
            String field = jsonFieldAt(nativeText, offset);
            return " nativeChars=" + nativeText.length() + " capturedChars=" + capturedText.length()
                    + " firstDiff=" + offset + (field.isEmpty() ? "" : " field=" + field);
        }

        /** Reports only a JSON key, never the possibly private value around a mismatch. */
        private static String jsonFieldAt(String text, int offset) {
            int colon = text.lastIndexOf(':', Math.min(offset, text.length()) - 1);
            if (colon < 0) return "";
            int keyEnd = colon - 1;
            while (keyEnd >= 0 && Character.isWhitespace(text.charAt(keyEnd))) keyEnd--;
            if (keyEnd < 0 || text.charAt(keyEnd) != '"') return "";
            int keyStart = text.lastIndexOf('"', keyEnd - 1);
            if (keyStart < 0) return "";
            String key = text.substring(keyStart + 1, keyEnd);
            if (key.isEmpty()) return "";
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                        && !(c >= '0' && c <= '9') && c != '_') return "";
            }
            return key;
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
