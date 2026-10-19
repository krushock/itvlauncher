# Windows version of restore.sh: re-enable every package listed in a debloat file, restore the
# stock launcher as home and reset animation scales. Pass the same list you used with disable.ps1.
#
# Usage: tools\restore.ps1 -TvHost 192.168.1.50 examples\xiaomi-mi-tv-4a\debloat.txt [-Stock stock.launcher.package]
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$List,
    [string]$Stock = 'com.google.android.tvlauncher',
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$repo = Split-Path -Parent $PSScriptRoot
$List = (Resolve-Path $List).Path
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost

$pkgs = Get-Content $List | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
$cmds = @($pkgs | ForEach-Object { "pm enable $_" }) + @(
    'pm enable com.google.android.tungsten.setupwraith 2>/dev/null; echo setup-app-on',   # Google TV, see build-and-install.ps1
    "pm enable $Stock",
    "cmd package set-home-activity --user 0 $Stock",
    'settings put global window_animation_scale 1.0',
    'settings put global transition_animation_scale 1.0',
    'settings put global animator_duration_scale 1.0',
    'pm list packages -d'
)
# Back to the stock home: switch the launcher's Home button service off (kept when com.krushock.itvlauncher is kept)
if ($Stock -ne 'com.krushock.itvlauncher') {
    $cmds += 'cur=$(settings get secure enabled_accessibility_services); new=$(echo "$cur" | sed -E "s#:?com\.krushock\.itvlauncher/\.HomeKeyService##; s#^:##"); if [ -z "$new" ] || [ "$new" = null ]; then settings delete secure enabled_accessibility_services; else settings put secure enabled_accessibility_services "$new"; fi; echo home-button-off'
}
$cmds | & $py -I (Join-Path $repo 'tools\tv.py') --batch
