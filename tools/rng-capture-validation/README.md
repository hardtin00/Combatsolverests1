# STS1 RNG capture and Java/C++ trace experiment

This is isolated validation code; it is not part of the production mod or engine protocol.

The live probe JAR must include `ModTheSpire.json` at the archive root. MTS 3.30.3 reads
its `version`, `sts_version`, and `mts_version` fields during mod discovery; a class-only
JAR can trigger the “missing or bad version number” warning before patch loading. The
compile-only API stubs below are used to compile the probe and must not be packaged.

```powershell
$jdk = 'C:\Program Files\Java\jdk1.6.0_43\bin'
$classes = Join-Path $env:TEMP 'cs1-rng-probe-classes'
$jar = Join-Path $env:TEMP 'CS1RngProbe.jar'
$sources = @(Get-ChildItem tools\rng-capture-validation\stubs -Recurse -File -Filter *.java | ForEach-Object FullName) + @((Resolve-Path tools\rng-capture-validation\LiveRngProbe.java).Path)
New-Item -ItemType Directory -Force $classes | Out-Null
& "$jdk\javac.exe" -source 1.6 -target 1.6 -d $classes $sources
& "$jdk\jar.exe" cf $jar -C $classes rngprobe -C tools\rng-capture-validation ModTheSpire.json
```

Confirm `jar tf $jar` contains `ModTheSpire.json` and `rngprobe/LiveRngProbe.class`, but
no `com/megacrit/` or `com/evacipated/` stub classes, before temporary deployment.

`JavaRngTrace` and `cpp_rng_trace` take `seed0`, `seed1` (hex) and `counter` from a real
STS1 runtime capture. Both initialize independent RNG instances and emit one TSV row per
operation: operation name, value (float/double as raw IEEE bits), resulting state pair,
and wrapper counter. Compare output line-by-line; do not substitute fabricated seeds.

Compile Java 6-compatible bytecode against the installed game JAR, then run it under the
game's Java 8 runtime:

```powershell
$game = 'C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire'
$classes = Join-Path $env:TEMP 'cs1-rng-validation-classes'
New-Item -ItemType Directory -Force $classes | Out-Null
& 'C:\Program Files\Java\jdk1.6.0_43\bin\javac.exe' -source 1.6 -target 1.6 -d $classes tools\rng-capture-validation\JavaRngTrace.java
& "$game\jre\bin\java.exe" -cp "$classes;$game\desktop-1.0.jar" JavaRngTrace <seed0-hex> <seed1-hex> <counter>
```

Compile C++ using the repository's MinGW toolchain with both
`vendor/sts_lightspeed/include` and `tools/rng-capture-validation` on the include path.
For execution, add the toolchain `bin` folder to the current PowerShell process `PATH`
because the dynamic MinGW runtime DLLs are not installed system-wide. Capture must happen
in a real combat at a stable player-input boundary and compare each live stream's states
before and after reading. This CLI trace runner does not capture live game memory.

```powershell
$toolbin = 'C:\Users\3476\Documents\Codex\2026-10-15\jie\work\mingw64\mingw64\bin'
$env:PATH = "$toolbin;$env:PATH"
& "$toolbin\g++.exe" -std=c++17 -O0 -I vendor\sts_lightspeed\include -I tools\rng-capture-validation tools\rng-capture-validation\cpp_rng_trace.cpp -o build\rng-capture-validation\cpp_rng_trace.exe
& "$toolbin\objdump.exe" -p build\rng-capture-validation\cpp_rng_trace.exe | Select-String 'DLL Name'
```

For each real captured stream, run both traces with the exact same `before` seed pair and
counter, redirect to separate TSV files, and compare line by line:

```powershell
$seed0 = '<captured-seed0-hex>'
$seed1 = '<captured-seed1-hex>'
$counter = '<captured-counter>'
& "$game\jre\bin\java.exe" -cp "$classes;$game\desktop-1.0.jar" JavaRngTrace $seed0 $seed1 $counter | Set-Content build\rng-capture-validation\java.tsv
& build\rng-capture-validation\cpp_rng_trace.exe $seed0 $seed1 $counter | Set-Content build\rng-capture-validation\cpp.tsv
Compare-Object (Get-Content build\rng-capture-validation\java.tsv) (Get-Content build\rng-capture-validation\cpp.tsv)
```

The `random-int-inclusive-large` operation uses upper range `2^30`, and
`random-int-inclusive-max-safe` uses `INT_MAX - 1`; both exercise bounded rejection
sampling using a real captured state. The isolated C++ trace validates ranges before calling
`sts::Random::random(range)`, since that implementation computes `range + 1` in signed
32-bit arithmetic. Run the standalone boundary regression with:

```powershell
& 'C:\Users\3476\Documents\Codex\2026-10-15\jie\work\mingw64\mingw64\bin\g++.exe' -std=c++17 -O0 -I tools\rng-capture-validation tools\rng-capture-validation\cpp_rng_boundary_check.cpp -o build\rng-capture-validation\cpp_rng_boundary_check.exe
& build\rng-capture-validation\cpp_rng_boundary_check.exe
```

The regression rejects `INT_MIN`, `-1`, and `INT_MAX` before invoking the engine RNG;
it accepts `0` and `INT_MAX - 1`. `INT_MAX` is not silently omitted from analysis: it is
an invalid input to the Java bounded API after signed wraparound, while the direct C++ call
would invoke undefined behavior. Test it at the validation boundary, never by making the
unsafe RNG call.

## Event-level unupgraded True Grit validation

`TrueGritSelectionProbe.java` adds an isolated MTS hook for the exact
`CardGroup.getRandomCard(Random)` call made by True Grit's `ExhaustAction`. It is armed by
`TrueGrit.use`, then accepts only `player.hand`, `AbstractDungeon.cardRandomRng`, and an
active `ExhaustAction` with `isRandom=true` and `amount=1`. The prefix records the six RNG
states and ordered hand candidates immediately before the original call; the postfix
records the original selected card identity and six post-call states. It never calls an RNG
method, changes a return value, or changes game state. Output goes to the ignored local
`build/rng-capture-validation/event-capture.tsv`.

Compile the probe with Java 6 bytecode and annotation processing disabled (the installed
game classpath contains Java 8 annotation processors):

```powershell
$jdk = 'C:\Program Files\Java\jdk1.6.0_43\bin'
$game = 'C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire'
$classes = Join-Path $env:TEMP 'CS1RngEventHookClasses'
$sources = @(Get-ChildItem tools\rng-capture-validation\stubs -Recurse -File -Filter *.java | ForEach-Object FullName) + @((Resolve-Path tools\rng-capture-validation\LiveRngProbe.java).Path, (Resolve-Path tools\rng-capture-validation\TrueGritSelectionProbe.java).Path)
New-Item -ItemType Directory -Force $classes | Out-Null
& "$jdk\javac.exe" -proc:none -source 1.6 -target 1.6 -d $classes $sources
& "$jdk\javac.exe" -proc:none -source 1.6 -target 1.6 -cp "$game\desktop-1.0.jar" -d $classes tools\rng-capture-validation\TrueGritRandomTrace.java
& "$jdk\jar.exe" cf (Join-Path $env:TEMP 'CS1RngProbe.event-hook.jar') -C $classes rngprobe -C tools\rng-capture-validation ModTheSpire.json
```

Build `cpp_true_grit_rng_trace.cpp` like the regular C++ trace, then pass the
`cardRandomRng` pre-state and recorded inclusive bound (`candidateCount - 1`) to the Java 8
and C++ one-call trace. Require equality of selected index, both state words, and counter;
map the index against the logged candidate UUID order, then compare with `selected` and
`post` from that same event row. A prior stable-boundary sample is not an event pre-state.
Only install the isolated JAR after the game has exited normally; remove it only after a
later normal exit. A missing event row means the experiment is BLOCKED, not passed.
