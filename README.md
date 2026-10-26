# vidéoflows

Free, open-source offline video player for Android: your own videos in a vertical swipe feed.

[![license](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)
[![release](https://img.shields.io/github/v/release/Iurika-N-T-R/videoflows?style=flat-square)](../../releases/latest)

vidéoflows is an independent app written from scratch. It is not affiliated with any short-video platform, nor with VideoLAN.

## Using it
Swipe **up** for the next video, **down** for the previous one. Tap to pause, **double-tap** to add to favorites, drag the bar at the bottom to seek.

**Top bar**
- **Play order** (left): smart mix (unseen first, then least recently watched), random, unseen only, newest, oldest, name A→Z.
- **Pour toi / Favoris**: everything, or only your favorites.
- **Search** (right): live results on title, folder, tags and notes; play all the results as a feed. The pill under the bar shows the active filter; ✕ clears it.
- At launch, a card offers to **resume** the last video where you left it.

**On each video**
- **Folder** (round button or folder chip): play only this folder.
- **♥**: favorite. **Notes**: notes pinned to the current time; tap a timestamp to jump back to it. **Tags**: your tags plus automatic ones (ratio 9:16, 16:9…, resolution 4K, 1080p, 720p, SD).
- **Infos**: resolution, frame rate, codec, duration, size, date; play the whole folder, restart, or hide the video from the feed.
- **▶ 1×** pill: playback speed (1×, 1.5×, 2×, 0.5×). Speaker: mute.

**Bottom bar**
- **Explorer**: unseen, short (≤30 s), with notes, favorites; every folder; every tag (or combine several).
- **+**: add a folder to scan (subfolders included).
- **Récents**: what you watched, by day, with resume.
- **Biblio**: stats; folders (switch one off to leave it out of the feed, trash to remove it, ignore a subfolder); hidden videos; playback settings (order at launch, autoplay next, loop, remember position, start muted); rescan; export favorites, tags and notes as JSON.

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
