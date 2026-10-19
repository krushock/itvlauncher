#!/usr/bin/env python3
"""Set up iTV Launcher on a TV in one go, and update it later by running it again.

Two ways in, one installer:
- From a release package (itvlauncher-<version>.zip): installs the ready-made APK next to this script. Only
  Python is needed.
- From source (this repository): checks the JDK and the Android SDK (offering to install what's missing), offers
  to create a release signing key, builds the launcher, then installs it the same way.

Either way it prepares the Python environment for tools/tv.py, finds TVs with network debugging on, connects (the
TV asks to allow this computer once), installs the APK, grants what each feature needs and makes the launcher the
home screen: on Google TV through the launcher's Home button service, elsewhere by switching the stock launcher off.

Run it through ./setup.sh (macOS, Linux) or setup.ps1 (Windows). Options:
    --tv IP       the TV to use (default: the one used last time, or pick from a network scan)
    --scan        look for TVs on the network even when one was used before
    --apk FILE    install this APK instead of the packaged one or a fresh build
    --debug       from source: build a debug version instead of a signed release
    --yes         accept the default answer to every question
"""
import argparse
import concurrent.futures
import ipaddress
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import venv
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
LAUNCHER = ROOT / "launcher"
VENV = TOOLS / "venv"
WINDOWS = os.name == "nt"
MAC = sys.platform == "darwin"
PKG = "com.krushock.itvlauncher"
GOOGLE_TV_HOME = "com.google.android.apps.tv.launcherx"
CONFIG = Path.home() / ".config" / "krushock"
REMEMBERED = CONFIG / "itvlauncher-setup.json"   # the TV used last time
SIGNING = Path(os.environ.get("ITVLAUNCHER_SIGNING") or CONFIG / "itvlauncher-signing.properties")
KEYSTORE = CONFIG / "itvlauncher-release.jks"
SETUP_APP = "com.google.android.tungsten.setupwraith"
HOME_SERVICE = PKG + "/.HomeKeyService"
REMOTE_APK = "/data/local/tmp/itvlauncher.apk"
PLATFORM = "platforms;android-37.0"
BUILD_TOOLS = "build-tools;36.0.0"

args = None


# ---- talking to the person --------------------------------------------------------------------------------------

def step(text):
    print(f"\n== {text}")


def ok(text):
    print(f"   ok: {text}")


def note(text):
    print(f"   {text}")


def stop(text):
    print(f"\n   Stopped: {text}", file=sys.stderr)
    sys.exit(1)


def ask_yes(question, default=True):
    if args.yes:
        note(f"{question} {'yes' if default else 'no'} (--yes)")
        return default
    hint = "Y/n" if default else "y/N"
    answer = input(f"   {question} [{hint}] ").strip().lower()
    return default if not answer else answer.startswith("y")


def ask_text(question, default=None):
    if args.yes and default:
        note(f"{question} {default} (--yes)")
        return default
    shown = f" [{default}]" if default else ""
    answer = input(f"   {question}{shown} ").strip()
    return answer or default


def run(cmd, env=None, quiet=False, timeout=None):
    """Runs a command; returns (exit code, combined output). Output is shown unless quiet."""
    proc = subprocess.run([str(c) for c in cmd], env=env, timeout=timeout,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if not quiet and proc.stdout:
        print(proc.stdout, end="")
    return proc.returncode, proc.stdout or ""


# ---- Python environment -----------------------------------------------------------------------------------------

def venv_python():
    return VENV / ("Scripts/python.exe" if WINDOWS else "bin/python")


def prepare_python():
    step("Python")
    if sys.version_info < (3, 9):
        stop(f"Python 3.9 or newer is needed, this is {sys.version.split()[0]}.")
    ok(f"Python {sys.version.split()[0]}")
    py = venv_python()
    if not py.exists():
        note("Creating tools/venv for the TV connection tool...")
        venv.EnvBuilder(with_pip=True).create(VENV)
    if run([py, "-c", "import adb_shell, rsa, cryptography"], quiet=True)[0] != 0:
        note("Installing its packages (adb-shell, rsa, cryptography)...")
        code, out = run([py, "-m", "pip", "install", "-q", "-r", TOOLS / "requirements.txt"], quiet=True)
        if code != 0:
            print(out)
            stop("couldn't install the Python packages (see above).")
    ok("tools/venv is ready")


def tv(command, host, timeout=180):
    """Runs a shell command on the TV through tools/tv.py; returns (exit code, output)."""
    env = dict(os.environ, TV_HOST=host)
    return run([venv_python(), "-I", TOOLS / "tv.py", command], env=env, quiet=True, timeout=timeout)


# ---- JDK and Android SDK ----------------------------------------------------------------------------------------

def java_version(home):
    java = Path(home) / "bin" / ("java.exe" if WINDOWS else "java")
    if not java.exists():
        return 0
    try:
        out = run([java, "-version"], quiet=True, timeout=30)[1]
    except (OSError, subprocess.TimeoutExpired):
        return 0
    m = re.search(r'version "(\d+)(?:\.(\d+))?', out)
    if not m:
        return 0
    major = int(m.group(1))
    return int(m.group(2)) if major == 1 and m.group(2) else major


def find_java():
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(os.environ["JAVA_HOME"])
    if MAC:
        candidates += ["/Applications/Android Studio.app/Contents/jbr/Contents/Home",
                       "/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home"]
        code, out = run(["/usr/libexec/java_home", "-v", "17+"], quiet=True) if Path("/usr/libexec/java_home").exists() else (1, "")
        if code == 0:
            candidates.append(out.strip())
        brew = shutil.which("brew")
        if brew:
            code, out = run([brew, "--prefix", "openjdk"], quiet=True)
            if code == 0:
                candidates.append(out.strip() + "/libexec/openjdk.jdk/Contents/Home")
    elif WINDOWS:
        for base in (os.environ.get("ProgramFiles", r"C:\Program Files"), os.environ.get("LOCALAPPDATA", "")):
            candidates += [str(Path(base) / "Android" / "Android Studio" / "jbr")]
    else:
        candidates += [str(Path.home() / "android-studio" / "jbr"), "/opt/android-studio/jbr", "/snap/android-studio/current/jbr"]
    java = shutil.which("java")
    if java:
        candidates.append(str(Path(java).resolve().parent.parent))
    for home in candidates:
        if home and java_version(home) >= 17:
            return home
    return None


def prepare_java():
    step("Java (JDK 17 or newer)")
    home = find_java()
    if not home and MAC and shutil.which("brew"):
        if ask_yes("No JDK 17+ found. Install one with Homebrew (brew install openjdk)?"):
            if run(["brew", "install", "openjdk"])[0] == 0:
                home = find_java()
    if not home:
        stop("no JDK 17 or newer. Install Android Studio (it brings one): https://developer.android.com/studio, "
             "or set JAVA_HOME to a JDK 17+.")
    ok(f"JDK {java_version(home)} at {home}")
    return home


def sdk_candidates():
    c = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    if MAC:
        c += [str(Path.home() / "Library/Android/sdk"), "/opt/homebrew/share/android-commandlinetools",
              "/usr/local/share/android-commandlinetools"]
    elif WINDOWS:
        c += [str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk")]
    else:
        c += [str(Path.home() / "Android/Sdk")]
    return [Path(p) for p in c if p]


def sdkmanager(sdk):
    name = "sdkmanager.bat" if WINDOWS else "sdkmanager"
    for p in [sdk / "cmdline-tools" / "latest" / "bin" / name, sdk / "cmdline-tools" / "bin" / name]:
        if p.exists():
            return p
    found = shutil.which("sdkmanager")
    return Path(found) if found else None


def has_platform(sdk):
    return any(p.name.startswith("android-37") for p in (sdk / "platforms").glob("android-*"))


def prepare_sdk(java_home):
    step("Android SDK")
    sdk = next((p for p in sdk_candidates() if (p / "platforms").exists() or sdkmanager(p)), None)
    if not sdk and MAC and shutil.which("brew"):
        if ask_yes("No Android SDK found. Install the command-line tools with Homebrew?"):
            run(["brew", "install", "--cask", "android-commandlinetools"])
            sdk = next((p for p in sdk_candidates() if sdkmanager(p)), None)
    if not sdk:
        stop("no Android SDK. Install Android Studio (https://developer.android.com/studio) and open it once, "
             "or set ANDROID_HOME.")
    if not has_platform(sdk):
        manager = sdkmanager(sdk)
        if not manager:
            stop(f"the SDK at {sdk} lacks Android platform 37, and there's no sdkmanager to add it. "
                 "Add it in Android Studio: Settings > Languages & Frameworks > Android SDK.")
        if not ask_yes(f"The SDK lacks Android platform 37. Install {PLATFORM} and {BUILD_TOOLS}?"):
            stop("Android platform 37 is needed to build.")
        env = dict(os.environ, JAVA_HOME=java_home)
        subprocess.run([str(manager), f"--sdk_root={sdk}", "--licenses"], env=env)   # asks to accept
        if subprocess.run([str(manager), f"--sdk_root={sdk}", PLATFORM, BUILD_TOOLS], env=env).returncode != 0:
            stop("sdkmanager couldn't install the SDK packages (see above).")
    ok(f"SDK at {sdk}")
    return str(sdk)


# ---- finding and connecting to the TV ---------------------------------------------------------------------------

def remembered():
    try:
        return json.loads(REMEMBERED.read_text())
    except (OSError, ValueError):
        return {}


def remember(data):
    CONFIG.mkdir(parents=True, exist_ok=True)
    REMEMBERED.write_text(json.dumps(data, indent=2))


def port_open(host, port=5555, timeout=0.5):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def local_network():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))   # no packet is sent: this only picks the interface a route would use
        ip = s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()
    return ipaddress.ip_network(f"{ip}/24", strict=False)


def scan():
    net = local_network()
    if not net:
        return []
    note(f"Looking for TVs with network debugging on in {net}...")
    hosts = [str(h) for h in net.hosts()]
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as pool:
        found = [h for h, up in zip(hosts, pool.map(port_open, hosts)) if up]
    named = []
    for h in found:
        try:
            name = socket.gethostbyaddr(h)[0].split(".")[0]
        except OSError:
            name = ""
        named.append((h, name))
    return named


def pick_tv():
    step("Your TV")
    saved = remembered()
    if args.tv:
        return args.tv, saved
    if os.environ.get("TV_HOST") and not args.scan:
        return os.environ["TV_HOST"], saved
    if saved.get("tv") and not args.scan:
        label = f"{saved.get('model', 'TV')} at {saved['tv']}"
        if port_open(saved["tv"]):
            if ask_yes(f"Use {label}, as last time?"):
                return saved["tv"], saved
        else:
            note(f"{label} (used last time) isn't answering; it may be off, or its address changed.")
    found = scan()
    if found:
        for i, (host, name) in enumerate(found, 1):
            note(f"{i}. {host}" + (f"  ({name})" if name else ""))
        choice = ask_text("Which one? Number, or type another address:", "1")
        if choice and choice.isdigit() and 1 <= int(choice) <= len(found):
            return found[int(choice) - 1][0], saved
        if choice:
            return choice, saved
    else:
        note("No TV with network debugging on was found. On the TV:")
        note("  Google TV: Settings > System > About > press OK on 'Android TV OS build' 7 times,")
        note("             then Settings > System > Developer options > USB debugging on.")
        note("  Android TV: Settings > Device Preferences > About > 'Build' 7 times, then USB debugging.")
        note("  Its address is under Settings > Network.")
    host = ask_text("TV address (e.g. 192.168.1.50):")
    if not host:
        stop("no TV address.")
    return host, saved


def connect(host):
    step(f"Connecting to {host}")
    if not port_open(host, timeout=3):
        stop(f"nothing answers on {host}:5555. Is the TV on, with USB debugging switched on?")
    note("If the TV asks 'Allow USB debugging?', tick 'Always allow from this computer' and choose OK.")
    try:
        env = dict(os.environ, TV_HOST=host)
        code, out = run([venv_python(), "-I", TOOLS / "tv.py", "--probe"], env=env, quiet=True, timeout=180)
    except subprocess.TimeoutExpired:
        code, out = 1, ""
    if code != 0 or "ro.product.model" not in out:
        print(out[-800:])
        stop("couldn't connect. Accept the prompt on the TV within two minutes, then run setup again.")
    info = dict(re.findall(r"(ro\.[\w.]+) = (.*)", out))
    model = info.get("ro.product.model", "TV").strip()
    android = info.get("ro.build.version.release", "?").strip()
    sdk = int(info.get("ro.build.version.sdk", "0").strip() or 0)
    ok(f"{info.get('ro.product.manufacturer', '').strip()} {model}, Android {android}")
    if sdk and sdk < 26:
        stop(f"the launcher needs Android 8 or newer; this TV runs Android {android}.")
    return model


def current_home(host):
    """The TV's own home app, to switch off on TVs other than Google TV (None when it's already this launcher)."""
    code, out = tv("cmd package resolve-activity --brief -a android.intent.action.MAIN "
                   "-c android.intent.category.HOME | tail -n 1", host)
    pkg = out.strip().split("/")[0] if code == 0 and "/" in out else ""
    if not pkg or pkg in (PKG, "android") or pkg.startswith("com.android.tv.settings"):
        return None
    return pkg


# ---- signing and building ---------------------------------------------------------------------------------------

def prepare_signing(java_home):
    step("Release signing")
    if args.debug:
        note("--debug: building a debug version (no signing key needed).")
        return False
    if SIGNING.exists():
        ok(f"signing key set up ({SIGNING})")
        return True
    note("A release build is signed with your own key. Every update must be signed with the same key,")
    note("so it's created once and kept outside the project.")
    if not ask_yes(f"Create a signing key now (in {CONFIG})?"):
        note("Building a debug version instead.")
        return False
    if KEYSTORE.exists():
        stop(f"{KEYSTORE} exists but {SIGNING.name} doesn't: add its details to {SIGNING} (see README).")
    CONFIG.mkdir(parents=True, exist_ok=True)
    if not WINDOWS:
        os.chmod(CONFIG, 0o700)
    password = secrets.token_urlsafe(24)
    keytool = Path(java_home) / "bin" / ("keytool.exe" if WINDOWS else "keytool")
    code, out = run([keytool, "-genkeypair", "-keystore", KEYSTORE, "-storetype", "PKCS12", "-alias", "itvlauncher",
                     "-keyalg", "RSA", "-keysize", "4096", "-validity", "36500", "-storepass", password,
                     "-keypass", password, "-dname", "CN=itvlauncher"], quiet=True)
    if code != 0:
        print(out)
        stop("keytool couldn't create the key (see above).")
    SIGNING.write_text(f"storeFile={KEYSTORE.as_posix()}\nstorePassword={password}\n"
                       f"keyAlias=itvlauncher\nkeyPassword={password}\n")
    if not WINDOWS:
        os.chmod(KEYSTORE, 0o600)
        os.chmod(SIGNING, 0o600)
    ok(f"created {KEYSTORE.name} and {SIGNING.name} in {CONFIG}")
    note("Back both files up somewhere safe (a password manager works): without them you can't update")
    note("the launcher on your TV without uninstalling it first.")
    return True


def build(java_home, sdk, release):
    """Builds the launcher from source; returns the APK."""
    variant = "release" if release else "debug"
    step(f"Building the {variant} version")
    props = LAUNCHER / "local.properties"
    if not props.exists():
        props.write_text("sdk.dir=" + Path(sdk).as_posix() + "\n")
    gradlew = LAUNCHER / ("gradlew.bat" if WINDOWS else "gradlew")
    env = dict(os.environ, JAVA_HOME=java_home, ANDROID_HOME=sdk)
    task = "assembleRelease" if release else "assembleDebug"
    if subprocess.run([str(gradlew), task, "-q", "--console=plain"], cwd=LAUNCHER, env=env).returncode != 0:
        stop("the build failed (see above).")
    apk = LAUNCHER / "app" / "build" / "outputs" / "apk" / variant / f"app-{variant}.apk"
    if not apk.exists():
        stop(f"the build finished but {apk} is missing.")
    ok(f"{apk.relative_to(ROOT)} ({apk.stat().st_size // 1024} KB)")
    return apk


def packaged_apk():
    """The ready-made APK of a release package (itvlauncher-<version>.apk next to setup.sh), if this is one."""
    found = sorted(ROOT.glob("itvlauncher-*.apk"))
    return found[-1] if found else None


# ---- installing on the TV ---------------------------------------------------------------------------------------

def batch(host, commands):
    """Runs shell commands on the TV over one connection, each separately; returns the output."""
    env = dict(os.environ, TV_HOST=host)
    proc = subprocess.run([str(venv_python()), "-I", str(TOOLS / "tv.py"), "--batch"], input="\n".join(commands),
                          env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=300)
    return proc.stdout or ""


def install_apk(host, apk):
    step("Installing on the TV")
    env = dict(os.environ, TV_HOST=host, MSYS_NO_PATHCONV="1")
    if subprocess.run([str(venv_python()), "-I", str(TOOLS / "b64push.py"), str(apk), REMOTE_APK], env=env,
                      stdout=subprocess.DEVNULL).returncode != 0:
        stop("couldn't upload the APK to the TV.")
    while True:
        out = tv(f"pm install -r {REMOTE_APK}", host)[1]
        if "Success" in out:
            break
        if "UPDATE_INCOMPATIBLE" in out:
            note("The TV has the launcher signed with another key (a debug build, or someone else's release),")
            note("and Android won't install over it. Uninstalling removes your arrangement: back it up first")
            note("on the TV with Controls > Home screen > Back up layout, and restore it afterwards.")
            if not ask_yes("Uninstall the launcher that's on the TV now?", default=False):
                tv(f"rm -f {REMOTE_APK}", host)
                stop("the launcher on the TV was left as it was.")
            tv(f"pm uninstall {PKG}", host)
            continue
        tv(f"rm -f {REMOTE_APK}", host)
        if "VERIFICATION" in out:
            stop("Play Protect stopped the install. Allow it on the TV when it asks, then run setup again.")
        stop("the install failed: " + out.strip()[-300:])
    tv(f"rm -f {REMOTE_APK}", host)
    ok("installed")


def grant(host):
    step("Permissions (each for one feature)")
    out = batch(host, [
        f"pm grant {PKG} android.permission.ACCESS_FINE_LOCATION; pm grant {PKG} android.permission.ACCESS_COARSE_LOCATION",
        f"pm grant {PKG} android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; pm grant {PKG} android.permission.READ_MEDIA_IMAGES 2>/dev/null",
        f"pm grant {PKG} android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null",
        f"cmd notification allow_dnd {PKG}",
        f"pm grant {PKG} android.permission.READ_TV_LISTINGS",
        f"appops set {PKG} GET_USAGE_STATS allow",
        f"sleep 2; cmd notification allow_listener {PKG}/{PKG}.MediaWatcher",
        "echo granted",
    ])
    if "granted" not in out:
        print(out)
        stop("couldn't grant the permissions.")
    ok("Wi-Fi name, photos, Do Not Disturb, Top Shelf, recent apps, notifications and Now Playing")


def make_home(host, google_tv, stock):
    step("Making it the home screen")
    if google_tv:
        # Google TV's home stays on (voice search and the Google Home panel need it); the Home button service takes
        # the button over. Switched off by an older install, it must load once or its title pages hang.
        was_off = GOOGLE_TV_HOME in tv(f"pm list packages -d {GOOGLE_TV_HOME}", host)[1]
        add_service = ('cur=$(settings get secure enabled_accessibility_services); case "$cur" in *' + HOME_SERVICE
                       + '*) ;; null|"") settings put secure enabled_accessibility_services ' + HOME_SERVICE
                       + ' ;; *) settings put secure enabled_accessibility_services "$cur:' + HOME_SERVICE
                       + '" ;; esac; settings put secure accessibility_enabled 1')
        commands = [f"pm enable {GOOGLE_TV_HOME}", f"pm enable {SETUP_APP} 2>/dev/null"]
        if was_off:
            note("Google TV's home was switched off by an older install: letting it load once (20 seconds)...")
            commands.append(f"am start -n {GOOGLE_TV_HOME}/.home.HomeActivity >/dev/null 2>&1; sleep 20")
        batch(host, commands + [add_service])
        if HOME_SERVICE not in tv("settings get secure enabled_accessibility_services", host)[1]:
            stop("the Home button service didn't switch on. On the TV: Settings > System > Accessibility > "
                 "Home button for this home screen > On.")
        ok("Home opens the launcher (Home button service); hold Home for Google TV's panel")
    else:
        commands = [f"cmd package set-home-activity --user 0 {PKG}/.MainActivity"]
        if stock and stock != PKG:
            commands += [f"pm disable-user --user 0 {stock}",
                         f"cmd package set-home-activity --user 0 {PKG}/.MainActivity"]
        batch(host, commands)
        home = tv("cmd package resolve-activity --brief -a android.intent.action.MAIN "
                  "-c android.intent.category.HOME | tail -n 1", host)[1].strip()
        if PKG not in home:
            stop(f"Home still opens {home}. Your TV may have another home app that outranks this one.")
        ok("Home opens the launcher" + (f" ({stock} switched off)" if stock and stock != PKG else ""))
    tv("input keyevent KEYCODE_HOME", host)


def main():
    global args
    parser = argparse.ArgumentParser(description="Set up iTV Launcher on a TV.")
    parser.add_argument("--tv", help="the TV's address")
    parser.add_argument("--scan", action="store_true", help="look for TVs even when one was used before")
    parser.add_argument("--apk", help="install this APK instead of the packaged one or a fresh build")
    parser.add_argument("--debug", action="store_true", help="from source: build a debug version, not a signed release")
    parser.add_argument("--yes", action="store_true", help="accept the default answer to every question")
    args = parser.parse_args()

    apk = Path(args.apk).resolve() if args.apk else packaged_apk()
    from_source = apk is None
    if from_source and not (LAUNCHER / "app").exists():
        stop("no APK next to this script and no launcher source to build: download a release package.")
    print("iTV Launcher setup" + ("" if from_source else f": installing {apk.name}"))
    prepare_python()
    if from_source:
        java_home = prepare_java()
        sdk = prepare_sdk(java_home)
    host, saved = pick_tv()
    model = connect(host)
    google_tv = GOOGLE_TV_HOME in tv(f"pm list packages {GOOGLE_TV_HOME}", host)[1]
    stock = None if google_tv else (current_home(host) or saved.get("stock"))
    remember({"tv": host, "model": model, **({"stock": stock} if stock else {})})
    if from_source:
        apk = build(java_home, sdk, prepare_signing(java_home))
    install_apk(host, apk)
    grant(host)
    make_home(host, google_tv, stock)
    step("Done")
    note(f"{model} now has iTV Launcher. Run setup again any time to update it.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n   Stopped.")
        sys.exit(130)
