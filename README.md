# ARIOS · آريوس

Offline Android music player with live, word-by-word synced lyrics. Dark, silver identity. No account, no subscription, no ads.

## Features
- **Library:** Folders · Artists · Albums · Tracks · Playlists · Favorites, plus an A–Z / Arabic alphabet strip on the left and sorting.
- **Playback (Media3/ExoPlayer):** gapless playback, queue (reorder/remove), shuffle and repeat, speed 0.25×–3×, sleep timer, system equalizer.
- **Background and system:** lock-screen and notification controls (with a favourite button), Android Auto, a home-screen widget, landscape layout.
- **Lyrics:** LRC, enhanced LRC (`<mm:ss.xx>` word tags) and NetEase YRC. Word-by-word highlight (estimated when the file only has line times), auto-scroll, tap a line to seek, two languages at once (translation lines), RTL Arabic, IBM Plex Sans Arabic.
- **Automatic lyrics:** LRCLIB → NetEase → QQ Music → Lyrics.ovh. Results are cached for offline use, and you can search sources manually.
- **Manual sync:** per-song timing offset, a tap-to-sync editor, and .lrc import.
- **Artwork:** embedded cover → MediaStore → iTunes Search, cached on disk.
- **Legal downloads:** Internet Archive (public domain / Creative Commons), or a direct link to a file you own. Choose quality and format, pause and resume, saved to `Music/ARIOS`, added to the library with artwork and lyrics.
- **Files and backup:** import MP3/FLAC/M4A/OGG/OPUS/WAV files; back up and restore playlists, favourites, settings and edited lyrics.
- **Languages:** Arabic and English (follows the system, or choose in Settings).

## New in 1.3
- **Sound:** in-app equalizer (11 tuned presets including Tarab, draggable EQ curve, bass / 3D surround / loudness knobs), true crossfade (0–12 s, two players), volume normalisation (ReplayGain or measured loudness).
- **Lyrics sharing:** image cards (Glass, Cover, Aurora, Minimal; Story or Square) and short MP4 videos with word-by-word highlighting and the song's audio.
- **Listening stats:** minutes, plays, streaks, daily bars, a 24-hour listening clock, top songs and artists, plus an animated story-style "Your year in ARIOS" recap you can share.
- **Smart playlists:** Top 25, Recently played, Recently added, Forgotten favourites, Never played, Podcasts & long audio.
- **Tag editor:** writes into MP3 (ID3) and FLAC files and keeps lyrics intact; other formats are saved in ARIOS. One tap cleans YouTube-style names.
- **Library tools:** duplicate finder (keeps the best copy), hide folders.
- **Car mode** with huge controls and live lyrics. Long audio resumes where you stopped and gets ±30 s buttons.
- **Sleep timer:** fade-out, or stop at the end of the song.
- **Live visualizer** read from the decoded audio (no microphone permission), and a blurred, drifting cover backdrop.
- **Themes:** six accents, AMOLED black, or colours taken from the current cover.
- **Live-lyrics widget** (4×2).

## Build
```
./gradlew assembleRelease   # needs Android SDK 35
./gradlew testDebugUnitTest # lyrics parser tests + screen renders (app/build/screens)
```
Release signing reads `keystore.properties` (not committed). Without it the release is signed with the debug key.
