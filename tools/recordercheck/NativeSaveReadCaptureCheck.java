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
        readAndReturn(decodeMismatch, MAIN, primary, raw -> "different native decode");
        require(decodeMismatch.parsedSave() == null && decodeMismatch.failure().contains("decode mismatch"),
                "decode mismatch was accepted"); checks++;

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
        require(nullResult.parsedSave() == null && nullResult.failure().contains("unavailable"), "null native result was accepted"); checks++;

        System.out.println("NativeSaveReadCaptureCheck passed=" + checks + " failed=0; exact opened-stream bytes, path, decode and SaveFile identity verified.");
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
