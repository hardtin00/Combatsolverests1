import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Reads declarations directly from the installed game's class files; it never loads game classes. */
public final class FieldBytecodeAudit {
    private static final class Info {
        String parent;
        final Map<String, String> fields = new HashMap<String, String>();
        final Map<String, Integer> fieldAccess = new HashMap<String, Integer>();
        final Map<String, String> methods = new HashMap<String, String>();
        final Map<String, Integer> methodAccess = new HashMap<String, Integer>();
    }
    private static final Map<String, Info> CLASSES = new HashMap<String, Info>();
    private static final Map<String, Boolean> READ = new HashMap<String, Boolean>();

    private static void read(JarFile jar, String name) throws Exception {
        if (READ.containsKey(name)) return;
        READ.put(name, Boolean.TRUE);
        String entryName = name + ".class";
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) throw new IllegalStateException("class missing: " + name.replace('/', '.'));
        InputStream in = jar.getInputStream(entry);
        try {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                private final Info info = new Info();
                @Override public void visit(int version, int access, String className, String signature,
                                            String superName, String[] interfaces) {
                    info.parent = superName;
                    CLASSES.put(className, info);
                }
                @Override public FieldVisitor visitField(int access, String fieldName, String descriptor,
                                                         String signature, Object value) {
                    info.fields.put(fieldName, descriptor);
                    info.fieldAccess.put(fieldName, Integer.valueOf(access));
                    return null;
                }
                @Override public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                                           String signature, String[] exceptions) {
                    String key = methodName + descriptor;
                    info.methods.put(key, descriptor);
                    info.methodAccess.put(key, Integer.valueOf(access));
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally { in.close(); }
        Info info = CLASSES.get(name);
        if (info != null && info.parent != null && jar.getJarEntry(info.parent + ".class") != null) read(jar, info.parent);
    }

    private static String fieldOwner(String owner, String name) {
        for (String c = owner; c != null;) {
            Info info = CLASSES.get(c);
            if (info == null) return null;
            if (info.fields.containsKey(name)) return c;
            c = info.parent;
        }
        return null;
    }
    private static String methodOwner(String owner, String name, String descriptor) {
        String key = name + descriptor;
        for (String c = owner; c != null;) {
            Info info = CLASSES.get(c);
            if (info == null) return null;
            if (info.methods.containsKey(key)) return c;
            c = info.parent;
        }
        return null;
    }
    private static String dotted(String name) { return name.replace('/', '.'); }
    private static int failures;
    private static void field(String owner, String name, String expectedDescriptor, Boolean expectStatic) {
        String found = fieldOwner(owner, name);
        if (found == null) {
            System.out.println("MISSING_FIELD " + dotted(owner) + "." + name);
            failures++;
            return;
        }
        Info info = CLASSES.get(found);
        String descriptor = info.fields.get(name);
        int access = info.fieldAccess.get(name).intValue();
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        boolean ok = expectedDescriptor.equals(descriptor) && (expectStatic == null || expectStatic.booleanValue() == isStatic);
        System.out.println((ok ? "MATCH_FIELD " : "MISMATCH_FIELD ") + dotted(owner) + "." + name +
                " declared=" + dotted(found) + " descriptor=" + descriptor + " static=" + isStatic +
                " expected=" + expectedDescriptor + (expectStatic == null ? "" : " static=" + expectStatic));
        if (!ok) failures++;
    }
    private static void privateInstanceField(String owner, String name, String expectedDescriptor) {
        Info info = CLASSES.get(owner);
        if (info == null || !info.fields.containsKey(name)) {
            System.out.println("MISSING_FIELD " + dotted(owner) + "." + name);
            failures++;
            return;
        }
        int access = info.fieldAccess.get(name).intValue();
        boolean isPrivate = (access & Opcodes.ACC_PRIVATE) != 0;
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        boolean ok = expectedDescriptor.equals(info.fields.get(name)) && isPrivate && !isStatic;
        System.out.println((ok ? "MATCH_PRIVATE_INSTANCE_FIELD " : "MISMATCH_PRIVATE_INSTANCE_FIELD ")
                + dotted(owner) + "." + name + " descriptor=" + info.fields.get(name)
                + " private=" + isPrivate + " static=" + isStatic + " expected=" + expectedDescriptor);
        if (!ok) failures++;
    }
    private static void absentDeclaredField(String owner, String name) {
        Info info = CLASSES.get(owner);
        boolean absent = info == null || !info.fields.containsKey(name);
        System.out.println((absent ? "ABSENT_DECLARED_FIELD " : "UNEXPECTED_DECLARED_FIELD ")
                + dotted(owner) + "." + name);
        if (!absent) failures++;
    }
    private static void method(String owner, String name, String descriptor, Boolean expectStatic) {
        String found = methodOwner(owner, name, descriptor);
        if (found == null) {
            System.out.println("MISSING_METHOD " + dotted(owner) + "." + name + descriptor);
            failures++;
            return;
        }
        Info info = CLASSES.get(found);
        int access = info.methodAccess.get(name + descriptor).intValue();
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        boolean ok = expectStatic == null || expectStatic.booleanValue() == isStatic;
        System.out.println((ok ? "MATCH_METHOD " : "MISMATCH_METHOD ") + dotted(owner) + "." + name + descriptor +
                " declared=" + dotted(found) + " static=" + isStatic);
        if (!ok) failures++;
    }
    private static void run(JarFile jar) throws Exception {
        String d="com/megacrit/cardcrawl/dungeons/AbstractDungeon", settings="com/megacrit/cardcrawl/core/Settings";
        String room="com/megacrit/cardcrawl/rooms/AbstractRoom", player="com/megacrit/cardcrawl/characters/AbstractPlayer";
        String energy="com/megacrit/cardcrawl/core/EnergyManager", panel="com/megacrit/cardcrawl/ui/panels/EnergyPanel";
        String card="com/megacrit/cardcrawl/cards/AbstractCard", power="com/megacrit/cardcrawl/powers/AbstractPower";
        String relic="com/megacrit/cardcrawl/relics/AbstractRelic", potion="com/megacrit/cardcrawl/potions/AbstractPotion";
        String group="com/megacrit/cardcrawl/monsters/MonsterGroup", monster="com/megacrit/cardcrawl/monsters/AbstractMonster";
        String sentry="com/megacrit/cardcrawl/monsters/exordium/Sentry", manager="com/megacrit/cardcrawl/actions/GameActionManager";
        String random="com/megacrit/cardcrawl/random/Random", xs="com/badlogic/gdx/math/RandomXS128";
        field(settings,"seed","Ljava/lang/Long;",true);
        for(String n:new String[]{"actNum","floorNum","ascensionLevel","actionManager","aiRng","cardRandomRng","miscRng","monsterHpRng","potionRng","shuffleRng","player","isScreenUp"}) {
            String desc = n.equals("actNum")||n.equals("floorNum")||n.equals("ascensionLevel") ? "I" :
                    n.equals("isScreenUp") ? "Z" : n.equals("player") ? "Lcom/megacrit/cardcrawl/characters/AbstractPlayer;" :
                    n.equals("actionManager") ? "Lcom/megacrit/cardcrawl/actions/GameActionManager;" : "Lcom/megacrit/cardcrawl/random/Random;";
            field(d,n,desc,true);
        }
        method(d,"getCurrRoom","()Lcom/megacrit/cardcrawl/rooms/AbstractRoom;",true);
        field(room,"phase","Lcom/megacrit/cardcrawl/rooms/AbstractRoom$RoomPhase;",false);
        field(room,"event","Lcom/megacrit/cardcrawl/events/AbstractEvent;",false);
        field(room,"eliteTrigger","Z",false);
        field(room,"isBattleOver","Z",false);
        field(room,"skipMonsterTurn","Z",false);
        System.out.println("UNAVAILABLE_FIELD com.megacrit.cardcrawl.rooms.AbstractRoom.name (probe does not read; encounter name must not be synthesized)");
        field(room,"monsters","Lcom/megacrit/cardcrawl/monsters/MonsterGroup;",false);
        field(manager,"turnHasEnded","Z",false);
        field(manager,"currentAction","Lcom/megacrit/cardcrawl/actions/AbstractGameAction;",false);
        field(manager,"usingCard","Z",false); field(manager,"phase","Lcom/megacrit/cardcrawl/actions/GameActionManager$Phase;",false);
        for(String n:new String[]{"actions","cardQueue","monsterQueue"}) field(manager,n,"Ljava/util/ArrayList;",false);
        field(manager,"turn","I",true);
        for(String n:new String[]{"chosenClass","currentHealth","maxHealth","currentBlock","gold","gameHandSize","energy","stance","powers","hand","drawPile","discardPile","exhaustPile","relics","potions"}) {
            String desc = n.equals("currentHealth")||n.equals("maxHealth")||n.equals("currentBlock")||n.equals("gold")||n.equals("gameHandSize") ? "I" :
                    n.equals("chosenClass") ? "Lcom/megacrit/cardcrawl/characters/AbstractPlayer$PlayerClass;" :
                    n.equals("energy") ? "Lcom/megacrit/cardcrawl/core/EnergyManager;" :
                    n.equals("stance") ? "Lcom/megacrit/cardcrawl/stances/AbstractStance;" :
                    n.equals("powers")||n.equals("relics")||n.equals("potions") ? "Ljava/util/ArrayList;" : "Lcom/megacrit/cardcrawl/cards/CardGroup;";
            field(player,n,desc,false);
        }
        field(player,"endTurnQueued","Z",false);
        field(player,"cardsPlayedThisTurn","I",false);
        field(player,"maxOrbs","I",false);
        field(player,"potionSlots","I",false);
        field("com/megacrit/cardcrawl/core/AbstractCreature","lastDamageTaken","I",false);
        field(energy,"energyMaster","I",false); method(panel,"getCurrentEnergy","()I",true);
        field(manager,"cardsPlayedThisTurn","Ljava/util/ArrayList;",false);
        field(manager,"totalDiscardedThisTurn","I",true);
        field(manager,"damageReceivedThisCombat","I",true);
        for(String n:new String[]{"ID","amount"}) field(power,n,n.equals("ID")?"Ljava/lang/String;":"I",false);
        // Timing is not an AbstractPower member: these concrete vanilla
        // powers each own a private instance field read at end-of-round.
        for (String n : new String[]{"WeakPower", "FrailPower", "VulnerablePower", "DoubleDamagePower", "DrawReductionPower"})
        {
            String owner = "com/megacrit/cardcrawl/powers/" + n;
            privateInstanceField(owner, "justApplied", "Z");
            method(owner, "atEndOfRound", "()V", false);
        }
        field("com/megacrit/cardcrawl/powers/ArtifactPower", "ID", "Ljava/lang/String;", false);
        field("com/megacrit/cardcrawl/powers/ArtifactPower", "amount", "I", false);
        method(sentry, "getMove", "(I)V", false);
        method(sentry, "takeTurn", "()V", false);
        absentDeclaredField("com/megacrit/cardcrawl/powers/IntangiblePlayerPower", "justApplied");
        field(card,"uuid","Ljava/util/UUID;",false); field(card,"cardID","Ljava/lang/String;",false);
        for(String n:new String[]{"timesUpgraded","cost","costForTurn","misc"}) field(card,n,"I",false);
        for(String n:new String[]{"freeToPlayOnce","retain"}) field(card,n,"Z",false);
        field(card,"isEthereal","Z",false);
        field("com/megacrit/cardcrawl/cards/CardGroup","group","Ljava/util/ArrayList;",false);
        field(relic,"relicId","Ljava/lang/String;",false); field(relic,"counter","I",false);
        field(potion,"ID","Ljava/lang/String;",false); field(potion,"slot","I",false);
        field(group,"monsters","Ljava/util/ArrayList;",false);
        field("com/megacrit/cardcrawl/events/exordium/DeadAdventurer","enemy","I",false);
        method("com/megacrit/cardcrawl/events/exordium/DeadAdventurer","getMonster","()Ljava/lang/String;",false);
        for(String n:new String[]{"id","nextMove","intent"}) field(monster,n,n.equals("id")?"Ljava/lang/String;":n.equals("nextMove")?"B":"Lcom/megacrit/cardcrawl/monsters/AbstractMonster$Intent;",false);
        for(String n:new String[]{"currentHealth","maxHealth","currentBlock"}) field(monster,n,"I",false);
        for(String n:new String[]{"isDead","isEscaping","halfDead","escapeNext"}) field(monster,n,"Z",false);
        field(monster,"moveHistory","Ljava/util/ArrayList;",false); field(monster,"powers","Ljava/util/ArrayList;",false);
        for(String n:new String[]{"firstMove"}) field(sentry,n,"Z",false);
        for(String n:new String[]{"beamDmg","dazedAmt"}) field(sentry,n,"I",false);
        field(random,"random","Lcom/badlogic/gdx/math/RandomXS128;",false); field(random,"counter","I",false);
        method(xs,"getState","(I)J",false);
    }
    private static void dump(String name) {
        Info i=CLASSES.get(name);
        if(i==null)return;
        System.out.println("CLASS_FIELDS " + dotted(name));
        for(Map.Entry<String,String> e:i.fields.entrySet())
            System.out.println("  " + e.getKey() + " " + e.getValue() + " access=" + i.fieldAccess.get(e.getKey()));
        System.out.println("CLASS_METHODS " + dotted(name));
        for(Map.Entry<String,String> e:i.methods.entrySet())
            System.out.println("  " + e.getKey() + " access=" + i.methodAccess.get(e.getKey()));
    }
    public static void main(String[] args) throws Exception {
        if(args.length<1 || args.length>2) throw new IllegalArgumentException("usage: FieldBytecodeAudit <desktop-1.0.jar> [--dump]");
        JarFile jar=new JarFile(args[0]);
        try {
            String[] classes={"com/megacrit/cardcrawl/dungeons/AbstractDungeon","com/megacrit/cardcrawl/core/Settings",
                    "com/megacrit/cardcrawl/rooms/AbstractRoom","com/megacrit/cardcrawl/characters/AbstractPlayer",
                    "com/megacrit/cardcrawl/core/EnergyManager","com/megacrit/cardcrawl/ui/panels/EnergyPanel",
                    "com/megacrit/cardcrawl/cards/AbstractCard","com/megacrit/cardcrawl/cards/CardGroup",
                    "com/megacrit/cardcrawl/powers/AbstractPower","com/megacrit/cardcrawl/powers/WeakPower",
                    "com/megacrit/cardcrawl/powers/FrailPower","com/megacrit/cardcrawl/powers/VulnerablePower",
                    "com/megacrit/cardcrawl/powers/DoubleDamagePower","com/megacrit/cardcrawl/powers/DrawReductionPower",
                    "com/megacrit/cardcrawl/powers/ArtifactPower",
                    "com/megacrit/cardcrawl/powers/IntangiblePlayerPower","com/megacrit/cardcrawl/relics/AbstractRelic",
                    "com/megacrit/cardcrawl/potions/AbstractPotion","com/megacrit/cardcrawl/monsters/MonsterGroup",
                    "com/megacrit/cardcrawl/monsters/AbstractMonster","com/megacrit/cardcrawl/monsters/exordium/Sentry",
                    "com/megacrit/cardcrawl/rooms/MonsterRoom","com/megacrit/cardcrawl/rooms/MonsterRoomElite",
                    "com/megacrit/cardcrawl/rooms/EventRoom","com/megacrit/cardcrawl/events/exordium/DeadAdventurer",
                    "com/megacrit/cardcrawl/actions/GameActionManager","com/megacrit/cardcrawl/actions/AbstractGameAction",
                    "com/megacrit/cardcrawl/random/Random","com/badlogic/gdx/math/RandomXS128"};
            for(String c:classes) read(jar,c);
            if(args.length>1 && "--dump".equals(args[1])) {
                for(String c:new String[]{"com/megacrit/cardcrawl/core/AbstractCreature","com/megacrit/cardcrawl/powers/AbstractPower",
                        "com/megacrit/cardcrawl/powers/WeakPower","com/megacrit/cardcrawl/powers/FrailPower",
                        "com/megacrit/cardcrawl/powers/VulnerablePower","com/megacrit/cardcrawl/powers/DoubleDamagePower",
                        "com/megacrit/cardcrawl/powers/DrawReductionPower","com/megacrit/cardcrawl/powers/IntangiblePlayerPower",
                        "com/megacrit/cardcrawl/powers/ArtifactPower",
                        "com/megacrit/cardcrawl/cards/AbstractCard","com/megacrit/cardcrawl/monsters/AbstractMonster",
                        "com/megacrit/cardcrawl/rooms/AbstractRoom","com/megacrit/cardcrawl/rooms/MonsterRoom",
                        "com/megacrit/cardcrawl/rooms/MonsterRoomElite","com/megacrit/cardcrawl/rooms/EventRoom",
                        "com/megacrit/cardcrawl/events/exordium/DeadAdventurer",
                        "com/megacrit/cardcrawl/monsters/MonsterGroup","com/megacrit/cardcrawl/monsters/exordium/Sentry",
                        "com/megacrit/cardcrawl/actions/GameActionManager","com/megacrit/cardcrawl/characters/AbstractPlayer"}) dump(c);
                return;
            }
            run(jar);
        } finally { jar.close(); }
        System.out.println("FIELD_AUDIT_RESULT="+(failures==0?"PASS":"FAIL")+" failures="+failures);
        if(failures!=0) System.exit(1);
    }
}
