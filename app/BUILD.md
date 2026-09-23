# DC_SOP — Android app

The app is a thin WebView wrapper around this same repository's `index.html`,
published at <https://alcove-realty.github.io/alcove-sop/>. Ordinary edits to
the SOP text appear in the app straight away with **no rebuild** — the app
fetches the live page. A rebuild is only needed when the app's own behaviour
changes: the address it loads, the launcher icon, the app name, or the
permissions it asks for.

| | |
|---|---|
| Package | `com.alcove.sop` |
| App name | DC_SOP |
| Current | versionCode **11**, versionName **1.10** |
| Minimum | Android 10 (API 29), targets API 34 |
| Loads | `https://alcove-realty.github.io/alcove-sop/` |
| Published | [`DC_SOP.apk`](../DC_SOP.apk) at the repo root |

## What is in here

```
app/AndroidManifest.xml               versionCode / versionName live here
app/src/com/alcove/sop/MainActivity.java
app/res/values/strings.xml            the "DC_SOP" label
app/res/mipmap-*/ic_launcher.png      launcher icon, 5 densities
```

The app also bundles three asset files as its **offline fallback**, used only
when the network is unreachable. They are not duplicated here because they are
already in this repository:

| asset path in the APK | take it from |
|---|---|
| `assets/sop.html` | `index.html` at the repo root |
| `assets/alcove-logo.png` | `alcove-logo.png` at the repo root |
| `assets/welcome-bg.jpg` | `welcome-bg.jpg` at the repo root |

## The signing key — read this before building

Android accepts a new build as an **update** only if it is signed with the same
key as the copy already installed. That key is **not in this repository and must
never be** — this repo is public, and anyone holding the key could sign software
as Alcove Realty.

It lives in Google Drive as `alcove-release.keystore`, with the passwords and
alias written beside it in `READ ME FIRST.txt`. The certificate every release so
far carries is:

```
SHA-256  6e:d0:fb:10:0f:30:02:69:4c:eb:96:30:a6:c6:fc:80:
         7d:0b:05:f7:14:e7:c1:f2:88:66:11:cb:dd:e8:22:80
```

Check a finished build against that fingerprint before calling it an update. If
it differs, everyone would have to uninstall the old app before installing —
which wipes whatever they had filled in and saved inside it.

## Building

There is no Gradle here. The toolchain is the Android SDK command-line tools
plus a JDK:

- `C:\Android\Sdk\build-tools\35.0.0` — `aapt2`, `d8`, `zipalign`, `lib/apksigner.jar`
- `C:\Android\Sdk\platforms\android-34\android.jar`
- JDK 21 (`javac`, `java`, `keytool`)

Before a release, bump these so they agree. The update banner compares them and
silently misbehaves if they drift apart:

1. `android:versionCode` and `android:versionName` in `AndroidManifest.xml`
2. `CURRENT_VERSION_CODE` in the bundled `assets/sop.html` (the offline copy)
3. the `DC_SOP v1.x` strings in the toolbar tooltip in `index.html`
4. the **version Sheet** the banner reads, described below. This one lives
   outside the repository, and it was missed at the v1.10 release: months
   later the Sheet still read `4 / 1.3`.

### The version Sheet

The "a new version is available" banner reads a single row from a Google Sheet:

```
id    1XvjtwcUpLoJV5GDU4eB0LFdjjvJ63cqxq4_iFYUopjU     (tab: Sheet1)
row   versionCode | versionName | downloadUrl | note
```

`index.html` raises the banner only when **both** of these hold:

```js
latestCode > CURRENT_VERSION_CODE   &&   downloadUrl is not empty
```

`downloadUrl` has been empty since the beginning, so the banner has never
actually appeared. That is the only reason the stale row did no harm.

### The banner cannot tell who has what - fix this at the next rebuild

The app loads the **live** `index.html`, so every reader is served the same
`CURRENT_VERSION_CODE` no matter which APK they installed. That makes the
comparison unwinnable:

- leave the constant at the old code, and the banner fires for everybody and
  keeps firing for the people who have already updated;
- move it to the new code, and `latestCode <= CURRENT_VERSION_CODE` is true for
  everybody, so it never fires at all, including for the people still on the
  old build.

The app has to state its own version. It already exposes a JavaScript bridge
(`AndroidPrint`), so the smallest fix is to publish the installed versionCode
through that same object and let the page prefer it, falling back to the
constant when it is absent (a desktop browser, where nothing is installed):

```java
@JavascriptInterface
public int versionCode(){
  try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; }
  catch (Exception e) { return 0; }
}
```

Until that ships, leave `downloadUrl` empty and announce new builds by hand.
Keeping the Sheet row truthful (`11 / 1.10` today) costs nothing and cannot
raise the banner, because `11 > 11` is false and `downloadUrl` is still empty

Then, with `$P` the project folder and `$V` a fresh version tag:

```powershell
# 1  resources
aapt2 compile --dir "$P\res" -o "$P\build\compiled_res_$V"
$link = @('link','-I',$JAR,'--manifest',"$P\AndroidManifest.xml",
          '-o',"$P\build\base_$V.apk",'--auto-add-overlay')
foreach($f in (Get-ChildItem "$P\build\compiled_res_$V" -Filter *.flat)){
  $link += '-R'; $link += $f.FullName }
aapt2 @link

# 2  java  (-source/-target 8, NOT --release: it conflicts with -bootclasspath)
$srcs = @(Get-ChildItem "$P\src" -Recurse -Filter *.java | % { $_.FullName })
javac -source 8 -target 8 -bootclasspath $JAR -classpath $JAR -d "$P\build\classes_$V" @srcs

# 3  dex
$cls = @(Get-ChildItem "$P\build\classes_$V" -Recurse -Filter *.class | % { $_.FullName })
d8 --output "$P\build\dex_$V" @cls

# 4  an APK is a zip: add classes.dex and the three assets to base_$V.apk
# 5  zipalign -f -p 4  →  aligned_$V.apk
# 6  apksigner sign --ks <keystore> --ks-key-alias <alias> --out DC_SOP-$V-signed.apk aligned_$V.apk
```

Verify with `aapt2 dump badging` (versionCode) and
`apksigner verify -v --print-certs` (the fingerprint above), then replace
`DC_SOP.apk` at the repo root.

### Two things that will waste your afternoon

**`@srcs` on a single file.** This project has exactly one `.java`, so
`Get-ChildItem … | % { $_.FullName }` returns a **String**, not an array, and
`@srcs` splats it one character at a time. javac then fails with
`error: invalid flag: :` — that colon is the one in `C:\`. Always wrap the
whole pipeline in `@( … )`.

**d8's "not found" warnings are normal.** `android.app.Activity`,
`java.lang.Runnable` and friends are reported missing because d8 is not handed
the platform jar. Dexing still succeeds; ignore them.

## Things deliberately not done

**Never make the app download and install an APK by itself.** A build that used
`DownloadManager` plus `REQUEST_INSTALL_PACKAGES` and launched the system
installer was blocked by Google Play Protect as "harmful app" — that sequence is
the behavioural signature of a dropper, and no manifest flag tunes it away. The
update banner must stay an ordinary link that opens the browser and lets the
person install by hand.

**Cache mode is pinned.** `settings.setCacheMode(WebSettings.LOAD_NO_CACHE)` in
`MainActivity.onCreate` exists because the WebView otherwise served a stale
snapshot of the page and edits appeared not to publish. Leave it.
