# OTT Player: functional specification

What the app does today, as built from the code in this repository. This is a
description of current behaviour, not a wish list; anything marked **Open** is a
question still to be settled.

## 1. Purpose and scope

OTT Player is an Android TV app for watching live TV channels served by a
self-hosted [JioTV Go](https://github.com/jiotv-go/jiotv_go) server, using the
owner's own Jio subscription. It is operated entirely with a TV remote.

In scope:
- Live channels only (no catch-up, recording, VOD or timeshift).
- One server, whose playlist and guide URLs are fixed at build time.
- DASH channels protected by Widevine (decrypted by the TV's own DRM) and plain
  HLS channels. The app does not bypass or remove DRM.
- Sideloaded install over adb; no Play Store, no accounts, no analytics.

## 2. Inputs

| Input | Source | Used for |
|---|---|---|
| Playlist (M3U) | `OTT_PLAYLIST_URL`, built into the app | The channel list: name, id, language, genre, logo, stream URL, Widevine licence URL |
| Guide (XMLTV, gzipped) | `OTT_GUIDE_URL`, built into the app | "Now" and "Next" programme titles and times |
| Channel logos | `tvg-logo` URLs in the playlist | Tiles and rows |
| Music list name | `OTT_MUSIC_LIST_NAME`, built into the app | Title of the ★ music list |

From each playlist entry the app reads `tvg-id`, `tvg-language`, `tvg-type` (or
`group-title`), `tvg-logo`, the name after the last comma, the stream URL and an
optional `#KODIPROP:inputstream.adaptive.license_key=` line. A missing language
or genre becomes "Other". A channel's **number** is its position in the full
playlist (1-based).

## 3. Channel organisation

### 3.1 Filtering
- Channels whose name starts with "test" are hidden.
- If a channel exists in both HD and SD in the same language, only the HD copy is
  shown.
- The user chooses which **languages** are on and which **genres** are off
  (section 6). Defaults: Telugu, Hindi, Kannada and English on; all genres on.

### 3.2 Categories ("groups"), in this order
1. **★ Favourites**: channels the user has starred, in the order they were added
   (only if there is at least one).
2. **★ Music mix** (name configurable): every Music channel in Telugu, Hindi and
   English, regardless of the language settings.
3. **All channels**: every shown channel, then one "All ‹language›" list per
   language.
4. One category per shown **language × genre** pair, e.g. "Telugu Movies",
   "Hindi News". Languages are ordered with the four defaults first, then by
   channel count; genres follow a fixed order (Entertainment, Movies, Music,
   News, Kids, Sports, Infotainment, Lifestyle, Devotional, Business,
   Educational, Shopping), then any others alphabetically.

### 3.3 Recent
The last 6 channels watched, most recent first.

## 4. Screens

### 4.1 Home page (shown at launch)
- **Live preview** (top left): the last watched channel keeps playing in a small
  window, with "Continue watching · ‹category›", the channel name, the current
  programme with its times and progress bar, and the next programme. A clock and
  date are shown.
- **Tabs**: ★ For you, All channels, one tab per shown language, ⚙ Settings.
- **Tiles** under the selected tab (up to 2 rows of 6 on screen, scrolling):
  - *For you*: a **Recent** row of channels, and a **Your lists** row
    (Favourites, Music mix).
  - *All channels*: Everything, then one tile per language.
  - *A language*: one tile per genre, with a header "‹LANGUAGE› · N channels in
    M genres".
  - *Settings*: one "Languages & genres" tile.
  - Category tiles show the name, channel count and up to 4 logos; channel tiles
    show the name, number, current programme and progress.

### 4.2 Watching (full screen)
- The channel plays full screen.
- **Info banner** (shows on channel change or on request, hides after 4.5 s):
  logo, number, name (★ if a favourite), "‹language› · ‹genre› · position / count",
  now (title, times, progress) and next.
- **Tuning card** while a new channel starts: logo, number, name and category.
- First time only: a hint bar listing the main keys, hidden after 6 s.

### 4.3 Channel panel (bottom overlay while watching)
- Header: the current language, its genres as chips (current one highlighted)
  and "N channels · position / N".
- 5 channel rows: number, logo, name (★ if favourite), current programme,
  progress bar, and a dot on the channel that is playing. A scroll bar appears
  when the list is longer than 5.
- Detail pane for the focused channel: now and next, and a hint about
  favourites.

### 4.4 Settings
- Two lists: **Languages** (on/off) and **Genres** (on/off), each with a channel
  count that updates as you toggle.
- A summary: "Showing X of Y channels in N categories" and a preview of the first
  6 categories the home page will show.
- Changes are saved when you leave with Back; the app then rebuilds the
  categories, refreshes the guide for the new channel set and confirms with
  "Saved · N channels in M categories".

## 5. Remote control

### Home page
| Key | Action |
|---|---|
| Up / Down | Move between the preview, the tabs and the tile rows |
| Left / Right | Switch tab, or move along a tile row |
| OK | Preview → full screen; tab → into its tiles (Settings tab → Settings); channel tile → watch; category tile → watch with the panel open on that category |
| CH + / CH − | Change the preview's channel |
| Menu | Open Settings |
| Back | From tiles → back to tabs; otherwise "Press Back again to exit" (within 2.5 s) |

### Watching
| Key | Action |
|---|---|
| OK / Down / Guide | Open the channel panel on the current category |
| CH + / CH − | Next / previous channel; past the end of a category it continues into the next / previous one (wrapping around) |
| Left | Previous channel ("No previous channel yet" if none) |
| Up / Right / Info | Show the info banner |
| Hold OK (0.6 s) or Menu | Add / remove the channel from Favourites |
| Back | Home page |

### Channel panel
| Key | Action |
|---|---|
| Up / Down | Previous / next channel (wraps) |
| Left / Right | Previous / next category |
| CH + / CH − | Jump 5 channels down / up |
| OK | Watch the focused channel and close the panel |
| Hold OK or Menu | Add / remove the focused channel from Favourites |
| Back / Guide | Close |

### Settings
| Key | Action |
|---|---|
| Up / Down | Move in a list |
| Left / Right | Languages list / Genres list |
| OK | Turn the item on or off |
| Back | Save and return |

### Anywhere except Settings: number entry
Typing digits (up to 4) shows the number and, if it exists, the channel's name.
After 1.5 s without another digit the app switches to that channel. If no channel
has that number it says "No channel N"; if the channel is hidden it says which
language and genre to turn on in Settings.

## 6. Playback behaviour
- Starts playback once 0.5 s is buffered (fast switching on a LAN); buffers up
  to 30 s.
- Holding CH + / CH − (presses less than 0.4 s apart) waits 0.3 s after the last
  press before starting a stream, so channels in between aren't loaded.
- Remembers each channel's redirected manifest URL for 10 minutes to skip the
  redirect on the next switch; if that URL fails it retries with the original.
- **Recovery**:
  - Falling behind the live window: jumps back to live.
  - Errors or end of stream: retries after 1, 2, 4, 8 s, then every 15 s, forever.
    "Reconnecting…" is shown; from the third failure it says "‹Channel› isn't
    available right now · CH + to try the next one".
  - Buffering for 20 s counts as a failure and triggers a retry.
  - "Loading…" appears if buffering lasts more than 1.2 s (outside tuning).
- Playback stops when the app goes to the background and resumes on the same
  channel when it returns. The screen is kept on while the app is open.

## 7. Data, caching and refresh
- **Playlist**: shown from the on-device copy straight away, then refreshed from
  the server with a conditional request; replaced only if the new copy parses and
  has channels. If there is no copy yet and the server can't be reached, the app
  shows "Can't reach the server. Retrying…" and retries after 2 s, 5 s, then
  every 10 s.
- **Guide**: downloaded only when it changed; trimmed to the shown channels and
  to programmes from 3 hours ago to 36 hours ahead; that trimmed copy is saved on
  the device so later starts don't parse the full file.
- Playlist and guide are re-checked every 3 hours while the app is open.
- **Logos**: fetched only for tiles on screen, shrunk to 160 px, cached in memory
  (8 MB) and on disk.
- Without guide data, screens show "No guide information".

## 8. Remembered between sessions
- Last channel and the category it was played from (resumed at launch; otherwise
  the first channel of the first category).
- Favourites, Recent (6), and the language and genre settings.
- HTTP validators (ETag / Last-Modified) for the playlist and guide.
- Nothing is backed up off the device (`allowBackup=false`).

## 9. Build and install
- `./build.sh` builds a signed release APK inside Docker (JDK 17, Android SDK
  35, Gradle 8.11.1); nothing Android-related is installed on the host.
- The first build creates `release.keystore` and stores its password in
  `local.env` (both git-ignored; they must be kept for future updates).
- `./build.sh install` installs over adb to the TV at `OTT_TV` and starts the app.
- Requires Android 8.0 (API 26) or later; landscape only; works with or without
  a Leanback launcher.

## 10. Not supported
- Catch-up / past programmes, recording, pause or rewind of live TV.
- A full multi-channel EPG grid (only now/next per channel).
- Search, parental controls, profiles, subtitles or audio-track selection.
- Changing the server URLs without rebuilding.
- Plain `http://` streams (Android blocks cleartext by default at API 35).

## 11. Open questions
1. Should channel numbers stay as playlist positions (they shift when the server's
   playlist changes), or be fixed per channel?
2. Is the music mix's language list (Telugu, Hindi, English) meant to follow the
   language settings or stay fixed as now?
3. Should the server URLs be settable on the TV instead of at build time?
4. Should plain HTTP servers be supported?
