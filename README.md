<h1 align="center">CleanXiaomiTV</h1>

<p align="center">My Xiaomi TV, minus the ads: a clean home screen, a richer screensaver and live channels.</p>

![The home screen: today's photo, the clock and weather, the song playing under the glass buttons, and the dock](docs/screenshot-home.png)

<table>
  <tr>
    <td width="50%"><img src="docs/screenshot-grid.png" alt="All apps in smaller tiles, five to a row"></td>
    <td width="50%"><img src="docs/screenshot-move.png" alt="Move mode: hold OK on a tile and move it with the arrows"></td>
  </tr>
  <tr>
    <td align="center">All apps, five to a row</td>
    <td align="center">Hold OK to move an app with the remote</td>
  </tr>
</table>

![The screensaver: photo of the day, time and weather top left, the song playing bottom left](docs/screenshot-screensaver.jpg)

**Built and tested on** a Xiaomi MiTV-MOOR2 (venom), Google TV / Android 11, MediaTek, 1080p.

## What it does

- **Debloat**: [`debloat.txt`](debloat.txt) disables 38 packages: Xiaomi and Google telemetry, PatchWall and the ad rows, demo mode, duplicate cast services, preloaded apps and unused system leftovers. Nothing is uninstalled, and one command restores everything.
- **Ad-blocking DNS**: the installer points the TV's Private DNS at [AdGuard](https://adguard-dns.io), so ads and trackers are blocked in every app.
- **Home screen**: today's photo of the day behind everything, with liquid glass buttons, dock and menus over it; the weather next to the clock; a dock of four apps and five smaller tiles per row below it, out of sight until you press Down. Hold OK on a tile to move it. English and Arabic.
- **For you**: a row above the dock with what you are watching and the next episodes on your Jellyfin server, then the latest videos of the YouTube channels you pick; OK opens them in the Jellyfin and YouTube apps. It only shows once you set it up (see [Your own values](#your-own-values)).
- **Now playing**: while Spotify or another music app plays, a slim glass pill under the top buttons with the cover, song, artist and previous / play-pause / next, tinted in the cover's colour. Hold OK on it to open the app.
- **Screensaver**: photos of the day with a slow zoom, the time and weather top left, and the song playing bottom left.
- **Apps** in [`modules/`](modules/): **Weather** (7-day forecast, city search) and **Channels** (Spanish and international live channels from [TDTChannels](https://www.tdtchannels.com)' public list of free streams).

## Install

On the TV: Settings → Device Preferences → About → press OK on **Build** seven times, then Developer options → **USB debugging** on.

On a computer on the same Wi-Fi, with JDK 17 and the Android SDK (`brew install openjdk@17 android-commandlinetools`, then `sdkmanager "platforms;android-34" "build-tools;34.0.0"`):

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50                     # your TV's IP; accept "Allow USB debugging?" on the TV

tools/disable.sh debloat.txt                     # debloat
INPUT_KEY_SERVICE=1 launcher/build-and-install.sh   # the launcher, made the default home

cd modules && ./gradlew assembleRelease && cd ..   # the apps
for m in weather channels; do
  tools/venv/bin/python -I tools/b64push.py modules/$m/build/outputs/apk/release/$m-release.apk /data/local/tmp/$m.apk
  tools/venv/bin/python -I tools/tv.py "pm install -r /data/local/tmp/$m.apk; rm -f /data/local/tmp/$m.apk"
done

# the screensaver
tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components com.majnioui.cleanxiaomitv/com.gathin.tvhome.ClockDream; settings put secure screensaver_enabled 1'
```

`INPUT_KEY_SERVICE=1` keeps the remote's Input button working once the stock home is gone, and on Xiaomi remotes the YouTube and Apps buttons too; Android switches it off on every reinstall, so pass it each time. `PRIVATE_DNS=off` skips the AdGuard DNS.

## Configure

Everything is in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml): the dock apps, hidden apps, the weather city (Madrid by default), `grid_columns`, and switches for each feature (`home_photo`, `dream_photos`, `dream_analog`, `now_playing`). The analog clock switch can also be flipped on the TV: Settings pill, then the On/Off row at the bottom; that choice wins over `config.xml` and a theme. Rebuild with `launcher/build-and-install.sh`, or push a theme from [`themes/`](themes/) without rebuilding.

### Your own values

The Jellyfin server, its API key and your user id, the YouTube channels for the shelf and your weather location stay out of the repo, in a `private.json` on each TV. Copy [`themes/private.example/private.json`](themes/private.example/private.json) to `themes/private/private.json` (ignored by git), fill it in and push it to every TV:

```bash
TV_HOST=<tv-ip> tools/theme.sh --private themes/private/private.json
```

Run it once per TV, with that TV's IP. If more than one person uses the TV, list them under `profiles`: Home then gets a button with the current name, and OK on it switches the shelf to the next person's Jellyfin items and channels. `tools/theme.sh --reset` leaves `private.json` in place.

## Undo

```bash
TV_HOST=192.168.1.50 tools/restore.sh debloat.txt com.google.android.apps.tv.launcherx
```

This re-enables every package in the list, gives Home back to the stock Google TV launcher and switches the AdGuard DNS off. Remove the launcher afterwards in Settings → Apps if you like.

## Network

No analytics and no accounts. The only requests the TV makes for these features go to:
[Open-Meteo](https://open-meteo.com) for the weather (only the city's coordinates), Bing's photo of the day
archive for the home screen and the screensaver, your own Jellyfin server and YouTube's public channel feeds for the
shelf (only when you set them up), [TDTChannels](https://www.tdtchannels.com)' public list and programme guide and the broadcasters' own
streams for Channels, and AdGuard as the DNS resolver. Each can be
switched off in `config.xml` (or by not installing that app).

## Credits

Built on [CleanYourTV](https://github.com/Gathin23/cleanyourtv) by [Gathin](https://github.com/Gathin23). MIT licensed, see [`LICENSE`](LICENSE). Font: Outfit (OFL). Icons: Material (Apache 2.0), Feather (MIT), Lucide (ISC).
