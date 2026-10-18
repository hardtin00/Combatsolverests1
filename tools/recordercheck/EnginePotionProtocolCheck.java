package com.combatsolver1.runtime;

/** Regression for the production PLANPOTION ID parser used by EngineBridge. */
public final class EnginePotionProtocolCheck {
    public static void main(String[] args) {
        check("PLANPOTION idx=0 id=Dexterity Potion tgt=0", "Dexterity Potion");
        check("PLANPOTION idx=2 id=Heart of Iron tgt=0", "Heart of Iron");
        check("PLANPOTION idx=1 id=AttackPotion tgt=1", "AttackPotion");
        require(EngineProtocolFields.samePotionId("Dexterity Potion", "Dexterity Potion"),
                "exact native/protocol potion identity did not match");
        require(!EngineProtocolFields.samePotionId("Dexterity", "Dexterity Potion"),
                "mismatched potion IDs were accepted");
        require(EngineProtocolFields.potionId("PLANPOTION idx=0 id=Dexterity Potion") == null,
                "missing target delimiter was accepted");
        require(EngineProtocolFields.potionId("PLANPOTION idx=0 tgt=0") == null,
                "missing potion ID was accepted");
        require(EngineProtocolFields.potionId("PLANCARD idx=0 id=Strike_R tgt=0") == null,
                "non-potion protocol line was accepted");
        System.out.println("ENGINE_POTION_PROTOCOL_CHECK_OK assertions=8 multiword_ids=preserved malformed=reject");
    }

    private static void check(String line, String expected) {
        String actual = EngineProtocolFields.potionId(line);
        require(expected.equals(actual), "potion ID mismatch: expected=" + expected + " actual=" + actual);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
