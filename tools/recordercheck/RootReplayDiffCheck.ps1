$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'RootReplayDiff.psm1') -Force -DisableNameChecking
$checks=0
function Assert([bool]$Condition,[string]$Message) { if(-not $Condition){ throw "FAIL: $Message" }; $script:checks++ }

$traceLines=@('TRACE0 hp=52 max=82 blk=0 en=3 turn=2 draw=7 disc=0 exh=0 hand=[Strike_R] mons=44/40',
              'TRACE1 hp=52 max=82 blk=0 en=2 turn=2 draw=7 disc=1 exh=0 hand=[] mons=38/40')
$traceText=($traceLines -join "`n")+"`nREPLAY_OK n=1`n"
$valid=Test-ReplayResult ([pscustomobject]@{ExitCode=0;Text=$traceText;TimedOut=$false}) 1
Assert ($valid.Kind -eq 'replay_ok' -and $valid.Trace.ContainsKey(1)) 'REPLAY_OK with exact count and TRACE indices'
$invalid=Test-ReplayResult ([pscustomobject]@{ExitCode=0;Text="REPLAY_INVALID n=3 bits=1`n";TimedOut=$false}) 3
Assert ($invalid.Kind -eq 'replay_invalid' -and $invalid.RejectAction -eq 3) 'REPLAY_INVALID exit 0 reports rejection action'
$terminalInvalid=Test-ReplayResult ([pscustomobject]@{ExitCode=2;Text="REPLAY_INVALID n=2 reason=terminal`n";TimedOut=$false}) 2
Assert ($terminalInvalid.Kind -eq 'replay_invalid' -and $terminalInvalid.RejectAction -eq 2) 'explicit rejection is retained even with engine rejection exit code'
$conflict=Test-ReplayResult ([pscustomobject]@{ExitCode=0;Text="REPLAY_INVALID n=1`nREPLAY_OK n=1`n";TimedOut=$false}) 1
Assert ($conflict.Kind -eq 'protocol_error') 'conflicting protocol markers are rejected'
$noProtocol=Test-ReplayResult ([pscustomobject]@{ExitCode=0;Text='noise only';TimedOut=$false}) 1
Assert ($noProtocol.Kind -eq 'protocol_error') 'missing protocol is rejected'
$timeout=Test-ReplayResult ([pscustomobject]@{ExitCode=-1;Text='';TimedOut=$true}) 1
Assert ($timeout.Kind -eq 'execution_error') 'timeout is execution_error'
$crash=Test-ReplayResult ([pscustomobject]@{ExitCode=7;Text='';TimedOut=$false}) 1
Assert ($crash.Kind -eq 'execution_error') 'nonzero exit is execution_error'
$badTrace=Test-ReplayResult ([pscustomobject]@{ExitCode=0;Text="TRACE0 hp=1 max=1 blk=0 en=0 turn=1 draw=0 disc=0 exh=0 hand=[] mons=1`nREPLAY_OK n=1";TimedOut=$false}) 1
Assert ($badTrace.Kind -eq 'protocol_error') 'missing TRACE1 cannot confirm action prefix'
$shift=Compare-TraceState ([pscustomobject]@{hp=51;maxHp=82;block=0;energy=3;engineTurn=2;draw=7;discard=0;exhaust=0}) ([pscustomobject]@{hp=51;maxHp=82;block=0;energy=3;turn=3;drawPile=(1..7);discardPile=@();exhaustPile=@()}) 1 1
Assert ($shift.Status -eq 'matched_visible_fields' -and $shift.Compared -eq 8) 'game turn = engine + 1 is aligned and visible summary fields compare'
$hp=Compare-TraceState ([pscustomobject]@{hp=52;maxHp=82;block=0;energy=3;engineTurn=2;draw=7;discard=0;exhaust=0}) ([pscustomobject]@{hp=51;maxHp=82;block=0;energy=3;turn=3;drawPile=(1..7);discardPile=@();exhaustPile=@()}) 1 1
Assert ($hp.Status -eq 'difference' -and $hp.Differences[0].Field -eq 'hp') 'first HP mismatch is located'
$phase=Compare-TraceState ([pscustomobject]@{hp=52;maxHp=82;block=0;energy=3;engineTurn=2;draw=7;discard=0;exhaust=0}) ([pscustomobject]@{stage='end-turn-enqueued';hp=52}) 1 1
Assert ($phase.Status -eq 'not_comparable') 'intermediate stage is not compared'
$unknown=Compare-TraceState ([pscustomobject]@{hp=52;maxHp=82;block=0;energy=3;engineTurn=2;draw=7;discard=0;exhaust=0}) ([pscustomobject]@{mystery=4}) 1 1
Assert ($unknown.Status -eq 'not_comparable' -and $unknown.Unavailable -contains 'hp') 'unknown-only state cannot be a match'
$knownUnknown=Compare-TraceState ([pscustomobject]@{hp=52;maxHp=82;block=0;energy=3;engineTurn=2;draw=7;discard=0;exhaust=0}) ([pscustomobject]@{hp=52;maxHp=82;block=0;energy=3;turn=3;drawPile=(1..7);discardPile=@();exhaustPile=@();mobs=@('unknown')}) 1 1
Assert ($knownUnknown.Status -eq 'partial_match_with_unavailable_fields' -and $knownUnknown.Unavailable[0] -match 'mobs') 'matching visible values plus unknown native fields remain explicitly partial'
Assert ((Get-ActionKind '60000000') -eq 'unsupported') 'unsupported action encoding is classified explicitly'
$terminal=Compare-TraceState $null $null 2 2
Assert ($terminal.Status -eq 'not_comparable') 'missing terminal post remains incomparable'

$temp=Join-Path ([IO.Path]::GetTempPath()) ('root-diff-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $fixture=Join-Path $temp 'bundle'; New-Item -ItemType Directory -Path $fixture | Out-Null
    $root=[pscustomobject]@{type='root';battleKey='fixture'}
    $row=[pscustomobject]@{type='card';paired=$true;card=[pscustomobject]@{id='Strike_R'};pre=[pscustomobject]@{hand=@([pscustomobject]@{id='Strike_R'});mobs=@([pscustomobject]@{id='Test';alive=$true;hp=10})};post=[pscustomobject]@{hp=51;maxHp=82;block=0;energy=2;turn=1;drawPile=@();discardPile=@('Strike_R');exhaustPile=@()};target=0;targetMode='single';targetTrusted=$true;targetEngineIndex=0;targetLiveIndex=0;targetIdentity='Test#1'}
    $jsonlPath=Join-Path $fixture 'combat.jsonl'; $journalPath=Join-Path $fixture 'actions.journal'; $savePath=Join-Path $fixture 'engine-in.save'
    [IO.File]::WriteAllLines($jsonlPath,@(($root|ConvertTo-Json -Compress -Depth 5),($row|ConvertTo-Json -Compress -Depth 5)),[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllLines($journalPath,@('# actionMeta index=1 type=card hand=0 card=Strike_R targetMode=single targetTrusted=true targetEngineIndex=0 targetLiveIndex=0 targetIdentity=Test#1','0'),[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($savePath,'fixture-save',[Text.UTF8Encoding]::new($false))
    $hashJ=(Get-FileHash $jsonlPath -Algorithm SHA256).Hash.ToLowerInvariant(); $hashA=(Get-FileHash $journalPath -Algorithm SHA256).Hash.ToLowerInvariant(); $hashS=(Get-FileHash $savePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest=[pscustomobject]@{status='closed';outcome='victory';actionCount=1;journalDirty=$false;recordingFile='combat.jsonl';recordingSha256=$hashJ;actionJournalFile='actions.journal';actionJournalSha256=$hashA;engineCombatSaveFile='engine-in.save';engineCombatSaveSha256=$hashS}
    $manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixture 'manifest.json') -Encoding UTF8
    $assessment=Get-ArchiveAssessment $fixture
    Assert ($assessment.IntegrityOk -and $assessment.Alignment.Ok -and $assessment.StrictEligible) 'valid fixture hashes/count/action alignment pass production gate'
    $postless=Compare-TraceState ([pscustomobject]@{hp=51}) $null 1 1
    Assert ($postless.Status -eq 'not_comparable' -and $postless.Reason -match 'missing') 'paired intermediate action without stable post is not compared'
    $terminalManifest=[pscustomobject]@{actionCount=2;finalActionStatus='unpaired-no-stable-post'}
    $terminalMeta=@{1=@{type='card';index='1';hand='0';card='Strike_R';targetMode='single';targetTrusted='true';targetEngineIndex='0';targetLiveIndex='0';targetIdentity='Test#1'};2=@{type='card';index='2'}}
    $terminalAlignment=Test-ActionAlignment @('0','0') @($root,$row) $terminalManifest $terminalMeta
    Assert ($terminalAlignment.TerminalUnpaired -and $terminalAlignment.Ok) 'journal-only final victory action remains explicitly unpaired without invalidating the paired prefix'
    $untrusted=$row | ConvertTo-Json -Depth 8 | ConvertFrom-Json; $untrusted.targetTrusted=$false
    $untrustedAlignment=Test-ActionAlignment @('0') @($root,$untrusted) $manifest (@{1=@{type='card';index='1';hand='0';card='Strike_R';targetTrusted='false';targetMode='single'}})
    Assert (-not $untrustedAlignment.Ok -and $untrustedAlignment.AlignedPrefix -eq 0) 'untrusted target blocks action alignment'
    $missingEnd=[pscustomobject]@{type='endTurn';phase='turn-transition';pre=[pscustomobject]@{turn=1};post=$null}
    $nextCard=[pscustomobject]@{type='card';paired=$true;pre=[pscustomobject]@{turn=2};post=$row.post}
    $broken=Test-StateChain @($root,$missingEnd,$nextCard)
    Assert (@($broken | Where-Object Boundary -eq 'missing_stable_post').Count -gt 0) 'endTurn without stable post is reported as chain gap'
    $dirty=$manifest.PSObject.Copy(); $dirty.journalDirty=$true; $dirty | ConvertTo-Json | Set-Content (Join-Path $fixture 'manifest.json') -Encoding UTF8
    $assessmentDirty=Get-ArchiveAssessment $fixture
    Assert (-not $assessmentDirty.StrictEligible -and $assessmentDirty.EvidenceMode -eq 'diagnostic_only') 'dirty bundle is limited to diagnostic evidence'
    $dirtyRunner={ param($engine,$save,$actions,$timeout) [pscustomobject]@{ExitCode=0;TimedOut=$false;Text=$traceText} }.GetNewClosure()
    $dirtyResult=Invoke-RootReplayDiff $fixture 'fixture-engine' $dirtyRunner
    Assert ($dirtyResult.EvidenceLevel -eq 'diagnostic_only_L2' -and -not $dirtyResult.StrictMatch) 'successful replay cannot clear dirty evidence status'
    $manifest.journalDirty=$false; $manifest | ConvertTo-Json | Set-Content (Join-Path $fixture 'manifest.json') -Encoding UTF8
    Add-Content -LiteralPath $jsonlPath 'tampered'
    $tampered=Get-ArchiveAssessment $fixture
    Assert (-not $tampered.IntegrityOk -and -not $tampered.StrictEligible) 'hash mismatch blocks production gate'
    $script:gateCalled=$false
    $gateRunner={ param($engine,$save,$actions,$timeout) $script:gateCalled=$true; [pscustomobject]@{ExitCode=0;TimedOut=$false;Text=$traceText} }.GetNewClosure()
    $tamperedResult=Invoke-RootReplayDiff $fixture 'fixture-engine' $gateRunner
    Assert (-not $script:gateCalled -and $tamperedResult.Replay.Status -eq 'not_run' -and $tamperedResult.Comparisons.Count -eq 0) 'hash mismatch prevents engine execution and trusted comparisons'
    Remove-Item $jsonlPath
    $missing=Get-ArchiveAssessment $fixture
    Assert (-not $missing.IntegrityOk) 'missing JSONL blocks production gate'
    [IO.File]::WriteAllLines($jsonlPath,@(($root|ConvertTo-Json -Compress),($row|ConvertTo-Json -Compress),($row|ConvertTo-Json -Compress)),[Text.UTF8Encoding]::new($false))
    $duplicate=Get-ArchiveAssessment $fixture
    Assert (-not $duplicate.Alignment.Ok) 'duplicate action row is rejected'
    $misaligned=[pscustomobject]@{status='closed';actionCount=1;journalDirty=$false;recordingFile='combat.jsonl';recordingSha256=(Get-FileHash $jsonlPath -Algorithm SHA256).Hash.ToLowerInvariant();actionJournalFile='actions.journal';actionJournalSha256=$hashA;engineCombatSaveFile='engine-in.save';engineCombatSaveSha256=$hashS}
    $misaligned | ConvertTo-Json | Set-Content (Join-Path $fixture 'manifest.json') -Encoding UTF8
    [IO.File]::WriteAllLines($journalPath,@('# actionMeta index=1 type=card hand=0 card=Strike_R targetMode=single targetTrusted=true targetEngineIndex=0 targetLiveIndex=0 targetIdentity=Test#1','80000000'),[Text.UTF8Encoding]::new($false))
    $misaligned.actionJournalSha256=(Get-FileHash $journalPath -Algorithm SHA256).Hash.ToLowerInvariant(); $misaligned | ConvertTo-Json | Set-Content (Join-Path $fixture 'manifest.json') -Encoding UTF8
    $wrongKind=Get-ArchiveAssessment $fixture
    Assert (-not $wrongKind.Alignment.Ok) 'journal/JSONL action kind mismatch is rejected'
    $unsupportedAlignment=Test-ActionAlignment @('60000000') @($root,$row) ([pscustomobject]@{actionCount=1;finalActionStatus='paired'}) (@{1=@{type='card';index='1'}})
    Assert (-not $unsupportedAlignment.Ok -and $unsupportedAlignment.AlignedPrefix -eq 0) 'unsupported action cannot enter aligned comparison prefix'
    $row2=$row | ConvertTo-Json -Depth 8 | ConvertFrom-Json; $row2.post.hp=50
    [IO.File]::WriteAllLines($jsonlPath,@(($root|ConvertTo-Json -Compress -Depth 5),($row|ConvertTo-Json -Compress -Depth 5),($row2|ConvertTo-Json -Compress -Depth 5)),[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllLines($journalPath,@('# actionMeta index=1 type=card hand=0 card=Strike_R targetMode=single targetTrusted=true targetEngineIndex=0 targetLiveIndex=0 targetIdentity=Test#1','0','# actionMeta index=2 type=card hand=0 card=Strike_R targetMode=single targetTrusted=true targetEngineIndex=0 targetLiveIndex=0 targetIdentity=Test#1','0'),[Text.UTF8Encoding]::new($false))
    $manifest.actionCount=2; $manifest.recordingSha256=(Get-FileHash $jsonlPath -Algorithm SHA256).Hash.ToLowerInvariant(); $manifest.actionJournalSha256=(Get-FileHash $journalPath -Algorithm SHA256).Hash.ToLowerInvariant(); $manifest | ConvertTo-Json | Set-Content (Join-Path $fixture 'manifest.json') -Encoding UTF8
    $prefixRunner={ param($engine,$save,$actions,$timeout) if($actions.Count -eq 1){[pscustomobject]@{ExitCode=0;TimedOut=$false;Text="TRACE0 hp=52 max=82 blk=0 en=3 turn=0 draw=7 disc=0 exh=0 hand=[Strike_R] mons=44`nTRACE1 hp=51 max=82 blk=0 en=2 turn=0 draw=0 disc=1 exh=0 hand=[] mons=38`nREPLAY_OK n=1`n"}}else{[pscustomobject]@{ExitCode=0;TimedOut=$false;Text="TRACE0 hp=52 max=82 blk=0 en=3 turn=0 draw=7 disc=0 exh=0 hand=[Strike_R] mons=44`nTRACE1 hp=99 max=82 blk=0 en=2 turn=0 draw=0 disc=1 exh=0 hand=[] mons=38`nREPLAY_INVALID n=2 bits=1`n"}} }.GetNewClosure()
    $sequenceResult=Invoke-RootReplayDiff $fixture 'fixture-engine' $prefixRunner
    $firstConfirmed=$sequenceResult.Comparisons | Where-Object EngineAction -eq 1 | Select-Object -First 1
    Assert ($sequenceResult.Replay.LongestReplayPrefix -eq 1 -and $null -ne $firstConfirmed -and $firstConfirmed.Status -eq 'matched_visible_fields') 'failed later prefix cannot contaminate previously confirmed action comparison'
    $fakeRunner={ param($engine,$save,$actions,$timeout) if($actions.Count -eq 1){[pscustomobject]@{ExitCode=0;TimedOut=$false;Text=$traceText}}else{[pscustomobject]@{ExitCode=0;TimedOut=$false;Text="TRACE0 hp=1 max=1 blk=0 en=0 turn=1 draw=0 disc=0 exh=0 hand=[] mons=1`nREPLAY_INVALID n=2 bits=1"}} }.GetNewClosure()
    $firstRun=Test-ReplayResult (& $fakeRunner 'engine' 'save' @('0') 90) 1
    $laterRun=Test-ReplayResult (& $fakeRunner 'engine' 'save' @('0','1') 90) 2
    Assert ($firstRun.Kind -eq 'replay_ok' -and $firstRun.Trace[1].hp -eq 52 -and $laterRun.Kind -eq 'replay_invalid') 'later failed prefix does not alter earlier successful prefix result'
} finally { Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue }

Write-Output ("ROOT_REPLAY_DIFF_TESTS=PASS checks={0}" -f $checks)
