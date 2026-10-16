package com.combatsolver1.runtime;

import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.localization.CardStrings;
import com.megacrit.cardcrawl.localization.PotionStrings;

import java.util.HashMap;
import java.util.Map;

/**
 * 卡牌 ID → 当前语言下的显示名（引擎计划里只有 ID，比如 Strike_R）。
 * 名字来自游戏自带的语言包，找不到时退回内置的常用对照表。
 */
public final class CardNames {
    private CardNames() {
    }

    private static final Map<String, String> cache = new HashMap<>();
    private static final Map<String, String> fallback = new HashMap<>();
    private static final Map<String, String> potionCache = new HashMap<>();
    private static final Map<String, String> potionFallback = new HashMap<>();

    static {
        fallback.put("Strike_R", "打击");
        fallback.put("Defend_R", "防御");
        fallback.put("Bash", "痛击");
        fallback.put("Anger", "愤怒");
        fallback.put("Armaments", "军备");
        fallback.put("AscendersBane", "进阶之灾");
        fallback.put("Bloodletting", "放血");
        fallback.put("Body Slam", "猛撞");
        fallback.put("Burning Pact", "燃烧契约");
        fallback.put("Clash", "冲突");
        fallback.put("Cleave", "顺劈斩");
        fallback.put("Clothesline", "勒颈");
        fallback.put("Combust", "燃烧");
        fallback.put("Corruption", "腐化");
        fallback.put("Dark Embrace", "黑暗拥抱");
        fallback.put("Disarm", "缴械");
        fallback.put("Dual Wield", "双持");
        fallback.put("Entrench", "稳固");
        fallback.put("Evolve", "进化");
        fallback.put("Exhume", "掘墓");
        fallback.put("Feel No Pain", "无痛");
        fallback.put("Fiend Fire", "恶魔之炎");
        fallback.put("Flame Barrier", "烈焰屏障");
        fallback.put("Flex", "屈伸");
        fallback.put("Ghostly Armor", "幽魂护甲");
        fallback.put("Havoc", "浩劫");
        fallback.put("Headbutt", "头槌");
        fallback.put("Heavy Blade", "重刃");
        fallback.put("Hemokinesis", "血液动力");
        fallback.put("Immolate", "燔祭");
        fallback.put("Impervious", "铜墙铁壁");
        fallback.put("Infernal Blade", "地狱之刃");
        fallback.put("Inflame", "燃烧之焰");
        fallback.put("Iron Wave", "铁波");
        fallback.put("Juggernaut", "势不可挡");
        fallback.put("Limit Break", "极限破裂");
        fallback.put("Metallicize", "金属化");
        fallback.put("Offering", "献祭");
        fallback.put("Perfected Strike", "完美打击");
        fallback.put("Pommel Strike", "手柄打击");
        fallback.put("Power Through", "奋力突破");
        fallback.put("Pummel", "连击");
        fallback.put("Rage", "愤怒姿态");
        fallback.put("Rampage", "横冲直撞");
        fallback.put("Reaper", "收割");
        fallback.put("Rupture", "撕裂");
        fallback.put("Searing Blow", "灼热打击");
        fallback.put("Second Wind", "第二风");
        fallback.put("Seeing Red", "见红");
        fallback.put("Sentinel", "哨兵");
        fallback.put("Sever Soul", "断魂");
        fallback.put("Shockwave", "冲击波");
        fallback.put("Shrug It Off", "抖落");
        fallback.put("Spot Weakness", "洞察弱点");
        fallback.put("True Grit", "坚韧");
        fallback.put("Twin Strike", "双重打击");
        fallback.put("Uppercut", "上勾拳");
        fallback.put("Warcry", "战吼");
        fallback.put("Whirlwind", "旋风斩");
        fallback.put("Wild Strike", "狂野打击");
        fallback.put("Battle Trance", "战斗恍惚");
        fallback.put("Berserk", "狂暴");
        fallback.put("Barricade", "壁垒");
        fallback.put("Bludgeon", "巨刃");
        fallback.put("Brutality", "暴行");
        fallback.put("Double Tap", "二连击");
        fallback.put("Demon Form", "恶魔形态");
        fallback.put("Dropkick", "飞踢");
        fallback.put("Feed", "进食");
        fallback.put("Fire Breathing", "火焰吐息");
        fallback.put("Intimidate", "恐吓");
        fallback.put("Reckless Charge", "鲁莽冲锋");
        fallback.put("Sword Boomerang", "回旋剑");
        fallback.put("Thunderclap", "雷霆一击");
        fallback.put("Blood for Blood", "以血还血");
        fallback.put("Berserk", "狂暴");
        fallback.put("Combust", "燃烧");
        fallback.put("Dazed", "眩晕");
        fallback.put("Slimed", "黏液");
        fallback.put("Wound", "伤口");
        fallback.put("Burn", "灼伤");
        fallback.put("Void", "虚空");
        fallback.put("Injury", "创伤");
        fallback.put("Regret", "悔恨");
        fallback.put("Decay", "腐朽");
        fallback.put("Doubt", "怀疑");
        fallback.put("Shame", "羞耻");
        fallback.put("Pain", "痛苦");
        fallback.put("Normality", "常态");
        fallback.put("Necronomicurse", "亡灵诅咒");
        fallback.put("Curse of the Bell", "钟之诅咒");

        potionFallback.put("Block Potion", "格挡药水");
        potionFallback.put("Fire Potion", "火焰药水");
        potionFallback.put("Explosive Potion", "爆炸药水");
        potionFallback.put("Strength Potion", "力量药水");
        potionFallback.put("Weak Potion", "虚弱药水");
        potionFallback.put("Fear Potion", "恐惧药水");
        potionFallback.put("Blood Potion", "鲜血药水");
        potionFallback.put("Regen Potion", "再生药水");
        potionFallback.put("Energy Potion", "能量药水");
        potionFallback.put("Swift Potion", "迅捷药水");
        potionFallback.put("Liquid Bronze", "液态青铜");
        potionFallback.put("Duplication Potion", "复制药水");
        potionFallback.put("Dexterity Potion", "敏捷药水");
        potionFallback.put("Focus Potion", "专注药水");
        potionFallback.put("Poison Potion", "剧毒药水");
        potionFallback.put("Speed Potion", "速度药水");
        potionFallback.put("Fairy Potion", "瓶中精灵");
        potionFallback.put("Ancient Potion", "远古药水");
        potionFallback.put("Essence of Steel", "钢铁精华");
        potionFallback.put("Gambler's Brew", "赌徒酿");
        potionFallback.put("Smoke Bomb", "烟雾弹");
    }

    public static String zh(String cardId) {
        if (cardId == null || cardId.isEmpty()) {
            return "?";
        }
        String cached = cache.get(cardId);
        if (cached != null) {
            return cached;
        }
        String name = null;
        try {
            CardStrings strings = CardCrawlGame.languagePack.getCardStrings(cardId);
            if (strings != null && strings.NAME != null && !strings.NAME.isEmpty()
                    && !strings.NAME.startsWith("Missing")) {
                name = strings.NAME;
            }
        } catch (Throwable ignored) {
            // 语言包里没有这张牌，用内置表兜底
        }
        if (name == null) {
            name = fallback.get(cardId);
        }
        if (name == null) {
            name = cardId;
        }
        cache.put(cardId, name);
        return name;
    }

    /** 药水 ID → 当前语言显示名（游戏语言包 + 内置对照表兜底）。 */
    public static String zhPotion(String potionId) {
        if (potionId == null || potionId.isEmpty()) {
            return "药水";
        }
        String cached = potionCache.get(potionId);
        if (cached != null) {
            return cached;
        }
        String name = null;
        try {
            PotionStrings strings = CardCrawlGame.languagePack.getPotionString(potionId);
            if (strings != null && strings.NAME != null && !strings.NAME.isEmpty()
                    && !strings.NAME.startsWith("Missing")) {
                name = strings.NAME;
            }
        } catch (Throwable ignored) {
            // 语言包里没有这瓶药水，用内置表兜底
        }
        if (name == null) {
            name = potionFallback.get(potionId);
        }
        if (name == null) {
            name = potionId;
        }
        potionCache.put(potionId, name);
        return name;
    }
}
