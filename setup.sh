#!/bin/sh
# iTV Launcher setup for macOS and Linux: installs the launcher on your TV, or updates it. Options: --help.
cd "$(dirname "$0")" || exit 1
if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3 is needed: https://www.python.org/downloads/"
  exit 1
fi
exec python3 tools/setup.py "$@"
