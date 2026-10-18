package com.combatsolver1.search;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Pure value protocol. Exhume v3 carries the whole candidate pool for native identity checks. */
public final class ChoiceProtocol {
    private ChoiceProtocol() {}
    public static Plan.Action parse(String line) {
        Map<String,String> fields = new HashMap<>();
        for (String token : line.split(" +")) {
            int at = token.indexOf('=');
            if (at > 0) fields.put(token.substring(0, at), token.substring(at+1));
        }
        int version;
        try { version = Integer.parseInt(fields.get("v")); }
        catch (RuntimeException e) { throw new IllegalArgumentException("selection protocol version required"); }
        Plan.Action a = new Plan.Action("SELECT", "选择卡牌", -1, 0);
        a.kind = Plan.Action.KIND_SELECT;
        a.colorIndex = 5;
        a.selectProtocolVersion = version;
        a.selectTask = fields.get("task");
        a.selectSource = fields.get("source");
        a.selectMulti = "1".equals(fields.get("multi"));
        a.selectSkip = "1".equals(fields.get("skip"));
        a.selectBits = fields.get("bits");
        String indices = fields.get("idxs"), cards = fields.get("cards");
        if (a.selectTask == null || a.selectSource == null || indices == null || cards == null)
            throw new IllegalArgumentException("incomplete choice");
        if (!indices.equals("-")) {
            String[] ii = indices.split(",", -1), cc = cards.split("\\|", -1);
            if (ii.length != cc.length || ii.length > 10) throw new IllegalArgumentException("choice length");
            Set<Integer> seen = new HashSet<>();
            for (int i=0; i<ii.length; i++) {
                int idx = Integer.parseInt(ii[i]);
                if (idx < 0 || idx > 65535 || !seen.add(idx)) throw new IllegalArgumentException("choice index");
                String[] c = cc[i].split(":", -1);
                if (c.length != 3) throw new IllegalArgumentException("choice card");
                a.selectIndices.add(idx);
                a.selectCards.add(new Plan.ChoiceCard(decode(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])));
            }
        } else if (!cards.equals("-")) throw new IllegalArgumentException("choice cards without indices");
        if (a.selectSkip && (!a.selectTask.equals("CODEX") || a.selectMulti || !a.selectIndices.isEmpty()))
            throw new IllegalArgumentException("invalid skip");
        if (!a.selectMulti && !a.selectSkip && a.selectIndices.size() != 1)
            throw new IllegalArgumentException("single selection count");
        int encoded = a.selectMulti ? 3 << 29 : (2 << 29) | (a.selectSkip ? 3 : a.selectIndices.get(0));
        if (a.selectMulti) for (int idx : a.selectIndices) {
            if (idx >= 10) throw new IllegalArgumentException("multi selection range");
            encoded |= 1 << idx;
        }
        if (a.selectBits == null || !Integer.toHexString(encoded).equalsIgnoreCase(a.selectBits))
            throw new IllegalArgumentException("choice bits do not match indices");
        a.selectIndex = a.selectIndices.isEmpty() ? -1 : a.selectIndices.get(0);
        if ("EXHUME".equals(a.selectTask)) {
            if (version != 3 || !"exhaust".equals(a.selectSource) || a.selectMulti || a.selectSkip
                    || a.selectIndices.size() != 1 || a.selectCards.size() != 1) {
                throw new IllegalArgumentException("Exhume selection protocol v3 required");
            }
            String pool = fields.get("pool");
            if (pool == null || pool.isEmpty() || "-".equals(pool))
                throw new IllegalArgumentException("Exhume candidate pool missing");
            Set<Integer> poolIndices = new HashSet<>();
            for (String item : pool.split("\\|", -1)) {
                String[] parts = item.split(":", -1);
                if (parts.length != 4) throw new IllegalArgumentException("Exhume candidate descriptor");
                int index = parseRange(parts[0], 0, 65535, "Exhume candidate index");
                String id = decode(parts[1]);
                int upgrades = parseRange(parts[2], 0, 255, "Exhume upgrade count");
                int cost = parseRange(parts[3], -9, 99, "Exhume candidate cost");
                if (!poolIndices.add(index) || "Exhume".equals(id))
                    throw new IllegalArgumentException("invalid Exhume candidate");
                a.selectCandidates.add(new Plan.ChoiceCandidate(index, id, upgrades, cost));
            }
            Plan.ChoiceCard selected = a.selectCards.get(0);
            boolean selectedMatches = false;
            for (Plan.ChoiceCandidate candidate : a.selectCandidates) {
                if (candidate.sourceIndex == a.selectIndex && sameCard(candidate, selected)) {
                    selectedMatches = true;
                    break;
                }
            }
            if (!selectedMatches) throw new IllegalArgumentException("selected Exhume card not in candidate pool");
        } else if (version != 2 || fields.containsKey("pool")) {
            throw new IllegalArgumentException("unsupported selection protocol version");
        }
        return a;
    }
    private static int parseRange(String value, int min, int max, String field) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new IllegalArgumentException(field);
            return parsed;
        } catch (NumberFormatException e) { throw new IllegalArgumentException(field); }
    }
    private static boolean sameCard(Plan.ChoiceCard a, Plan.ChoiceCard b) {
        return a.id.equals(b.id) && a.upgrades == b.upgrades && a.cost == b.cost;
    }
    private static String decode(String hex) {
        if (hex.isEmpty() || hex.length()%2 != 0) throw new IllegalArgumentException("card id encoding");
        byte[] bytes = new byte[hex.length()/2];
        for(int i=0;i<bytes.length;i++) bytes[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
