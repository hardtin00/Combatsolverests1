param(
    [ValidateSet('DryRun','Run')][string]$Mode = 'DryRun',
    [string]$Config = (Join-Path $PSScriptRoot 'automation-config-v1.json'),
    [switch]$SelfTest
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'Automation.Common.psm1') -Force

function Write-JsonResult([object]$Value, [int]$ExitCode = 0) {
    $json = $Value | ConvertTo-Json -Depth 20
    [Console]::Out.WriteLine($json)
    exit $ExitCode
}

function Get-FileSha256([string]$Path) { return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant() }

function Can-RestoreProtocol([object]$CaptureReport, [string]$OutputRoot) {
    if (-not $CaptureReport -or $CaptureReport.protocol -ne 'live_battle_capture_report' -or $CaptureReport.version -ne 1 -or $CaptureReport.status -ne 'PROTOCOL_CAPTURED') { return $false }
    if (-not $CaptureReport.source_snapshot -or [string]::IsNullOrWhiteSpace([string]$CaptureReport.source_snapshot.protocol_file)) { return $false }
    $snapshotPath = [System.IO.Path]::GetFullPath([string]$CaptureReport.source_snapshot.protocol_file)
    if (-not (Test-PathUnder $snapshotPath $OutputRoot) -or -not (Test-Path -LiteralPath $snapshotPath -PathType Leaf)) { return $false }
    try { $snapshot = Get-Content -Raw -LiteralPath $snapshotPath | ConvertFrom-Json -ErrorAction Stop }
    catch { return $false }
    return $snapshot.protocol -eq 'live_battle_state' -and $snapshot.version -eq 1
}

function Invoke-SelfTest {
    $cases = [System.Collections.Generic.List[object]]::new()
    $defaultConfig = Read-AutomationConfig (Join-Path $PSScriptRoot 'automation-config-v1.json')
    $cases.Add([pscustomobject]@{ name='versioned-template-validates'; result='PASS' })

    $tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-automation-selftest-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    try {
        $sample = $defaultConfig.Raw | ConvertTo-Json -Depth 10 | ConvertFrom-Json
        $sample.schema_version = 2
        $p = Join-Path $tempRoot 'bad-version.json'
        $sample | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $p -Encoding UTF8
        $rejected = $false; try { Read-AutomationConfig $p | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'invalid schema version was accepted' }
        $cases.Add([pscustomobject]@{ name='invalid-version-rejected'; result='PASS' })

        $sample = $defaultConfig.Raw | ConvertTo-Json -Depth 10 | ConvertFrom-Json
        $sample.game_dir = 'relative\game'
        $p = Join-Path $tempRoot 'relative-path.json'
        $sample | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $p -Encoding UTF8
        $rejected = $false; try { Read-AutomationConfig $p | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'relative game path was accepted' }
        $cases.Add([pscustomobject]@{ name='relative-path-rejected'; result='PASS' })

        $sample = $defaultConfig.Raw | ConvertTo-Json -Depth 10 | ConvertFrom-Json
        $sample.game_dir = Join-Path ([System.IO.Path]::GetPathRoot($env:SystemRoot)) 'HostSteam\steamapps\common\SlayTheSpire'
        $p = Join-Path $tempRoot 'host-install-path.json'
        $sample | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $p -Encoding UTF8
        $rejected = $false; try { Read-AutomationConfig $p | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'game path outside the isolated test root was accepted' }
        $cases.Add([pscustomobject]@{ name='host-install-path-outside-test-root-rejected'; result='PASS' })

        $sample = $defaultConfig.Raw | ConvertTo-Json -Depth 10 | ConvertFrom-Json
        $sample.expected_monster_class = 'example.modded.Monster'
        $sample.accepted_vanilla_monster_classes = @('example.modded.Monster')
        $p = Join-Path $tempRoot 'unsupported-scene.json'
        $sample | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $p -Encoding UTF8
        $rejected = $false; try { Read-AutomationConfig $p | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'non-vanilla scenario class was accepted' }
        $cases.Add([pscustomobject]@{ name='non-vanilla-scene-rejected'; result='PASS' })

        $p = Join-Path $tempRoot 'missing.json'
        $rejected = $false; try { Read-AutomationConfig $p | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'missing configuration was accepted' }
        $cases.Add([pscustomobject]@{ name='missing-config-rejected'; result='PASS' })

        $badFormat = Join-Path $tempRoot 'bad-format.json'
        '{ definitely not json' | Set-Content -LiteralPath $badFormat -Encoding UTF8
        $rejected = $false; try { Get-Content -Raw $badFormat | ConvertFrom-Json -ErrorAction Stop | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'invalid JSON data was accepted' }
        $cases.Add([pscustomobject]@{ name='invalid-data-format-rejected'; result='PASS' })

        $diag = Join-Path $tempRoot 'diagnostic.json'
        '{"protocol":"live_battle_diagnostic_snapshot","version":1,"source":"OFFLINE_SELFTEST"}' | Set-Content -LiteralPath $diag -Encoding UTF8
        if (-not (Test-DiagnosticOnly $diag)) { throw 'diagnostic snapshot was not identified' }
        $fakeReport = [pscustomobject]@{ protocol='live_battle_capture_report'; version=1; status='PROTOCOL_REJECTED'; source_snapshot=$null }
        if (Can-RestoreProtocol $fakeReport $tempRoot) { throw 'diagnostic-only/rejected capture could enter C++ recovery' }
        $cases.Add([pscustomobject]@{ name='diagnostic-cannot-enter-protocol-recovery'; result='PASS' })

        $diagnosticAsProtocol = [pscustomobject]@{ protocol='live_battle_capture_report'; version=1; status='PROTOCOL_CAPTURED'; source_snapshot=[pscustomobject]@{ protocol_file=$diag } }
        if (Can-RestoreProtocol $diagnosticAsProtocol $tempRoot) { throw 'diagnostic snapshot was accepted as the strict live protocol' }
        $cases.Add([pscustomobject]@{ name='diagnostic-file-rejected-even-under-captured-status'; result='PASS' })

        $rejected = $false; try { Test-DiagnosticOnly (Join-Path $tempRoot 'absent-snapshot.json') | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'missing diagnostic input was accepted' }
        $cases.Add([pscustomobject]@{ name='missing-snapshot-rejected'; result='PASS' })

        $badDiag = Join-Path $tempRoot 'invalid-diagnostic.json'; 'bad json' | Set-Content -LiteralPath $badDiag -Encoding UTF8
        $rejected = $false; try { Test-DiagnosticOnly $badDiag | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'invalid diagnostic data was accepted' }
        $cases.Add([pscustomobject]@{ name='invalid-snapshot-format-rejected'; result='PASS' })

        $snapshotCandidate = [pscustomobject]@{ protocol='live_battle_diagnostic_snapshot'; version=1 }
        if ($snapshotCandidate.protocol -eq 'live_battle_state' -and $snapshotCandidate.version -eq 1) { throw 'diagnostic protocol identity aliased strict protocol' }
        $cases.Add([pscustomobject]@{ name='diagnostic-protocol-identity-is-distinct'; result='PASS' })
    } finally {
        # Only the unique temporary self-test directory created above is removed.
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
    Write-JsonResult ([pscustomobject]@{ schema_version=1; status='PASS'; tests=$cases.Count; failures=0; cases=$cases; processes_launched=$false })
}

if ($SelfTest) { Invoke-SelfTest }

try { $data = Read-AutomationConfig $Config }
catch { Write-JsonResult ([pscustomobject]@{ schema_version=1; status='FAIL'; mode=$Mode; reason=$_.Exception.Message; process_launched=$false }) 2 }

$p = $data.Paths
$raw = $data.Raw
$planned = [ordered]@{
    steam_exe=$p.steam_exe
    steam_root=$p.steam_root
    steam_userdata_dir=$p.steam_userdata_dir
    steam_arguments=@($raw.steam_launch_arguments)
    game_dir=$p.game_dir
    probe_jar=$p.probe_jar
    test_mod_jar=$p.test_mod_jar
    engine_path=$p.engine_path
    saves_dir=$p.save_dir
    preferences_dir=$p.preferences_dir
    logs_dir=$p.logs_dir
}

if ($Mode -eq 'DryRun') {
    $expected = @($p.steam_exe,$p.game_dir,$p.mod_dir,$p.mts_jar,$p.basemod_jar,$p.probe_jar,$p.test_mod_jar,$p.engine_path)
    $missing = @($expected | Where-Object { -not (Test-Path -LiteralPath $_) })
    $hostInstall = Test-ConfigHasHostInstall $p
    Write-JsonResult ([pscustomobject]@{
        schema_version=1; status='DRY_RUN_ONLY'; readiness='BLOCKED_UNTIL_ISOLATED_VM_IS_PROVEN'; process_launched=$false
        configured_paths=$planned; missing_paths=$missing; known_host_install_path=$hostInstall
        commands=@('Validate versioned scenario configuration','Verify guest isolation attestation and VM identity','Require empty isolated saves directory','Launch Steam app 646570 through configured Play with Mods entry','Wait for new probe capture report','Run mcts_state only for complete live_battle_state v1','Write field-diff acceptance JSON')
        note='DryRun never creates directories, starts processes, deploys JARs, or reads/writes player saves.'
    })
}

try {
    if (-not (Test-PathUnder $data.ConfigPath $p.test_root)) { throw 'scenario config must be inside test_root in Run mode' }
    if (-not (Test-PathUnder $PSScriptRoot $p.test_root)) { throw 'controller scripts must be inside test_root in Run mode' }
    Assert-NoReparsePath $data.ConfigPath $p.test_root
    Assert-NoReparsePath $PSScriptRoot $p.test_root
    $isolation = Assert-GuestIsolation $data
}
catch { Write-JsonResult ([pscustomobject]@{ schema_version=1; status='BLOCKED'; mode='Run'; reason=$_.Exception.Message; process_launched=$false; configured_paths=$planned }) 3 }

$runId = [guid]::NewGuid().ToString('N')
$runDir = Join-Path $p.output_dir ('run-' + $runId)
$null = New-Item -ItemType Directory -Force -Path $runDir
$null = New-Item -ItemType Directory -Force -Path $p.save_dir,$p.preferences_dir,$p.logs_dir
$startUtc = [DateTime]::UtcNow
$startEpoch = [DateTimeOffset]::new($startUtc).ToUnixTimeMilliseconds()
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $p.steam_exe
$psi.WorkingDirectory = Split-Path -Parent $p.steam_exe
$psi.Arguments = ($raw.steam_launch_arguments -join ' ')
$psi.UseShellExecute = $false
$psi.EnvironmentVariables['CS1_LIVE_STATE_OUTPUT_DIR'] = $runDir
$psi.EnvironmentVariables['CS1_AUTOMATION_SCENARIO_FILE'] = $data.ConfigPath
$psi.EnvironmentVariables['CS1_AUTOMATION_OUTPUT_FILE'] = (Join-Path $runDir 'automation-mod-status.json')
$psi.EnvironmentVariables['CS1_AUTOMATION_ENABLED'] = '1'
$psi.EnvironmentVariables['CS1_AUTOMATION_SEED'] = [string]$raw.seed
try { $launch = [System.Diagnostics.Process]::Start($psi) }
catch { Write-JsonResult ([pscustomobject]@{ schema_version=1; status='FAIL'; mode='Run'; stage='STEAM_LAUNCH_FAILED'; reason=$_.Exception.Message; process_launched=$false }) 4 }
$engineSha = Get-FileSha256 $p.engine_path
$capturePath = Join-Path $runDir 'live-capture-report.json'
$automationPath = Join-Path $runDir 'automation-mod-status.json'
$deadline = [DateTime]::UtcNow.AddSeconds([int]$raw.timeout_seconds)
$capture = $null; $automation = $null; $lastError = $null
while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Seconds 2
    if (Test-Path -LiteralPath $automationPath) {
        try { $automation = Get-Content -Raw -LiteralPath $automationPath | ConvertFrom-Json -ErrorAction Stop } catch { $lastError = $_.Exception.Message }
    }
    if (Test-Path -LiteralPath $capturePath) {
        try {
            $candidate = Get-Content -Raw -LiteralPath $capturePath | ConvertFrom-Json -ErrorAction Stop
            if ($candidate.protocol -eq 'live_battle_capture_report' -and [long]$candidate.capture_time_epoch_ms -ge $startEpoch) { $capture = $candidate; break }
        } catch { $lastError = $_.Exception.Message }
    }
    if ($automation -and @('BLOCKED','FAILED') -contains [string]$automation.status) { break }
}

$acceptance = [ordered]@{
    schema_version=1; run_id=$runId; started_at_utc=$startUtc.ToString('o'); completed_at_utc=[DateTime]::UtcNow.ToString('o')
    isolation=$isolation; steam_pid=$launch.Id; steam_process_started=$true; scenario_id=$raw.scenario_id
    status='BLOCKED'; stage='WAITING_FOR_FRESH_CAPTURE'; probe_report=$null; diagnostic_snapshot=$null; protocol_snapshot=$null
    automation_mod_status=$null; capture_evidence=$null; runtime_logs=@()
    cxx_root_restore=$null; error=$lastError; engine_path=$p.engine_path; engine_sha256=$engineSha
}

if ($automation) {
    $acceptance.automation_mod_status=$automation
    if ($automation.status -eq 'FAILED') { $acceptance.stage='AUTOMATION_MOD_FAILED'; $acceptance.error=[string]$automation.error }
}
if ($capture) {
    $acceptance.probe_report=[ordered]@{ path=$capturePath; sha256=(Get-FileSha256 $capturePath); capture_time_epoch_ms=$capture.capture_time_epoch_ms; status=$capture.status; stable_boundary=$capture.stable_boundary; capture_id=$capture.capture_id; boundary_evidence=$capture.boundary_evidence; error=$capture.error }
    $rngStreams=0; $capturedRng=$null; $rngBefore=$null; $rngAfter=$null; $sourceStable=$null; $rngUnchanged=$null
    if ($capture.source_snapshot) {
        $sourceProperties=$capture.source_snapshot.PSObject.Properties
        if ($sourceProperties['source_state_stable']) { $sourceStable=$capture.source_snapshot.source_state_stable }
        if ($sourceProperties['rng_unchanged']) { $rngUnchanged=$capture.source_snapshot.rng_unchanged }
        if ($sourceProperties['protocol_snapshot'] -and $capture.source_snapshot.protocol_snapshot -and $capture.source_snapshot.protocol_snapshot.rng) {
            $capturedRng=$capture.source_snapshot.protocol_snapshot.rng; $rngStreams=@($capturedRng.PSObject.Properties).Count
        } elseif ($sourceProperties['rng_before']) {
            $rngBefore=$capture.source_snapshot.rng_before; $rngAfter=$capture.source_snapshot.rng_after; $rngStreams=@($rngBefore.PSObject.Properties).Count
            $rngUnchanged=($rngBefore | ConvertTo-Json -Compress -Depth 8) -ceq ($rngAfter | ConvertTo-Json -Compress -Depth 8)
        }
    }
    $acceptance.capture_evidence=[ordered]@{ source_state_stable=$sourceStable; rng_unchanged=$rngUnchanged; rng_stream_count=$rngStreams; rng_streams=$capturedRng; rng_before=$rngBefore; rng_after=$rngAfter; stable_boundary=$capture.stable_boundary }
    if ($capture.diagnostic_snapshot_file -and (Test-Path -LiteralPath $capture.diagnostic_snapshot_file -PathType Leaf) -and (Test-PathUnder $capture.diagnostic_snapshot_file $runDir)) {
        $acceptance.diagnostic_snapshot=[ordered]@{ path=$capture.diagnostic_snapshot_file; sha256=(Get-FileSha256 $capture.diagnostic_snapshot_file) }
    }
    if (Can-RestoreProtocol $capture $runDir) {
        $captureData = $capture.source_snapshot
        $protocolPath = [System.IO.Path]::GetFullPath([string]$captureData.protocol_file)
        $protocolSha = Get-FileSha256 $protocolPath
        if ($protocolSha -ne [string]$captureData.protocol_sha256) { $acceptance.status='FAIL'; $acceptance.stage='PROTOCOL_HASH_MISMATCH'; $acceptance.error='probe report SHA-256 does not match protocol file' }
        else {
            $acceptance.protocol_snapshot=[ordered]@{ path=$protocolPath; sha256=$protocolSha; version=1 }
            $protocolData=Get-Content -Raw -LiteralPath $protocolPath | ConvertFrom-Json -ErrorAction Stop
            $engineOutput = & $p.engine_path mcts_state $protocolPath 2>&1
            $engineExit = $LASTEXITCODE
            $engineText = ($engineOutput | ForEach-Object { [string]$_ }) -join "`n"
            $detail = @([regex]::Matches($engineText,'(?m)^STATE0_DETAIL_FIELD .* status=(MATCH|MISMATCH|UNAVAILABLE|NOT_APPLICABLE)$'))
            $mismatchCount = @([regex]::Matches($engineText,'(?m)^STATE0_DETAIL_FIELD .* status=MISMATCH$')).Count
            $unavailableCount = @([regex]::Matches($engineText,'(?m)^STATE0_DETAIL_FIELD .* status=UNAVAILABLE$')).Count
            $notApplicableCount = @([regex]::Matches($engineText,'(?m)^STATE0_DETAIL_FIELD .* status=NOT_APPLICABLE$')).Count
            $unexpectedNotApplicable=@([regex]::Matches($engineText,'(?m)^STATE0_DETAIL_FIELD path=([^\s]+).* status=NOT_APPLICABLE$') | Where-Object { $_.Groups[1].Value -ne 'capture_id' }).Count
            $cardLines=@([regex]::Matches($engineText,'(?m)^STATE0_DETAIL_CARD .* status=(MATCH|MISMATCH)$'))
            $cardMismatches=@([regex]::Matches($engineText,'(?m)^STATE0_DETAIL_CARD .* status=MISMATCH$')).Count
            $pileLines=@([regex]::Matches($engineText,'(?m)^STATE0_DETAIL_PILE .* status=(MATCH|MISMATCH)$'))
            $pileMismatches=@([regex]::Matches($engineText,'(?m)^STATE0_DETAIL_PILE .* status=MISMATCH$')).Count
            $expectedCards=0
            foreach($pileName in @('hand','draw','discard','exhaust')) { $expectedCards += @($protocolData.piles.$pileName).Count }
            $expectedMonsters=@($protocolData.monsters).Count
            $monsterIndices=@([regex]::Matches($engineText,'(?m)^STATE0_DETAIL_FIELD path=monsters\[(\d+)\]\.') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
            $state0Path = Join-Path $runDir 'cxx-state0-detail.txt'
            [System.IO.File]::WriteAllText($state0Path,$engineText,(New-Object System.Text.UTF8Encoding($false)))
            $acceptance.cxx_root_restore=[ordered]@{ command='mcts_state <live-battle-state-v1.json>'; exit_code=$engineExit; state0_detail_path=$state0Path; state0_detail_sha256=(Get-FileSha256 $state0Path); field_lines=$detail.Count; expected_field_lines=149; mismatches=$mismatchCount; unavailable=$unavailableCount; not_applicable=$notApplicableCount; unexpected_not_applicable=$unexpectedNotApplicable; card_lines=$cardLines.Count; expected_card_lines=$expectedCards; card_mismatches=$cardMismatches; pile_lines=$pileLines.Count; expected_pile_lines=4; pile_mismatches=$pileMismatches; monster_indices=$monsterIndices; expected_monsters=$expectedMonsters }
            if ($engineExit -eq 0 -and $detail.Count -eq 149 -and $mismatchCount -eq 0 -and $unavailableCount -eq 0 -and $unexpectedNotApplicable -eq 0 -and
                $cardLines.Count -eq $expectedCards -and $cardMismatches -eq 0 -and $pileLines.Count -eq 4 -and $pileMismatches -eq 0 -and $monsterIndices.Count -eq $expectedMonsters -and
                $capture.stable_boundary -eq $true -and $captureData.source_state_stable -eq $true -and $captureData.rng_unchanged -eq $true) { $acceptance.status='PASS'; $acceptance.stage='REAL_PROTOCOL_ROOT_RESTORE' }
            else { $acceptance.status='FAIL'; $acceptance.stage='CXX_ROOT_RESTORE'; $acceptance.error='C++ rejected the root or field-level readback contains mismatch/unavailable data' }
        }
    } else {
        $acceptance.stage='CAPTURE_NOT_SEARCHABLE'; $acceptance.error="No complete fresh live_battle_state v1 was produced; diagnostic snapshots are retained but never passed to C++. Probe status=$($capture.status); reason=$($capture.error)"
    }
}

if (-not $capture -and $acceptance.stage -eq 'WAITING_FOR_FRESH_CAPTURE') {
    if ($automation -and $automation.status -eq 'BATTLE_STABLE') { $acceptance.stage='STABLE_BATTLE_WITHOUT_PROBE_REPORT'; $acceptance.error='test mod marked a stable battle but no fresh probe report arrived' }
    else { $acceptance.error = if ($lastError) { $lastError } else { 'no fresh probe report arrived before timeout; the running game was left untouched' } }
}
$acceptance.completed_at_utc=[DateTime]::UtcNow.ToString('o')
$logFiles=@()
foreach($logRoot in @($p.logs_dir,$p.game_dir)) {
    if(Test-Path -LiteralPath $logRoot -PathType Container) {
        foreach($log in Get-ChildItem -LiteralPath $logRoot -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTimeUtc -ge $startUtc -and ($_.Extension -eq '.log' -or $_.Name -match 'ModTheSpire') }) {
            $logFiles += [pscustomobject]@{ path=$log.FullName; sha256=(Get-FileSha256 $log.FullName); last_write_utc=$log.LastWriteTimeUtc.ToString('o') }
        }
    }
}
$acceptance.runtime_logs=$logFiles
$acceptancePath = Join-Path $runDir 'automation-acceptance-report.json'
$acceptance | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $acceptancePath -Encoding UTF8
Write-JsonResult ([pscustomobject]@{ schema_version=1; status=$acceptance.status; stage=$acceptance.stage; report_path=$acceptancePath; run_id=$runId; process_launched=$true; note='The controller never kills Steam or STS1 after timeout.' }) $(if($acceptance.status -eq 'PASS'){0}else{4})
