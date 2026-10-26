# Swadeshi Appstore

An offline Android app that lists apps built in India, so you can find a swadeshi
alternative without digging through the Play Store.

I kept losing track of which apps were actually Indian, so I made this for myself
first. It grew from there.

## What it does

- Browse Indian apps by category, or search by name or developer.
- **Check my phone** shows which apps you already have that have an Indian alternative.
- Save the ones you like, and open them in the store you prefer.
- Works fully offline. No account, no ads, no analytics, no trackers.

## Privacy

Everything stays on your phone. The app asks for a name (optional) and keeps that,
plus your saved list, in its own private storage on the device. Nothing is uploaded,
and there is no server. The full policy is in `legal/`.

## Building it

You need JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). Open the
folder in Android Studio, or run these from a terminal:

```bash
./gradlew assembleDebug      # test build  (com.vasudev.swadeshistore.debug)
./gradlew assembleRelease    # release build
./gradlew bundleRelease      # Play bundle
```

## Signing a release

Create a `keystore.properties` file at the root. It is gitignored, so never commit it:

```properties
storeFile=/absolute/path/to/your-release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Without it Gradle still builds, but the release APK and bundle come out unsigned.
One thing to remember: once the app is published, the release key must never change,
or existing users cannot update.

## Notes

- Native Java, no third-party libraries.
- Min Android 5.0 (API 21), target API 34.
- The catalogue is a bundled JSON file in `app/src/main/assets/`.

## TEST build

The `debug` variant builds a separate TEST app (`com.vasudev.swadeshistore.debug`)
signed with a fixed debug key. Keep `swadeshi-debug.keystore` at the project root
so TEST builds update over each other. It is gitignored and never published; if it
is missing, Gradle falls back to the machine's standard debug key.

## License

(c) 2026 Vasudev Dangaya.
