package com.combatsolver1.runtime;

/** Keeps automatic, unplayable curse resolution out of the player-action journal. */
public final class CardActionRecordingPolicy {
    private CardActionRecordingPolicy() { }

    public static boolean shouldRecord(String cardType, boolean hasBlueCandle) {
        return cardType != null && (!"CURSE".equals(cardType) || hasBlueCandle);
    }
}
