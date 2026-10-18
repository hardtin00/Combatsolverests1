param(
    [string]$BundlePath = '',
    [string]$EnginePath = '',
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'RootReplayDiff.psm1') -Force -DisableNameChecking

if ($SelfTest) {
    & (Join-Path $PSScriptRoot 'RootReplayDiffCheck.ps1')
    exit $LASTEXITCODE
}
if (-not $BundlePath) { throw 'BundlePath is required unless -SelfTest is used' }
if (-not $EnginePath) { $EnginePath = Join-Path (Get-Location) 'build/engine/combatsolver1-engine.exe' }
$result = Invoke-RootReplayDiff $BundlePath $EnginePath
$json = $result | ConvertTo-Json -Depth 16
Write-Output $json
$matched = [int](($result.Comparisons | Measure-Object Matched -Sum).Sum)
$compared = [int](($result.Comparisons | Measure-Object Compared -Sum).Sum)
$diff = if ($result.FirstDifference) { "action=$($result.FirstDifference.EngineAction) $($result.FirstDifference.Differences | ForEach-Object { "$($_.Field): engine=$($_.Engine), native=$($_.Native)" } -join '; ')" } else { 'none' }
Write-Output ("SUMMARY bundle={0} integrity={1} chain={2} replay={3} longestPrefix={4} visibleFields={5}/{6} firstDifference={7} evidence={8}" -f $result.Bundle,$result.Archive.Integrity,$result.ActionChain.Status,$result.Replay.Status,$result.Replay.LongestReplayPrefix,$matched,$compared,$diff,$result.EvidenceLevel)
