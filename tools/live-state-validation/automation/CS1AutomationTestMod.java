package cs1automation;

import basemod.BaseMod;
import basemod.interfaces.PostInitializeSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Isolated test driver. It invokes native menu/room lifecycle entry points and never creates game DTOs. */
@SpireInitializer
public final class CS1AutomationTestMod implements PostInitializeSubscriber, PostUpdateSubscriber {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String ENABLED = "1";
    private static final int MENU_PLAY = 0, PLAY_NORMAL = 1, IRONCLAD = 2, NEW_RUN = 3, NEOW = 4, MAP = 5, WAIT_BATTLE = 6, DONE = 7, FAILED = 8;
    private static CS1AutomationTestMod instance;
    private int stage = MENU_PLAY;
    private int ticks;
    private int neowClicks;
    private int mapClicks;
    private int cooldown;
    private String outputPath;
    private String error;
    private long configuredSeed;
    private int targetMonsterCount = 1;
    private List<String> acceptedMonsters = new ArrayList<String>();
    private boolean enabled;

    public static void initialize() { if (instance == null) instance = new CS1AutomationTestMod(); }
    private CS1AutomationTestMod() { }

    @Override public void receivePostInitialize() { BaseMod.subscribe(this); }

    @Override public void receivePostUpdate() {
        if (!enabled && stage == MENU_PLAY && ticks == 0) initializeFromEnvironment();
        if (!enabled || stage == DONE || stage == FAILED) return;
        ticks++;
        if (ticks > 18000) { fail("automation state machine timed out after 18000 game updates"); return; }
        try { drive(); }
        catch (Throwable t) { fail(t.getClass().getName() + ": " + String.valueOf(t.getMessage())); }
    }

    private void initializeFromEnvironment() {
        ticks = 1;
        if (!ENABLED.equals(System.getenv("CS1_AUTOMATION_ENABLED"))) { stage = FAILED; return; }
        String config = System.getenv("CS1_AUTOMATION_SCENARIO_FILE");
        outputPath = System.getenv("CS1_AUTOMATION_OUTPUT_FILE");
        if (config == null || outputPath == null) { fail("controller scenario/output environment is missing"); return; }
        try {
            JsonObject root = new com.google.gson.JsonParser().parse(new String(java.nio.file.Files.readAllBytes(new File(config).toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schema_version").getAsInt() != 1 || !"ironclad-single-monster-v1".equals(root.get("scenario_id").getAsString())
                    || !root.get("automation_enabled").getAsBoolean() || !"IRONCLAD".equals(root.get("character").getAsString())
                    || !"com.megacrit.cardcrawl.rooms.MonsterRoom".equals(root.get("room_class").getAsString())
                    || !"com.megacrit.cardcrawl.monsters.exordium.JawWorm".equals(root.get("expected_monster_class").getAsString()))
                throw new IllegalStateException("unsupported automation scenario configuration");
            configuredSeed = Long.parseLong(System.getenv("CS1_AUTOMATION_SEED"));
            targetMonsterCount = root.get("target_monster_count").getAsInt();
            for (com.google.gson.JsonElement e : root.getAsJsonArray("accepted_vanilla_monster_classes")) acceptedMonsters.add(e.getAsString());
            if (targetMonsterCount != 1 || acceptedMonsters.size() != 1 || !acceptedMonsters.contains("com.megacrit.cardcrawl.monsters.exordium.JawWorm")) throw new IllegalStateException("single vanilla Jaw Worm allowlist is required");
            enabled = true; writeStatus("READY", "configuration validated; waiting for native main menu", null);
        } catch (Throwable t) { fail("scenario configuration rejected: " + t.getMessage()); }
    }

    private void drive() throws Exception {
        switch (stage) {
            case MENU_PLAY: if (clickPlay()) { stage = PLAY_NORMAL; writeStatus("MENU_PLAY_SELECTED", "native PLAY button activated", null); } break;
            case PLAY_NORMAL: if (clickPlayNormal()) { stage = IRONCLAD; writeStatus("PLAY_NORMAL_SELECTED", "native Play Normal panel activated", null); } break;
            case IRONCLAD: if (selectIronclad()) { stage = NEW_RUN; writeStatus("IRONCLAD_SELECTED", "native Ironclad character option selected", null); } break;
            case NEW_RUN: if (startNativeRun()) { stage = NEOW; writeStatus("NATIVE_NEW_RUN_STARTED", "native character confirmation and seed initialization completed", null); } break;
            case NEOW: if (handleNeowOrAdvance()) { stage = MAP; writeStatus("NEOW_COMPLETED", "native Neow dialog advanced to map flow", null); } break;
            case MAP: if (driveNativeMap()) { stage = WAIT_BATTLE; writeStatus("NATIVE_MONSTER_ROOM_ENTERED", "native map transition entered the configured vanilla room", null); } break;
            case WAIT_BATTLE: inspectBattle(); break;
            default: fail("invalid automation state " + stage);
        }
        if (cooldown > 0) cooldown--;
    }

    private boolean clickPlay() throws Exception {
        Object menu = staticField("com.megacrit.cardcrawl.core.CardCrawlGame", "mainMenuScreen");
        if (menu == null || !"MAIN_MENU".equals(enumName(field(menu, "screen")))) return false;
        Object buttons = field(menu, "buttons");
        if (!(buttons instanceof List)) return false;
        for (Object button : (List<?>)buttons) if ("PLAY".equals(enumName(field(button, "result")))) { invoke(button, "buttonEffect"); return true; }
        return false;
    }

    private boolean clickPlayNormal() throws Exception {
        Object menu = staticField("com.megacrit.cardcrawl.core.CardCrawlGame", "mainMenuScreen");
        if (menu == null || !"PANEL_MENU".equals(enumName(field(menu, "screen")))) return false;
        Object panelScreen = field(menu, "panelScreen");
        Object panels = field(panelScreen, "panels");
        if (!(panels instanceof List)) return false;
        for (Object panel : (List<?>)panels) if ("PLAY_NORMAL".equals(enumName(field(panel, "result")))) { invoke(panel, "buttonEffect"); return true; }
        return false;
    }

    private boolean selectIronclad() throws Exception {
        Object menu = staticField("com.megacrit.cardcrawl.core.CardCrawlGame", "mainMenuScreen");
        if (menu == null || !"CHAR_SELECT".equals(enumName(field(menu, "screen")))) return false;
        Object screen = field(menu, "charSelectScreen");
        Object options = field(screen, "options");
        if (!(options instanceof List)) return false;
        for (Object option : (List<?>)options) {
            Object character = field(option, "c");
            if ("IRONCLAD".equals(enumName(field(character, "chosenClass")))) {
                Object hb = field(option, "hb"); setField(hb, "clicked", true); invoke(option, "update"); return true;
            }
        }
        fail("native character-select screen has no Ironclad option"); return false;
    }

    private boolean startNativeRun() throws Exception {
        Object menu = staticField("com.megacrit.cardcrawl.core.CardCrawlGame", "mainMenuScreen");
        if (menu == null || !"CHAR_SELECT".equals(enumName(field(menu, "screen")))) return false;
        Object screen = field(menu, "charSelectScreen");
        Object settings = Class.forName("com.megacrit.cardcrawl.core.Settings");
        setStaticField((Class<?>)settings, "seed", Long.valueOf(configuredSeed));
        Object confirm = field(screen, "confirmButton"); Object hb = field(confirm, "hb");
        if (Boolean.TRUE.equals(field(confirm, "isDisabled"))) return false;
        setField(hb, "clicked", true); invoke(screen, "updateButtons");
        return true;
    }

    private boolean handleNeowOrAdvance() throws Exception {
        Object room = invokeStatic("com.megacrit.cardcrawl.dungeons.AbstractDungeon", "getCurrRoom");
        if (room == null) return false;
        if (!"NeowRoom".equals(room.getClass().getSimpleName())) { stage = MAP; return true; }
        if (cooldown > 0) return false;
        Object screen = staticField("com.megacrit.cardcrawl.dungeons.AbstractDungeon", "screen");
        if (!"EVENT".equals(enumName(screen))) return false;
        Object event = field(room, "event");
        if (event == null) { fail("NeowRoom has no native event object"); return false; }
        Object text = field(event, "roomEventText");
        Object list = field(text, "optionList");
        if (!(list instanceof List) || ((List<?>)list).isEmpty()) return false;
        Object first = ((List<?>)list).get(0);
        Object disabled = optionalField(first, "disabled");
        if (Boolean.TRUE.equals(disabled)) { fail("first native Neow option is disabled; refusing to guess another choice"); return false; }
        Object hb = field(first, "hb"); setField(hb, "clicked", true); cooldown = 20; neowClicks++;
        if (neowClicks > 8) { fail("Neow did not advance after eight native first-option choices"); return false; }
        writeStatus("NEOW_OPTION_SELECTED", "selected native option index 0; choice count=" + neowClicks, null);
        return false;
    }

    private boolean driveNativeMap() throws Exception {
        Object screen = staticField("com.megacrit.cardcrawl.dungeons.AbstractDungeon", "screen");
        if (!"MAP".equals(enumName(screen))) {
            Object room=invokeStatic("com.megacrit.cardcrawl.dungeons.AbstractDungeon", "getCurrRoom");
            if (room != null && "com.megacrit.cardcrawl.rooms.MonsterRoom".equals(room.getClass().getName())) return true;
            if (mapClicks > 0 && room != null && !"NeowRoom".equals(room.getClass().getSimpleName()))
                fail("native map transition entered a room outside the configured MonsterRoom allowlist: " + room.getClass().getName());
            return false;
        }
        if (cooldown > 0) return false;
        Object dungeonMap = staticField("com.megacrit.cardcrawl.dungeons.AbstractDungeon", "map");
        if (!(dungeonMap instanceof List) || ((List<?>)dungeonMap).isEmpty()) { fail("native map is unavailable"); return false; }
        Object current = invokeStatic("com.megacrit.cardcrawl.dungeons.AbstractDungeon", "getCurrMapNode");
        boolean firstChosen = Boolean.TRUE.equals(staticField("com.megacrit.cardcrawl.dungeons.AbstractDungeon", "firstRoomChosen"));
        if (firstChosen && current == null) return false;
        Object target = null;
        if (!firstChosen) {
            Object row0 = ((List<?>)dungeonMap).get(0);
            if (row0 instanceof List) for (Object node : (List<?>)row0) if (node != null) { target = node; break; }
        } else {
            Object row = null;
            for (Object candidateRow : (List<?>)dungeonMap) if (candidateRow instanceof List) for (Object node : (List<?>)candidateRow) {
                if (node == null || current == null) continue;
                if (!Boolean.TRUE.equals(invoke(node, "isConnectedTo", current))) continue;
                Object room = optionalField(node, "room");
                if (room == null || !"com.megacrit.cardcrawl.rooms.MonsterRoom".equals(room.getClass().getName())) continue;
                Object group = optionalField(room, "monsters"); Object monsters = group == null ? null : optionalField(group, "monsters");
                // MonsterRoom creates its native MonsterGroup in onPlayerEntry. If it is already present,
                // prefer only an exact match; otherwise enter through the native room lifecycle and
                // validate the real roster before reporting success.
                if (monsters instanceof List) {
                    if (((List<?>)monsters).size() != targetMonsterCount) continue;
                    Object firstMonster = ((List<?>)monsters).get(0);
                    if (firstMonster == null || !acceptedMonsters.contains(firstMonster.getClass().getName())) continue;
                }
                target = node; break;
            }
        }
        if (target == null) {
            if (firstChosen) fail("no immediately connected native MonsterRoom has the configured vanilla single-monster roster; refusing an unapproved route");
            return false;
        }
        Object hb = field(target, "hb");
        if (mapClicks >= 4) { fail("native map click did not advance after four attempts"); return false; }
        moveNativeCursorToHitbox(hb);
        Object mapScreen = staticField("com.megacrit.cardcrawl.dungeons.AbstractDungeon", "dungeonMapScreen");
        setField(mapScreen, "clicked", true);
        cooldown = 30; mapClicks++;
        writeStatus("MAP_NODE_REQUESTED", "native map UI click queued for node; click count=" + mapClicks, null);
        return false;
    }

    private void moveNativeCursorToHitbox(Object hitbox) throws Exception {
        float cx = ((Number)field(hitbox,"cX")).floatValue(); float cy = ((Number)field(hitbox,"cY")).floatValue();
        Class<?> settings = Class.forName("com.megacrit.cardcrawl.core.Settings");
        int width = ((Number)staticField("com.megacrit.cardcrawl.core.Settings","WIDTH")).intValue(); int height = ((Number)staticField("com.megacrit.cardcrawl.core.Settings","HEIGHT")).intValue();
        Object graphics = staticField("com.badlogic.gdx.Gdx","graphics"); Object input = staticField("com.badlogic.gdx.Gdx","input");
        int screenW = ((Number)invoke(graphics,"getWidth")).intValue(); int screenH = ((Number)invoke(graphics,"getHeight")).intValue();
        int x = Math.round(cx * screenW / (float)width); int y = screenH - Math.round(cy * screenH / (float)height);
        invoke(input,"setCursorPosition",Integer.valueOf(x),Integer.valueOf(y));
    }

    private void inspectBattle() throws Exception {
        Object room = invokeStatic("com.megacrit.cardcrawl.dungeons.AbstractDungeon", "getCurrRoom");
        if (room == null || !"com.megacrit.cardcrawl.rooms.MonsterRoom".equals(room.getClass().getName())) return;
        Object group = field(room,"monsters"); Object monsters = field(group,"monsters");
        if (!(monsters instanceof List) || ((List<?>)monsters).size() != targetMonsterCount) { fail("entered vanilla MonsterRoom has an unsupported monster count"); return; }
        for (Object monster : (List<?>)monsters) if (!acceptedMonsters.contains(monster.getClass().getName())) { fail("encounter contains a monster outside the configured vanilla allowlist: " + monster.getClass().getName()); return; }
        Object phase = field(room,"phase"); Object manager = staticField("com.megacrit.cardcrawl.dungeons.AbstractDungeon","actionManager");
        Object player = staticField("com.megacrit.cardcrawl.dungeons.AbstractDungeon","player");
        Object actions = optionalField(manager,"actions"); Object cardQueue = optionalField(manager,"cardQueue"); Object monsterQueue = optionalField(manager,"monsterQueue");
        boolean stable = "COMBAT".equals(enumName(phase)) && manager != null && player != null
                && !Boolean.TRUE.equals(optionalField(room,"isBattleOver")) && !Boolean.TRUE.equals(optionalField(room,"skipMonsterTurn"))
                && !Boolean.TRUE.equals(optionalField(manager,"turnHasEnded")) && !Boolean.TRUE.equals(optionalField(player,"endTurnQueued"))
                && optionalField(manager,"currentAction") == null && !Boolean.TRUE.equals(optionalField(manager,"usingCard"))
                && actions instanceof List && ((List<?>)actions).isEmpty() && cardQueue instanceof List && ((List<?>)cardQueue).isEmpty()
                && monsterQueue instanceof List && ((List<?>)monsterQueue).isEmpty()
                && !Boolean.TRUE.equals(staticField("com.megacrit.cardcrawl.dungeons.AbstractDungeon","isScreenUp"));
        if (stable) { stage=DONE; writeStatus("BATTLE_STABLE", "vanilla Ironclad single-monster battle reached; no cards were played", null); }
    }

    private void fail(String why) { error=why; enabled=false; stage=FAILED; writeStatus("FAILED", why, why); }

    private void writeStatus(String status, String message, String failure) {
        if (outputPath == null || outputPath.trim().isEmpty()) return;
        try {
            File out = new File(outputPath); File parent=out.getParentFile(); if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            JsonObject j=new JsonObject(); j.addProperty("protocol","cs1_automation_mod_status"); j.addProperty("version",1);
            j.addProperty("status",status); j.addProperty("stage",stage); j.addProperty("tick",ticks); j.addProperty("observed_at_epoch_ms",System.currentTimeMillis());
            j.addProperty("scenario_id","ironclad-single-monster-v1"); j.addProperty("native_menu_flow",true); j.addProperty("native_battle_objects_only",true);
            j.addProperty("seed",configuredSeed); j.addProperty("neow_choices",neowClicks); j.addProperty("map_clicks",mapClicks); j.addProperty("message",message);
            if (failure == null) j.add("error",null); else j.addProperty("error",failure);
            OutputStreamWriter writer=new OutputStreamWriter(new FileOutputStream(out,false),StandardCharsets.UTF_8);
            try { JSON.toJson(j,writer); } finally { writer.close(); }
        } catch (Throwable ignored) { }
    }

    private static String enumName(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Object staticField(String className,String name) throws Exception { return field(Class.forName(className),null,name); }
    private static Object invokeStatic(String className,String name) throws Exception { return invoke(Class.forName(className),name); }
    private static Object field(Object target,String name) throws Exception { return field(target.getClass(),target,name); }
    private static Object optionalField(Object target,String name) { try { return field(target,name); } catch (Throwable ignored) { return null; } }
    private static Object field(Class<?> type,Object receiver,String name) throws Exception {
        for(Class<?> c=type;c!=null;c=c.getSuperclass()) try { Field f=c.getDeclaredField(name); f.setAccessible(true); return f.get(receiver); } catch(NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(type.getName()+"."+name);
    }
    private static void setField(Object target,String name,Object value) throws Exception { Field f=findField(target.getClass(),name); f.setAccessible(true); f.set(target,value); }
    private static void setStaticField(Class<?> type,String name,Object value) throws Exception { Field f=findField(type,name); f.setAccessible(true); f.set(null,value); }
    private static Field findField(Class<?> type,String name) throws Exception { for(Class<?> c=type;c!=null;c=c.getSuperclass()) try { return c.getDeclaredField(name); } catch(NoSuchFieldException ignored) { } throw new NoSuchFieldException(type.getName()+"."+name); }
    private static Object invoke(Object targetOrClass,String name,Object... args) throws Exception {
        Class<?> type=targetOrClass instanceof Class?(Class<?>)targetOrClass:targetOrClass.getClass();
        for(Class<?> c=type;c!=null;c=c.getSuperclass()) for(Method m:c.getDeclaredMethods()) if(m.getName().equals(name)&&m.getParameterTypes().length==args.length) {
            Class<?>[] p=m.getParameterTypes(); boolean ok=true; for(int i=0;i<p.length;i++) if(args[i]!=null && !wrap(p[i]).isAssignableFrom(args[i].getClass())) { ok=false; break; }
            if(ok) { m.setAccessible(true); return m.invoke(targetOrClass instanceof Class?null:targetOrClass,args); }
        }
        throw new NoSuchMethodException(type.getName()+"."+name+"/"+args.length);
    }
    private static Class<?> wrap(Class<?> c) { if(!c.isPrimitive())return c; if(c==boolean.class)return Boolean.class;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==float.class)return Float.class;if(c==double.class)return Double.class;if(c==short.class)return Short.class;if(c==byte.class)return Byte.class;if(c==char.class)return Character.class;return c; }
}
