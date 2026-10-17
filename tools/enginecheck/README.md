# External engine smoke check

`EngineBridgeCheck.java` checks real Java bridge parsing against the compiled
vendored engine: initial search, a known three-action replay, and rejection of an
invalid action journal. It requires a user-provided combat save and its matching
three-action journal; private game saves are not bundled.

Compile this file alongside the complete Mod sources using the same game dependency
classpath and Java 8 target as tools/build/build-release.ps1. Then run:

```text
java -cp <compiled-classes> EngineBridgeCheck <engine.exe> <combat.save> <three-action-journal.txt>
```

The check runs 100 search iterations per valid case, not a full benchmark. It does
not launch the game, mutate saves, install a Mod or change user settings.
