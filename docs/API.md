# Slop plan service — API reference for this plugin

Everything the plugin needs to know about the backend, and what it deliberately does
not use.

Backend: FastAPI, `/home/jemo/projects/html-plan-service/html-planner/backend`.
Auth: `Authorization: Bearer <api_key>` on every `/api/*` route.
Errors: `{"detail": "..."}`, with FastAPI's status codes.

## The whole surface

| Method | Path | Auth | Used by the plugin |
|---|---|---|---|
| POST | `/api/auth/register` | no | no |
| POST | `/api/auth/login` | no | **yes** — sign in |
| GET | `/api/projects` | yes | **yes** — project names for result labels |
| POST | `/api/projects` | yes | no |
| GET | `/api/projects/{id}` | yes | no |
| DELETE | `/api/projects/{id}` | yes | no |
| POST | `/api/projects/{id}/pin` | yes | no |
| DELETE | `/api/projects/{id}/pin` | yes | no |
| POST | `/api/projects/{id}/archive` | yes | no |
| POST | `/api/projects/{id}/unarchive` | yes | no |
| GET | `/api/plans` | yes | fallback only |
| GET | `/api/plans/search` | yes | **yes** — the main search path |
| GET | `/api/plans/public` | no | no |
| POST | `/api/plans` | yes | no |
| GET | `/api/plans/{id}` | yes | **yes** — refresh a stored result |
| PUT | `/api/plans/{id}` | yes | no |
| DELETE | `/api/plans/{id}` | yes | no |
| POST | `/api/plans/{id}/publish` | yes | no |
| POST | `/api/plans/{id}/unpublish` | yes | no |
| POST | `/api/plans/{id}/pin` | yes | no |
| DELETE | `/api/plans/{id}/pin` | yes | no |
| POST | `/api/plans/{id}/archive` | yes | no |
| POST | `/api/plans/{id}/unarchive` | yes | no |
| GET | `/api/plans/{id}/versions` | yes | no |
| GET | `/api/plans/{id}/versions/{n}` | yes | no |
| POST | `/api/plans/{id}/versions` | yes | no |
| POST | `/api/plans/{id}/versions/{n}/restore` | yes | no |
| GET | `/api/plans/{id}/diff` | yes | no |
| GET | `/health` | no | **yes** — settings connectivity check |
| GET | `/p/{slug}` | no (public plans only) | **yes** — where a public result opens |
| GET | `/plan/{slug}?token=` | token in query | **yes** — where a private result opens |
| GET | `/dashboard`, `/archive`, `/app` | no | no — HTML UI, not an API |

## Search: why it is server-side

A launcher search runs on every keystroke, on a phone, over a network that may be slow.

`GET /api/plans` returns `html_content` — the entire HTML document — for **every** plan
the user owns. A hundred plans at 50 KB each is ~5 MB per keystroke. The plugin does not
need a single byte of that HTML: opening a result only requires the slug, because the
plan is served as a page at `/p/{slug}` or `/plan/{slug}`.

So the plugin calls the lightweight endpoint instead:

    GET /api/plans/search?q=<terms>&limit=<n>&include_archived=<bool>

Response items carry metadata and `size_bytes`, never `html_content`:

```json
{
  "plans": [
    {
      "id": "0f8f…", "project_id": "3c1a…", "title": "Berlin weekend",
      "slug": "berlin-weekend", "url": "/p/berlin-weekend",
      "description": "Three days, Museumsinsel",
      "is_public": true, "is_archived": false, "is_pinned": true,
      "created_at": "2026-01-31T09:12:44", "updated_at": "2026-02-02T18:03:11",
      "size_bytes": 48210
    }
  ],
  "total": 1,
  "query": "berlin"
}
```

`total` is the number of matches **before** `limit` was applied.

### Fallback for servers without the endpoint

The search endpoint is new. If it is missing, FastAPI matches the literal path against
`/api/plans/{plan_id}`, rejects `search` as a non-UUID and answers **400**. The plugin
treats **both 400 and 404** as "this server has no search route", remembers that for the
life of the process, and falls back to `GET /api/plans` with client-side filtering. So
the plugin works against an un-updated deployment from day one, and the round trip is
paid at most once.

Search scope: always the signed-in user's own plans, plus whatever the API returns for
them. The authenticated API has no cross-account search, so that is the ceiling.

## Authentication

The service issues a long-lived API key at registration: `plan_<token_urlsafe(32)>`.
There is no token expiry and no refresh endpoint, so:

- The plugin stores **only** the API key. The password is typed once, used against
  `/api/auth/login`, and never written anywhere.
- The key lives in a DataStore file, excluded from Android cloud backup via
  `backup_rules.xml` and `data_extraction_rules.xml`.
- `POST /api/auth/register` is intentionally not wired up. Creating an account from a
  launcher is the wrong place for it; the web dashboard already does it.

Status codes the plugin maps:

| Response | Meaning | What the plugin does |
|---|---|---|
| 401 / 403 | key missing, revoked or wrong | `PluginState.SetupRequired` → settings screen |
| 400 on `/search` | server has no search route | silent fallback to client-side filtering |
| 404 | plan or route genuinely missing | log, return nothing for that result |
| anything else | server problem | log, fall back to the cached snapshot |

## Opening a result

| Plan state | URL built by the plugin |
|---|---|
| public | `{base}/p/{slug}` |
| private | `{base}/plan/{slug}?token={api_key}` |

Private plans have no anonymous route — `/plan/{slug}` returns 401 without the token, and
`?token=` is the mechanism the service's own dashboard uses. It does mean the key travels
in a URL, so the browser may retain it in history. The plugin builds these URLs locally
and hands them to the browser through an `ACTION_VIEW` intent; nothing is sent anywhere
else. If that trade-off is unacceptable, serve private plans through a short-lived
server-side redirect instead and have the plugin point at that.

## What the plugin does not do, and why

- **Writing.** No create/update/delete/publish. A launcher is a fast read surface; an
  accidental edit from a mistyped search would be bad. `ACTION_VIEW` is read-only.
- **Version history and diffs.** Real features, but a diff does not fit in a launcher
  result row. Worth a future plugin section or a deep link if users ask for it.
- **Projects as a browseable tree.** `FileProvider` results are flat. Projects are used
  only to label results ("Berlin weekend · City trips"); a browsable hierarchy would
  need `isDirectory = true` plus path-prefix matching, which the SDK supports but which
  would make every keystroke a directory walk.