package com.combatsolver1;

import basemod.BaseMod;
import basemod.interfaces.OnPlayerTurnStartSubscriber;
import basemod.interfaces.OnStartBattleSubscriber;
import basemod.interfaces.PostBattleSubscriber;
import basemod.interfaces.PostDeathSubscriber;
import basemod.interfaces.PostDrawSubscriber;
import basemod.interfaces.PostInitializeSubscriber;
import basemod.interfaces.PostRenderSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

import com.combatsolver1.runtime.CombatController;
import com.combatsolver1.runtime.CardTargetRef;
import com.combatsolver1.runtime.CombatRecorder;

/**
 * Mod 入口。ModTheSpire 通过 @SpireInitializer 注解发现本类并调用 initialize()。
 */
@SpireInitializer
public class CombatSolver1Mod implements
        PostInitializeSubscriber,
        OnStartBattleSubscriber,
        PostBattleSubscriber,
        PostDeathSubscriber,
        OnPlayerTurnStartSubscriber,
        PostDrawSubscriber,
        PostUpdateSubscriber,
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
        // 外部引擎（sts_lightspeed）是可选的：路径从系统属性 / 环境变量 / 设置文件读取，
        // 不再写死开发机路径；无论启用还是回退都在日志里说明原因，测试结果才能追溯来源。
        configureEngineBridge();
        CombatLog.info("post-initialize");
    }

    private static void configureEngineBridge() {
        // 先把解析到的游戏目录写进日志：多个静默失败（找不到存档 / 找不到桥目录）都在这一步暴露
        CombatLog.info("game dir: " + com.combatsolver1.runtime.EngineBridge.gameDirectory());
        String engine = firstNonEmpty(System.getProperty("combatsolver1.engine"),
                System.getenv("COMBATSOLVER1_ENGINE"), settingsValue("engine.path"));
        String mingw = firstNonEmpty(System.getProperty("combatsolver1.mingw"),
                System.getenv("COMBATSOLVER1_MINGW"), settingsValue("engine.mingw"));
        if (engine.isEmpty()) {
            CombatLog.info("solver source: local java search"
                    + "（未配置外部引擎；设置文件 ~/.combatsolver1/settings.properties 里写 engine.path=... 可启用）"
                    + " —— 注意：内置引擎自带一套近似 RNG，**跨回合结果只是近似预测**，不要当成精确路线");
            return;
        }
        if (!new java.io.File(engine).isFile()) {
            CombatLog.info("solver source: local java search（配置的外部引擎不存在：" + engine + "）");
            return;
        }
        com.combatsolver1.runtime.EngineBridge.configure(engine, mingw);
        CombatLog.info("solver source: sts_lightspeed (" + engine + ")"
                + (mingw.isEmpty() ? "" : "，MinGW: " + mingw));
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    /** 从 ~/.combatsolver1/settings.properties 读一个键（没有返回空串）。 */
    private static String settingsValue(String key) {
        java.io.File file = new java.io.File(
                new java.io.File(System.getProperty("user.home", "."), ".combatsolver1"),
                "settings.properties");
        if (!file.isFile()) {
            return "";
        }
        java.util.Properties properties = new java.util.Properties();
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            properties.load(in);
            return properties.getProperty(key, "");
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public void receiveOnBattleStart(AbstractRoom battleRoom) {
        com.combatsolver1.runtime.CombatRecorder.onBattleStart();
        CombatController.instance().onBattleStart(battleRoom);
    }

    @Override
    public void receivePostBattle(AbstractRoom battleRoom) {
        com.combatsolver1.runtime.CombatRecorder.onBattleEnd("victory");
        CombatController.instance().onBattleEnd();
    }

    @Override
    public void receivePostDeath() {
        com.combatsolver1.runtime.CombatRecorder.onBattleEnd("death");
        CombatController.instance().onBattleEnd();
    }

    /** Called from AbstractPlayer.useCard after native target resolution, before card effects run. */
    public static void recordCardUse(AbstractCard card, AbstractMonster target) {
        // 只有攻击牌才有意义的伤害值：技能/能力牌的 card.damage 是游戏内部的占位值（0 或 1），
        // 照抄进记录会让差分去找一个不存在的“同名牌”，把数据问题误报成差异。
        int recordedDamage = card.type == AbstractCard.CardType.ATTACK ? card.damage : 0;
        CardTargetRef targetRef = CardTargetRef.capture(card, target);
        CombatRecorder.onCardUsed(card.cardID, card.costForTurn, recordedDamage, card.block, targetRef);
        CombatController.instance().onCardUsed(card, targetRef);
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
        // Returning to the main menu can leave a combat without either native completion callback.
        if (!com.combatsolver1.runtime.CombatRecorder.currentBattleId().isEmpty()
                && !com.megacrit.cardcrawl.dungeons.AbstractDungeon.isPlayerInDungeon()) {
            com.combatsolver1.runtime.CombatRecorder.onBattleEnd("interrupted");
            CombatController.instance().onBattleEnd();
        }
        CombatController.instance().update();
    }

    @Override
    public void receivePostRender(SpriteBatch sb) {
        CombatController.instance().render(sb);
    }
}
