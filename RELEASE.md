# Release guide

End-to-end walkthrough for shipping StrataSpent Android to the Google
Play Store. The first release takes the longest (account setup, store
listing, etc.); subsequent releases are usually `bundleRelease` + upload.

## 0. One-time: keystore + Play Console account

### Generate a release keystore

This produces the `.jks` that signs every release APK/AAB you'll ever
upload to this Play Console listing. **Lose it and you cannot ship updates
to the same listing.** Back it up off-machine.

```powershell
keytool -genkey -v `
        -keystore strataspent-release.jks `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -alias strataspent
```

Answer the prompts (name, org, locality). Pick a strong password and
record it in a password manager.

### Wire signing into the build

Copy the example and fill in the real values:

```powershell
copy keystore.properties.example keystore.properties
notepad keystore.properties
```

`keystore.properties` and `*.jks` are gitignored. `app/build.gradle.kts`
auto-picks the release signing config when the file is present.

### Create the Play Console account

- Visit https://play.google.com/console
- One-time US$25 registration fee
- Verify identity, agree to the Developer Distribution Agreement
- Create a new app:
  - App name: **StrataSpent**
  - Default language: English (US) or whatever fits
  - App or game: App
  - Free or paid: Free
  - Acknowledge the developer program policies

## 1. Build the release bundle

```powershell
.\gradlew.bat bundleRelease
```

Output: `app\build\outputs\bundle\release\app-release.aab` — the file
you upload to Play Console.

Smoke-test the release build locally too:

```powershell
.\gradlew.bat assembleRelease
$adb = 'C:\Users\migue\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb install app\build\outputs\apk\release\app-release.apk
```

If anything crashes that worked in debug, it's almost certainly a
missing ProGuard keep rule. Check `app/proguard-rules.pro`.

## 2. Play Console store listing

In your app's Play Console:

### App content

- **Privacy policy URL** — host `play-store/listing/privacy-policy.md`
  publicly (GitHub Pages, your domain, anywhere reachable) and paste
  the URL here.
- **App access** — "All functionality available without special access"
  (free Firebase Auth signup).
- **Ads** — No, the app contains no ads.
- **Content rating** — fill out the questionnaire; expect "Everyone".
- **Target audience** — 13 and up (avoid family-friendly category
  unless you do COPPA work).
- **Data safety** — disclose what's in
  `play-store/listing/privacy-policy.md` (email, names, expenses,
  device token). Mark all as "user-provided / app function" use.

### Main store listing

Paste these from `play-store/listing/`:

- **Short description** — `short-description.txt` (80-char limit)
- **Full description** — `full-description.txt` (4000-char limit)
- **App icon** — upload `play-store/high-res-icon.png` (512×512)
- **Feature graphic** — upload `play-store/feature-graphic.png`
  (1024×500)
- **Phone screenshots** — at least 2; upload from
  `play-store/screenshots/` (see the README there for what to capture)

### App category + tags

- Category: **Finance**
- Tags: Budget, Expenses, Money management

## 3. Internal testing track (do this first)

Even for first releases, push to **Internal testing** before promoting:

1. Test → Internal testing → Create new release
2. Upload `app-release.aab`
3. Release notes — paste `play-store/listing/release-notes.txt`
4. Save → Review release → Start rollout to Internal testing
5. Add testers (email addresses of yourself / family); copy the opt-in
   link they need to use.

Internal testing reviews are typically < 1 hour for the first version.

## 4. Promote to production

Once internal testing works:

1. Test → Internal testing → Promote release → **Production**
2. Confirm version code, release notes, etc.
3. **Review release** → fix any policy warnings
4. **Start rollout to production**

First production review takes 1–7 days. Subsequent updates usually
clear in under 24 hours.

## 5. Each subsequent release

```powershell
# In app/build.gradle.kts bump versionCode (must increase) and versionName
.\gradlew.bat bundleRelease
```

Upload the new .aab to **Production → Create new release** → paste
notes → roll out.

---

## Critical things NOT to forget

- **Increment `versionCode`** in `app/build.gradle.kts` for every
  release. Play Console rejects duplicates.
- **Don't commit `keystore.properties` or the `.jks`** — both are in
  `.gitignore`; double-check before pushing.
- **Match the Firebase Auth SHA-1 fingerprint** of the upload key in
  Firebase Console (Project Settings → Android app → Add fingerprint),
  otherwise Google Sign-In breaks in production builds. Get the SHA-1
  with:
  ```powershell
  keytool -list -v -keystore strataspent-release.jks -alias strataspent
  ```
- **Production Firestore rules** — the suggested rules in
  `app/src/main/java/com/strataspent/app/data/model/Models.kt` are
  already enforced by your `ai-studio` database. Don't switch back to
  test-mode rules before shipping; that's a public data leak.
- **Gemini API key** — `BuildConfig.GEMINI_API_KEY` is baked into the
  APK. Restrict the key in Google Cloud Console → APIs & Services →
  Credentials to **Android apps** with the SHA-1 fingerprint above and
  the package name `com.strataspent.app`, so a stolen key from a
  decompiled APK can't be reused outside your app.
- **Test in airplane mode** before each release to verify the offline
  OCR queue still works.

## Folder layout for Play Store assets

```
play-store/
├── feature-graphic.png       # 1024×500 banner (you create)
├── high-res-icon.png         # 512×512 store icon (you create)
├── listing/
│   ├── short-description.txt
│   ├── full-description.txt
│   ├── privacy-policy.md
│   └── release-notes.txt
└── screenshots/
    ├── README.md             # capture checklist
    ├── 01-groups.png
    ├── 02-group-detail.png
    └── …
```

`feature-graphic.png` and `high-res-icon.png` **are** generated by the
project — a quick Java2D rendering of the brand. Regenerate any time
with:

```powershell
$jdk = 'C:\Program Files\Android\Android Studio\jbr\bin'
& "$jdk\javac.exe" tools/RenderPlayAssets.java
& "$jdk\java.exe"  -cp tools RenderPlayAssets
```

Outputs land at `play-store/high-res-icon.png` and
`play-store/feature-graphic.png`. They're a serviceable first pass — you
can replace them with custom designs from Figma / Photopea / a designer
before going to production.

## Automated release builds via GitHub Actions

`.github/workflows/release.yml` builds + signs an AAB whenever you push
a `v*` tag (or click "Run workflow" in the Actions tab). It needs four
**repo secrets** configured (Settings → Secrets and variables →
Actions):

| Secret | What it is |
|---|---|
| `GOOGLE_SERVICES_JSON_B64` | Base64 of `app/google-services.json` |
| `RELEASE_KEYSTORE_B64` | Base64 of your `.jks` file |
| `KEYSTORE_PASSWORD` | Store password |
| `KEY_PASSWORD` | Key password |
| `KEY_ALIAS` | Key alias (e.g. `strataspent`) |
| `GEMINI_API_KEY` | Gemini API key for OCR / voice parsing |

Encode binary files with:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("app\google-services.json")) | clip
[Convert]::ToBase64String([IO.File]::ReadAllBytes("strataspent-release.jks")) | clip
```

After all secrets are set, tagging a release ships the AAB:
```powershell
git tag v1.0.1
git push origin v1.0.1
```

The Action attaches `app-release.aab` to the auto-created GitHub
Release. Download it from there and upload to Play Console.
