# Slop Plans — a Kvaesitso plugin

Search your [Slop plan service](https://github.com/) from the Kvaesitso launcher: type,
tap, and the plan opens in the browser.

Built on the Kvaesitso plugin SDK (`de.mm20.launcher2:plugin-sdk`) from the
[starter template](../template).

## What it does

- Searches every plan in your account as you type, in the launcher's file search.
- Pinned plans rank first; an empty query shows them plus your most recent work.
- Results are labelled with their project ("Berlin weekend · City trips").
- Tapping a result opens the served page — public plans at `/p/{slug}`, private ones
  through the service's own authenticated route.
- Archived plans are hidden unless you ask for them in the settings.
- **Demo mode**: out of the box it serves built-in sample plans, so a fresh install is
  never a dead search box. Turn it off once you are signed in.

## Install

Grab the APK from the [releases page](https://github.com/Jemo69/Plugin-Slop-Plans/releases)
and install it, then:

1. In Kvaesitso: **Settings → Plugins → Slop Plans → enable**, then select it as a file
   search provider.
2. Open the plugin settings from the plugin detail page and sign in:
   - **Server** — defaults to `https://slop-plans.vercel.app`. Point it at your own
     instance if you run one: `http://192.168.1.20:8000` works too, and the release
     build allows plain http for exactly that case.
   - **Email + password** — exchanged for an API key, which is all the plugin stores.
3. Turn **Demo mode** off in the options.

### From source

```bash
./gradlew :app:installDebug
```

### If the server is unreachable

The plugin names the actual failure rather than saying "cannot reach the server" for
everything: DNS, refused connection, TLS rejection, timeout, blocked plain HTTP. Tap
**Test connection** in the settings to see which one it is.

## Authentication

The password is used once against `POST /api/auth/login` and never persisted. What the
plugin keeps is the returned API key (`plan_…`), stored in DataStore and excluded from
cloud backup. There is no token refresh — the key does not expire, and if it is revoked
the plugin flips to `SetupRequired` and sends you back to the sign-in screen.

Full details, including every route and what the plugin deliberately leaves alone, are
in [docs/API.md](docs/API.md).

## Search: server-side, with a fallback

`GET /api/plans` returns the full `html_content` of every plan — megabytes, on every
keystroke. The plugin does not need any of it: opening a result only needs the slug.

So search goes to a lightweight endpoint that returns metadata only:

```
GET /api/plans/search?q=<terms>&limit=<n>&include_archived=<bool>
```

If your server has not been updated yet, that request returns 400 (FastAPI reads
`search` as a plan id). The plugin notices, remembers it, and falls back to
`GET /api/plans` with client-side filtering. Either way you can search.

**To get server-side search on your deployment**, add the endpoint described in
[docs/API.md](docs/API.md) — it is one route in `routes/plans.py`, and the plugin picks
it up on the next launch with no app update.

## Development

```bash
./gradlew :app:testDebugUnitTest   # unit tests, no device
./gradlew :app:installDebug        # build and install
adb logcat | grep SlopPlan        # the plugin logs here
```

Tests use Ktor's `MockEngine`, so the real client — URL building, bearer header, status
mapping, JSON parsing — is exercised without a server.

## Layout

```
app/src/main/java/de/jemo/kvaesitso/slopplans/
├── SlopPlanProvider.kt     FileProvider subclass: search, refresh, plugin state
├── SettingsActivity.kt      Compose settings: server, sign in, options
├── UserData.kt              DataStore: server URL, API key, preferences
└── api/
    ├── SlopApi.kt          wire models, mirroring the FastAPI schemas
    ├── SlopApiClient.kt    Ktor client, auth header, error mapping
    └── Mappers.kt           Plan -> launcher result, plus the fallback filter
```

## Before you ship it

- [ ] Point the default server URL at your production deployment
      (`SlopApiClient.DEFAULT_BASE_URL`) instead of `10.0.2.2`.
- [ ] Change `applicationId` / `namespace` to your own id, and the provider authority
      with it. Run `./rename.sh <id> "<Name>"` before the first release — the authority
      must never change once anyone has installed the plugin.
- [ ] Replace `ic_plugin` and `app_name` with your branding.
- [ ] Add a real signing config; see [docs/RELEASE.md](docs/RELEASE.md).
- [ ] Decide whether the `?token=` URL for private plans is acceptable for your threat
      model (docs/API.md explains the trade-off).

## License

Apache License 2.0, matching the SDK. See `LICENSE.txt`.