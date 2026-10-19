package com.combatsolver1.runtime;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/** Exercises the production byte-stream capture and parsed-save binding used by Continue loading. */
public final class NativeSaveReadCaptureCheck {
    private static final String MAIN = "C:/sts/preferences/saves/IRONCLAD.autosave";
    private static final String BACKUP = MAIN + ".backUp";

    private NativeSaveReadCaptureCheck() { }

    public static void main(String[] args) throws Exception {
        int checks = 0;
        require(NativeReadEvidence.useNativeLoadedSave(true, true),
                "verified Continue source did not retain priority"); checks++;
        require(!NativeReadEvidence.useNativeLoadedSave(true, false),
                "invalid Continue evidence suppressed fresh battle autosave selection"); checks++;
        require(!NativeReadEvidence.useNativeLoadedSave(false, false),
                "new battle without Continue evidence selected an absent native source"); checks++;
        byte[] primary = "{\"seed\":101,\"hp\":70,\"label\":\"铁甲\"}".getBytes(Charset.defaultCharset());
        byte[] backup = "{\"seed\":101,\"hp\":69}".getBytes(Charset.defaultCharset());

        NativeReadEvidence.Transaction normal = transaction();
        Object primarySave = new Object();
        NativeReadEvidence.CapturedSave first = readAndParse(normal, MAIN, primary, primarySave, identityDecoder());
        require(first.path.equals(MAIN) && Arrays.equals(first.bytes, primary), "main read did not capture actual bytes/path"); checks++;
        require(normal.hasParsedSave(primarySave), "parsed SaveFile identity was not bound"); checks++;

        NativeReadEvidence.Transaction fallback = transaction();
        readAndReturn(fallback, MAIN, primary, identityDecoder()); // primary read was corrupt at Gson parse; no parsed callback
        Object backupSave = new Object();
        NativeReadEvidence.CapturedSave fallbackResult = readAndParse(fallback, BACKUP, backup, backupSave, identityDecoder());
        require(fallbackResult.path.equals(BACKUP) && Arrays.equals(fallbackResult.bytes, backup), "backup fallback source was not selected"); checks++;

        NativeReadEvidence.Transaction identical = transaction();
        NativeReadEvidence.CapturedSave identicalResult = readAndParse(identical, BACKUP, primary, new Object(), identityDecoder());
        require(identicalResult.path.equals(BACKUP), "identical payload falsely claimed primary source"); checks++;

        NativeReadEvidence.Transaction distinct = transaction();
        NativeReadEvidence.CapturedSave distinctResult = readAndParse(distinct, MAIN, primary, new Object(), identityDecoder());
        require(Arrays.equals(distinctResult.bytes, primary), "distinct slot payload changed during capture"); checks++;

        NativeReadEvidence.Transaction callbackBound = transaction();
        NativeReadEvidence.ReadScopes scopes = new NativeReadEvidence.ReadScopes();
        scopes.begin(callbackBound, MAIN);
        scopes.begin(callbackBound, "C:/sts/preferences/other-layout.json");
        InputStream unrelatedRead = new ByteArrayInputStream("other".getBytes(Charset.defaultCharset()));
        require(scopes.finish(unrelatedRead) == unrelatedRead, "unrelated nested read was captured"); checks++;
        Object callbackSave = new Object();
        InputStream exactRead = scopes.finish(new ByteArrayInputStream(primary));
        byte[] exactBytes = drainAndClose(exactRead);
        callbackBound.returned(new String(exactBytes, Charset.defaultCharset()), identityDecoder());
        callbackBound.sealSuccessfulLoad(callbackSave);
        require(callbackBound.hasParsedSave(callbackSave)
                        && callbackBound.parsedSave().path.equals(MAIN)
                        && Arrays.equals(callbackBound.parsedSave().bytes, primary),
                "nested FileHandle.read context did not restore the outer source path and bytes"); checks++;

        NativeReadEvidence.Transaction wrongPath = transaction();
        InputStream unrelated = new ByteArrayInputStream(primary);
        require(wrongPath.wrap("C:/sts/preferences/saves/SILENT.autosave", unrelated) == unrelated,
                "unrelated character slot was wrapped"); checks++;
        wrongPath.returned(new String(primary, Charset.defaultCharset()), identityDecoder());
        require(wrongPath.parsedSave() == null && wrongPath.failure().contains("path mismatch"), "wrong source path gained evidence"); checks++;

        NativeReadEvidence.Transaction changedOnDisk = transaction();
        byte[] bytesAtOpen = primary.clone();
        InputStream opened = new ByteArrayInputStream(bytesAtOpen);
        InputStream capturedStream = changedOnDisk.wrap(MAIN, opened);
        byte[] loaded = drainAndClose(capturedStream);
        Arrays.fill(bytesAtOpen, (byte) 'x'); // models a disk rewrite after native read completed
        String loadedText = new String(loaded, Charset.defaultCharset());
        changedOnDisk.returned(loadedText, identityDecoder());
        require(changedOnDisk.parsedSave() == null, "captured bytes were trusted before successful native load completion"); checks++;
        require(!loadedText.equals(new String(bytesAtOpen, Charset.defaultCharset())),
                "disk mutation fixture does not reproduce the old second-read mismatch"); checks++;
        changedOnDisk.sealSuccessfulLoad(new Object());
        require(changedOnDisk.parsedSave() != null && Arrays.equals(changedOnDisk.parsedSave().bytes, primary),
                "later disk mutation changed captured load evidence"); checks++;

        NativeReadEvidence.Transaction decodeMismatch = transaction();
        byte[] nativeJson = "{\"seed\":102,\"hp\":70,\"label\":\"铁甲\"}".getBytes(Charset.defaultCharset());
        InputStream mismatchStream = decodeMismatch.wrap(MAIN, new ByteArrayInputStream(primary));
        drainAndClose(mismatchStream);
        decodeMismatch.returned(new String(nativeJson, Charset.defaultCharset()), identityDecoder());
        require(decodeMismatch.parsedSave() == null && decodeMismatch.failure().contains("decode mismatch"),
                "decode mismatch was accepted"); checks++;
        require(decodeMismatch.failure().contains("firstDiff=") && decodeMismatch.failure().contains("field=seed"),
                "decode mismatch did not identify first differing field without values"); checks++;

        InputStream outsideStream = new ByteArrayInputStream(primary);
        require(NativeReadEvidence.wrap(null, MAIN, outsideStream) == outsideStream,
                "read outside an active transaction was wrapped"); checks++;

        NativeReadEvidence.Transaction wrongObject = transaction();
        Object parsedObject = new Object();
        readAndParse(wrongObject, MAIN, primary, parsedObject, identityDecoder());
        require(!wrongObject.hasParsedSave(new Object()), "different active SaveFile object matched parsed evidence"); checks++;

        NativeReadEvidence.Transaction incomplete = transaction();
        InputStream partial = incomplete.wrap(MAIN, new ByteArrayInputStream(primary));
        partial.read();
        partial.close();
        incomplete.returned(new String(primary, Charset.defaultCharset()), identityDecoder());
        require(incomplete.parsedSave() == null && incomplete.failure().contains("incomplete"), "partial stream was accepted"); checks++;

        NativeReadEvidence.Transaction empty = transaction();
        readAndReturn(empty, MAIN, new byte[0], identityDecoder());
        require(empty.parsedSave() == null && empty.failure().contains("incomplete"), "empty save was accepted"); checks++;

        NativeReadEvidence.Transaction nullResult = transaction();
        nullResult.returned(null, identityDecoder());
        require(nullResult.parsedSave() == null && nullResult.failure().contains("without an observed FileHandle.read"),
                "missing read hook was not classified"); checks++;

        NativeReadEvidence.Transaction missedReadHook = transaction();
        missedReadHook.returned(new String(primary, Charset.defaultCharset()), identityDecoder());
        String firstCaptureFailure = missedReadHook.failure();
        missedReadHook.sealSuccessfulLoad(new Object());
        require(missedReadHook.failure().equals(firstCaptureFailure),
                "successful-load sealing erased the original read-hook failure"); checks++;

        NativeReadEvidence.LoadSaveCalls calls = new NativeReadEvidence.LoadSaveCalls();
        NativeReadEvidence.ReadScopes callReads = new NativeReadEvidence.ReadScopes();
        byte[] mismatchShape = new byte[13561];
        Arrays.fill(mismatchShape, (byte) 'A');
        NativeReadEvidence.Transaction sharedLegacy = transaction();
        drainAndClose(sharedLegacy.wrap(MAIN, new ByteArrayInputStream(mismatchShape)));
        sharedLegacy.returned("12345678901234567890123", identityDecoder());
        require(sharedLegacy.failure().contains("nativeChars=23 capturedChars=13561 firstDiff=0"),
                "legacy shared transaction fixture did not reproduce the observed mismatch shape"); checks++;

        NativeReadEvidence.LoadSaveCall mainCall = calls.begin(MAIN, BACKUP);
        callReads.begin(mainCall.transaction, MAIN);
        InputStream mainInput = callReads.finish(new ByteArrayInputStream(mismatchShape));
        byte[] mainBytes = drainAndClose(mainInput);
        NativeReadEvidence.LoadSaveCall mainCompleted = calls.complete(
                new String(mainBytes, Charset.defaultCharset()), identityDecoder());
        require(mainCompleted.id == mainCall.id && mainCompleted.transaction.parsedSave() == null
                        && mainCompleted.transaction.failure().contains("verified; SaveFile parse not yet confirmed"),
                "loadSaveString call did not complete against its own captured stream"); checks++;

        NativeReadEvidence.LoadSaveCall interruptedMain = calls.begin(MAIN, BACKUP);
        callReads.begin(interruptedMain.transaction, MAIN);
        drainAndClose(callReads.finish(new ByteArrayInputStream(primary)));
        NativeReadEvidence.LoadSaveCall backupCall = calls.begin(MAIN, BACKUP);
        NativeReadEvidence.LoadSaveCall backupCompleted = calls.complete("12345678901234567890123", identityDecoder());
        require(interruptedMain.id != backupCall.id && backupCompleted.id == backupCall.id,
                "nested loadSaveString calls did not receive distinct transaction IDs"); checks++;
        require(backupCompleted.streamPath().isEmpty() && backupCompleted.returnedTextLength() == 23
                        && backupCompleted.transaction.failure().contains("without an observed FileHandle.read"),
                "23-character return was incorrectly paired with another invocation's autosave bytes"); checks++;
        calls.clear();

        System.out.println("NativeSaveReadCaptureCheck passed=" + checks + " failed=0; exact per-call stream/path/decode and SaveFile identity verified.");
    }

    private static NativeReadEvidence.Transaction transaction() {
        return new NativeReadEvidence.Transaction(MAIN, BACKUP);
    }

    private static NativeReadEvidence.Decoder identityDecoder() {
        return raw -> raw;
    }

    private static NativeReadEvidence.CapturedSave readAndParse(NativeReadEvidence.Transaction tx,
            String path, byte[] bytes, Object save, NativeReadEvidence.Decoder decoder) throws Exception {
        readAndReturn(tx, path, bytes, decoder);
        tx.sealSuccessfulLoad(save);
        NativeReadEvidence.CapturedSave result = tx.parsedSave();
        require(result != null && result.saveObject == save, "successful parse did not bind its source");
        return result;
    }

    private static void readAndReturn(NativeReadEvidence.Transaction tx, String path, byte[] bytes,
            NativeReadEvidence.Decoder decoder) throws Exception {
        InputStream stream = tx.wrap(path, new ByteArrayInputStream(bytes));
        byte[] read = drainAndClose(stream);
        tx.returned(new String(read, Charset.defaultCharset()), decoder);
    }

    private static byte[] drainAndClose(InputStream stream) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[7];
        int count;
        while ((count = stream.read(buffer)) >= 0) if (count > 0) bytes.write(buffer, 0, count);
        stream.close();
        return bytes.toByteArray();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
