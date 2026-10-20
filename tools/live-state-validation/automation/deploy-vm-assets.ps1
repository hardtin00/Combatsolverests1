param(
    [ValidateSet('DryRun','Deploy')][string]$Mode='DryRun',
    [string]$Config='',
    [Parameter(Mandatory=$true)][string]$ProbeSource,
    [Parameter(Mandatory=$true)][string]$TestModSource
)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'Automation.Common.psm1') -Force
if ([string]::IsNullOrWhiteSpace($Config)) { $Config=Join-Path $PSScriptRoot 'automation-config-v1.json' }
try { $data=Read-AutomationConfig $Config } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 2 }
$p=$data.Paths
if($Mode -eq 'Deploy') {
    if(-not (Test-PathUnder $data.ConfigPath $p.test_root) -or -not (Test-PathUnder $PSScriptRoot $p.test_root)) { [Console]::Error.WriteLine('Deploy mode requires the config and deployment script inside test_root'); exit 3 }
    try { Assert-NoReparsePath $data.ConfigPath $p.test_root; Assert-NoReparsePath $PSScriptRoot $p.test_root } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 3 }
}
foreach($source in @($ProbeSource,$TestModSource)) {
    if(-not [System.IO.Path]::IsPathRooted($source)){ [Console]::Error.WriteLine("source path must be absolute: $source"); exit 2 }
    $full=[System.IO.Path]::GetFullPath($source)
    if(-not (Test-PathUnder $full $p.test_root)){ [Console]::Error.WriteLine("source must be inside isolated test_root: $full"); exit 3 }
    if(-not (Test-Path -LiteralPath $full -PathType Leaf)){ [Console]::Error.WriteLine("source JAR missing: $full"); exit 2 }
}
if([System.IO.Path]::GetFileName($p.probe_jar) -eq 'CombatSolver1.jar' -or [System.IO.Path]::GetFileName($p.test_mod_jar) -eq 'CombatSolver1.jar') { throw 'deployment target may not be the formal CombatSolver1.jar' }
if($Mode -eq 'DryRun') {
    [pscustomobject]@{schema_version=1;status='DRY_RUN_ONLY';copied=$false;targets=@($p.probe_jar,$p.test_mod_jar);sources=@([System.IO.Path]::GetFullPath($ProbeSource),[System.IO.Path]::GetFullPath($TestModSource));note='No target directories or files were created or modified.'} | ConvertTo-Json -Depth 5
    exit 0
}
try { $null=Assert-GuestIsolation $data -AllowMissingAutomationAssets } catch { [Console]::Error.WriteLine("BLOCKED: $($_.Exception.Message)"); exit 3 }
New-Item -ItemType Directory -Force -Path $p.mod_dir | Out-Null
$pairs=@(@{Source=[System.IO.Path]::GetFullPath($ProbeSource);Target=$p.probe_jar},@{Source=[System.IO.Path]::GetFullPath($TestModSource);Target=$p.test_mod_jar})
$missingTargets=@()
foreach($pair in $pairs) {
    if(Test-Path -LiteralPath $pair.Target) {
        $src=(Get-FileHash -Algorithm SHA256 -LiteralPath $pair.Source).Hash
        $dst=(Get-FileHash -Algorithm SHA256 -LiteralPath $pair.Target).Hash
        if($src -ne $dst){ throw "refusing to overwrite an existing isolated JAR with different bytes: $($pair.Target)" }
        continue
    }
    $missingTargets += $pair
}
foreach($pair in $missingTargets) {
    Copy-Item -LiteralPath $pair.Source -Destination $pair.Target
    if((Get-FileHash -Algorithm SHA256 -LiteralPath $pair.Source).Hash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $pair.Target).Hash){ throw "deployed JAR verification failed: $($pair.Target)" }
}
[pscustomobject]@{schema_version=1;status='DEPLOYED_TO_ISOLATED_VM';copied=$true;targets=@($p.probe_jar,$p.test_mod_jar);sha256=@((Get-FileHash -Algorithm SHA256 -LiteralPath $p.probe_jar).Hash.ToLowerInvariant(),(Get-FileHash -Algorithm SHA256 -LiteralPath $p.test_mod_jar).Hash.ToLowerInvariant())} | ConvertTo-Json -Depth 5
