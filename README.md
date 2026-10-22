# vidéoflows

Free, open-source vertical video feed for Android, for the videos on your phone.

[![license](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)
[![release](https://img.shields.io/github/v/release/Iurika-N-T-R/videoflows?style=flat-square)](../../releases/latest)

vidéoflows is an independent app written from scratch. It is not affiliated with any short-video platform, nor with VideoLAN.

## Using it
- Tap **+** to add videos from your phone. They stay in the feed between launches.
- Swipe **up** for the next video, **down** for the previous one. Videos loop; tap to pause or resume.
- Like, save, follow, repost and comment on each video. Everything is kept on your phone.
- **Profile → Export** saves the whole feed (titles, likes, saves, comments) as a JSON file.

## Install
Download the APK from the [latest release](../../releases/latest). Needs Android 7.0+ on a 64-bit device.

## Build
```
./gradlew assembleRelease
```
Needs JDK 17–21 and the Android SDK (platform 36).
Release builds are signed only if a `keystore.properties` file sits at the project root (git-ignored); otherwise they come out unsigned.

## Licenses
vidéoflows is MIT-licensed (see [LICENSE](LICENSE)).
Video playback uses [libVLC](https://code.videolan.org/videolan/vlc-android), licensed under the LGPL 2.1+; see [third_party/LIBVLC_NOTICE.txt](third_party/LIBVLC_NOTICE.txt).
