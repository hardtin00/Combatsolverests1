package com.combatsolver1.runtime;

import com.combatsolver1.search.ChoiceProtocol;
import com.combatsolver1.search.Plan;

import java.util.List;

/** Exercises the production protocol parser, identity matcher, and Exhume execution gate. */
public final class ExhumeExecutionCheck {
    public static void main(String[] args) {
        Plan.Action action = parse("1", "537472696b655f52:0:1", "0:42617368:0:2|1:537472696b655f52:0:1");
        require(action.isSelect() && action.selectCandidates.size() == 2, "v3 choice identity was not parsed");
        require(ExhumeChoiceSafety.supports(action, "engine"), "engine Exhume selection was rejected");
        require(!ExhumeChoiceSafety.supports(action, "local"), "local/unknown choice was accepted");
        Plan.Turn exhumeTurn = turnWithExhume(action);
        require(ExhumeChoiceSafety.canPlayExhume(exhumeTurn, 0, "engine"), "verified engine choice was not allowed before play");
        require(!ExhumeChoiceSafety.canPlayExhume(exhumeTurn, 0, "local"), "local route could play Exhume before an unsupported choice");
        require(!ExhumeChoiceSafety.canPlayExhume(turnWithExhume(null), 0, "engine"), "Exhume without a following choice was allowed");
        Plan.Action incomplete = parse("1", "537472696b655f52:0:1", "0:42617368:0:2|1:537472696b655f52:0:1");
        incomplete.selectCandidates.clear();
        require(!ExhumeChoiceSafety.canPlayExhume(turnWithExhume(incomplete), 0, "engine"),
                "incomplete choice was allowed before Exhume");
        require(!ExhumeChoiceSafety.canPlayExhume(exhumeTurn, 1, "engine"), "choice action was treated as an Exhume card");

        // A: unique candidate; B: multiple IDs; C: same ID with different upgrades.
        ExhumeChoiceSafety.Match unique = ExhumeChoiceSafety.match(action,
                candidates("Bash:0:2", "Strike_R:0:1"));
        require(unique.matched() && unique.liveIndex == 1,
                "unique selected instance did not map after candidate order change");
        Plan.Action many = parse("2", "537472696b655f52:1:1", "0:42617368:0:2|1:537472696b655f52:0:1|2:537472696b655f52:1:1");
        ExhumeChoiceSafety.Match upgraded = ExhumeChoiceSafety.match(many,
                candidates("Strike_R:0:1", "Bash:0:2", "Strike_R:1:1"));
        require(upgraded.matched() && upgraded.liveIndex == 2,
                "candidate order change or upgrade distinction failed");
        // D: same ID and same upgrade/cost cannot identify which physical card instance to click.
        Plan.Action duplicate = parse("1", "537472696b655f52:0:1", "0:537472696b655f52:0:1|1:537472696b655f52:0:1");
        require(!ExhumeChoiceSafety.match(duplicate, candidates("Strike_R:0:1", "Strike_R:0:1")).matched(),
                "ambiguous identical instances were accepted");
        require(!ExhumeChoiceSafety.match(action, candidates("Bash:0:2")).matched(), "changed pool accepted");

        // F/G: wait for the native expected grid; a different interface must fail closed.
        ExhumeChoiceSafety.Gate gate = new ExhumeChoiceSafety.Gate(0, false);
        gate.observeResult(false, true, true, false, 1);
        gate.observeResult(false, true, true, false, 2);
        require(gate.phase() == ExhumeChoiceSafety.Phase.WAITING_FOR_SCREEN, "premature wait failure");
        gate.observeScreen(true, true, true, false, 3);
        require(gate.phase() == ExhumeChoiceSafety.Phase.PRESS_PENDING, "expected grid not accepted");
        require(gate.beforeGridUpdate(true, true, false, false, 4) == ExhumeChoiceSafety.Pulse.PRESS,
                "native click press missing");
        require(gate.beforeGridUpdate(true, true, false, false, 5) == ExhumeChoiceSafety.Pulse.RELEASE,
                "native click release missing");
        gate.observeResult(true, true, false, false, 6);
        gate.observeResult(false, true, true, true, 7);
        require(gate.phase() == ExhumeChoiceSafety.Phase.COMPLETE, "stable matching native result did not complete");

        ExhumeChoiceSafety.Gate wrongUi = new ExhumeChoiceSafety.Gate(0, false);
        wrongUi.observeScreen(true, false, false, false, 1);
        require(wrongUi.phase() == ExhumeChoiceSafety.Phase.FAILED, "wrong screen was not rejected");
        ExhumeChoiceSafety.Gate absent = new ExhumeChoiceSafety.Gate(0, false);
        absent.observeResult(false, true, true, false, 1);
        absent.observeResult(false, true, true, false, 2);
        absent.observeResult(false, true, true, false, 3);
        require(absent.phase() == ExhumeChoiceSafety.Phase.FAILED, "missing grid did not stop route");

        // I: post-state drift; J: cancellation; K: timeout; manual intervention.
        ExhumeChoiceSafety.Gate drift = new ExhumeChoiceSafety.Gate(0, false);
        driveToResult(drift);
        drift.observeResult(false, true, true, false, 6);
        require(drift.phase() == ExhumeChoiceSafety.Phase.FAILED, "post-selection drift was accepted");
        ExhumeChoiceSafety.Gate cancelled = new ExhumeChoiceSafety.Gate(0, false);
        cancelled.observeScreen(true, true, true, false, 1);
        require(cancelled.beforeGridUpdate(true, true, false, true, 2) == ExhumeChoiceSafety.Pulse.NONE
                && cancelled.phase() == ExhumeChoiceSafety.Phase.CANCELLED, "J cancellation was not immediate");
        ExhumeChoiceSafety.Gate timeout = new ExhumeChoiceSafety.Gate(0, false);
        timeout.observeScreen(false, false, false, false, ExhumeChoiceSafety.Gate.TIMEOUT_MILLIS + 1);
        require(timeout.phase() == ExhumeChoiceSafety.Phase.FAILED, "timeout did not stop route");
        ExhumeChoiceSafety.Gate user = new ExhumeChoiceSafety.Gate(0, false);
        user.observeScreen(true, true, true, false, 1);
        require(user.beforeGridUpdate(true, true, true, false, 2) == ExhumeChoiceSafety.Pulse.NONE
                && user.phase() == ExhumeChoiceSafety.Phase.FAILED, "manual UI input was not rejected");

        // Native single-candidate fast path does not open a screen, but must still verify post-state.
        ExhumeChoiceSafety.Gate singleton = new ExhumeChoiceSafety.Gate(0, true);
        singleton.observeResult(false, true, true, true, 1);
        require(singleton.phase() == ExhumeChoiceSafety.Phase.COMPLETE, "native singleton path did not complete");
        require(ExhumeChoiceSafety.nativeEnergyCost(-9, false, false, 3) == 0
                && ExhumeChoiceSafety.nativeEnergyCost(-1, false, false, 3) == 3
                && ExhumeChoiceSafety.nativeEnergyCost(1, false, false, 3) == 1,
                "native energy expectation mismatch");
        require(ChoiceProtocol.parse("PLANSELECT v=2 task=CODEX source=deck multi=0 skip=0 bits=40000000 idxs=0 cards=537472696b655f52:0:1")
                .isSelect(), "legacy non-Exhume selection regressed");
        expectRejected("PLANSELECT v=2 task=EXHUME source=exhaust multi=0 skip=0 bits=40000001 idxs=1 cards=537472696b655f52:0:1");
        System.out.println("EXHUME_EXECUTION_CHECK_OK cases=15 identity=verified wait/click/result=verified cancel/timeout/drift=fail_closed singleton=verified legacy=compatible");
    }

    private static void driveToResult(ExhumeChoiceSafety.Gate gate) {
        gate.observeScreen(true, true, true, false, 1);
        gate.beforeGridUpdate(true, true, false, false, 2);
        gate.beforeGridUpdate(true, true, false, false, 3);
    }
    private static Plan.Action parse(String index, String selected, String pool) {
        return ChoiceProtocol.parse("PLANSELECT v=3 task=EXHUME source=exhaust multi=0 skip=0 bits=4000000"
                + index + " idxs=" + index + " cards=" + selected + " pool=" + pool);
    }
    private static List<Plan.ChoiceCard> candidates(String... values) {
        java.util.ArrayList<Plan.ChoiceCard> result = new java.util.ArrayList<Plan.ChoiceCard>();
        for (String value : values) {
            String[] parts = value.split(":");
            result.add(new Plan.ChoiceCard(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        }
        return result;
    }
    private static Plan.Turn turnWithExhume(Plan.Action selection) {
        Plan.Turn turn = new Plan.Turn();
        turn.actions.add(new Plan.Action("Exhume", "Exhume", -1, 1));
        if (selection != null) turn.actions.add(selection);
        return turn;
    }
    private static void expectRejected(String line) {
        try { ChoiceProtocol.parse(line); throw new AssertionError("unsafe protocol accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
