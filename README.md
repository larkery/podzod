# podzod

A personal podcast server for one user that does three things:

- implements enough of the **gpodder v2 API** for AntennaPod to sync subscriptions and play state
- adds a **Lyrion (LMS) plugin** to browse those subscriptions, mark episodes listened, unlistened, skipped or starred, and report LMS playback back so the phone sees it
- picks **something sensible to play next** from your podcasts and your BBC Sounds follows, and queues it on an LMS player (`/next`, `/skip`)

Clojure + SQLite. The LMS plugin is a thin Perl client that podzod serves itself.

## Running

```sh
cp config.example.edn config.edn   # edit it
echo 'a password' > password
clojure -M:run config.edn          # or: clojure -T:build uber && java -jar target/podzod.jar config.edn
clojure -M:test                    # tests
```

### NixOS

```sh
nix run github:jlesquembre/clj-nix#deps-lock   # once, and again whenever deps.edn changes; commit deps-lock.json
```

```nix
# flake inputs: podzod.url = "path:/home/hinton/Projects/podzod";  (or a git URL)
imports = [ podzod.nixosModules.default ];
services.podzod = {
  enable = true;
  user = "tom";
  passwordFile = "/run/secrets/podzod";
  defaultPlayer = "aa:bb:cc:dd:ee:ff";
  openFirewall = true;
  extraConfig = ''
    :feeds {"https://example.com/story.xml" {:serial true}}
  '';
};
```

## AntennaPod

Go to Settings → Synchronization → choose a provider → **gpodder.net**, then enter the server address `http://host:8086` and your username and password. If AntennaPod refuses plain HTTP, put podzod behind your reverse proxy with TLS. Proxy headers are respected.

Subscriptions are treated as yours rather than per-device, so every device ends up with the same list.

## LMS plugin

1. Go to LMS Settings → Manage Plugins. Under **Additional Repositories**, add `http://host:8086/lms/repo.xml`.
2. Tick **Podzod**, apply, and restart LMS.
3. In Settings → Advanced → Podzod, enter the password. The server URL and username are already filled in.

LMS checks the plugin version in `install.xml`. **Bump it when you change the plugin** so LMS offers the update.

Under *My Apps → Podzod* you'll find these items:

- Play something (with ≤30m and ≤1h variants)
- Skip this
- Star what's playing
- Starred & one-offs
- Subscriptions → feed → episode, with actions Play now/next/at end, Star, Mark listened/unlistened, and Skip

Episode markers: ✓ listened · ◐ in progress · ★ starred · ⨯ skipped.

The plugin also:

- reports playback position every 30s and on pause, stop and track change. podzod writes these as gpodder play actions from device `lyrion`, so AntennaPod picks them up
- resumes podcasts where you left off, whether you stopped on the phone or in LMS
- exposes `podzod bbc <offset>`, which fetches your BBC Sounds follows using the BBC Sounds plugin's signed-in session

## Picking the next episode

The rules, in order:

1. **Starred and one-off episodes come first, strictly.** The oldest-starred one that fits `max` wins. Starring an episode of a serial lets it play out of order.
2. Otherwise each feed offers **at most one** candidate, so a daily show doesn't swamp a monthly one:
   - **Serial** feeds offer only the next unheard episode in sequence. If that episode doesn't fit `max`, the feed offers nothing. A feed counts as serial if it is set by hand, has `itunes:type=serial`, or (with no `itunes:type`) has at least 80% of episodes numbered.
   - **News** feeds offer the newest unheard episode no older than `max-age-days` (default 7). A feed counts as news if it is set by hand, is in the iTunes *News* category, or publishes most days.
   - **Episodic** feeds offer a random unheard episode from the latest 20, weighted towards newer ones.
3. A feed is then chosen at random. Its chance is its `:weight` (default 1) × staleness, where staleness runs from 1 (heard just now) up to 2 (not heard for a fortnight, or never).

Some episodes are never offered:

- anything finished (≥95% played, or within 60s of the end)
- anything skipped
- anything picked in the last 12h
- anything with unknown duration, when you've given `max`

In-progress episodes are judged on their remaining time.

`GET /feeds` shows how each feed was classified and why, its weight, and its current candidate. Override any of these in `:feeds` in the config.

BBC Sounds programmes you follow appear as feeds named `bbc:<programme id>`. They count as episodic unless you override them. BBC progress comes from BBC itself, and a BBC episode resumes using the plugin's `?offset=`.

## HTTP endpoints

All endpoints use HTTP Basic auth, except `/health` and the two plugin-repository files.

| | |
|---|---|
| `POST /next?player=&max=30m&mode=next` | Queue a pick. `mode`: `next` (default: after the current track, and start playing if idle), `now`, `append`. `max` accepts `30m`, `1h30m`, `45` (minutes) |
| `POST /skip?player=&max=` | Mark what's playing as skipped (it won't be picked again), then `/next` with `mode=now` |
| `POST /star?url=` / `DELETE /star?url=` / `POST /star/current?player=` | Star or unstar an episode |
| `POST /oneoff?url=` (or `text=`, or a JSON body) | Add a one-off episode. Accepts an audio URL, an Apple Podcasts episode link, a Pocket Casts link, or a feed URL with `episode=<guid or title words>`. One-offs are starred automatically |
| `GET /feeds?max=` | Debug view of the selector |
| `/api/2/...` | gpodder: auth, devices, subscriptions, episodes |

### Home Assistant

```yaml
rest_command:
  podcast_next:
    url: "http://host:8086/next?max={{ max | default('') }}&player={{ player | default('') }}"
    method: POST
    username: tom
    password: !secret podzod
  podcast_skip:
    url: "http://host:8086/skip?player={{ player | default('') }}"
    method: POST
    username: tom
    password: !secret podzod
```

### One-offs from your phone

In AntennaPod, an episode's ⋮ menu → Share → **Share media file address** gives you the audio URL. To send it to podzod, set up the **HTTP Shortcuts** app with a shortcut that accepts shared text and POSTs it to `http://host:8086/oneoff` as form field `text`. The same shortcut works for links shared from Apple Podcasts or Pocket Casts. Spotify-only episodes can't be played.

gpodder has no way to sync favourites or the queue, so AntennaPod can't send stars. One partial workaround: if you set `:download-means-star true`, downloading an episode on the phone stars it. Check that AntennaPod actually sends `download` actions before relying on this. Every action is stored raw in `episode_actions.raw`.
