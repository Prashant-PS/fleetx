# Fleet Bharat AI+

An AI assistant for fleet managers (React + Spring Boot), backed by:

- a **demo fleet** of 40 vehicles (Indian-market trucks, buses and EVs) with live status, faults, service plans,
  fuel/AdBlue/battery data, documents and contracts. Replace `FleetRepository.generate()` to connect real data;
- the research workbook `Commercial_Vehicle_Feature_Matrix_and_STP.xlsx` (OEM feature matrix and STP);
- live fleet news from publisher RSS feeds;
- Claude (optional) for open-ended answers, tool use and web search.

## Fleet Bharat AI+ (home page)

- **Ask anything about your fleet** with four modes: *Search* (quick), *Deep analysis* (thorough, high effort),
  *Create report* (structured report you can save) and *Take action* (proposes schedules, reminders, saves and fixes).
  Voice input where the browser supports it. Follow-ups keep the conversation's context.
- **Try asking**: what needs my attention today, vehicles at risk of downtime, weekly fleet report, expiring contracts.
- **AI agents** (rule-based, work without an API key):
  - *Fleet Hygiene*: missing VIN, duplicate registrations, odometer rollback, stale GPS, unassigned driver, implausible efficiency, missing service date
  - *Compliance*: expired / expiring documents, contracts and subscriptions, driver licences
  - *Vehicle Operations*: fault codes, service overdue/due, idling, low fuel, low AdBlue, tyre pressure, offline vehicles, plus a downtime-risk score
  - *EV Operations*: not charged enough for the next departure, charger faults, battery health, low charge
  - *Tire Analytics*: tyres worn to the limit or due within 30 days, under-inflation, irregular wear, hot tyres
  - *Reporting*: weekly fleet report (KPIs, efficiency by fuel, open issues, downtime risk, actions)
  Findings can be resolved, saved, scheduled or handed to AI+.
- **Tire analytics** (`/tyres`, or ask "What is my fleet tire status?"): every tyre's tread, life left in km and
  days and replace-by date; estimated **tyre emissions** (tyre-wear particles in kg/week and g/km, extra CO₂ and fuel
  cost from under-inflation); a **costed plan** (replace, retread, inflate, wheel alignment, heat inspection) with
  indicative ₹ prices, 30/90-day budget and savings; an axle diagram per vehicle; reminders for each action.
  Demo tyre data comes from `TyreRepository`; assumptions are listed on the page.
- **Custom agents**: pick any checks and narrow them by fuel, type, depot or make; run or schedule them.
- **Schedules**: run agents daily/weekly/monthly or once, and one-off reminders. Results arrive as notifications (bell)
  and in **Saved**. A weekly report schedule is created on first start.
- **Saved**: kept answers, reports and agent results.
- **My fleet**: KPIs, filterable vehicle table and a vehicle drawer (health, documents, open issues).
- **Fleet apps**, **Help & support**, and **Classic search** (the earlier Google-style search page at `/search`).

**Actions are never executed by the AI.** It proposes them as cards; the user presses Confirm.

Without an API key the assistant routes questions to the agents by keyword. With `ANTHROPIC_API_KEY` set,
Claude (`claude-opus-5-5`) runs a tool loop over: `fleet_overview`, `list_vehicles`, `get_vehicle`, `run_agent`,
`downtime_risk`, `search_workbook`, `fleet_news`, `propose_action` (and web search in Search/Deep analysis modes).

Workspace data (saved items, schedules, custom agents, notifications, resolved findings) is stored in
`fleet.store.file` (default `./data/fleetboard-store.json`, relative to where the backend runs).

# Classic search

## How answers work

1. **From the workbook (always on, instant).** It recognises features (e.g. "geofence", "route planning")
   and OEMs, including brand aliases such as BharatBenz → Daimler Truck, Scania/MAN → TRATON and
   DAF → PACCAR, then reads the matching rows:
   - feature + OEM → status, regional scope and evidence note from the Evidence register
   - feature → which OEMs are Documented / Partner / Announced / Not verified, plus definition and priority
   - two or more OEMs ("Volvo vs Tata") → side-by-side comparison table and the features where they differ
   - one OEM → STP profile and feature coverage counts
   - a segment, package or STP element ("uptime package") → that row
2. **AI Overview with Claude (when `ANTHROPIC_API_KEY` is set).** After the instant results load, an
   AI Overview streams in above them:
   - answers anything about fleets and commercial vehicles: trucks, buses, vans, specs, EVs, TCO,
     telematics, regulation, OEM news, grounded in the workbook where it applies
   - comparisons come back as tables
   - **web search** for current facts (specs, prices, regulations, news), with the pages shown as sources
   - **follow-up questions** in the same box, with the conversation kept as context
   - **People also ask**: suggested next questions; click to ask, or ↗ to run a new search
   The workbook answer stays available under "Quick facts from the workbook".

AI settings live in `backend/src/main/resources/application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `fleet.ai.model` | `claude-opus-5-5` | Claude model |
| `fleet.ai.effort` | `medium` | `low` is faster, `high`/`xhigh`/`max` are more thorough |
| `fleet.ai.web-search` | `true` | Allow up to 3 web searches per answer (billed per search) |
| `fleet.ai.enabled` | `true` | Turn AI off even when a key is set |

## Latest fleet news

The backend pulls RSS/Atom feeds from eight fleet and commercial-vehicle publishers (Trucking Info,
Transport Topics, FreightWaves, CCJ, Automotive Fleet, Work Truck, Motor India, Sustainable Bus) at
startup and every 30 minutes, and serves them from memory. No API key is needed.

- **Home page**: "Latest fleet updates" cards, interleaved so every publisher's newest story shows first.
- **Results page**: "Top stories" strip of stories matching the search, and a **News** tab with all matches.
- **AI**: matching headlines are passed to Claude, so "latest fleet news" or "what's new with electric
  trucks" gets a summary with links (plus web search when the feeds are not enough).

Edit `fleet.news.feeds` in `application.properties` to add or remove publishers (`Name|feed URL`).
Stories link to the publisher; only headline, summary and image link are shown. Some publishers' image
CDNs block hotlinking, in which case the card shows a placeholder.

## Run it

Requirements: **JDK 17+**, **Maven 3.9+**, **Node 18+**.

Backend (port 8090):

```bash
cd backend
mvn spring-boot:run
```

Optional, for AI answers (PowerShell: `$env:ANTHROPIC_API_KEY="sk-ant-..."` before running):

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

Frontend (port 5173, proxies `/api` to 8090):

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

## Using different data

The workbook is bundled at `backend/src/main/resources/data/fleet-data.xlsx`. To search another file
without rebuilding, set `fleet.data.file` to its path:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--fleet.data.file=C:/path/to/file.xlsx
```

Every sheet is indexed generically: the first row with two or more filled cells is the header row.

## Deploy a free demo on Render

The repository is ready for Render's free plan: `Dockerfile` builds the frontend and backend into one container
(Spring Boot serves the React app and the API on `$PORT`), and `render.yaml` describes the service.

1. Push this repository to GitHub (a private repository works).
2. In the Render dashboard choose **New > Blueprint**, connect GitHub and pick the repository.
3. When asked for environment variables:
   - `FLEET_DEMO_PASSWORD`: recommended. Visitors get the browser's sign-in prompt; any user name, this password.
   - `ANTHROPIC_API_KEY`: optional. Turns on Claude answers; only set it together with a password, because
     anyone who can open the demo spends these credits.
4. Deploy. The first build takes about 5-10 minutes; the app is then at `https://<service-name>.onrender.com`.

Free-plan behaviour: the service sleeps after about 15 minutes without visitors and takes up to a minute to wake;
the disk is not persistent, so saved items, schedules and custom agents reset on each restart or deploy.
Health check: `/healthz` (never password-protected).

To try the production build locally: `docker build -t fleetboard . && docker run -p 8090:8090 -e FLEET_DEMO_PASSWORD=demo fleetboard`.

## API

| Endpoint | Returns |
|---|---|
| `GET /api/search?q=...` | `{ query, tookMs, total, answer: { text, sources, generatedBy }, results: [...] }` |
| `GET /api/suggest?q=...` | up to 8 autocomplete strings |
| `POST /api/ai/answer` | body `{ question, history: [{question, answer}] }`; server-sent events `status`, `delta`, `sources`, `done`, `error` |
| `GET /api/news?q=...&limit=12` | latest stories, or stories matching `q`: `{ updatedAt, sources, items: [{ title, url, summary, source, publishedAt, imageUrl }] }` |
| `POST /api/assistant/chat` | body `{ message, mode, history, userName }`; SSE events `status`, `delta`, `action`, `sources`, `done`, `error` |
| `POST /api/actions/execute` | `{ type, title, payload }` for a confirmed action card |
| `GET /api/fleet/summary`, `/api/fleet/vehicles`, `/api/fleet/vehicles/{id}`, `/api/fleet/facets` | fleet data |
| `GET /api/agents`, `POST /api/agents/{id}/run` | built-in agents (`attention` = most urgent across agents) |
| `GET/POST /api/custom-agents`, `PUT/DELETE /api/custom-agents/{id}`, `POST /api/custom-agents/{id}/run` | custom agents |
| `GET /api/tyres`, `GET /api/tyres/vehicles/{id}` | tyre analytics for the fleet / one vehicle |
| `POST /api/findings/{id}/resolve` | hide a finding once handled |
| `GET/POST /api/schedules`, `PATCH/DELETE /api/schedules/{id}`, `POST /api/schedules/{id}/run` | schedules |
| `GET/POST /api/saved`, `GET/DELETE /api/saved/{id}` | saved items |
| `GET /api/notifications`, `POST /api/notifications/read` | notifications |
| `GET /api/info` | record count, sheets, OEMs, whether AI answers and web search are enabled |
