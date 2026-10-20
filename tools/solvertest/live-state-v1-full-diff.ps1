param(
    [Parameter(Mandatory=$true)][string]$StateFile,
    [Parameter(Mandatory=$true)][string]$EngineOutputFile,
    [string]$Manifest = ''
)
$ErrorActionPreference = 'Stop'
if (-not $Manifest) { $Manifest = Join-Path $PSScriptRoot '..\..\docs\LIVE_BATTLE_STATE_V1_FIELD_AUDIT.md' }
$state = Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json
$engine = Get-Content -Raw -LiteralPath $EngineOutputFile
$rows = [System.Collections.Generic.List[object]]::new()
function Resolve-ProtocolPath($root, [string]$path) {
    $current = $root
    $segments = $path.Substring(2).Split('.')
    foreach ($segment in $segments) {
        if ($segment.EndsWith('[]')) {
            $name = $segment.Substring(0, $segment.Length - 2)
            $current = $current.$name
            return ,$current
        }
        $current = $current.$segment
    }
    return $current
}
## Read only the canonical path inventory table. The audit document also has
## historical resolution tables containing path-like rows; treating those as
## protocol fields inflated the 149-shape diff to 175 and mislabeled them BLOCKED.
$inCanonicalInventory = $false
$seenCanonicalRow = $false
foreach ($line in Get-Content -LiteralPath $Manifest) {
    if (-not $inCanonicalInventory) {
        if ($line -match '^\| Protocol path \| Type \| Classification \|') { $inCanonicalInventory = $true }
        continue
    }
    if ($line -match '^\| `(\$\.[^`]+)` \|') {
        $seenCanonicalRow = $true
    } elseif ($line -match '^\|[-: |]+\|$') {
        continue
    } else {
        if ($seenCanonicalRow) { break }
        continue
    }
    $path = $Matches[1]
    $normalized = $path.Substring(2) -replace '\[(\d+)\]', '[]'
    $classification = if ($line -match '\| \*\*(REQUIRED|CONDITIONAL|DERIVABLE|INTERNAL|UNUSED|BLOCKED)\*\* \|') { $Matches[1] } else { 'BLOCKED' }
    $status = 'UNAVAILABLE'
    $evidence = 'C++ emitted no independently comparable field for this protocol path.'
    if ($path -eq '$.capture_id' -and $engine -match '(?m)^STATE0_DETAIL_FIELD path=capture_id .* status=NOT_APPLICABLE') {
        $status = 'NOT_APPLICABLE'
        $evidence = 'Validated transport UUID; intentionally excluded from BattleContext/simulation state.'
    }

    # A path shape below an empty source array is explicitly not applicable in
    # this fixture. It is never counted as a successful comparison.
    $segments = $path -split '\.'
    $arrayContainer = $null
    foreach ($segment in $segments) {
        if ($segment -match '^([^\[]+)\[\]$') {
            $arrayContainer = $segment.Substring(0, $segment.Length - 2)
            break
        }
    }
    if ($arrayContainer) {
        try { $arrayValue = Resolve-ProtocolPath $state $path } catch { $arrayValue = $null }
        $firstArray = $path.IndexOf('[]', [System.StringComparison]::Ordinal)
        $parent = if ($firstArray -ge 0) { $path.Substring(0, $firstArray) } else { '$' }
        if ($arrayValue -is [array] -and $arrayValue.Count -eq 0) {
            $emptyPile = [regex]::Match($engine, '(?m)^STATE0_DETAIL_PILE path=' + [regex]::Escape($parent.Substring(2)) + ' source_count=0 restored_count=0 status=(MATCH|MISMATCH)$')
            if ($emptyPile.Success) {
                $status = $emptyPile.Groups[1].Value
                $evidence = 'C++ importer explicitly rejects nonempty stable-boundary limbo/stasis state and reports the validated restored-empty invariant.'
            } else {
                $status = 'NOT_APPLICABLE'
                $evidence = "protocol array $parent is explicitly empty in this offline fixture; the row has no item instances to compare"
            }
        }
    }

    if ($status -ne 'NOT_APPLICABLE') {
        $escaped = [regex]::Escape($normalized)
        $shapePattern = '^' + [regex]::Escape($normalized.Replace('[]','__ARRAY__')).Replace('__ARRAY__','\[\d+\]') + '$'
        $fieldLines = @([regex]::Matches($engine, '(?m)^STATE0_DETAIL_FIELD path=([^\s]+) source=(.*?) restored=(.*?) status=(MATCH|MISMATCH|UNAVAILABLE|NOT_APPLICABLE)(?:\s+reason=[^\r\n]*)?$') |
            Where-Object { $_.Groups[1].Value -eq $normalized -or ($normalized.Contains('[]') -and $_.Groups[1].Value -match $shapePattern) })
        if ($fieldLines.Count -gt 0) {
            $individualStatuses = @($fieldLines | ForEach-Object { $_.Groups[4].Value } | Select-Object -Unique)
            if ($individualStatuses.Count -eq 1) { $status = $individualStatuses[0] }
            else { $status = 'MISMATCH' }
            $evidence = 'C++ STATE0_DETAIL_FIELD readback; all represented instances checked.'
        } elseif ($normalized -match '^rng\.(ai|card|misc|monster_hp|potion|shuffle)\.(stream_id|seed0|seed1|counter)$') {
            $parentPath = $normalized -replace '\.(stream_id|seed0|seed1|counter)$',''
            if ($engine -match ('(?m)^STATE0_DETAIL_FIELD path=' + [regex]::Escape($parentPath) + ' .* status=(MATCH|MISMATCH|UNAVAILABLE)$')) {
                $status = $Matches[1]
                $evidence = 'strict whole-RNG-object equality from C++ readback, expanded to leaf path'
            }
        } elseif ($normalized -match '^piles\.(hand|draw|discard|exhaust)\[\](?:\.(uuid|id|special_data|cost|cost_for_turn|upgraded|upgrade_count|free_to_play_once|retain))?$') {
            $pileName = $Matches[1]
            $leafName = $Matches[2]
            $sourcePile = $state.piles.$pileName
            $pileLine = [regex]::Match($engine, '(?m)^STATE0_DETAIL_PILE path=piles\.' + [regex]::Escape($pileName) + ' source_count=(\d+) restored_count=(\d+) status=(MATCH|MISMATCH)$')
            $cardLines = @([regex]::Matches($engine, '(?m)^STATE0_DETAIL_CARD uuid=([^\s]+) dense_id=(\d+) pile=([^\s]+) pile_index=(\d+) source_id=([^\s]+) normalized_id=(.*?) status=(MATCH|MISMATCH)(?:\s+[^\r\n]*)?$') |
                Where-Object { $_.Groups[3].Value -eq $pileName })
            if ($pileLine.Success -and [int]$pileLine.Groups[1].Value -eq $sourcePile.Count -and
                    [int]$pileLine.Groups[2].Value -eq $sourcePile.Count -and $cardLines.Count -eq $sourcePile.Count) {
                $badCard = @($cardLines | Where-Object { $_.Groups[7].Value -ne 'MATCH' -or [int]$_.Groups[4].Value -ge $sourcePile.Count })
                if ($badCard.Count) { $status = 'MISMATCH' }
                elseif ($leafName -eq 'upgrade_count') {
                    $upgradeValid = @($sourcePile | Where-Object { ($_.upgrade_count -ne 0 -and $_.upgrade_count -ne 1) -or ([bool]$_.upgraded -ne ($_.upgrade_count -eq 1)) }).Count -eq 0
                    $status = if ($upgradeValid) { 'MATCH' } else { 'MISMATCH' }
                    $evidence = 'C++ parser validates upgrade_count against restored upgraded flag; unsupported multi-upgrade states are rejected.'
                } else {
                    $status = 'MATCH'
                    $evidence = if ($leafName -eq 'uuid') { 'Exact UUID is the key of the validated source-UUID to restored dense uniqueId mapping; rows prove one-to-one pile/order binding.' } else { 'C++ STATE0_DETAIL_CARD compares the restored CardInstance at the mapped dense id and original pile order.' }
                }
            } elseif ($pileLine.Success -and $sourcePile.Count -eq 0 -and [int]$pileLine.Groups[2].Value -eq 0) {
                $status = 'MATCH'
                $evidence = 'C++ restored pile is explicitly empty (size readback); no item fields apply.'
            }
        } elseif ($normalized -match '^player\.statuses\[\](?:\.(id|amount|just_applied))?$') {
            $statusLine = [regex]::Match($engine, '(?m)^STATE0_DETAIL_FIELD path=player\.statuses .* status=(MATCH|MISMATCH|UNAVAILABLE)$')
            if ($statusLine.Success) { $status = $statusLine.Groups[1].Value; $evidence = 'Normalized status list compared to values reconstructed from Player status storage and just-applied bits.' }
        } elseif ($normalized -match '^monsters\[\]\.statuses\[\](?:\.(id|amount|just_applied))?$') {
            $statusLines = @([regex]::Matches($engine, '(?m)^STATE0_DETAIL_FIELD path=monsters\[\d+\]\.statuses .* status=(MATCH|MISMATCH|UNAVAILABLE)$'))
            if ($statusLines.Count -eq $state.monsters.Count) {
                if (@($statusLines | Where-Object { $_.Groups[1].Value -ne 'MATCH' }).Count) { $status = 'MISMATCH' } else { $status = 'MATCH' }
                $evidence = 'Each Sentry status list compared to restored status values and just-applied bits.'
            }
        } elseif ($normalized -match '^relic_counters(?:\[\](?:\.(id|value))?)?$') {
            $counterLine = [regex]::Match($engine, '(?m)^STATE0_DETAIL_FIELD path=relic_counters .* status=(MATCH|MISMATCH|UNAVAILABLE)$')
            if ($counterLine.Success) { $status = $counterLine.Groups[1].Value; $evidence = 'Relic counters compared against named BattleContext counter fields.' }
        } elseif ($normalized -match '^(potions|relics)\[\]\.id$') {
            $collectionLine = [regex]::Match($engine, '(?m)^STATE0_DETAIL_FIELD path=' + $Matches[1] + ' .* status=(MATCH|MISMATCH|UNAVAILABLE)$')
            if ($collectionLine.Success) {
                $status = $collectionLine.Groups[1].Value
                $evidence = if ($Matches[1] -eq 'potions') { 'Ordered slot IDs compared to BattleContext potion enum readback.' } else { 'Relic IDs compared as a normalized set against Player relic bitsets.' }
            }
        } elseif ($normalized -match '^player\.counters\.orange_pellets\[\]$') {
            $counterLine = [regex]::Match($engine, '(?m)^STATE0_DETAIL_FIELD path=player\.counters\.orange_pellets .* status=(MATCH|MISMATCH|UNAVAILABLE)$')
            if ($counterLine.Success) { $status = $counterLine.Groups[1].Value; $evidence = 'Three orange-pellet category flags compared against restored Player bitset.' }
        } elseif ($normalized -match '^monster_group\.(extra_roll|skip_turn)\[\]$') {
            $flagLine = [regex]::Match($engine, '(?m)^STATE0_DETAIL_FIELD path=monster_group\.' + $Matches[1] + ' .* status=(MATCH|MISMATCH|UNAVAILABLE)$')
            if ($flagLine.Success) { $status = $flagLine.Groups[1].Value; $evidence = 'MonsterGroup bitset flags compared against restored slot-order booleans.' }
        }
    }
    $rows.Add([pscustomobject]@{Path=$path; Classification=$classification; Status=$status; Evidence=$evidence})
}
$out = Join-Path ([System.IO.Path]::GetDirectoryName($EngineOutputFile)) 'live-state-v1-full-field-diff.txt'
$counts = @{}
foreach ($name in @('MATCH','MISMATCH','UNAVAILABLE','NOT_APPLICABLE')) { $counts[$name] = @($rows | Where-Object Status -eq $name).Count }
$firstDiff = @($rows | Where-Object Status -in @('MISMATCH','UNAVAILABLE') | Select-Object -First 1)
$classificationSummary = @($rows | Group-Object Classification | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ','
@(
    "evidence=OFFLINE_FIXTURE_NOT_REAL_GAME_STATE protocol_path_shapes=$($rows.Count) MATCH=$($counts.MATCH) MISMATCH=$($counts.MISMATCH) UNAVAILABLE=$($counts.UNAVAILABLE) NOT_APPLICABLE=$($counts.NOT_APPLICABLE) classifications=$classificationSummary first_difference=$(if($firstDiff.Count){$firstDiff[0].Path + ':' + $firstDiff[0].Status}else{'NONE'})",
    '| Path | Classification | Status | Evidence |', '|---|---|---|---|',
    @($rows | ForEach-Object { "| ``$($_.Path)`` | $($_.Classification) | **$($_.Status)** | $($_.Evidence) |" })
) | Set-Content -LiteralPath $out -Encoding UTF8
Write-Output "STATE0_DETAIL_PATH_DIFF evidence=OFFLINE_FIXTURE_NOT_REAL_GAME_STATE paths=$($rows.Count) MATCH=$($counts.MATCH) MISMATCH=$($counts.MISMATCH) UNAVAILABLE=$($counts.UNAVAILABLE) NOT_APPLICABLE=$($counts.NOT_APPLICABLE) classifications=$classificationSummary first_difference=$(if($firstDiff.Count){$firstDiff[0].Path + ':' + $firstDiff[0].Status}else{'NONE'}) report=$out"
