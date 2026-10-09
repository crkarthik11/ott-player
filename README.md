# OTT Player

A small, fast Android TV app for watching live channels from any M3U playlist, such
as a self-hosted [JioTV Go](https://github.com/jiotv-go/jiotv_go) server with your own
Jio subscription: full-screen live TV, a home page with tabs, a bottom channel panel,
and a guide. About 1 MB, built on Media3 ExoPlayer with plain Android views. No
analytics, no ads, nothing locked.

It plays DASH channels with Widevine (decrypted by the TV itself, as any licensed
player does), HLS, and MPEG-TS streams. It does not bypass or remove DRM.

## Features

- **Setup from your phone:** on first start the TV shows a QR code. Scan it, paste
  your playlist's URL (and a guide URL if the playlist doesn't name one), tap Save,
  and the TV loads it. Change it later under Settings → Playlist & guide.

- **Home page:** your last channel keeps playing in a live preview. Tabs for
  ★ For you (Recent channels, Favourites, a music mix), All channels, each language,
  and Settings; each language tab lists its genres.
- **Channel panel:** the current language as a label, its genres as chips, and the
  channels with what's on now, a progress bar and a scroll bar.
- **Settings:** choose which languages and genres appear. Test feeds and SD copies
  of HD channels are hidden automatically.
- **Fast switching:** cached playlist and guide, about half a second from key press
  to picture on a LAN, and a "Tuning" card while a channel starts.
- **Recovers by itself:** jumps back to live after a hiccup, retries with back-off,
  restarts stalled streams, and says when a channel is unavailable.
- **Guide:** downloads JioTV Go's guide only when it changes, keeps just the shown
  channels, and caches that on the TV.
- **Logos:** loaded only for channels on screen, then cached.

## Remote

| Watching | |
|---|---|
| OK / Down | Open the channel panel |
| CH + / CH − | Next / previous channel (continues into the next category) |
| Left | Last channel |
| Up / Right / Info | Show what's on |
| 0–9 | Jump to a channel number (with a preview of the name) |
| Hold OK (or Menu) | Add / remove the channel from Favourites |
| Back | Home page |

| Panel | |
|---|---|
| Up / Down | Move through channels |
| Left / Right | Previous / next category |
| CH + / CH − | Page through channels |
| OK | Watch |
| Back | Close |

On the home page, Up/Down move between the preview, the tabs and the tiles,
Left/Right switch tab or move along a row, and Menu opens Settings.

## Build and install

Requirements: Docker, and for installing, an Android TV with adb debugging enabled
on your network.

```bash
cp local.env.example local.env   # then set your server's URLs and the TV's IP
./build.sh                       # builds app/build/outputs/apk/release/app-release.apk
./build.sh install               # builds and installs on the TV at OTT_TV
```

Everything builds inside Docker (`builder/Dockerfile`: JDK 17, Android SDK, Gradle),
so nothing Android-related is installed on the host. The first build creates
`release.keystore` and saves its password to `local.env`. Both are git-ignored;
back them up, because updates must be signed with the same key.

The build reads these optional settings from `local.env` (or the environment):

| Setting | Default |
|---|---|
| `OTT_PLAYLIST_URL` | none: the app asks for one on first start |
| `OTT_GUIDE_URL` | none: the guide named in the playlist (`x-tvg-url`), if any |
| `OTT_MUSIC_LIST_NAME` | `Music mix` |
| `OTT_TV` | none (needed for `install`) |

The playlist and guide URLs are only a starting point: once a playlist is entered on
the TV, that one is used. For JioTV Go, use an instance with DRM enabled, served over
HTTPS so Widevine licence requests work.
