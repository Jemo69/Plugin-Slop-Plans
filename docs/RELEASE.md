# Building and releasing

## Day to day

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:installDebug         # build + install on the connected device
./gradlew :app:testDebugUnitTest    # unit tests (no device needed)
./gradlew :app:lintDebug            # Android Lint
./gradlew build                     # everything: assemble + test + lint
```

## Testing on a device

1. Install Kvaesitso.
2. `./gradlew :app:installDebug`.
3. Open Kvaesitso → Settings → Plugins.
4. Enable the plugin, then pick the type it provides (e.g. select it as your weather
   provider).

The plugin runs in its own process, so:

```bash
adb logcat | grep -E "SlopPlan|SlopSettings"|adb logcat | grep -E "SlopPlan|SlopSettings"|adb logcat | grep -E "SlopPlan|SlopSettings"
```

## Releasing

### 1. Bump the version

Both numbers, in `app/build.gradle.kts`:

```kotlin
versionCode = 2      // must increase on every upload
versionName = "1.1.0" // what users see
```

### 2. Tag and let CI build it

```bash
git tag v1.1.0
git push origin v1.1.0
```

`.github/workflows/release.yml` runs the unit tests, builds the APK and attaches it to a
GitHub release. Copy the URL of the asset into your release notes — that is what users
download.

### 3. Signing

The template builds **unsigned** release APKs; the CI workflow therefore ships the debug
APK, which is signed with the local debug key and installs fine. Before you publish
anything you care about, add a real signing config — otherwise every build has a different
signature and users cannot upgrade in place.

Create a keystore once, then keep it out of git:

```bash
keytool -genkey -v -keystore ~/keys/mypplugin.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias myplugin
```

Add to `app/build.gradle.kts`:

```kotlin
android {
    signingConfigs {
        create("release") {
            // Read from ~/.gradle/gradle.properties or environment variables, never commit.
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "$HOME/keys/mypplugin.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // Enable shrinking once the plugin works without debug symbols.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

Then give the release job the matching repository secrets (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

If you turn on `isMinifyEnabled`, test an installed release build on a device before
shipping. The rules in `proguard-rules.pro` keep the manifest entry points and
serializers; a plugin the launcher cannot instantiate fails silently.

## Where users install plugins from

Anything that serves an `.apk` over HTTPS works. The usual choices:

- **GitHub Releases** — free, what the official plugins use.
- **F-Droid** — needs a build recipe, reproducible builds, and metadata in their repo.
- **Your own site** — fine, as long as the URL is stable and the APK is signed.

If you open-source the plugin, the official list lives in the
[Kvaesitso repository](https://github.com/MM2-0/Kvaesitso) under `docs/`.