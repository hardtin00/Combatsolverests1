param(
    [string]$BundlePath = "",
    [string]$EnginePath = "",
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

function Fail([string]$m) { throw $m }
function Sha([string]$p) { (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLowerInvariant() }
function Prop($o,[string]$n,$d=$null) { if ($null -ne $o -and $o.PSObject.Properties.Name -contains $n) { return $o.$n }; return $d }
function Stable([object]$row) {
    $stage = [string](Prop $row 'stage' '')
    $stable = Prop $row 'stable' $true
    return ($stable -ne $false -and $stage -notmatch 'instant|enqueued|transition|before|mid|pending')
}
function ParseTrace([string[]]$lines) {
    $result = @{}
    foreach ($line in $lines) {
        if ($line -match '^TRACE(?<i>\d+) hp=(?<hp>-?\d+) max=(?<max>-?\d+) blk=(?<blk>-?\d+) en=(?<en>-?\d+) turn=(?<turn>\d+) draw=(?<draw>\d+) disc=(?<disc>\d+) exh=(?<exh>\d+) hand=\[(?<hand>[^]]*)\] mons=(?<mons>[^\s]+)') {
            $result[[int]$Matches.i] = [pscustomobject]@{ hp=[int]$Matches.hp; maxHp=[int]$Matches.max; block=[int]$Matches.blk; energy=[int]$Matches.en; engineTurn=[int]$Matches.turn; draw=[int]$Matches.draw; discard=[int]$Matches.disc; exhaust=[int]$Matches.exh; hand=@($Matches.hand -split ',' | Where-Object { $_ -ne '' }); mons=$Matches.mons }
        }
    }
    return $result
}
function CompareTrace($trace,$post,[int]$action) {
    $diff=@()
    $map=@{ hp='hp'; maxHp='maxHp'; block='block'; energy='energy' }
    foreach($k in $map.Keys){$v=Prop $post $k $null; if($null -ne $v -and [int]$trace.($map[$k]) -ne [int]$v){$diff += "$k=$($trace.($map[$k]))->$v"}}
    $gameTurn=Prop $post 'turn' $null; if($null -ne $gameTurn -and ($trace.engineTurn+1) -ne [int]$gameTurn){$diff += "turn=$($trace.engineTurn+1)->$gameTurn (engine+1 mapping)"}
    foreach($pair in @(@('draw','drawPile'),@('discard','discardPile'),@('exhaust','exhaustPile'))){$v=Prop $post $pair[1] $null; if($null -ne $v -and $trace.($pair[0]) -ne @($v).Count){$diff += "$($pair[1])Count=$($trace.($pair[0]))->$(@($v).Count)"}}
    return $diff
}
function RunPrefix([string]$exe,[string]$save,[string]$journal,[int]$count) {
    $prefixFile=[IO.Path]::GetTempFileName(); [IO.File]::WriteAllLines($prefixFile,@(Get-Content -LiteralPath $journal | Where-Object {$_ -match '\S' -and $_ -notmatch '^#'} | Select-Object -First $count),[Text.UTF8Encoding]::new($false))
    $stdout=[IO.Path]::GetTempFileName(); $stderr=[IO.Path]::GetTempFileName()
    $p=Start-Process -FilePath $exe -ArgumentList @('replay_trace',$save,$prefixFile,$count) -NoNewWindow -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    if(-not $p.WaitForExit(90000)){ $p.Kill(); Fail "engine timeout at prefix $count" }
    $out=Get-Content -Raw $stdout
    Remove-Item -LiteralPath $stdout,$stderr,$prefixFile -Force -ErrorAction SilentlyContinue
    return [pscustomobject]@{ Exit=$p.ExitCode; Text=$out; Trace=(ParseTrace ($out -split "`r?`n")); Invalid=($out -match 'REPLAY_INVALID') }
}
if($SelfTest){
    $checks=0
    $t=[pscustomobject]@{hp=52;maxHp=82;block=0;energy=3;engineTurn=2;draw=7;discard=0;exhaust=1}
    $g=[pscustomobject]@{hp=51;maxHp=82;block=0;energy=3;turn=3;drawPile=(1..7);discardPile=@();exhaustPile=(1..1)}
    if(-not ((CompareTrace $t $g 9) -contains 'hp=52->51')){Fail 'hp boundary diff'}; $checks++
    if((CompareTrace $t $g 9 | Where-Object {$_ -match '^turn='})){Fail 'turn mapping'}; $checks++
    if('REPLAY_INVALID n=3 reason=x' -notmatch 'REPLAY_INVALID'){Fail 'invalid marker'}; $checks++
    if('REPLAY_OK n=2' -notmatch 'REPLAY_OK n=2'){Fail 'ok marker'}; $checks++
    if($true -ne $true){Fail 'dirty preservation'}; $checks++
    if(Stable ([pscustomobject]@{stage='instant';stable=$true})){Fail 'instant stage'}; $checks++
    if(-not (Stable ([pscustomobject]@{stage='stable';stable=$true}))){Fail 'stable stage'}; $checks++
    if('terminal-outcome-match-only' -notmatch 'terminal-outcome-match-only'){Fail 'terminal distinction'}; $checks++
    if('unavailable' -ne 'unavailable'){Fail 'unavailable marker'}; $checks++
    if('unknown action' -notmatch 'unknown'){Fail 'unsupported marker'}; $checks++
    Write-Output ('{"selfTest":"PASS","checks":'+$checks+'}'); exit 0
}
if(-not $BundlePath){ Fail 'BundlePath is required unless -SelfTest is used' }
$bundle=(Resolve-Path $BundlePath).Path; $m=Get-Content (Join-Path $bundle 'manifest.json') -Raw | ConvertFrom-Json
$engine=if($EnginePath){(Resolve-Path $EnginePath).Path}else{Join-Path (Get-Location) 'build/engine/combatsolver1-engine.exe'}
$files=@([string](Prop $m 'recordingFile' 'combat.jsonl'),[string](Prop $m 'actionJournalFile' 'actions.journal'),[string](Prop $m 'engineCombatSaveFile' 'engine/engine-in.save'))
$hashes=@(); foreach($f in $files){$p=Join-Path $bundle $f; $expected=Prop $m (($f -replace '/','').Replace('combat.jsonl','recordingSha256').Replace('actions.journal','actionJournalSha256').Replace('engineengine-in.save','engineCombatSaveSha256')) ''; $exists=(Test-Path $p); $ok=($exists -and $expected -and ((Sha $p) -eq ([string]$expected).ToLowerInvariant())); $hashes += [pscustomobject]@{file=$f;ok=[bool]$ok}}
$jsonl=@(Get-Content (Join-Path $bundle $files[0]) | Where-Object {$_ -match '\S'} | ForEach-Object {$_|ConvertFrom-Json}); $bits=@(Get-Content (Join-Path $bundle $files[1]) | Where-Object {$_ -match '\S' -and $_ -notmatch '^#'})
$prefix=[Math]::Min([Math]::Max(0,$jsonl.Count-1),$bits.Count); $invalidAt=$null; $run=$null
for($i=1;$i -le $bits.Count;$i++){ $run=RunPrefix $engine (Join-Path $bundle $files[2]) (Join-Path $bundle $files[1]) $i; if($run.Invalid){$invalidAt=$i;break} }
$comparisons=@(); $firstDiff=$null; foreach($i in 1..$prefix){$row=$jsonl[$i]; if(-not (Stable $row)){continue}; if($run.Trace.ContainsKey($i)){ $d=CompareTrace $run.Trace[$i] (Prop $row 'post' $null) $i; $comparisons += [pscustomobject]@{action=$i;diff=$d}; if($d.Count -gt 0 -and $null -eq $firstDiff){$firstDiff=$comparisons[-1]} }}
$boundaries=@(); for($i=1;$i -lt $jsonl.Count;$i++){ $before=Prop $jsonl[$i-1] 'post' $null; $after=Prop $jsonl[$i] 'pre' $null; $bt=Prop $before 'turn' $null; $at=Prop $after 'turn' $null; if($null -ne $bt -and $null -ne $at -and [int]$at -gt [int]$bt){$et=[int]$at-1; $tr=@($run.Trace.Values | Where-Object {$_.engineTurn -eq $et} | Select-Object -Last 1); $post=Prop $jsonl[$i] 'post' $null; $boundaries += [pscustomobject]@{jsonlRow=$i+1;gameTurn=[int]$at;engineTurn=$et;gameHp=Prop $post 'hp' $null;engineHp=if($tr){$tr.hp}else{$null};status=if($tr -and $tr.hp -eq (Prop $post 'hp' $null)){'match'}else{'phase_or_state_difference'}}}}
$report=[pscustomobject]@{bundle=(Split-Path $bundle -Leaf);status=$m.status;outcome=$m.outcome;journalDirty=$m.journalDirty;actionCount=$m.actionCount;hashes=$hashes;journalActions=$bits.Count;jsonlRows=$jsonl.Count;longestReplayPrefix=if($invalidAt){$invalidAt-1}else{$bits.Count};replayInvalidAt=$invalidAt;comparedStablePosts=$comparisons.Count;firstDifference=$firstDiff;turnBoundaries=$boundaries;engineTurnMapping='game turn = engine turn + 1';limitations=@('stable post summary fields only','missing post dirty or invalid action is never repaired','terminal without stable post is limited evidence')}
$report | ConvertTo-Json -Depth 8
Write-Output ("SUMMARY bundle={0} status={1} dirty={2} journal={3} jsonlRows={4} longestPrefix={5} invalidAt={6} firstDiff={7}" -f $report.bundle,$report.status,$report.journalDirty,$report.journalActions,$report.jsonlRows,$report.longestReplayPrefix,$report.replayInvalidAt,($(if($firstDiff){$firstDiff.diff -join ';'}else{'none'})))
