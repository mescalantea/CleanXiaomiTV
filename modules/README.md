# Modules

Two small Android TV apps that show up on the CleanXiaomiTV home screen as ordinary tiles. Weather is about 170 KB and Channels about 750 KB (it includes a video player).
None of them runs in the background, and each uses the network only to fetch its own data.

| | |
|---|---|
| ![Weather](docs/weather.png) | |
| **Weather**: today and a 7-day forecast from [Open-Meteo](https://open-meteo.com). **Change city** searches any city with the remote; **Refresh** reloads. | |
| ![The channel list over a channel playing](docs/channels.png) | ![A channel playing](docs/channels-playing.png) |
| **Channels**: Spanish national and regional channels, then international ones, from [TDTChannels](https://www.tdtchannels.com)' public list of free streams the broadcasters publish. It opens on the last channel; OK shows the channel list on the side (Left / Right jump between groups), Up / Down or CH+ / CH- switch channels. | Once a day the app fetches [TDTChannels' list](https://www.tdtchannels.com/lists/tv.m3u8) and keeps the last good copy; until the first fetch it uses `channels/src/main/res/raw/channels.m3u`, a snapshot of the Spanish channels. |

## Install

From the repo root, with the TV's IP:

```bash
cd modules && ./gradlew assembleRelease && cd ..
for m in weather channels; do
  TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/b64push.py modules/$m/build/outputs/apk/release/$m-release.apk /data/local/tmp/$m.apk
  TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/tv.py "pm install -r /data/local/tmp/$m.apk; rm -f /data/local/tmp/$m.apk"
done
```

The default city is Madrid: `latitude`, `longitude` and `place` in `weather/src/main/res/values/config.xml`.
Weather starts there and remembers whatever you pick with Change city.

Icons: [Material](https://fonts.google.com/icons) (Apache 2.0), [Feather](https://feathericons.com) (MIT) and
[Lucide](https://lucide.dev) (ISC). Font: Outfit (OFL, `FONT-LICENSE-OFL.txt`).
