# WoW Forever for Android

Play World of Warcraft Classic on Android handhelds (Adreno 6xx/7xx GPUs) at 60 fps.

## Install

1. Download the latest APK from [Releases](../../releases) and install it.
2. Open the app and run setup (downloads Wine/Proton and the Battle.net installer).
3. Install Battle.net, sign in, and install WoW Classic to the default location.
4. Press **Play**.

## Requirements

- Android 8.0 or newer, arm64
- Qualcomm Adreno 6xx/7xx GPU (tested on Retroid Pocket 5, Adreno 650)
- Enough free storage for a WoW Classic install

## Build

```
./gradlew :app:assembleLegacyRelease
```

## License

GPL-3.0. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES).
