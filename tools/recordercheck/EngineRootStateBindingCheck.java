package com.combatsolver1.runtime;

/** Exercises the production root-state gate used before a plan can be adopted. */
public final class EngineRootStateBindingCheck {
    private EngineRootStateBindingCheck() { }

    public static void main(String[] args) {
        String engine = "STATE0 hp=54 max=75 blk=0 en=3 turn=0 draw=8 disc=0 exh=0 hand=[Bash,Defend] mons=30:0|22:0";
        String live = "STATE hp=54 blk=0 en=3 turn=1 hand=[Bash,Defend] mons=30:0|22:0";
        require(EngineRootStateBinding.matches(engine, live), "matching initial root was rejected");
        require(!EngineRootStateBinding.matches(engine,
                "STATE hp=54 blk=0 en=2 turn=1 hand=[Bash,Defend] mons=30:0|22:0"),
                "changed live energy after asynchronous search was accepted");
        require(!EngineRootStateBinding.matches(engine,
                "STATE hp=53 blk=0 en=3 turn=1 hand=[Bash,Defend] mons=30:0|22:0"),
                "changed live HP after asynchronous search was accepted");
        require(!EngineRootStateBinding.matches(engine,
                "STATE hp=54 blk=0 en=3 turn=1 hand=[Bash] mons=30:0|22:0"),
                "changed live hand after asynchronous search was accepted");
        require(!EngineRootStateBinding.matches(engine,
                "STATE hp=54 blk=0 en=3 turn=1 hand=[Bash,Defend] mons=31:0|22:0"),
                "changed live monster state after asynchronous search was accepted");
        require(!EngineRootStateBinding.matches(engine,
                "STATE hp=54 blk=0 en=3 turn=2 hand=[Bash,Defend] mons=30:0|22:0"),
                "wrong engine/live turn mapping was accepted");
        require(!EngineRootStateBinding.matches(engine.replace(" en=3", ""), live),
                "missing root field was treated as a match");
        require(!EngineRootStateBinding.matches(null, live), "missing engine result was treated as a match");
        System.out.println("EngineRootStateBindingCheck passed: 8 root match/rejection cases.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
