package com.combatsolver1.runtime;

import java.io.File;

/** Validates one captured battle directory without loading the game or engine. */
public final class BattleBundleCheck {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: BattleBundleCheck <battle-artifact-directory>");
            System.exit(2);
        }
        BattleArtifacts.Validation result = BattleArtifacts.validate(new File(args[0]));
        if (!result.valid) {
            System.err.println("BUNDLE_INVALID reason=" + result.reason);
            System.exit(1);
        }
        System.out.println("BUNDLE_FILES_OK battleId=" + result.battleId
                + " contentPairing=pending-root-vs-engine-initialization-comparison");
    }
}
