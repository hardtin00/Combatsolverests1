Set-StrictMode -Version Latest

function Get-ConfigPathValue([object]$Config, [string]$Name) {
    $value = $Config.$Name
    if ([string]::IsNullOrWhiteSpace([string]$value)) { throw "required configuration path is empty: $Name" }
    if (-not [System.IO.Path]::IsPathRooted([string]$value)) { throw "configuration path must be absolute: $Name" }
    return [System.IO.Path]::GetFullPath([string]$value).TrimEnd('\')
}

function Test-PathUnder([string]$Path, [string]$Root) {
    $p = [System.IO.Path]::GetFullPath($Path).TrimEnd('\')
    $r = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    return $p.Equals($r, [System.StringComparison]::OrdinalIgnoreCase) -or
        $p.StartsWith(($r + '\'), [System.StringComparison]::OrdinalIgnoreCase)
}

function Read-AutomationConfig([string]$ConfigPath) {
    if (-not [System.IO.Path]::IsPathRooted($ConfigPath)) { $ConfigPath = Join-Path (Get-Location).Path $ConfigPath }
    if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) { throw "configuration file is missing: $ConfigPath" }
    try { $config = Get-Content -Raw -LiteralPath $ConfigPath | ConvertFrom-Json -ErrorAction Stop }
    catch { throw "configuration JSON is invalid: $($_.Exception.Message)" }
    if ($config.schema_version -ne 1) { throw "unsupported configuration schema_version: $($config.schema_version)" }
    if ($config.scenario_id -ne 'ironclad-single-monster-v1') { throw 'unsupported scenario_id' }
    if ($config.automation_enabled -ne $true) { throw 'automation_enabled must be true in an isolated test config' }
    if ($config.character -ne 'IRONCLAD' -or [int]$config.target_monster_count -ne 1) { throw 'only the versioned Ironclad single-monster scenario is supported' }
    if ($config.room_class -ne 'com.megacrit.cardcrawl.rooms.MonsterRoom' -or $config.expected_monster_class -ne 'com.megacrit.cardcrawl.monsters.exordium.JawWorm') { throw 'only the configured native Jaw Worm MonsterRoom scenario is supported' }
    if (-not ($config.accepted_vanilla_monster_classes -is [System.Array]) -or $config.accepted_vanilla_monster_classes.Count -eq 0) { throw 'accepted_vanilla_monster_classes must be a non-empty array' }
    if (@($config.accepted_vanilla_monster_classes | Where-Object { $_ -ne $config.expected_monster_class }).Count -gt 0 -or $config.accepted_vanilla_monster_classes.Count -ne 1) { throw 'monster allowlist must exactly match the single configured vanilla class' }
    $seedValue=0L
    if (-not [long]::TryParse([string]$config.seed,[ref]$seedValue)) { throw 'seed must be a signed 64-bit integer' }
    if ([string]$config.launcher_mode -ne 'STEAM_PLAY_WITH_MODS') { throw 'launcher_mode must be STEAM_PLAY_WITH_MODS' }
    if ([int]$config.timeout_seconds -lt 30 -or [int]$config.timeout_seconds -gt 3600) { throw 'timeout_seconds must be within 30..3600' }

    $names = @('test_root','steam_root','steam_exe','steam_userdata_dir','game_dir','mod_dir','mts_jar','basemod_jar','probe_jar','test_mod_jar','engine_path','save_dir','preferences_dir','logs_dir','output_dir','isolation_attestation')
    $paths = @{}
    foreach ($name in $names) { $paths[$name] = Get-ConfigPathValue $config $name }
    if (-not (Test-PathUnder $paths.game_dir $paths.test_root)) { throw 'game_dir must be inside test_root' }
    foreach ($name in @('steam_root','steam_exe','steam_userdata_dir','mod_dir','mts_jar','basemod_jar','probe_jar','test_mod_jar','engine_path','save_dir','preferences_dir','logs_dir','output_dir','isolation_attestation')) {
        if (-not (Test-PathUnder $paths[$name] $paths.test_root)) { throw "$name must be inside test_root" }
    }
    foreach ($name in @('steam_exe','steam_userdata_dir','game_dir','mts_jar','basemod_jar')) { if (-not (Test-PathUnder $paths[$name] $paths.steam_root)) { throw "$name must be inside steam_root" } }
    foreach ($name in @('save_dir','preferences_dir','logs_dir','mod_dir')) { if (-not (Test-PathUnder $paths[$name] $paths.game_dir)) { throw "$name must be inside game_dir" } }
    if (-not (Test-PathUnder $paths.mod_dir $paths.game_dir)) { throw 'mod_dir must be inside game_dir' }
    if (-not (Test-PathUnder $paths.probe_jar $paths.mod_dir) -or -not (Test-PathUnder $paths.test_mod_jar $paths.mod_dir)) { throw 'probe_jar and test_mod_jar must be inside mod_dir' }
    if (-not ($config.steam_launch_arguments -is [System.Array]) -or $config.steam_launch_arguments.Count -ne 2 -or
        [string]$config.steam_launch_arguments[0] -ne '-applaunch' -or [string]$config.steam_launch_arguments[1] -ne '646570') {
        throw 'steam_launch_arguments must explicitly launch STS1 app 646570 through Steam'
    }
    return [pscustomobject]@{ Raw=$config; Paths=$paths; ConfigPath=[System.IO.Path]::GetFullPath($ConfigPath) }
}

function Assert-NoReparsePath([string]$Path, [string]$Root) {
    $full = [System.IO.Path]::GetFullPath($Path)
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    if (-not (Test-PathUnder $full $rootFull)) { throw "path escapes test_root: $full" }
    $cursor = $full
    while ($cursor -and (Test-Path -LiteralPath $cursor)) {
        $item = Get-Item -LiteralPath $cursor -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw "reparse point is not allowed in isolated path: $cursor" }
        if ($cursor.Equals($rootFull, [System.StringComparison]::OrdinalIgnoreCase)) { break }
        $cursor = Split-Path -Parent $cursor
    }
}

function Test-ConfigHasHostInstall([object]$Paths) {
    $known = @(
        (Join-Path ${env:ProgramFiles(x86)} 'Steam\steamapps\common\SlayTheSpire'),
        (Join-Path ${env:ProgramFiles(x86)} 'Steam\steam.exe')
    ) | Where-Object { $_ }
    foreach ($name in @('steam_exe','game_dir')) {
        foreach ($hostPath in $known) {
            if ([string]$Paths[$name] -eq [System.IO.Path]::GetFullPath($hostPath).TrimEnd('\')) { return $true }
        }
    }
    return $false
}

function Assert-GuestIsolation([object]$ConfigData, [switch]$AllowMissingAutomationAssets) {
    $p = $ConfigData.Paths
    if (Test-ConfigHasHostInstall $p) { throw 'configured path points to the known host Steam/STS1 installation' }
    if (-not (Test-Path -LiteralPath $p.test_root -PathType Container)) { throw 'test_root does not exist' }
    foreach ($name in @('steam_root','steam_exe','steam_userdata_dir','game_dir','mod_dir','mts_jar','basemod_jar','probe_jar','test_mod_jar','engine_path','save_dir','preferences_dir','logs_dir','output_dir','isolation_attestation')) {
        Assert-NoReparsePath $p[$name] $p.test_root
    }

    $attestation = $null
    try { $attestation = Get-Content -Raw -LiteralPath $p.isolation_attestation | ConvertFrom-Json -ErrorAction Stop }
    catch { throw "isolation attestation missing or invalid: $($_.Exception.Message)" }
    if ($attestation.schema_version -ne 1 -or $attestation.vm_guest -ne $true -or $attestation.separate_virtual_disk -ne $true -or
        $attestation.no_host_writable_shares -ne $true -or $attestation.isolated_steam_userdata -ne $true -or
        $attestation.steam_cloud_isolated -ne $true -or $attestation.legal_steam_entitlement -ne $true -or
        $attestation.launcher_mode -ne 'STEAM_PLAY_WITH_MODS') { throw 'isolation attestation does not satisfy the required guest, storage, entitlement, and Cloud assertions' }

    $computer = Get-CimInstance Win32_ComputerSystem -ErrorAction Stop
    $vmText = "$($computer.Manufacturer) $($computer.Model)"
    if ($vmText -notmatch '(?i)virtual|vmware|virtualbox|qemu|hyper-v|kvm|xen|parallels') { throw "Windows did not identify a VM guest (manufacturer/model: $vmText)" }
    $drive = [System.IO.Path]::GetPathRoot($p.test_root).TrimEnd('\').TrimEnd(':')
    $logical = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='$drive`:'" -ErrorAction Stop
    if (-not $logical -or $logical.DriveType -ne 3) { throw 'test_root must be on a local fixed disk inside the guest' }
    $mapped = @(Get-PSDrive -PSProvider FileSystem | Where-Object { $_.DisplayRoot -or $_.Root -like '\\*' })
    if ($mapped.Count -gt 0) { throw 'mapped or network file-system drives are visible; guest isolation is not accepted' }
    try {
        $shares = @(Get-CimInstance Win32_Share -ErrorAction Stop | Where-Object { $_.Type -eq 0 -and $_.Path -and (Test-PathUnder $_.Path $p.test_root) })
        if ($shares.Count -gt 0) { throw 'a host-writable share is visible inside the configured test root' }
    } catch { if ($_.Exception.Message -match 'host-writable share') { throw }; throw "cannot verify guest shares: $($_.Exception.Message)" }

    $requiredFiles = @($p.steam_exe,$p.mts_jar,$p.basemod_jar,$p.engine_path,(Join-Path $p.game_dir 'desktop-1.0.jar'))
    if (-not $AllowMissingAutomationAssets) { $requiredFiles += @($p.probe_jar,$p.test_mod_jar) }
    foreach ($file in $requiredFiles) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "required isolated test file is missing: $file" }
    }
    if (-not (Test-Path -LiteralPath $p.mod_dir -PathType Container)) { throw 'configured test mod directory is missing' }
    if (Test-Path -LiteralPath (Join-Path $p.mod_dir 'CombatSolver1.jar')) { throw 'formal CombatSolver1.jar is present in the automation profile; refusing to mix production and test mods' }
    if (-not (Test-Path -LiteralPath $p.steam_userdata_dir -PathType Container)) { throw 'isolated Steam userdata directory is missing' }
    if (Test-Path -LiteralPath $p.save_dir) {
        $saveItems = @(Get-ChildItem -LiteralPath $p.save_dir -Force)
        if ($saveItems.Count -gt 0) { throw 'test save directory is not empty; refusing to reuse any run or autosave' }
    }
    if (Get-Process -Name steam -ErrorAction SilentlyContinue) { throw 'Steam is already running; it cannot inherit the isolated run environment. Do not terminate it automatically.' }
    return [pscustomobject]@{ Vm=$vmText; TestRoot=$p.test_root; SaveDirectoryEmpty=$true }
}

function Test-DiagnosticOnly([string]$SnapshotPath) {
    if (-not (Test-Path -LiteralPath $SnapshotPath -PathType Leaf)) { throw 'diagnostic snapshot file is missing' }
    try { $snapshot = Get-Content -Raw -LiteralPath $SnapshotPath | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'diagnostic snapshot JSON is invalid' }
    if ($snapshot.protocol -ne 'live_battle_diagnostic_snapshot' -or $snapshot.version -ne 1) { throw 'not a versioned diagnostic-only snapshot' }
    return $true
}

Export-ModuleMember -Function Read-AutomationConfig,Assert-GuestIsolation,Assert-NoReparsePath,Test-DiagnosticOnly,Test-PathUnder,Test-ConfigHasHostInstall
