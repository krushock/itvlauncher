# iTV Launcher setup for Windows: installs the launcher on your TV, or updates it. Options: -- --help.
# Run with: powershell -ExecutionPolicy Bypass -File setup.ps1
Set-Location $PSScriptRoot
if (Get-Command py -ErrorAction SilentlyContinue) { & py -3 tools\setup.py @args }
elseif (Get-Command python -ErrorAction SilentlyContinue) { & python tools\setup.py @args }
else { Write-Host 'Python 3 is needed: https://www.python.org/downloads/'; exit 1 }
exit $LASTEXITCODE
