# vidéoflows

Free, open-source offline video player for Android: your own videos in a vertical swipe feed.

[![license](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)
[![release](https://img.shields.io/github/v/release/Iurika-N-T-R/videoflows?style=flat-square)](../../releases/latest)

vidéoflows is an independent app written from scratch. It is not affiliated with any short-video platform, nor with VideoLAN.

## Using it
Swipe **up** for the next video, **down** for the previous one. Videos loop; tap to pause or resume.

**Top bar**
- **Shuffle** (left): plays the feed in random order.
- **Favorites / All**: only your favorites, or everything.
- **Search** (right): matches title, folder, tags and notes. The pill under the bar shows active filters; tap it to clear them.

**On each video**
- **Folder** (round button): show only this folder (✓ when active).
- **♥**: add to favorites.
- **Notes**: your own notes on the video.
- **Tags**: tag the video. Ratio (9:16, 16:9…) and resolution (4K, 1080p, 720p, SD) are tagged automatically.
- **ⓘ**: resolution, ratio, frame rate, codec, duration, size.
- **▶ 1×** pill: playback speed (1×, 1.5×, 2×, 0.5×).

**Bottom bar**
- **Tags**: pick several tags to see videos that have all of them (e.g. `#9:16` + `#3D`).
- **+**: folders. **Add** a folder to scan (subfolders included), **Exclude** a subfolder, tap one to remove it. Rescanned at every launch.
- **Recent**: what you watched last.
- **Library**: stats, **Rescan**, **Export** everything as JSON.

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
