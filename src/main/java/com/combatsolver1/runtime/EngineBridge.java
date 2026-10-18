package com.combatsolver1.runtime;

import com.combatsolver1.CombatLog;
import com.combatsolver1.search.Plan;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.Writer;

/**
 * 与 sts_lightspeed 引擎的桥：调用引擎跑战斗搜索，并把它的结构化输出解析成我们的 Plan。
 *
 * 两个引擎命令：
 *   mcts_save    <存档> <模拟次数>                      —— 从战斗起点推演整场
 *   replay_search <存档> <动作文件> <模拟次数>          —— 先回放已打出的动作，再从当前状态推演
 *
 * 结构化输出（机器接口，ASCII）：
 *   PLANCARD idx=<手牌下标> id=<卡牌ID> cost=<费用> upg=<0/1> tgt=<目标下标>
 *   PLANPOTION idx=<药水槽> id=<药水ID> tgt=<目标下标>   （tgt>5 表示丢弃）
 *   PLANSELECT idx=<选择下标> | PLANSELECT idxs=<逗号分隔>
 *   PLANTURNEND n=<第几回合> hp= blk= en= turn= hand=[...] mons=...
 *   PLANEND hp=<最终生命> maxHp=<最大生命>
 *   STATE / STATE0 ...（实机可对比的状态行）
 *   REPLAY_OK n=<回放动作数> / REPLAY_INVALID n=<第几个动作> bits=<十六进制>
 */
public final class EngineBridge {
    private EngineBridge() {
    }

    private static String enginePath = "";
    private static String mingwBin = "";
    private static File workDir;

    /**
     * 游戏安装目录。
     *
     * 原来 `new File("saves")` / `new File("mods/combatsolver1-bridge")` 都是相对**进程工作目录**，
     * 而经 ModTheSpire 启动时 JVM 的工作目录经常不是游戏根目录 → 找不到自动存档 →
     * 静默回退到内置近似引擎，UI 上完全看不出来。这里改成从游戏本体的 jar 位置推导绝对路径，
     * 并允许用系统属性 / 环境变量 / 设置项覆盖。
     */
    public static File gameDirectory() {
        String configured = System.getProperty("combatsolver1.gameDir", "");
        if (configured.isEmpty()) {
            configured = System.getenv("COMBATSOLVER1_GAMEDIR");
        }
        if (configured != null && !configured.isEmpty()) {
            File f = new File(configured);
            if (f.isDirectory()) {
                return f;
            }
        }
        try {
            // 关键：引用**游戏本体**的类（不是本 mod 的类），它的 code source 才是游戏目录
            File self = new File(com.megacrit.cardcrawl.core.Settings.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            File dir = self.isDirectory() ? self : self.getParentFile();
            if (dir != null && dir.isDirectory()) {
                return dir;
            }
        } catch (Throwable ignored) {
            // 取不到就退回工作目录
        }
        return new File(System.getProperty("user.dir", "."));
    }

    public static void configure(String engineExe, String mingwBinDir) {
        enginePath = engineExe;
        mingwBin = mingwBinDir;
    }

    public static boolean isConfigured() {
        return enginePath != null && !enginePath.isEmpty();
    }

    static File engineExecutable() { return isConfigured() ? new File(enginePath) : null; }

    /** 当前实际使用的引擎描述：写进日志与战斗记录，避免测试结果对不上来源。 */
    public static String describe() {
        return isConfigured() ? "sts_lightspeed(" + enginePath + ")" : "local";
    }

    public static File workDirectory() {
        if (workDir == null) {
            workDir = new File(gameDirectory(), "mods/combatsolver1-bridge");
        }
        return workDir;
    }

    /** 一次引擎调用的结果。 */
    public static class Fetch {
        public Plan plan;
        public String state0;
        public String state;
        public String lastMons;
        /** 引擎给出的最终战斗结局：0 未定、1 胜利、2 失败。 */
        public int outcome = -1;
        public boolean replayInvalid;
        public int replayActions = -1;
    }

    /** 找到游戏目录下最新的自动存档（战斗开始时游戏刚写过）。 */
    public static java.io.File latestAutosave() {
        java.io.File dir = new java.io.File(gameDirectory(), "saves");
        java.io.File best = null;
        java.io.File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        for (java.io.File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".autosave")) {
                continue;
            }
            if (best == null || f.lastModified() > best.lastModified()) {
                best = f;
            }
        }
        return best;
    }

    /**
     * 战斗开始时存档是异步写盘的：等待最多 2.5 秒，只返回达到新鲜度门槛的文件。
     * 超时或线程中断时拒绝旧存档，不再把上一次战斗的存档当成本场输入。
     */
    public static java.io.File freshAutosave(long notOlderThanMillis) {
        long deadline = System.currentTimeMillis() + 2500L;
        java.io.File best = latestAutosave();
        while (System.currentTimeMillis() < deadline) {
            best = latestAutosave();
            if (best != null && best.lastModified() >= notOlderThanMillis) {
                return best;
            }
            try {
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return best != null && best.lastModified() >= notOlderThanMillis ? best : null;
            }
        }
        if (best != null && best.lastModified() >= notOlderThanMillis) return best;
        CombatLog.info("no fresh autosave for this battle; stale save rejected");
        return null;
    }

    /** 复制存档到临时路径，供引擎读取（避免与游戏写入冲突）。 */
    public static String stageSaveFile(java.io.File source, java.io.File tempDir) {
        try {
            if (source == null) {
                return null;
            }
            if (tempDir != null && !tempDir.isDirectory()) {
                tempDir.mkdirs();
            }
            java.io.File dst = new java.io.File(tempDir, "engine-in.save");
            java.nio.file.Files.copy(source.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            CombatLog.error("stage save failed", t);
            return null;
        }
    }

    /**
     * 把自动存档整理成引擎能直接用的“战斗起点存档”。
     *
     * 游戏只在部分时机写盘：像“?”节点现场变成战斗时，存档里记的还是事件房、没有怪物，
     * 引擎就会回一句 “Only save files from the start of combat are supported right now.”
     * 并给不出计划。此时只要把存档里的房间类型改成当前真正在打的房间类型，
     * 引擎就会用同一套 RNG 重新生成这一场遭遇（已验证能对上实机）。
     *
     * @param roomClassName 实机房间类名，例如 com.megacrit.cardcrawl.rooms.MonsterRoom
     * @return 转换后的战斗起点存档路径；转换失败返回 null，调用方不得静默使用其他存档
     */
    public static String prepareCombatSave(java.io.File autosave, String roomClassName,
                                           java.io.File tempDir) {
        String staged = stageSaveFile(autosave, tempDir);
        if (staged == null) {
            return null;
        }
        if (roomClassName == null || roomClassName.isEmpty()) {
            return staged;
        }
        try {
            if (tempDir != null && !tempDir.isDirectory() && !tempDir.mkdirs()) {
                return null;
            }
            File rawJson = new File(tempDir, "save-dump.json");
            if (!runSimple("json", staged, rawJson.getAbsolutePath())) {
                return null;
            }
            String text = new String(java.nio.file.Files.readAllBytes(rawJson.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            String patched = patchRoomType(text, roomClassName);
            if (patched == null) {
                return null;
            }
            File patchedJson = new File(tempDir, "save-patched.json");
            java.nio.file.Files.write(patchedJson.toPath(),
                    patched.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            File combatSave = new File(tempDir, "engine-in.save");
            if (!runSimple("json_to_save", patchedJson.getAbsolutePath(),
                    combatSave.getAbsolutePath())) {
                return null;
            }
            CombatLog.info("engine combat save prepared for " + roomClassName);
            return combatSave.getAbsolutePath();
        } catch (Throwable t) {
            CombatLog.error("prepare combat save failed", t);
            return null;
        }
    }

    /** 把 JSON 里的 current_room 换成实机房间类名；已经是目标值就原样返回。 */
    static String patchRoomType(String json, String roomClassName) {
        int at = json.indexOf("\"current_room\"");
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at);
        if (colon < 0) {
            return null;
        }
        int firstQuote = json.indexOf('"', colon + 1);
        int secondQuote = firstQuote < 0 ? -1 : json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return null;
        }
        String current = json.substring(firstQuote + 1, secondQuote);
        if (current.equals(roomClassName)) {
            return json;
        }
        return json.substring(0, firstQuote + 1) + roomClassName + json.substring(secondQuote);
    }

    /** 跑一条简单的引擎子命令（json / json_to_save）。 */
    private static boolean runSimple(String... args) {
        if (!isConfigured()) {
            return false;
        }
        try {
            String[] command = new String[args.length + 1];
            command[0] = enginePath;
            System.arraycopy(args, 0, command, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(enginePath).getParentFile());
            if (mingwBin != null && !mingwBin.isEmpty()) {
                String path = pb.environment().get("PATH");
                pb.environment().put("PATH", mingwBin + ";" + (path == null ? "" : path));
            }
            Process p = pb.start();
            startWatchdog(p);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            while (r.readLine() != null) {
                // 读到 EOF，避免子进程卡在管道上
            }
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Throwable t) {
            CombatLog.error("engine command failed: " + args[0], t);
            return false;
        }
    }

    /** 从战斗起点推演整场（引擎命令 mcts_save）。 */
    public static Fetch fetchPlan(String savePath, int simulations) {
        return run(savePath, null, simulations);
    }

    /** 先回放动作日志，再从当前状态推演（引擎命令 replay_search）。 */
    public static Fetch fetchReplayPlan(String savePath, String journalPath, int simulations) {
        return run(savePath, journalPath, simulations);
    }

    private static Fetch run(String savePath, String journalPath, int simulations) {
        if (!isConfigured()) {
            return null;
        }
        try {
            ProcessBuilder pb;
            if (journalPath == null) {
                pb = new ProcessBuilder(enginePath, "mcts_save", savePath,
                        String.valueOf(simulations));
            } else {
                pb = new ProcessBuilder(enginePath, "replay_search", savePath, journalPath,
                        String.valueOf(simulations));
            }
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(enginePath).getParentFile());
            if (mingwBin != null && !mingwBin.isEmpty()) {
                String path = pb.environment().get("PATH");
                pb.environment().put("PATH", mingwBin + ";" + (path == null ? "" : path));
            }
            Process p = pb.start();
            startWatchdog(p);

            Fetch fetch = new Fetch();
            Plan plan = new Plan();
            plan.source = "engine";
            Plan.Turn current = null;
            boolean finished = false;
            int lastTurnNumber = 0;
            int lastLiveTurn = -1;
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                // 引擎在计划之后还会打印搜索树；必须把输出全部读完，否则会让子进程阻塞在管道上。
                if (finished) {
                    continue;
                }
                if (s.startsWith("STATE0")) {
                    fetch.state0 = s;
                    fetch.lastMons = field(s, "mons=");
                    continue;
                }
                if (s.startsWith("STATE ")) {
                    fetch.state = s;
                    fetch.lastMons = field(s, "mons=");
                    continue;
                }
                if (s.startsWith("REPLAY_OK")) {
                    fetch.replayActions = intField(s, "n=", -1);
                    continue;
                }
                if (s.startsWith("REPLAY_INVALID")) {
                    fetch.replayInvalid = true;
                    continue;
                }
                if (s.startsWith("PLANEND")) {
                    plan.endHp = intField(s, "hp=", plan.endHp);
                    fetch.outcome = intField(s, "outcome=", fetch.outcome);
                    String mons = field(s, "mons=");
                    if (mons != null) {
                        fetch.lastMons = mons;
                    }
                    finished = true;
                    continue;
                }
                if (s.startsWith("PLANTURNEND")) {
                    if (current == null) {
                        current = new Plan.Turn();
                    }
                    current.turnNumber = intField(s, "n=", current.turnNumber);
                    // 引擎的回合计数从 0 开始：这里 turn= 正是“这一段动作所在的实机回合”。
                    current.liveTurnNumber = intField(s, "turn=", -1);
                    current.hpAfter = intField(s, "hp=", current.hpAfter);
                    current.endBlock = intField(s, "blk=", -1);
                    current.endEnergy = intField(s, "en=", -1);
                    current.endState = s;
                    current.endsTurn = true;
                    current.killsAll = allMonstersDead(field(s, "mons="));
                    fetch.lastMons = field(s, "mons=");
                    lastTurnNumber = current.turnNumber;
                    lastLiveTurn = current.liveTurnNumber;
                    plan.turns.add(current);
                    current = null;
                    continue;
                }
                if (s.startsWith("PLANCARD")) {
                    if (current == null) {
                        current = new Plan.Turn();
                    }
                    Plan.Action a = new Plan.Action(field(s, "id="), null,
                            intField(s, "tgt=", -1), intField(s, "cost=", 0));
                    a.kind = Plan.Action.KIND_CARD;
                    a.handIndex = intField(s, "idx=", -1);
                    a.upgraded = intField(s, "upg=", 0) != 0;
                    a.cardName = a.cardId;
                    current.actions.add(a);
                    continue;
                }
                if (s.startsWith("PLANPOTION")) {
                    if (current == null) {
                        current = new Plan.Turn();
                    }
                    int target = intField(s, "tgt=", -1);
                    Plan.Action a = new Plan.Action("POTION:" + field(s, "id="), field(s, "id="),
                            target, 0);
                    a.kind = Plan.Action.KIND_POTION;
                    a.colorIndex = 4;
                    a.potionIndex = intField(s, "idx=", -1);
                    a.discardPotion = target > 5;
                    current.actions.add(a);
                    continue;
                }
                if (s.startsWith("PLANSELECT")) {
                    if (current == null) {
                        current = new Plan.Turn();
                    }
                    Plan.Action a = com.combatsolver1.search.ChoiceProtocol.parse(s);
                    current.actions.add(a);
                    continue;
                }
            }
            int exitCode = p.waitFor();
            // Reject partial output from a crash, timeout or invalid replay.
            if (exitCode != 0 || !finished || fetch.replayInvalid
                    || (journalPath != null && fetch.replayActions < 0)) {
                CombatLog.info("engine result rejected: exit=" + exitCode
                        + " complete=" + finished + " replayInvalid=" + fetch.replayInvalid);
                return null;
            }
            if (current != null) {
                if (current.turnNumber == 0) {
                    current.turnNumber = lastTurnNumber + 1;
                }
                if (current.liveTurnNumber < 0) {
                    current.liveTurnNumber = lastLiveTurn + 1;
                }
                plan.turns.add(current);
            }
            plan.state0Line = fetch.state0;
            plan.stateLine = fetch.state;
            plan.turnsToWin = plan.turns.size();
            String ref = fetch.state != null ? fetch.state : fetch.state0;
            if (ref != null) {
                int startHp = intField(ref, "hp=", plan.endHp);
                plan.startHp = startHp;
                plan.predictedHpLoss = Math.max(0, startHp - plan.endHp);
            }
            plan.predictedWin = fetch.outcome == 1
                    || (plan.endHp > 0 && planEndsWithAllMonstersDead(fetch));
            fetch.plan = plan.turns.isEmpty() ? null : plan;
            return fetch;
        } catch (Throwable t) {
            CombatLog.error("engine bridge failed", t);
            return null;
        }
    }

    /** 引擎要是卡死（复杂战斗 + 高预算），最多等 25 秒就强杀，避免工作线程永远挂着。 */
    private static void startWatchdog(final Process p) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!p.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)) {
                        CombatLog.info("engine bridge timeout; killing engine process");
                        p.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "CombatSolver1-EngineWatchdog");
        t.setDaemon(true);
        t.start();
    }

    /** 把动作日志写入桥目录，返回文件路径。 */
    public static String writeJournal(Iterable<String> actionHex) {
        if (BattleArtifacts.isActive(BattleArtifacts.currentId())) {
            if (BattleArtifacts.replaceJournal(actionHex)) {
                File file = new File(BattleArtifacts.sessionDirectory(), "actions.journal");
                return file.getAbsolutePath();
            }
            String reason = BattleArtifacts.journalFailureReason();
            CombatLog.info("write journal failed: battle artifact journal could not be updated; reason="
                    + (reason == null || reason.isEmpty() ? "no underlying IOException captured" : reason));
            return null;
        }
        try {
            java.io.File dir = workDirectory();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            File f = new File(workDirectory(), "journal.txt");
            Writer w = new FileWriter(f);
            try {
                for (String hex : actionHex) {
                    w.write(hex);
                    w.write('\n');
                }
            } finally {
                w.close();
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            CombatLog.error("write journal failed", t);
            return null;
        }
    }

    /** 取出 "key=value" 形式的整数字段；找不到返回 def。 */
    static int intField(String line, String key, int def) {
        int at = line.indexOf(key);
        if (at < 0) {
            return def;
        }
        int i = at + key.length();
        int start = i;
        while (i < line.length()) {
            char c = line.charAt(i);
            if ((c >= '0' && c <= '9') || (i == start && c == '-')) {
                i++;
            } else {
                break;
            }
        }
        if (i == start) {
            return def;
        }
        try {
            return Integer.parseInt(line.substring(start, i));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 取出 "key=value" 形式的文本字段（到空格为止）；找不到返回 null。 */
    static String field(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) {
            return null;
        }
        int i = at + key.length();
        int start = i;
        if (key.endsWith("[")) {
            while (i < line.length() && line.charAt(i) != ']') i++;
            if (i < line.length()) i++;
        } else {
            while (i < line.length() && !Character.isWhitespace(line.charAt(i))) i++;
        }
        return i > start ? line.substring(start, i) : null;
    }

    /** 计划结束时是否所有怪物都已倒下。 */
    static boolean planEndsWithAllMonstersDead(Fetch fetch) {
        return allMonstersDead(fetch.lastMons);
    }

    /** "hp:blk|hp:blk" 形式的怪物列表是否全部 0 血。 */
    static boolean allMonstersDead(String mons) {
        if (mons == null || mons.isEmpty()) {
            return false;
        }
        for (String part : mons.split("\\|")) {
            int colon = part.indexOf(':');
            String hp = colon >= 0 ? part.substring(0, colon) : part;
            try {
                if (Integer.parseInt(hp.trim()) > 0) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
