# Windows version of build-and-install.sh: build the launcher APK and install it on the TV as the
# default home screen. Uses Android Studio's bundled JDK and the default SDK location unless
# JAVA_HOME / ANDROID_HOME are set.
#
# Usage: launcher\build-and-install.ps1 -TvHost 192.168.1.50 [-StockLauncher com.google.android.tvlauncher]
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = 'com.google.android.tvlauncher'
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
    $env:JAVA_HOME = $jbr
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
}

# A signed release build when the signing key is set up (see README), else a debug build. $env:BUILD_VARIANT = debug
# or release overrides. A TV only takes updates signed with the key it already has.
$signing = if ($env:ITVLAUNCHER_SIGNING) { $env:ITVLAUNCHER_SIGNING } else { Join-Path $HOME '.config\krushock\itvlauncher-signing.properties' }
$variant = if (Test-Path $signing) { 'release' } else { 'debug' }
if ($env:BUILD_VARIANT) { $variant = $env:BUILD_VARIANT }
if ($variant -eq 'release') { .\gradlew.bat assembleRelease -q --console=plain } else { $variant = 'debug'; .\gradlew.bat assembleDebug -q --console=plain }
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
Write-Host "Built the $variant version"

$py = '..\tools\venv\Scripts\python.exe'
$apk = "app\build\outputs\apk\$variant\app-$variant.apk"
& $py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
$out = & $py -I ..\tools\tv.py 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
Write-Host $out
if ("$out" -match 'UPDATE_INCOMPATIBLE') { throw 'The TV has this launcher signed with another key (debug vs release). Back up the layout, uninstall it (pm uninstall com.krushock.itvlauncher) and run this again; the stock launcher was left untouched' }
if ("$out" -notmatch 'Success') { throw 'pm install failed; the stock launcher was left untouched' }
$out = & $py -I ..\tools\tv.py 'cmd package set-home-activity --user 0 com.krushock.itvlauncher'
Write-Host $out
if ("$out" -match 'Error') { throw 'could not set the new launcher as home; the stock launcher was left untouched' }
# Google TV (launcherx) keeps its own home on: voice search asks it which mode the TV is in, it holds the Google
# Home panel, and its setup app answers Sony's country lookups. Google TV's home always wins the Home button while
# it's on, so this launcher's accessibility service (HomeKeyService) takes the button over instead. Other TVs
# switch their stock launcher off, as before.
$googleTvHome = 'com.google.android.apps.tv.launcherx'
$homeService = 'com.krushock.itvlauncher/.HomeKeyService'
$googleTv = "$(& $py -I ..\tools\tv.py "pm list packages $googleTvHome")" -match "package:$([regex]::Escape($googleTvHome))"
if ($googleTv) {
    # Switched off before (an older install): Google TV's home must run once to catch up, or its title pages (voice
    # search results) keep loading for a minute or more. Give it 20 seconds before the service takes Home over.
    $wasOff = "$(& $py -I ..\tools\tv.py "pm list packages -d $googleTvHome")" -match "package:$([regex]::Escape($googleTvHome))"
    $warmUp = if ($wasOff) { "am start -n $googleTvHome/.home.HomeActivity >/dev/null 2>&1; sleep 20; echo google-tv-home-loaded" } else { 'echo google-tv-home-ready' }
    $homeSteps = @(
        "pm enable $googleTvHome",
        'pm enable com.google.android.tungsten.setupwraith 2>/dev/null; echo setup-app-on',
        $warmUp,
        # add the Home button service to whatever accessibility services are already on
        ('cur=$(settings get secure enabled_accessibility_services); case "$cur" in *' + $homeService + '*) ;; null|"") settings put secure enabled_accessibility_services ' + $homeService + ' ;; *) settings put secure enabled_accessibility_services "$cur:' + $homeService + '" ;; esac; settings put secure accessibility_enabled 1; echo home-button-on')
    )
} else {
    $homeSteps = @(
        "pm disable-user --user 0 $StockLauncher",
        'cmd package set-home-activity --user 0 com.krushock.itvlauncher'
    )
}
(@(
    'pm grant com.krushock.itvlauncher android.permission.ACCESS_FINE_LOCATION 2>/dev/null; pm grant com.krushock.itvlauncher android.permission.ACCESS_COARSE_LOCATION 2>/dev/null; echo location-granted',
    'pm grant com.krushock.itvlauncher android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; pm grant com.krushock.itvlauncher android.permission.READ_MEDIA_IMAGES 2>/dev/null; echo photos-granted',
    'pm grant com.krushock.itvlauncher android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null; echo backup-granted',
    'cmd notification allow_dnd com.krushock.itvlauncher 2>/dev/null; echo dnd-granted',
    'pm grant com.krushock.itvlauncher android.permission.READ_TV_LISTINGS 2>/dev/null; echo top-shelf-granted',
    'appops set com.krushock.itvlauncher GET_USAGE_STATS allow 2>/dev/null; echo recent-apps-granted',
    'sleep 2; cmd notification allow_listener com.krushock.itvlauncher/com.krushock.itvlauncher.MediaWatcher 2>/dev/null; echo now-playing-granted'
) + $homeSteps) | & $py -I ..\tools\tv.py --batch
if ($googleTv) {
    $on = & $py -I ..\tools\tv.py 'settings get secure enabled_accessibility_services'
    if ("$on" -notmatch [regex]::Escape($homeService)) { throw "Installed, but the Home button service didn't switch on: Settings > Accessibility > Home button for this home screen" }
    & $py -I ..\tools\tv.py 'input keyevent KEYCODE_HOME' | Out-Null
    Write-Host "Installed. Home now opens this launcher; hold Home for Google TV's panel."
} else {
    $homeApp = & $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1'
    if ("$homeApp" -notmatch 'com\.krushock\.itvlauncher') { throw "Installed, but Home still opens $homeApp (see README: Working on the TV)." }
    Write-Host 'Installed. Press Home on the remote to see it.'
}
