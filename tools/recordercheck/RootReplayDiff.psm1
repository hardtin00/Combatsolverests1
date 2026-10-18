Set-StrictMode -Version Latest

function Get-Prop($Object, [string]$Name, $Default = $null) {
    if ($Object -is [Collections.IDictionary]) { if ($Object.Contains($Name)) { return ,$Object[$Name] }; return $Default }
    if ($null -ne $Object -and $Object.PSObject.Properties.Name -contains $Name) { return ,$Object.$Name }
    return $Default
}

function Get-NonCommentLines([string]$Path) {
    return @([IO.File]::ReadAllLines($Path) | Where-Object { $_.Trim() -and $_.TrimStart()[0] -ne '#' })
}

function Get-ActionMeta([string[]]$Lines) {
    $meta = @{}
    foreach ($line in $Lines) {
        if ($line -match '^#\s*actionMeta\s+(.+)$') {
            $item = @{}
            foreach ($part in ($Matches[1] -split '\s+')) {
                if ($part -match '^([^=]+)=(.*)$') { $item[$Matches[1]] = $Matches[2] }
            }
            if ($item.ContainsKey('index')) {
                $idx = 0
                if (-not [int]::TryParse($item.index, [ref]$idx) -or $meta.ContainsKey($idx)) { throw "invalid or duplicate actionMeta index '$($item.index)'" }
                $meta[$idx] = $item
            }
        }
    }
    return $meta
}

function Get-ActionKind([string]$Bits) {
    try { $value = [Convert]::ToUInt32($Bits.Trim(), 16) } catch { return 'invalid' }
    switch (($value -shr 29) -band 7) {
        0 { return 'card' }
        1 { return 'potion' }
        4 { return 'endTurn' }
        default { return 'unsupported' }
    }
}

function Test-ActionAlignment($Journal, $Rows, $Manifest, $Meta) {
    $errors = [Collections.Generic.List[string]]::new()
    $jsonActions = @($Rows | Where-Object { (Get-Prop $_ 'type' '') -ne 'root' })
    $expectedCount = [int](Get-Prop $Manifest 'actionCount' -1)
    if ($expectedCount -lt 0 -or $Journal.Count -ne $expectedCount) { $errors.Add("manifest actionCount=$expectedCount but journal has $($Journal.Count) actions") }
    $terminalUnpaired = ((Get-Prop $Manifest 'finalActionStatus' '') -eq 'unpaired-no-stable-post')
    $pairedExpected = if ($terminalUnpaired) { [Math]::Max(0, $Journal.Count - 1) } else { $Journal.Count }
    if ($jsonActions.Count -ne $pairedExpected) { $errors.Add("JSONL has $($jsonActions.Count) action rows; expected $pairedExpected based on terminal metadata") }
    $alignedPrefix=0; $alignmentOpen=$true
    for ($i = 0; $i -lt [Math]::Min($Journal.Count, $jsonActions.Count); $i++) {
        $number = $i + 1
        $kind = Get-ActionKind $Journal[$i]
        $jsonKind = [string](Get-Prop $jsonActions[$i] 'type' '')
        $thisAligned=($kind -eq $jsonKind -and $kind -notin @('unsupported','invalid'))
        if ($kind -ne $jsonKind) { $errors.Add("action $number kind mismatch: journal=$kind JSONL=$jsonKind"); $thisAligned=$false }
        if ($kind -in @('unsupported','invalid')) { $errors.Add("action $number uses $kind encoding"); $thisAligned=$false }
        if ($kind -eq 'card' -and (-not $Meta.ContainsKey($number) -or $Meta[$number].type -ne 'card')) { $errors.Add("card action $number has missing/mismatched journal metadata"); $thisAligned=$false }
        $explicitNumber=Get-Prop $jsonActions[$i] 'actionNumber' (Get-Prop $jsonActions[$i] 'sequence' $null)
        if ($null -ne $explicitNumber -and [int]$explicitNumber -ne $number) { $errors.Add("JSONL action number $explicitNumber appears at position $number"); $thisAligned=$false }
        if ($kind -eq 'endTurn' -and [Convert]::ToUInt32($Journal[$i].Trim(),16) -ne 2147483648) { $errors.Add("endTurn action $number contains unexpected payload bits"); $thisAligned=$false }
        if ($kind -eq 'card' -and $thisAligned) {
            $bits=[Convert]::ToUInt32($Journal[$i].Trim(),16); $handIndex=[int]($bits -band 65535); $targetIndex=[int](($bits -shr 16) -band 8191)
            $row=$jsonActions[$i]; $pre=Get-Prop $row 'pre' $null; [object[]]$hand=@(); if($null -ne $pre -and $pre.PSObject.Properties.Name -contains 'hand'){$hand=@($pre.hand)}; $cardId=[string](Get-Prop (Get-Prop $row 'card' $null) 'id' '')
            $trusted=[bool](Get-Prop $row 'targetTrusted' $false); $mode=[string](Get-Prop $row 'targetMode' '')
            if ($handIndex -ge $hand.Count -or [string](Get-Prop $hand[$handIndex] 'id' '') -ne $cardId -or [string]$Meta[$number].hand -ne [string]$handIndex -or [string]$Meta[$number].card -ne ($cardId -replace ' ','_')) {
                $errors.Add("card action $number hand index/card identity disagrees with JSONL pre-state (index=$handIndex count=$($hand.Count) handId=$([string](Get-Prop $hand[$handIndex] 'id' '')) rowId=$cardId metaHand=$($Meta[$number].hand) metaCard=$($Meta[$number].card))"); $thisAligned=$false
            }
            if (-not $trusted -or [string]$Meta[$number].targetTrusted -ne 'true' -or [string]$Meta[$number].targetMode -ne $mode) {
                $errors.Add("card action $number target is untrusted or mode differs"); $thisAligned=$false
            } elseif ($mode -eq 'single') {
                $liveIndex=[int](Get-Prop $row 'targetLiveIndex' -1); [object[]]$mobs=@(); if($null -ne $pre -and $pre.PSObject.Properties.Name -contains 'mobs'){$mobs=@($pre.mobs)}; $identity=[string](Get-Prop $row 'targetIdentity' '')
                $identityMatches=$false
                if($liveIndex -ge 0 -and $liveIndex -lt $mobs.Count) { $identityMatches=($identity -eq (([string](Get-Prop $mobs[$liveIndex] 'id' ''))+'#'+($targetIndex+1)) -and (Get-Prop $mobs[$liveIndex] 'alive' $false) -eq $true -and [int](Get-Prop $mobs[$liveIndex] 'hp' 0) -gt 0) }
                if ($targetIndex -ne [int](Get-Prop $row 'targetEngineIndex' -1) -or $targetIndex -ne [int]$Meta[$number].targetEngineIndex -or [string](Get-Prop $row 'target' -999) -ne [string]$targetIndex -or $identity -ne [string]$Meta[$number].targetIdentity -or -not $identityMatches) {
                    $errors.Add("card action $number target slot/identity differs"); $thisAligned=$false
                }
            } elseif ($mode -in @('none','self','all_enemies')) {
                if ($targetIndex -ne 0 -or [int](Get-Prop $row 'target' -999) -notin @(-1,0) -or [int](Get-Prop $row 'targetEngineIndex' -2) -ne -1 -or [int](Get-Prop $row 'targetLiveIndex' -2) -ne -1 -or [string](Get-Prop $row 'targetIdentity' '') -ne '') { $errors.Add("card action $number has unexpected target encoding for mode $mode"); $thisAligned=$false }
            } else { $errors.Add("card action $number has unsupported target mode '$mode'"); $thisAligned=$false }
        }
        if ($kind -eq 'potion') { $errors.Add("potion action $number is recognized but not validated by the current JSONL target/payload schema"); $thisAligned=$false }
        if ($alignmentOpen -and $thisAligned) { $alignedPrefix++ } else { $alignmentOpen=$false }
    }
    for ($i = 0; $i -lt $Journal.Count; $i++) {
        $expected = $i + 1
        if ($Meta.ContainsKey($expected) -and [int]$Meta[$expected].index -ne $expected) { $errors.Add("journal metadata sequence mismatch at action $expected") }
    }
    if ($terminalUnpaired -and $Journal.Count -gt 0) {
        $lastKind = Get-ActionKind $Journal[-1]
        $finalType = [string](Get-Prop ($Meta[$Journal.Count]) 'type' '')
        if ($lastKind -ne 'card' -or $finalType -ne 'card' -or $jsonActions.Count -ne ($Journal.Count-1)) { $errors.Add('unpaired terminal action does not align with exactly one final journal-only card') }
    }
    [pscustomobject]@{ Ok=($errors.Count -eq 0); Errors=@($errors); JsonActionCount=$jsonActions.Count; PairedActionCount=[Math]::Min($jsonActions.Count,$Journal.Count); AlignedPrefix=$alignedPrefix; TerminalUnpaired=$terminalUnpaired }
}

function Test-StateChain($Rows, $Manifest=$null) {
    $problems = [Collections.Generic.List[object]]::new()
    $actions = @($Rows | Where-Object { (Get-Prop $_ 'type' '') -ne 'root' })
    $roots=@($Rows | Where-Object { (Get-Prop $_ 'type' '') -eq 'root' })
    if ($roots.Count -eq 1 -and $actions.Count -gt 0) {
        $rootState=Get-Prop $roots[0] 'state' $null; $firstPre=Get-Prop $actions[0] 'pre' $null
        if ($null -ne $rootState -and $null -ne $firstPre) {
            $diff=@(); foreach($field in @('turn','hp','maxHp','block','energy','hand','drawPile','discardPile','exhaustPile','mobs')) {
                $left=Get-Prop $rootState $field $null; $right=Get-Prop $firstPre $field $null
                if($null -ne $left -and $null -ne $right) { if((ConvertTo-Json -InputObject $left -Depth 20 -Compress) -cne (ConvertTo-Json -InputObject $right -Depth 20 -Compress)){$diff+=$field} }
            }
            if($diff.Count){$problems.Add([pscustomobject]@{Action=1;Fields=$diff;Boundary='root-to-first-pre'})}
        }
    }
    for ($i = 1; $i -lt $actions.Count; $i++) {
        $previous = Get-Prop $actions[$i-1] 'post' $null
        $pre = Get-Prop $actions[$i] 'pre' $null
        if ($null -eq $pre) { $problems.Add([pscustomobject]@{ Action=$i+1; Fields=@('pre'); Boundary='missing_pre' }); continue }
        if ($null -eq $previous) { $problems.Add([pscustomobject]@{ Action=$i; Fields=@('post'); Boundary='missing_stable_post' }); continue }
        $fields = @('turn','hp','maxHp','block','energy','hand','drawPile','discardPile','exhaustPile','mobs')
        $different = @()
        foreach ($field in $fields) {
            $left = Get-Prop $previous $field $null; $right = Get-Prop $pre $field $null
            if ($null -ne $left -and $null -ne $right) {
                $a = ConvertTo-Json -InputObject $left -Depth 20 -Compress
                $b = ConvertTo-Json -InputObject $right -Depth 20 -Compress
                if ($a -cne $b) { $different += $field }
            }
        }
        if ($different.Count) { $problems.Add([pscustomobject]@{ Action=$i+1; Fields=$different }) }
    }
    if($actions.Count -gt 0) {
        for($i=0;$i -lt $actions.Count;$i++) {
            $post=Get-Prop $actions[$i] 'post' $null
            $paired=[bool](Get-Prop $actions[$i] 'paired' $false)
            $isTerminalRow=($i -eq $actions.Count-1 -and (Get-Prop $Manifest 'finalActionStatus' '') -eq 'unpaired-no-stable-post')
            if($null -eq $post -and -not $isTerminalRow -and ($paired -or (Get-Prop $actions[$i] 'type' '') -eq 'endTurn')) {
                $already=@($problems | Where-Object { $_.Boundary -eq 'missing_stable_post' -and $_.Action -eq ($i+1) }).Count -gt 0
                if(-not $already) {$problems.Add([pscustomobject]@{Action=$i+1;Fields=@('post');Boundary='missing_stable_post'})}
            }
        }
    }
    return @($problems)
}

function Get-ArchiveAssessment([string]$BundlePath) {
    $errors = [Collections.Generic.List[string]]::new()
    $bundle = (Resolve-Path -LiteralPath $BundlePath -ErrorAction Stop).Path
    $manifestPath = Join-Path $bundle 'manifest.json'
    $manifest = $null
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { $errors.Add('manifest.json missing') }
    else { try { $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -ErrorAction Stop } catch { $errors.Add("manifest parse error: $($_.Exception.Message)") } }
    $files = @(
        [pscustomobject]@{ Key='recordingFile'; Hash='recordingSha256'; Default='combat.jsonl'; Role='jsonl' },
        [pscustomobject]@{ Key='actionJournalFile'; Hash='actionJournalSha256'; Default='actions.journal'; Role='journal' },
        [pscustomobject]@{ Key='engineCombatSaveFile'; Hash='engineCombatSaveSha256'; Default='engine/engine-in.save'; Role='save' }
    )
    $checks = [Collections.Generic.List[object]]::new()
    $paths = @{}
    $integrity = $true
    foreach ($spec in $files) {
        if ($null -eq $manifest -or $manifest.PSObject.Properties.Name -notcontains $spec.Key -or $manifest.PSObject.Properties.Name -notcontains $spec.Hash) { $errors.Add("manifest missing required file/hash field for $($spec.Role)") }
        $relative = [string](Get-Prop $manifest $spec.Key $spec.Default)
        $expected = [string](Get-Prop $manifest $spec.Hash '')
        $path = Join-Path $bundle $relative
        $exists = Test-Path -LiteralPath $path -PathType Leaf
        $actual = if ($exists) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() } else { '' }
        $ok = ($null -ne $manifest) -and ($manifest.PSObject.Properties.Name -contains $spec.Key) -and ($manifest.PSObject.Properties.Name -contains $spec.Hash) -and $exists -and $expected -match '^[0-9a-fA-F]{64}$' -and $actual -ceq $expected.ToLowerInvariant()
        if (-not $ok) { $integrity = $false; $errors.Add("$($spec.Role) file/hash invalid: $relative") }
        $checks.Add([pscustomobject]@{ Role=$spec.Role; File=$relative; Exists=$exists; Expected=$expected; Actual=$actual; Match=[bool]$ok })
        $paths[$spec.Role] = $path
    }
    $jsonRows = @(); $journal = @(); $meta = @{}
    if ($paths.ContainsKey('jsonl') -and (Test-Path -LiteralPath $paths.jsonl)) {
        try { $jsonRows = @(Get-NonCommentLines $paths.jsonl | ForEach-Object { $_ | ConvertFrom-Json -ErrorAction Stop }) }
        catch { $errors.Add("JSONL parse error: $($_.Exception.Message)") }
    }
    if ($paths.ContainsKey('journal') -and (Test-Path -LiteralPath $paths.journal)) {
        try { $rawLines = [IO.File]::ReadAllLines($paths.journal); $journal = @(Get-NonCommentLines $paths.journal); $meta = Get-ActionMeta $rawLines }
        catch { $errors.Add("journal parse error: $($_.Exception.Message)") }
    }
    if ($jsonRows.Count -gt 0 -and (Get-Prop $jsonRows[0] 'type' '') -ne 'root') { $errors.Add('JSONL first row is not root') }
    if (@($jsonRows | Where-Object { (Get-Prop $_ 'type' '') -eq 'root' }).Count -ne 1) { $errors.Add('JSONL must contain exactly one root') }
    $alignment = Test-ActionAlignment $journal $jsonRows $manifest $meta
    $chain = @(Test-StateChain $jsonRows $manifest)
    $dirty = [bool](Get-Prop $manifest 'journalDirty' $true)
    $closed = ((Get-Prop $manifest 'status' '') -eq 'closed')
    if (-not $closed) { $errors.Add('manifest is not closed') }
    $strict = ($integrity -and $errors.Count -eq 0 -and $alignment.Ok -and $chain.Count -eq 0 -and -not $dirty -and $closed)
    [pscustomobject]@{
        Bundle=$bundle; Manifest=$manifest; Paths=$paths; Hashes=@($checks); IntegrityOk=$integrity
        Alignment=$alignment; ChainProblems=$chain; JournalDirty=$dirty; Closed=$closed; StructuralErrors=@($errors)
        UnavailableFields=@('hand card identities/order','monster identity/HP/status/intent','relic state','RNG state','full powers unless emitted by replay_trace')
        StrictEligible=$strict; Errors=@($errors); Rows=$jsonRows; Journal=$journal; Meta=$meta
        EvidenceMode=if ($strict) { 'strict_candidate' } else { 'diagnostic_only' }
    }
}

function Parse-TraceOutput([string]$Text) {
    $trace = @{}; $errors = [Collections.Generic.List[string]]::new()
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -match '^TRACE(?<idx>\d+) hp=(?<hp>-?\d+) max=(?<max>-?\d+) blk=(?<block>-?\d+) en=(?<energy>-?\d+) turn=(?<turn>\d+) draw=(?<draw>\d+) disc=(?<discard>\d+) exh=(?<exhaust>\d+) hand=\[(?<hand>[^]]*)\] mons=(?<mons>\S+)') {
            $idx=[int]$Matches.idx
            if ($trace.ContainsKey($idx)) { $errors.Add("duplicate TRACE$idx"); continue }
            $trace[$idx]=[pscustomobject]@{ hp=[int]$Matches.hp; maxHp=[int]$Matches.max; block=[int]$Matches.block; energy=[int]$Matches.energy; engineTurn=[int]$Matches.turn; draw=[int]$Matches.draw; discard=[int]$Matches.discard; exhaust=[int]$Matches.exhaust; hand=@($Matches.hand -split ',' | Where-Object { $_ -ne '' }); mobs=$Matches.mons }
        }
    }
    [pscustomobject]@{ Trace=$trace; Errors=@($errors) }
}

function Test-ReplayResult($Result, [int]$ExpectedActions) {
    $kind='protocol_error'; $reason='missing or malformed replay protocol'; $reject=$null; $text=[string](Get-Prop $Result 'Text' ''); $parsed=Parse-TraceOutput $text
    if ([bool](Get-Prop $Result 'TimedOut' $false)) { $kind='execution_error'; $reason='engine timeout' }
    elseif ($text -match '(?m)^REPLAY_INVALID\s+n=(\d+)(?:\s|$)' -and $text -match '(?m)^REPLAY_OK\s+n=\d+\s*$') { $kind='protocol_error'; $reason='conflicting REPLAY_INVALID and REPLAY_OK markers' }
    elseif ($text -match '(?m)^REPLAY_INVALID\s+n=(\d+)(?:\s|$)') {
        $reject=[int]$Matches[1]
        if ($reject -lt 1 -or $reject -gt $ExpectedActions) { $kind='protocol_error'; $reason="REPLAY_INVALID action $reject is outside requested prefix 1..$ExpectedActions" }
        else { $kind='replay_invalid'; $reason="engine rejected action $reject" }
    }
    elseif ([int](Get-Prop $Result 'ExitCode' -1) -ne 0) { $kind='execution_error'; $reason="engine exit code $($Result.ExitCode)" }
    elseif ($parsed.Errors.Count) { $kind='protocol_error'; $reason=$parsed.Errors -join '; ' }
    elseif ([string](Get-Prop $Result 'Text' '') -match '(?m)^REPLAY_OK\s+n=(\d+)\s*$') {
        $reported=[int]$Matches[1]
        if ($reported -ne $ExpectedActions) { $kind='protocol_error'; $reason="REPLAY_OK count $reported does not match requested $ExpectedActions" }
        elseif (-not $parsed.Trace.ContainsKey(0)) { $kind='protocol_error'; $reason='TRACE0 root missing' }
        else {
            $missing=@(0..$ExpectedActions | Where-Object { -not $parsed.Trace.ContainsKey($_) })
            $unexpected=@($parsed.Trace.Keys | Where-Object { $_ -gt $ExpectedActions })
            if ($missing.Count) { $kind='protocol_error'; $reason="missing TRACE indices: $($missing -join ',')" }
            elseif ($unexpected.Count) { $kind='protocol_error'; $reason="TRACE index exceeds executed actions: $($unexpected -join ',')" }
            else { $kind='replay_ok'; $reason='protocol confirmed requested action count' }
        }
    }
    [pscustomobject]@{ Kind=$kind; Reason=$reason; RejectAction=(Get-Prop $Result 'RejectAction' $reject); Trace=$parsed.Trace; ExpectedActions=$ExpectedActions; ExitCode=(Get-Prop $Result 'ExitCode' -1); Raw=[string](Get-Prop $Result 'Text' '') }
}

function Compare-TraceState($Trace, $Post, [int]$EngineAction, [int]$GameAction) {
    if ($null -eq $Post) { return [pscustomobject]@{ Status='not_comparable'; Reason='stable post missing'; Compared=0; Matched=0; Differences=@(); Unavailable=@('all post fields') } }
    if ($null -ne (Get-Prop $Post 'stage' $null) -and [string]$Post.stage -match 'before|instant|enqueued|pending|transition') {
        return [pscustomobject]@{ Status='not_comparable'; Reason="non-stable stage '$($Post.stage)'"; Compared=0; Matched=0; Differences=@(); Unavailable=@('all post fields') }
    }
    $diff=@(); $compared=0; $matched=0; $unavailable=@()
    foreach ($field in @('hp','maxHp','block','energy')) {
        $native=Get-Prop $Post $field $null
        if ($null -eq $native) { $unavailable += $field; continue }
        $compared++
        if ([int]$Trace.$field -eq [int]$native) { $matched++ } else { $diff += [pscustomobject]@{ Field=$field; Engine=$Trace.$field; Native=$native } }
    }
    $gameTurn=Get-Prop $Post 'turn' $null
    if ($null -eq $gameTurn) { $unavailable += 'turn' } else {
        $compared++; $mapped=[int]$Trace.engineTurn + 1
        if ($mapped -eq [int]$gameTurn) { $matched++ } else { $diff += [pscustomobject]@{ Field='turn'; Engine=$Trace.engineTurn; Native=$gameTurn; Mapping='game=engine+1' } }
    }
    foreach ($pair in @(@('draw','drawPile'),@('discard','discardPile'),@('exhaust','exhaustPile'))) {
        $native=Get-Prop $Post $pair[1] $null
        if ($null -eq $native) { $unavailable += "$($pair[1])Count"; continue }
        $compared++; $nativeCount=@($native).Count
        if ([int]$Trace.($pair[0]) -eq $nativeCount) { $matched++ } else { $diff += [pscustomobject]@{ Field="$($pair[1])Count"; Engine=$Trace.($pair[0]); Native=$nativeCount } }
    }
    $knownFields=@('hp','maxHp','block','energy','turn','drawPile','discardPile','exhaustPile')
    $unknown=@($Post.PSObject.Properties.Name | Where-Object { $_ -notin $knownFields })
    if($unknown.Count){$unavailable += ("uncompared native fields: "+($unknown -join ','))}
    $status=if($diff.Count){'difference'}elseif($compared -eq 0){'not_comparable'}elseif($unavailable.Count){'partial_match_with_unavailable_fields'}else{'matched_visible_fields'}
    [pscustomobject]@{ Status=$status; Reason='stage-aligned action post'; Compared=$compared; Matched=$matched; Differences=$diff; Unavailable=$unavailable; EngineAction=$EngineAction; GameAction=$GameAction }
}

function Quote-WindowsArg([string]$Value) {
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') { return $Value }
    $builder=[Text.StringBuilder]::new(); [void]$builder.Append('"'); $slashes=0
    foreach ($char in $Value.ToCharArray()) {
        if ($char -eq '\') { $slashes++; continue }
        if ($char -eq '"') { [void]$builder.Append(('\' * (2*$slashes+1))); [void]$builder.Append('"'); $slashes=0; continue }
        if ($slashes) { [void]$builder.Append(('\' * $slashes)); $slashes=0 }; [void]$builder.Append($char)
    }
    if ($slashes) { [void]$builder.Append(('\' * (2*$slashes))) }; [void]$builder.Append('"'); return $builder.ToString()
}

function Invoke-EnginePrefix([string]$Engine, [string]$Save, [string[]]$Actions, [int]$TimeoutSeconds=90, [scriptblock]$Runner=$null) {
    if ($null -ne $Runner) { return & $Runner $Engine $Save $Actions $TimeoutSeconds }
    $prefixFile=[IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllLines($prefixFile,$Actions,[Text.UTF8Encoding]::new($false))
        $psi=[Diagnostics.ProcessStartInfo]::new(); $psi.FileName=$Engine
        $args=@('replay_trace',$Save,$prefixFile,[string]$Actions.Count)
        $psi.Arguments=($args | ForEach-Object { Quote-WindowsArg ([string]$_) }) -join ' '
        $psi.UseShellExecute=$false; $psi.CreateNoWindow=$true; $psi.RedirectStandardOutput=$true; $psi.RedirectStandardError=$true
        $proc=[Diagnostics.Process]::new(); $proc.StartInfo=$psi
        if (-not $proc.Start()) { return [pscustomobject]@{ ExitCode=-1; Text=''; TimedOut=$false; Error='process start returned false' } }
        $outTask=$proc.StandardOutput.ReadToEndAsync(); $errTask=$proc.StandardError.ReadToEndAsync()
        if (-not $proc.WaitForExit($TimeoutSeconds*1000)) { try { $proc.Kill() } catch {}; return [pscustomobject]@{ ExitCode=-1; Text=''; TimedOut=$true; Error='timeout' } }
        $stderr=$errTask.GetAwaiter().GetResult(); $text=$outTask.GetAwaiter().GetResult()
        [pscustomobject]@{ ExitCode=$proc.ExitCode; Text=$text; TimedOut=$false; Error=$stderr }
    } catch { [pscustomobject]@{ ExitCode=-1; Text=''; TimedOut=$false; Error=$_.Exception.Message } }
    finally { Remove-Item -LiteralPath $prefixFile -Force -ErrorAction SilentlyContinue }
}

function Invoke-RootReplayDiff([string]$BundlePath, [string]$EnginePath, [scriptblock]$Runner=$null) {
    $archive=Get-ArchiveAssessment $BundlePath
    $runResults=[Collections.Generic.List[object]]::new(); $comparisons=[Collections.Generic.List[object]]::new()
    $longest=0; $invalidAt=$null; $stoppedReason=$null
    if (-not $archive.IntegrityOk -or $archive.StructuralErrors.Count -gt 0) { $stoppedReason='archive integrity/structure gate failed' }
    elseif ($archive.Alignment.JsonActionCount -eq 0 -and $archive.Journal.Count -eq 0) { $stoppedReason='no actions to replay' }
    else {
        $prefix=@()
        for ($count=1; $count -le $archive.Journal.Count; $count++) {
            $prefix += $archive.Journal[$count-1]
            $raw=Invoke-EnginePrefix $EnginePath $archive.Paths.save $prefix 90 $Runner
            $parsed=Test-ReplayResult $raw $count
            $runResults.Add([pscustomobject]@{ Prefix=$count; Result=$parsed })
            if ($parsed.Kind -eq 'replay_ok') { $longest=$count; continue }
            if ($parsed.Kind -eq 'replay_invalid') { $invalidAt=$parsed.RejectAction }
            else { $stoppedReason="$($parsed.Kind) at prefix $count`: $($parsed.Reason)" }
            break
        }
        if ($archive.JournalDirty) { $stoppedReason=if($stoppedReason){$stoppedReason+'; journalDirty=true'}else{'journalDirty=true: diagnostic_only'} }
        $rootRow=@($archive.Rows | Where-Object { (Get-Prop $_ 'type' '') -eq 'root' }) | Select-Object -First 1
        $rootRun=$runResults | Where-Object { $_.Result.Kind -eq 'replay_ok' -and $_.Result.Trace.ContainsKey(0) } | Select-Object -First 1
        if($null -ne $rootRow -and $null -ne $rootRun) {
            $rootCompare=Compare-TraceState $rootRun.Result.Trace[0] (Get-Prop $rootRow 'state' $null) 0 0
            $rootCompare | Add-Member -NotePropertyName Type -NotePropertyValue 'root'
            $rootCompare.Reason='protocol-confirmed TRACE0 aligned with root state'
            $comparisons.Add($rootCompare)
        }
        $jsonActions=@($archive.Rows | Where-Object { (Get-Prop $_ 'type' '') -ne 'root' })
        for ($i=0; $i -lt [Math]::Min($jsonActions.Count,[Math]::Min($longest,$archive.Alignment.AlignedPrefix)); $i++) {
            $actionNumber=$i+1; $row=$jsonActions[$i]
            if ($actionNumber -gt $archive.Alignment.PairedActionCount) { continue }
            $post=Get-Prop $row 'post' $null
            $stable=($null -ne $post -and ((Get-Prop $row 'paired' $false) -or (Get-Prop $row 'type' '') -eq 'endTurn'))
            if((Get-Prop $row 'type' '') -ne 'endTurn' -and [string](Get-Prop $row 'stage' '') -match 'instant|enqueued|transition|before|mid|pending') { $stable=$false }
            if (-not $stable) {
                $comparisons.Add([pscustomobject]@{ Action=$actionNumber; Type=$row.type; Status=if($archive.Alignment.TerminalUnpaired -and $actionNumber -eq $archive.Journal.Count){'terminal_not_comparable'}else{'not_comparable'}; Reason='no paired stable native post'; Compared=0; Matched=0; Differences=@(); Unavailable=@('stable post') })
                continue
            }
            $run=$runResults | Where-Object Prefix -eq $actionNumber | Select-Object -Last 1
            if ($null -eq $run -or $run.Result.Kind -ne 'replay_ok' -or -not $run.Result.Trace.ContainsKey($actionNumber)) {
                $comparisons.Add([pscustomobject]@{ Action=$actionNumber; Type=$row.type; Status='not_comparable'; Reason='no protocol-confirmed TRACE for this action'; Compared=0; Matched=0; Differences=@(); Unavailable=@('action TRACE') })
                continue
            }
            $comparisons.Add((Compare-TraceState $run.Result.Trace[$actionNumber] $post $actionNumber $actionNumber))
        }
        if ($archive.Alignment.TerminalUnpaired) {
            $comparisons.Add([pscustomobject]@{ Action=$archive.Journal.Count; Type='terminal'; Status='terminal_not_comparable'; Reason='final journal action has no stable JSONL post; manifest outcome can be checked separately'; Compared=0; Matched=0; Differences=@(); Unavailable=@('terminal full state') })
        }
    }
    $boundaryObservations=[Collections.Generic.List[object]]::new()
    if ($runResults.Count -gt 0) {
        $nativeByTurn=@{}
        for($i=0;$i -lt $jsonActions.Count;$i++) {
            $pre=Get-Prop $jsonActions[$i] 'pre' $null; $turn=Get-Prop $pre 'turn' $null
            if($null -ne $turn -and -not $nativeByTurn.ContainsKey([int]$turn)) { $nativeByTurn[[int]$turn]=[pscustomobject]@{Action=$i+1;Type=[string]$jsonActions[$i].type;State=$pre;Phase='pre-before-action'} }
        }
        foreach($gameTurn in ($nativeByTurn.Keys | Where-Object { [int]$_ -gt 1 } | Sort-Object)) {
            $candidate=$null
            $native=$nativeByTurn[$gameTurn]
            foreach($rr in $runResults) {
                if($rr.Result.Kind -ne 'replay_ok') { continue }
                foreach($idx in $rr.Result.Trace.Keys) {
                    if($idx -gt 0 -and $rr.Result.Trace.ContainsKey($idx-1) -and $rr.Result.Trace[$idx].engineTurn -eq ([int]$gameTurn-1) -and $rr.Result.Trace[$idx-1].engineTurn -lt ([int]$gameTurn-1) -and ($null -eq $candidate -or $idx -lt $candidate.Action)) {
                        $candidate=[pscustomobject]@{Action=[int]$idx;Trace=$rr.Result.Trace[$idx]}
                    }
                }
            }
            if($null -eq $candidate) { $boundaryObservations.Add([pscustomobject]@{GameTurn=[int]$gameTurn;NativeAction=$native.Action;NativePhase=$native.Phase;Status='not_comparable';Reason='no protocol-confirmed engine turn-transition TRACE';NativeHp=(Get-Prop $native.State 'hp' $null);EngineHp=$null}); continue }
            $nativeHp=Get-Prop $native.State 'hp' $null; $candidateTrace=$candidate.Trace
            $priorChainBreak=@($archive.ChainProblems | Where-Object { $_.Action -le $native.Action }).Count -gt 0
            $strictBoundary=($native.Action - 1 -eq $candidate.Action -and $archive.Alignment.AlignedPrefix -ge $candidate.Action -and -not $archive.JournalDirty -and -not $priorChainBreak)
            $sameHp=($null -ne $nativeHp -and [int]$nativeHp -eq $candidateTrace.hp)
            $boundaryObservations.Add([pscustomobject]@{GameTurn=[int]$gameTurn;EngineTurn=$candidateTrace.engineTurn;EngineAction=$candidate.Action;NativeAction=$native.Action;EnginePhase='after-engine-action-turn-transition';NativePhase=$native.Phase;EngineHp=$candidateTrace.hp;NativeHp=$nativeHp;Status=if($strictBoundary){if($sameHp){'comparable_match'}else{'comparable_difference'}}else{'restricted_boundary_observation'};Reason=if($strictBoundary){'native pre-state follows the confirmed engine end-turn transition'}else{'turn and transition stages align, but journal/JSONL action correspondence or cleanliness is insufficient'}})
        }
    }
    $firstDiff=@($comparisons | Where-Object Status -eq 'difference' | Select-Object -First 1)
    $protocolStatus=if($runResults.Count -eq 0){'not_run'}elseif($invalidAt){'replay_invalid'}elseif($stoppedReason -match 'execution_error|protocol_error'){$Matches[0]}elseif($longest -eq $archive.Journal.Count){'all_actions_replay_ok'}else{'prefix_only'}
    $strictMatch=($archive.StrictEligible -and $protocolStatus -eq 'all_actions_replay_ok' -and $archive.UnavailableFields.Count -eq 0 -and @($comparisons | Where-Object { $_.Status -in @('difference','not_comparable','terminal_not_comparable') }).Count -eq 0)
    [pscustomobject]@{
        Bundle=(Split-Path $archive.Bundle -Leaf); Archive=[pscustomobject]@{ Integrity=if($archive.IntegrityOk){'valid'}else{'invalid'}; Closed=$archive.Closed; Hashes=$archive.Hashes; Errors=$archive.Errors; JournalDirty=$archive.JournalDirty; Mode=$archive.EvidenceMode }
        ActionChain=[pscustomobject]@{ Status=if($archive.Alignment.Ok -and -not $archive.ChainProblems.Count){'aligned_with_no_visible_chain_break'}else{'issues'}; Alignment=$archive.Alignment; Problems=$archive.ChainProblems }
        Replay=[pscustomobject]@{ Status=$protocolStatus; LongestReplayPrefix=$longest; InvalidAt=$invalidAt; StoppedReason=$stoppedReason; PrefixResults=@($runResults | ForEach-Object { [pscustomobject]@{ Prefix=$_.Prefix; Kind=$_.Result.Kind; Reason=$_.Result.Reason; RejectAction=$_.Result.RejectAction; TraceIndices=@($_.Result.Trace.Keys | Sort-Object) } }) }
        Comparisons=@($comparisons); TurnBoundaryObservations=@($boundaryObservations); FirstDifference=if($firstDiff.Count){$firstDiff[0]}else{$null}
        EvidenceLevel=if($strictMatch){'strict_match_candidate_L4_requires_review'}elseif($archive.IntegrityOk -and $runResults.Count){'diagnostic_only_L2'}else{'blocked_L2'}
        StrictMatch=[bool]$strictMatch; EngineTurnMapping='game turn = engine turn + 1'; UnavailableFields=@('hand card identities/order','monster identity/HP/status/intent','relic state','RNG state','full powers unless emitted by replay_trace')
    }
}

Export-ModuleMember -Function Get-ActionKind,Get-ArchiveAssessment,Parse-TraceOutput,Test-ReplayResult,Compare-TraceState,Invoke-EnginePrefix,Invoke-RootReplayDiff,Test-ActionAlignment,Test-StateChain
