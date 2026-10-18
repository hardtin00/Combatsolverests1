package com.combatsolver1.runtime;

import com.combatsolver1.CombatSolver1Mod;
import com.evacipated.cardcrawl.modthespire.lib.LineFinder;
import com.evacipated.cardcrawl.modthespire.lib.Matcher;
import com.evacipated.cardcrawl.modthespire.lib.SpireInsertLocator;
import com.evacipated.cardcrawl.modthespire.lib.SpireInsertPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import javassist.CtBehavior;

/** Captures the exact monster passed from CardQueueItem into native card execution. */
@SpirePatch(clz = AbstractPlayer.class, method = "useCard",
        paramtypez = {AbstractCard.class, AbstractMonster.class, int.class})
public final class AbstractPlayerUseCardTargetRecorderPatch {
    private AbstractPlayerUseCardTargetRecorderPatch() { }

    @SpireInsertPatch(locator = BeforeCardEffects.class)
    public static void beforeCardEffects(AbstractPlayer __instance, AbstractCard c,
                                         AbstractMonster monster, int energyOnUse) {
        // Without Blue Candle the game cannot accept a curse as a player card action.
        // Curse effects that resolve during end-turn cleanup belong to the endTurn record.
        if (!CardActionRecordingPolicy.shouldRecord(
                c == null || c.type == null ? null : c.type.name(),
                __instance != null && __instance.hasRelic("Blue Candle"))) {
            return;
        }
        CombatSolver1Mod.recordCardUse(c, monster);
    }

    /** Insert immediately before c.use(...): calculateCardDamage has run, card effects have not. */
    public static final class BeforeCardEffects extends SpireInsertLocator {
        @Override
        public int[] Locate(CtBehavior ctMethodToPatch) throws Exception {
            Matcher matcher = new Matcher.MethodCallMatcher(AbstractCard.class, "use");
            return LineFinder.findInOrder(ctMethodToPatch, matcher);
        }
    }
}
