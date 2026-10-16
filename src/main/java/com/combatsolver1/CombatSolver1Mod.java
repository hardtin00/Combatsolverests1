package com.combatsolver1;

import basemod.BaseMod;
import basemod.interfaces.OnPlayerTurnStartSubscriber;
import basemod.interfaces.OnStartBattleSubscriber;
import basemod.interfaces.PostBattleSubscriber;
import basemod.interfaces.PostDrawSubscriber;
import basemod.interfaces.PostInitializeSubscriber;
import basemod.interfaces.PostRenderSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import basemod.interfaces.OnCardUseSubscriber;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

import com.combatsolver1.runtime.CombatController;

/**
 * Mod 入口。ModTheSpire 通过 @SpireInitializer 注解发现本类并调用 initialize()。
 */
@SpireInitializer
public class CombatSolver1Mod implements
        PostInitializeSubscriber,
        OnStartBattleSubscriber,
        PostBattleSubscriber,
        OnPlayerTurnStartSubscriber,
        PostDrawSubscriber,
        PostUpdateSubscriber,
        OnCardUseSubscriber,
        PostRenderSubscriber {

    public static final String MOD_ID = "combatsolver1";
    public static final String MOD_NAME = "Combat Solver 1";

    @SuppressWarnings("unused")
    public static void initialize() {
        CombatSolver1Mod mod = new CombatSolver1Mod();
        BaseMod.subscribe(mod);
        CombatLog.info("initialized");
    }

    @Override
    public void receivePostInitialize() {
        // 配置引擎桥（路径不存在时自动停用，模组退回自带的求解器）
        String engine = "C:\\Users\\3476\\Documents\\Codex\\2026-10-15\\jie\\work\\sts_build_mingw\\test.exe";
        String mingw = "C:\\Users\\3476\\Documents\\Codex\\2026-10-15\\jie\\work\\mingw64\\mingw64\\bin";
        if (new java.io.File(engine).isFile()) {
            com.combatsolver1.runtime.EngineBridge.configure(engine, mingw);
            CombatLog.info("engine bridge enabled");
        } else {
            CombatLog.info("engine bridge disabled (engine not found)");
        }
        CombatLog.info("post-initialize");
    }

    @Override
    public void receiveOnBattleStart(AbstractRoom battleRoom) {
        CombatController.instance().onBattleStart(battleRoom);
    }

    @Override
    public void receivePostBattle(AbstractRoom battleRoom) {
        com.combatsolver1.runtime.CombatRecorder.onBattleEnd();
        CombatController.instance().onBattleEnd();
    }

    @Override
    public void receiveCardUsed(AbstractCard card) {
        com.combatsolver1.runtime.CombatRecorder.onCardUsed(card.cardID, card.costForTurn,
                card.damage, card.block, CombatController.instance().recordingTarget());
        CombatController.instance().onCardUsed(card);
    }

    @Override
    public void receiveOnPlayerTurnStart() {
        CombatController.instance().onPlayerTurnStart();
    }

    @Override
    public void receivePostDraw(AbstractCard drawnCard) {
        CombatController.instance().onCardDrawn(drawnCard);
    }

    @Override
    public void receivePostUpdate() {
        CombatController.instance().update();
    }

    @Override
    public void receivePostRender(SpriteBatch sb) {
        CombatController.instance().render(sb);
    }
}
