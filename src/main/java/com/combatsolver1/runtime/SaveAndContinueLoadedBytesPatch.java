package com.combatsolver1.runtime;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.saveAndContinue.SaveAndContinue;

/** Verifies the actual FileHandle stream against STS1's private read/decode return value. */
@SpirePatch(clz = SaveAndContinue.class, method = "loadSaveString", paramtypez = {String.class})
public final class SaveAndContinueLoadedBytesPatch {
    private SaveAndContinueLoadedBytesPatch() { }

    @SpirePrefixPatch
    public static void beforeNativeRead() {
        NativeSaveLoadCapture.beginLoadSaveString();
    }

    @SpirePostfixPatch
    public static void afterNativeRead(String __result) {
        NativeSaveLoadCapture.observeNativeRead(__result);
    }
}
