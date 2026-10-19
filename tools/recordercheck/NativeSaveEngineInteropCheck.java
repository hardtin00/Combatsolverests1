package com.combatsolver1.runtime;

import com.badlogic.gdx.files.FileHandle;
import com.megacrit.cardcrawl.saveAndContinue.SaveFileObfuscator;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Local integration check: native Java save read/capture, C++ conversion, and living-plan search. */
public final class NativeSaveEngineInteropCheck {
    private NativeSaveEngineInteropCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException(
                "usage: <save> <engine> <workdir> <roomClass> <simulations> <expectedStatePrefix>");
        Path save = Paths.get(args[0]).toAbsolutePath();
        Path engine = Paths.get(args[1]).toAbsolutePath();
        Path work = Paths.get(args[2]).toAbsolutePath();
        String room = args[3];
        long simulations = Long.parseLong(args[4]);
        String expectedState = args[5];
        Files.createDirectories(work);

        FileHandle handle = new FileHandle(save.toFile());
        String nativeSourceText = handle.readString();
        String nativeJson = decodeNative(nativeSourceText);
        NativeReadEvidence.LoadSaveCalls calls = new NativeReadEvidence.LoadSaveCalls();
        NativeReadEvidence.LoadSaveCall call = calls.begin(save.toString(), save.toString() + ".backUp");
        call.observeStreamPath(save.toString());
        NativeReadEvidence.ReadScopes scopes = new NativeReadEvidence.ReadScopes();
        scopes.begin(call.transaction, save.toString());
        InputStream captured = scopes.finish(handle.read());
        byte[] loadedBytes = drainAndClose(captured);
        Object parsedSaveIdentity = new Object();
        NativeReadEvidence.LoadSaveCall completed = calls.complete(
                nativeJson, NativeSaveEngineInteropCheck::decodeNative);
        NativeReadEvidence.Transaction tx = completed.transaction;
        tx.sealSuccessfulLoad(parsedSaveIdentity);
        NativeReadEvidence.CapturedSave loaded = tx.parsedSave();
        if (loaded == null || !tx.hasParsedSave(parsedSaveIdentity)
                || !java.util.Arrays.equals(loaded.bytes, loadedBytes)) {
            throw new AssertionError("native Java FileHandle read did not bind exact bytes: " + tx.failure());
        }

        Path decoded = work.resolve("native-save-decoded.json");
        Path patched = work.resolve("native-save-combat.json");
        Path engineSave = work.resolve("engine-in.save");
        run(engine, work, "json", save.toString(), decoded.toString());
        String text = new String(Files.readAllBytes(decoded), StandardCharsets.UTF_8);
        String combatText = patchRoom(text, room);
        Files.write(patched, combatText.getBytes(StandardCharsets.UTF_8));
        run(engine, work, "json_to_save", patched.toString(), engineSave.toString());
        String search = run(engine, work, "mcts_save", engineSave.toString(), Long.toString(simulations));
        String state0 = lineStarting(search, "STATE0 ");
        String planEnd = lineStarting(search, "PLANEND ");
        if (state0 == null || !state0.startsWith(expectedState))
            throw new AssertionError("STATE0 did not match expected live root: " + state0);
        if (planEnd == null || !planEnd.matches(".*outcome=1(?:\\s|$).*"))
            throw new AssertionError("C++ search did not produce a living victory route: " + planEnd);
        System.out.println("NativeSaveEngineInteropCheck passed: Java native decode/capture exact; "
                + "loadSaveStringCall=" + completed.id + " streamPath=" + completed.streamPath()
                + "; " + state0 + "; " + planEnd);
    }

    private static String decodeNative(String source) throws Exception {
        return SaveFileObfuscator.isObfuscated(source)
                ? SaveFileObfuscator.decode(source, "key") : source;
    }

    private static byte[] drainAndClose(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String patchRoom(String json, String room) {
        String key = "\"current_room\"";
        int at = json.indexOf(key);
        int colon = at < 0 ? -1 : json.indexOf(':', at + key.length());
        int first = colon < 0 ? -1 : json.indexOf('"', colon + 1);
        int second = first < 0 ? -1 : json.indexOf('"', first + 1);
        if (second < 0) throw new IllegalArgumentException("current_room is missing or malformed");
        return json.substring(0, first + 1) + room + json.substring(second);
    }

    private static String run(Path executable, Path work, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(executable.toString());
        java.util.Collections.addAll(command, args);
        Process process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).start();
        byte[] output;
        try (InputStream stream = process.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) >= 0) if (count > 0) bytes.write(buffer, 0, count);
            output = bytes.toByteArray();
        }
        int exit = process.waitFor();
        String text = new String(output, Charset.defaultCharset());
        if (exit != 0) throw new IllegalStateException("engine command failed exit=" + exit
                + " command=" + args[0] + " output=" + text);
        return text;
    }

    private static String lineStarting(String text, String prefix) {
        for (String line : text.split("\\r?\\n")) if (line.startsWith(prefix)) return line;
        return null;
    }
}
