# tv-vibehack

**Your TV, minus the ads.**

Strip the bloatware from any Android TV or Google TV over Wi-Fi, and replace the ad-filled home
screen with a clean launcher that looks like an Apple TV. No root, no cable, nothing uninstalled,
and a one-line restore if you change your mind.

![Before: stock Google TV home with a sponsored ad. After: the tv-vibehack launcher](docs/stock-vs-new-side.jpg)

It started as an afternoon fixing my parents' Mi TV, which showed them an advert before they
could reach Netflix. The whole thing was done with Claude Code talking to the real TV, and the
prompt that did it is in this repo so you can do the same with yours.

---

## Table of contents

- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [What you get](#what-you-get)
- [Supported TVs](#supported-tvs)
- [Doing it by hand](#doing-it-by-hand)
- [The launcher](#the-launcher)
- [Troubleshooting](#troubleshooting)
- [Questions people ask](#questions-people-ask)
- [Contributing](#contributing)
- [Licence](#licence)

---

## How it works

Android TVs have the same debug bridge as Android phones. Once you switch on USB debugging in the
TV's developer options, the TV listens on your Wi-Fi and asks you once to allow your computer.
After that, everything is standard Android tooling:

1. **Back up** the list of installed packages and settings.
2. **Disable** the manufacturer's telemetry, ad services, content rows and preloaded junk with
   `pm disable-user`. Nothing is uninstalled, so a restore script can undo every step.
3. **Install** a small native launcher and make it the default home screen.

The bloat differs by brand, which is why the prompt asks your coding assistant to discover it
on your TV and ask you before touching anything you might use.

## Quick start

You need a computer on the same Wi-Fi as the TV and a coding assistant with shell access
(Claude Code, or anything similar).

**1. On the TV**
Settings → Device Preferences → About → press OK on **Build** seven times → go back →
Developer options → turn on **USB debugging**. Some TVs also show a Network or ADB debugging
toggle; turn it on too. Keep the TV switched on.

**2. On your computer**
Open your assistant, paste the prompt from [`PROMPT.md`](PROMPT.md), and fill in your TV model.

**3. Follow along**
Accept the "Allow USB debugging?" prompt when it appears on the TV, then answer the checklist
questions: which streaming apps you use, which four go in the dock, whether to keep the voice
assistant, Chromecast and firmware updates.

That's it. The assistant backs up, debloats, builds and installs the launcher, and checks every
step with screenshots pulled from the TV. On a 1 GB Mi TV the whole thing took one afternoon the
first time; with this repo as context, people report about fifteen minutes.

## What you get

A home screen that is yours again:

- **Pitch-black background** and a dock of your four favourite apps at the bottom, in a
  see-through glass band, exactly where Apple TV puts it.
- **A top shelf**, as on Apple TV: the focused dock app's name with an ambient glow in a colour
  taken from its artwork, and that app's Continue watching and previews right above the dock.
- **The rest of your apps in a grid** that slides up when you press Down.
- **One floating glass highlight** that springs from tile to tile instead of each tile drawing its
  own border. The focused tile lifts slightly, swings in from the direction you came, and shows its
  name; pressing against an edge gives a small bump instead of jumping somewhere else.
- **Folders, profiles and your own order**: hold OK on any tile to move it, dock it, hide it or put
  it in a folder. Each person can have a profile with their own arrangement.
- **Round frosted icon buttons**: Search (when the TV has Google Assistant or another search app),
  Recent, Inputs (a dropdown of HDMI ports, AV and Live TV), Wi-Fi, Notifications and Controls.
- **A Controls panel**, like Apple TV's Control Center: Now Playing (artwork, title, play/pause and
  skip for whatever app is playing), profiles, Do Not Disturb, weather, home screen layout, Display
  (Light, Dark or Automatic, Larger text, Reduce Motion), shortcuts into the TV's own settings, and
  the TV's Settings itself at the bottom.
- **A Recent button**, like Apple TV's App Switcher: the apps you used last as a row of cards over
  Home.
- **A notifications bell** with a red dot when there's something to read: system updates, low
  storage, app messages. Google TV shows these in its own launcher, so without this they'd have
  nowhere to go. OK opens one, holding OK dismisses it, and Clear all empties the list. Ongoing
  notices (playback, downloads) stay out of it.
- **A Wi-Fi icon that tells the truth**: signal strength, a Mac-style animation while connecting,
  "!" when connected but the internet isn't reachable, a cable on Ethernet, crossed out offline.
- **A greeting, the weather, a large thin clock and the date**, set in
  [Outfit](https://fonts.google.com/specimen/Outfit).
- **Two optional screensavers**: a matching Clock that drifts around the screen so nothing burns
  in, and Photos, a slideshow of your own pictures.

And a TV that stops working against you: no sponsored rows, no "Discover" nags, no analytics
phoning home, no apps you never asked for.

## Supported TVs

Anything running **Android TV** or **Google TV**: Xiaomi and Redmi, TCL, Hisense, Sony, OnePlus,
Realme, Nvidia Shield and most others.

Not supported: Samsung (Tizen), LG (webOS) and Amazon Fire TV. They run different systems or
won't let a third-party launcher take over Home.

Lists that people have tested and contributed live in [`examples/`](examples/):

| TV | OS |
|---|---|
| Xiaomi Mi TV 4A (MiTV-MOOQ1) | Android TV 10 |
| Xiaomi MiTV-AXSO0 | Android TV 9 |
| TCL 55" 4K Google TV, 2023 (G10) | Google TV 12 |
| SANYO ATV R2 | Android TV 9 |
| OnePlus TV (D00) | Google TV 14 |

Your TV not here? The prompt works regardless, it discovers the bloat itself. If you add your
list afterwards, the next person with your model skips that step. See
[Contributing](#contributing).

## Doing it by hand

Everything the assistant does, you can run yourself. The tools need Python 3 and nothing else.

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50                                   # your TV's IP address

tools/venv/bin/python -I tools/tv.py --probe                 # model, Android version, firmware
tools/venv/bin/python -I tools/tv.py 'pm list packages'      # run one shell command on the TV
printf 'cmd1\ncmd2\n' | tools/venv/bin/python -I tools/tv.py --batch   # several, one connection

tools/disable.sh examples/xiaomi-mi-tv-4a/debloat.txt        # apply a debloat list
tools/restore.sh examples/xiaomi-mi-tv-4a/debloat.txt        # undo it and restore the stock launcher
```

| Tool | What it does |
|---|---|
| [`tools/tv.py`](tools/tv.py) | A pure-Python ADB client: shell, batch, push, pull. Works where the stock `adb` binary is blocked. |
| [`tools/b64push.py`](tools/b64push.py) | Uploads larger files such as APKs as base64 chunks and verifies the checksum. |
| [`tools/disable.sh`](tools/disable.sh) / [`tools/restore.sh`](tools/restore.sh) | Apply or undo a debloat list. PowerShell versions sit next to them. |

### Windows

The PowerShell scripts work without WSL or Git Bash. With Android Studio installed nothing else
is needed: the install script uses Android Studio's bundled JDK and the default SDK location.

```powershell
python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt
powershell -ExecutionPolicy Bypass -File launcher\build-and-install.ps1 -TvHost 192.168.1.50
powershell -ExecutionPolicy Bypass -File tools\disable.ps1 -TvHost 192.168.1.50 examples\xiaomi-mi-tv-4a\debloat.txt
powershell -ExecutionPolicy Bypass -File tools\restore.ps1 -TvHost 192.168.1.50 examples\xiaomi-mi-tv-4a\debloat.txt
```

The install script stops before touching the stock launcher if the install or the home-screen
switch fails. Git Bash also works; the `.sh` scripts detect a Windows venv and disable MSYS path
rewriting themselves.

**Release builds:** the install script builds a signed, non-debuggable release when it finds a
signing key, and a debug build otherwise. Create the key once (keep a copy somewhere safe: every update
must be signed with it, or the TV refuses it), outside the repository:

```bash
mkdir -p ~/.config/krushock && keytool -genkeypair -keystore ~/.config/krushock/itvlauncher-release.jks \
  -storetype PKCS12 -alias itvlauncher -keyalg RSA -keysize 4096 -validity 36500
```

and put its details in `~/.config/krushock/itvlauncher-signing.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`), or point `ITVLAUNCHER_SIGNING` at that file. Switching a TV between a debug and
a release build means uninstalling first; carry the layout over with **Back up layout** / **Restore layout**
(a backup can also be pushed into `/sdcard/Android/data/com.krushock.itvlauncher/files/` over adb).

**Going back without a computer:** on Google TV, Controls → More settings → **Use Google TV home**
switches the Home button service off and Google TV is home again; Settings → System → Accessibility →
**Home button for this home screen** switches it back on. On TVs where the installer switched the stock
launcher off, the same menu item (**Restore … home**) opens its page in Settings: choose Enable, then OK
(coming back to this launcher means running the install script again).

## The launcher

A small native Android TV app (`com.krushock.itvlauncher`, about 1.6 MB), written in plain Java with
AndroidX RecyclerView and nothing heavier. It is built for sets with 1 GB of RAM: no blur, no
video, no background services.

**What it connects to, and nothing else:** the image addresses your own apps publish for their
Continue watching and previews (for the Top Shelf), and Open-Meteo for the weather, which only
receives the coordinates of the city you typed. No account, no analytics, no location permission.
Weather refreshes at most every 30 minutes, and only while Home is on screen. Without a connection
everything keeps working: Top Shelf cards show their titles instead of artwork, the last weather
reading stays up to three hours and then hides, and both pick up again by themselves when the
network comes back.

**Arrange it from the TV.** Hold OK on any tile for a menu: **Move** picks the tile up so the
arrows push it through the dock and the grid (OK places it, Back cancels), **Add to dock** /
**Remove from dock**, **Move to folder**, **Hide**, and **App info**. Folders show their first four
apps on the tile and open in the middle of the screen; hold OK on a folder to rename or ungroup it,
or on an app inside to take it out. Hidden apps collect in a *Hidden apps* tile at the end of the
grid; open it to bring one back. The arrangement is saved on the TV and survives reinstalls of the
launcher; newly installed apps join at the end. Clearing the launcher's storage
(`pm clear com.krushock.itvlauncher`) goes back to the defaults below.

**Profiles** live in the Controls panel (the sliders button): each one keeps its own dock, order,
folders and hidden apps, and the greeting says whose profile is on. *Choose profile on wake* shows
the picker every time the TV comes out of standby, like Apple TV.

**The Top Shelf.** Focus a dock app and its Continue watching (with a progress bar) and preview
rows appear above the dock, as on Apple TV; press Up to move into them and OK to play. This is
what apps publish to Android TV's own database, read with `READ_TV_LISTINGS`, which the install
script grants. Only apps that publish something get a shelf, and many apps on Google TV now only
fill Continue watching.

Moving onto a Top Shelf card fills
the screen behind it with that title's artwork, as on Apple TV (without the trailers). Rest on a
card for three seconds and it takes over the screen like the Apple TV app's top shelf: the title,
episode and time left over the artwork, **Play** (or **Resume**) and **More Info** (which opens the
app, since Android apps don't share a details page), and page dots; Left and Right page through the
row, Down or Back returns to the card, and Up goes on to the top bar.

**Recent** (the clock-arrow button, first in the top bar) is Apple TV's App Switcher: the apps you
used last, newest first, as cards over the home screen. OK opens one, Up or Back closes it. Apple
opens its switcher with a double-press of the TV button, but Android doesn't let a launcher catch
that, hence the button.

**Moving** works like Apple TV with a remote: pick a tile up with Move and each arrow press carries
it one slot; OK places it, Back puts it back. Folders are made from **Move to folder → New folder**,
which suggests a name from the app's category (Games, Music, Entertainment, Photos, News and so on),
as on Apple TV, or "Folder" when the app doesn't say; rename it from the folder's menu.

**Home screen** in Controls has tiles per row (4, 5 or 6; tiles shrink to fit and the dock grows
with it; Apple TV used 5 until tvOS 16 and 6 since tvOS 17), and **Back up layout**, which saves
every profile's arrangement, folders, hidden apps, weather city and display settings to
`Downloads/tvhome-layout.json`, with a copy on any USB drive that's plugged in, in
`Android/data/com.krushock.itvlauncher/files/`. **Restore layout** appears when it finds one of those (or,
where the TV allows it, the file at the top of a drive), so you can carry a layout to another TV or
bring it back after a reset. Since Android 11 an app can only read files it wrote itself, so the
drive copy is the one that travels.

**Display** in Controls: Light, Dark or Automatic (light from 7:00 to 19:00), Larger text, and
Reduce Motion, which drops the tilt, springs, wiggle and photo drift. It also turns on by itself
when the TV's animations are switched off.

**Weather** sits next to the greeting once you set a city in the Controls panel; °C or °F follows
the TV's region, with a switch in the same place.

**What it can't do:** turn the TV off or set a sleep timer. Those need system permissions, so the
Controls panel links to the TV's own settings for them. Apple's trailers in the Top Shelf are left
out on purpose: video on the home screen is too heavy for a 1 GB TV.

**What the install script switches on**, all over ADB and each for one feature: location (to read
the Wi-Fi name), storage (Photos screensaver, layout backup), TV listings (Top Shelf),
Do Not Disturb access, usage access (Recent), and a notification listener (the notifications bell,
and Now Playing, since Android only shows other apps' media to one). Nothing it reads leaves the TV.

**Configure** the defaults in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml):
the starting dock order and hidden apps (used until you arrange things on the TV), your TV's Live
TV package, and the starting number of tiles per row.

**Build and install** with one command:

```bash
TV_HOST=192.168.1.50 launcher/build-and-install.sh
```

It needs JDK 17 or newer and the Android SDK (platform 37, build-tools 36.0.0). With Android
Studio installed that's all there already: the script uses its bundled JDK and the SDK in
`~/Library/Android/sdk`. Without Android Studio, on macOS:

```bash
brew install openjdk android-commandlinetools
sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
```

The script then falls back to any JDK 17+ known to `/usr/libexec/java_home`. A release build
(`./gradlew assembleRelease`) is smaller and faster on 1 GB sets; sign it yourself before installing.

**Navigation** is deliberate: Up and Down move exactly one row, Up from the dock goes to the top
buttons, the ends of each row of tiles and of the button row hold focus instead of jumping
elsewhere, and the last focused app is remembered when you come back, even after the app list
changes.

<details>
<summary><strong>Optional: the Clock screensaver</strong></summary>

A large thin clock and the date over an ambient glow, in the launcher's style. Every minute the
clock drifts to a new spot and the glow shifts colour. The system runs it only while the TV is
idle. Turn it on over ADB, or pick "Clock" in the TV's screensaver settings:

```bash
tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components com.krushock.itvlauncher/.ClockDream; settings put secure screensaver_enabled 1'
```

It starts after the TV's "screen off" idle time (`settings get system screen_off_timeout`).
</details>

<details>
<summary><strong>Optional: the Photos screensaver</strong></summary>

Your own pictures, shuffled, each with a slow drift and a cross-fade into the next, and a small clock
in the corner. It shows every photo in any folder named **Screensaver**, on a USB drive or on the
TV's storage. Decoded at screen size so it stays light on 1 GB sets; your photos never leave the TV.
Pick "Photos" in the TV's screensaver settings, or over ADB:

```bash
tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components com.krushock.itvlauncher/.PhotoDream; settings put secure screensaver_enabled 1'
```

The install script grants the storage permission it needs. "Start screensaver" in the Controls
panel shows it straight away, on TVs that offer that.
</details>

## Troubleshooting

Things that bit us or contributors, so they don't bite you.

**Connecting**

- **macOS: `adb connect` says "No route to host"** although the port is open. Recent macOS treats
  the Google-signed `adb` as its own app under Local Network privacy and silently blocks it.
  `tools/tv.py` avoids the binary entirely.
- **No "wireless debugging" toggle** on some TVs, Xiaomi among them. USB debugging alone opens
  network ADB on port 5555.
- **Windows PowerShell 5.1 adds a byte-order mark** to text piped into a program, which turned the
  first batch command into gibberish. `tv.py` now strips it, and the `.ps1` scripts pipe clean UTF-8.
- **Git Bash rewrites paths** like `/data/local/tmp` into Windows paths. The scripts set
  `MSYS_NO_PATHCONV=1` for you. Set `JAVA_HOME` and `ANDROID_HOME` before building there.

**Working on the TV**

- **Chained `pm` commands** (`pm disable a; pm disable b`) may silently stop after the first. Run
  one per shell call; `--batch` does exactly that.
- **Large pushes hang** through the Python client. Use `tools/b64push.py` for APKs.
- **Screenshots come out empty** while Live TV or an HDMI input is on screen, seen on TCL. Press
  Home before `screencap`.
- **Disable the stock launcher only after the new one is installed**, or the Home button lands on
  a factory test app.
- **Google TV: don't switch the stock launcher off.** It always wins the Home button while it's
  enabled, but switching it off breaks voice search ("Google Assistant has trouble processing your
  request": the Assistant asks it which mode the TV is in), removes the Google Home panel, and its
  setup app (`com.google.android.tungsten.setupwraith`) then shows a black recovery screen on Home
  and stops answering Sony's country lookups. So on Google TV the install script leaves both on and
  switches on the launcher's accessibility service (`HomeKeyService`) instead: Home opens this
  launcher, holding Home opens Google TV's panel, and Google TV's home is swapped out whenever it
  appears (after a restart, backing out of an app). Seen on a Sony BRAVIA, Android 12. If an older
  install had switched Google TV's home off, the script lets it load once for 20 seconds after switching
  it back on: until it has, its title pages (voice search results) keep loading for a minute or more.
- **Play Protect warns about apps "built for an older version of Android"** and may block the
  install. The launcher targets the newest Android (`targetSdk` 37) to avoid that.
- **No on-screen keyboard** after switching launchers, seen on Xiaomi: the default input method
  pointed at a Baidu keyboard that isn't installed. Fix:
  `tools/tv.py 'ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'`
- **Third-party launchers** often charge for basics like a custom wallpaper. That's why this repo
  has its own.

## Questions people ask

**Is this safe? Can it brick the TV?**
No. Nothing is flashed, rooted or deleted. Packages are disabled for the user profile, which
lives in the data partition, not in the system image. The firmware is untouched. If something
goes wrong, `tools/restore.sh` puts everything back, and even without the tools you can re-enable
apps from the TV's own settings.

**What about firmware updates?**
Most lists disable the manufacturer's update service, so updates only arrive when you re-enable
it on purpose. If an update does land, the worst case is that a package comes back enabled and
you re-run the script.

**Won't the apps break as their APIs change?**
The streaming apps still update through the Play Store, which is left alone. The launcher
doesn't talk to them; it lists what's installed and launches it, using Android launcher intents
that have been stable for over a decade.

**How did it get access? Isn't that a security hole?**
Through the Android Debug Bridge, with one explicit "allow this computer" tap on the TV, the same
as developing an Android app. The TV only trusts the key you approved. You can switch USB
debugging off again afterwards and the launcher keeps working.

**Can I just download the launcher APK?**
Build it yourself with the one-line script above; it takes about a minute and you control what
goes on your TV. We don't publish prebuilt binaries.

## Contributing

The most useful contribution is your TV's debloat list, so the next person with that model
doesn't have to rediscover it. Launcher improvements and sharper prompt wording are welcome too.
See [CONTRIBUTING.md](CONTRIBUTING.md) for the format and the testing we ask for.

## Licence

MIT, see [`LICENSE`](LICENSE). The Outfit typeface is under the SIL Open Font License, see
`launcher/FONT-LICENSE-OFL.txt`.
