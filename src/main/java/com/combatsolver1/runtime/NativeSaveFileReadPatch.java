package com.combatsolver1.runtime;

import com.badlogic.gdx.files.FileHandle;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;

import java.io.InputStream;

/** Tee only the active Ironclad save-slot stream, preserving the native stream behavior. */
@SpirePatch(clz = FileHandle.class, method = "read", paramtypez = {})
public final class NativeSaveFileReadPatch {
    private NativeSaveFileReadPatch() { }

    @SpirePrefixPatch
    public static void beforeNativeSaveRead(FileHandle __instance) {
        NativeSaveLoadCapture.beforeNativeFileRead(__instance);
    }

    @SpirePostfixPatch
    public static InputStream captureNativeSaveBytes(InputStream __result) {
        return NativeSaveLoadCapture.wrapNativeRead(__result);
    }
}
