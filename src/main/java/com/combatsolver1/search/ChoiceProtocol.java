package com.combatsolver1.search;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Pure value protocol. Legacy index-only selections cannot be auto-executed. */
public final class ChoiceProtocol {
    private ChoiceProtocol() {}
    public static Plan.Action parse(String line) {
        Map<String,String> fields = new HashMap<>();
        for (String token : line.split(" +")) {
            int at = token.indexOf('=');
            if (at > 0) fields.put(token.substring(0, at), token.substring(at+1));
        }
        if (!"2".equals(fields.get("v"))) throw new IllegalArgumentException("selection protocol v2 required");
        Plan.Action a = new Plan.Action("SELECT", "选择卡牌", -1, 0);
        a.kind = Plan.Action.KIND_SELECT;
        a.colorIndex = 5;
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
        return a;
    }
    private static String decode(String hex) {
        if (hex.isEmpty() || hex.length()%2 != 0) throw new IllegalArgumentException("card id encoding");
        byte[] bytes = new byte[hex.length()/2];
        for(int i=0;i<bytes.length;i++) bytes[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
