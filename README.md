# GlassPlayer

A liquid-glass music player for Android, written in Kotlin with Jetpack Compose.
Package `com.example.glassplayer` · min SDK 26 · target SDK 34 · version 3.0

## What is in it
- Local library: scans the music on the phone, queue, shuffle, repeat, speed, sleep timer, crossfade, favorites, playlists, mixes
- Spotify-style layout: Home, Search, Library, Services, Chrome, with a sliding liquid-glass tab pill
- Now Playing screen, mini player, vinyl mode, audio visualizer, album-art colors
- Equalizer, bass boost, virtualizer, saved EQ profiles
- Background playback with a media notification, lock-screen controls and headset buttons
- Services: control Spotify, YouTube Music and SoundCloud through Android media sessions, plus API search
  (Spotify Web API, YouTube Data API, SoundCloud API) using keys you enter in the app
- Web players for the three services and a Chrome tab (an in-app browser starting at Google)
- Settings: appearance, backgrounds (image or looping MP4), audio, playback, notifications, library, gestures, storage

## Source map (app/src/main/java/com/example/glassplayer)
| File | Contents |
|---|---|
| MainActivity.kt | Player engine, app shell, navigation, library lists |
| SpotifyUi.kt | Bottom bar, Home, Search, Library screens |
| NowPlayingScreen.kt | Full-screen player, mini player, visualizer bars |
| Glass.kt | Glass modifier, backdrop, shared buttons and chips |
| Extras.kt | Equalizer (Fx), UI settings, visualizer, shake, mixes |
| SettingsUi.kt | Settings sections and background layer |
| Art.kt | Cover art loading (tags, system thumbnail, folder images) |
| PlaybackService.kt | Foreground service and media session |
| Services.kt, ServicesUi.kt | Other apps' media sessions and the Services tab |
| Cloud.kt, CloudUi.kt | Spotify, YouTube and SoundCloud API clients and results |
| WebPlayer.kt | Embedded web players with the liquid segmented bar |
| Browser.kt | Chrome tab |

## Build
GitHub: push to `main`; the workflow in `.github/workflows/build.yml` builds the debug APK.
Termux: `bash termux-setup.sh` once, then `./termux-push.sh "message"` to push.
Local: `./gradlew assembleDebug` (Gradle 8.7 wrapper, JDK 17 or newer, Android SDK 34).

## API keys (entered in the app, never stored in the source)
- Spotify: Client ID from developer.spotify.com, redirect URI `glassplayer://spotify-callback`
- YouTube: Data API v3 key from Google Cloud
- SoundCloud: client ID and secret (needs an Artist Pro account)
