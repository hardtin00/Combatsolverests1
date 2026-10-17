package com.combatsolver1.runtime;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

/** Captures the real old-turn state before AbstractRoom queues native end-turn cleanup. */
@SpirePatch(clz = AbstractRoom.class, method = "endTurn")
public final class AbstractRoomEndTurnRecorderPatch {
    private AbstractRoomEndTurnRecorderPatch() {
    }

    @SpirePrefixPatch
    public static void beforeEndTurn(AbstractRoom __instance) {
        CombatRecorder.onTurnEnding();
    }
}
