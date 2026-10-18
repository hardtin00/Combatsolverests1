package com.combatsolver1.runtime;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.saveAndContinue.SaveAndContinue;

/** Captures the exact path and content returned by STS1's private file-read/decode method. */
@SpirePatch(clz = SaveAndContinue.class, method = "loadSaveString", paramtypez = {String.class})
public final class SaveAndContinueLoadedBytesPatch {
    private SaveAndContinueLoadedBytesPatch() { }

    @SpirePostfixPatch
    public static void afterNativeRead(String __result) {
        NativeSaveLoadCapture.observeNativeRead(__result);
    }
}
