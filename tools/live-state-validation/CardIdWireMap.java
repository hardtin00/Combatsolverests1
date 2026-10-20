package rngprobe;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Exact IDs confirmed in the installed STS1 card constructors; engine IDs match CardId and SaveFileMappings. */
final class CardIdWireMap {
    private static final Map<String, String> WIRE_TO_ENGINE;
    private static final Set<String> CXX_EFFECT_IMPLEMENTATIONS;
    static {
        Map<String, String> m = new HashMap<String, String>();
        m.put("Strike_R", "STRIKE_RED");
        m.put("Defend_R", "DEFEND_RED");
        m.put("Bash", "BASH");
        m.put("True Grit", "TRUE_GRIT");
        m.put("Shrug It Off", "SHRUG_IT_OFF");
        m.put("Havoc", "HAVOC");
        m.put("Searing Blow", "SEARING_BLOW");
        m.put("RitualDagger", "RITUAL_DAGGER");
        m.put("Cleave", "CLEAVE");
        m.put("Bludgeon", "BLUDGEON");
        if (m.size() != 10 || new java.util.HashSet<String>(m.values()).size() != m.size())
            throw new IllegalStateException("duplicate card ID mapping");
        WIRE_TO_ENGINE = Collections.unmodifiableMap(m);
        Set<String> effects = new HashSet<String>();
        Collections.addAll(effects, "STRIKE_RED", "DEFEND_RED", "BASH", "TRUE_GRIT",
                "SEARING_BLOW", "RITUAL_DAGGER", "HAVOC", "SHRUG_IT_OFF", "CLEAVE", "BLUDGEON");
        CXX_EFFECT_IMPLEMENTATIONS = Collections.unmodifiableSet(effects);
    }
    private CardIdWireMap() { }
    static String toEngineId(String wireId) { return WIRE_TO_ENGINE.get(wireId); }
    static boolean supportsEngineId(String engineId) { return WIRE_TO_ENGINE.containsValue(engineId); }

    /** State mapping is separate from effect support; callers must check both. */
    static String effectModelStatus(String engineId) {
        if (CXX_EFFECT_IMPLEMENTATIONS.contains(engineId)) return "CXX_IMPLEMENTATION_PRESENT_NOT_L4_VERIFIED";
        return "UNMODELED_OR_UNVERIFIED";
    }
}
