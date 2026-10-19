#!/usr/bin/env python3
"""Builds a release package for people who just want to install: dist/itvlauncher-<version>.zip, holding the
signed APK, setup.sh / setup.ps1 and the few tools they use. Unzip, run setup, done: only Python is needed.

Needs the release signing key (setup.py creates one when building from source). Attach the zip to a GitHub Release.
    tools/venv/bin/python tools/package.py
"""
import argparse
import hashlib
import re
import shutil
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import setup  # noqa: E402  (the build, JDK and SDK checks live there)

ROOT = setup.ROOT
DIST = ROOT / "dist"
INSTALL = """# Installing iTV Launcher {version}

An Apple TV-style home screen for Android TV and Google TV (Android 8 or newer).

You need a computer on the same network as the TV, with Python 3 (https://www.python.org/downloads/).

1. On the TV, switch on USB debugging (it also allows debugging over the network):
   - Google TV: Settings > System > About > press OK on "Android TV OS build" 7 times,
     then Settings > System > Developer options > USB debugging.
   - Android TV: Settings > Device Preferences > About > press OK on "Build" 7 times,
     then Developer options > USB debugging.
2. On the computer, in this folder:
   - macOS / Linux:  ./setup.sh
   - Windows:        powershell -ExecutionPolicy Bypass -File setup.ps1
3. When the TV asks "Allow USB debugging?", tick "Always allow from this computer" and choose OK.

setup finds your TV on the network, installs the launcher and makes it the home screen. To update, download a
newer package and run setup again. More, including how to go back to your TV's own home screen:
https://github.com/krushock/itvlauncher

APK SHA-256: {sha}
"""


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description="Build a release package of iTV Launcher.")
    parser.add_argument("--yes", action="store_true", help="accept the default answer to every question")
    setup.args = argparse.Namespace(yes=parser.parse_args().yes, debug=False)
    if not setup.SIGNING.exists():
        setup.stop(f"no signing key ({setup.SIGNING}): a package must be a signed release. "
                   "Run ./setup.sh once to create the key.")
    version = re.search(r"versionName\s*=\s*'([^']+)'", (setup.LAUNCHER / "app" / "build.gradle").read_text()).group(1)
    java_home = setup.prepare_java()
    sdk = setup.prepare_sdk(java_home)
    apk = setup.build(java_home, sdk, release=True)

    setup.step(f"Packaging {version}")
    name = f"itvlauncher-{version}"
    folder = DIST / name
    shutil.rmtree(folder, ignore_errors=True)
    (folder / "tools").mkdir(parents=True)
    shutil.copy2(apk, folder / f"{name}.apk")
    for f in ["setup.sh", "setup.ps1", "LICENSE"]:
        shutil.copy2(ROOT / f, folder / f)
    for f in ["setup.py", "tv.py", "b64push.py", "requirements.txt"]:
        shutil.copy2(setup.TOOLS / f, folder / "tools" / f)
    sha = sha256(folder / f"{name}.apk")
    (folder / "INSTALL.txt").write_text(INSTALL.format(version=version, sha=sha))
    zip_path = DIST / f"{name}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(folder.rglob("*")):
            info = zipfile.ZipInfo.from_file(p, f"{name}/{p.relative_to(folder).as_posix()}")
            if p.is_file():
                info.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(info, p.read_bytes())   # keeps setup.sh executable
    setup.ok(f"{zip_path.relative_to(ROOT)} ({zip_path.stat().st_size // 1024} KB)")
    setup.note(f"APK SHA-256: {sha}")
    setup.note("Attach the zip to a GitHub Release: Releases > Draft a new release, tag v" + version + ".")


if __name__ == "__main__":
    main()
