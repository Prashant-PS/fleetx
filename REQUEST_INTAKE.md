# Request Intake: Fleet Bharat

## 1. Summary

Build a single-page, Google-style search web app called **Fleet Bharat** that answers questions about fleets
and commercial vehicles. Use **React** for the frontend and **Java Spring Boot** for the backend.

The landing page is an **AI+ assistant workspace** for fleet managers (§4.5). The Google-style search page stays available as "Classic search" at `/search`.

It draws on four sources:
1. **An Excel workbook (always on).** The research workbook in §3 is the primary knowledge base and is searched locally.
2. **Claude AI (optional).** When an Anthropic API key is set, Claude answers any fleet or commercial-vehicle question, makes comparisons, and can search the web.
3. **Live news.** RSS feeds from fleet-industry publishers supply the latest fleet updates.
4. **A demo fleet.** 40 generated vehicles that the assistant and agents analyse (§4.5). Swap it for a real telematics/ERP source later.

The app must stay fully usable with no API key, using only workbook answers and news.

## 2. Users and goals

- **Users:** fleet product managers, analysts and sales teams researching connected-vehicle and fleet-management services across truck OEMs.
- **Goals:**
  - Type a question in one search box and get a direct answer, ranked results and related news.
  - Compare OEMs or vehicles side by side.
  - Ask follow-up questions.
  - See the latest industry news.

## 3. Input data

**File:** `Commercial_Vehicle_Feature_Matrix_and_STP.xlsx`. Bundle it as a resource, with a config option to point at a different file.

**Layout of every sheet:**
- Rows 1–2 hold a merged title.
- Rows 3–4 hold a merged one-line description.
- Row 5 is the header row.
- Data starts at row 6.

**Detect the header row generically:** it is the first row with at least 2 non-blank cells.

| Sheet | Rows | Columns / content |
|---|---|---|
| Read Me | ~16 | Topic, Interpretation / instruction (explains the status codes and scope) |
| Feature Matrix | 60 | ID (F01–F60), Theme, Feature, then 10 OEM columns, then Proposed project priority, Market example source IDs, Market example URL |
| Feature Dictionary | 60 | ID, Theme, Feature, Acceptance definition, Customer outcome, Data / delivery prerequisite, Proposed priority, Example sources, Source URLs |
| Evidence | 600 | Feature ID, Feature, OEM family, Status, Exact platform / regional scope, Evidence note / limitation, Source IDs, Source URLs, Checked (one row per feature × OEM) |
| OEM STP | 10 | OEM, Segment emphasis, Target buyer / user, Positioning interpretation, Platform examples, Critical qualification, Source IDs, Source URLs |
| Customer Segments | ~8 | Segment, Working definition, Buyer / user, Priority pain, Service fit, Targeting decision, Qualification |
| Project STP | ~8 | STP element, Proposed decision, Validation / caveat |
| Service Packages | 5 | Package, Economic user, Included value, Measure of value, Commercial hypothesis |
| Validation Checklist | ~8 | Gate, Question, Required proof |
| Sources | ~53 | ID, Publisher, Source title, Scope / interpretation boundary, Publication / update, Reviewed, URL |

**OEM columns, in this order:** Daimler Truck, Volvo Group, TRATON, PACCAR, IVECO, Isuzu, Hino / ARCHION, Tata Motors, Ashok Leyland, Hyundai CV.

**Status codes in the matrix and evidence:**
- **D** = Documented
- **P** = Partner
- **A** = Announced (not live)
- **NV** = Not verified

**Domain rules for these codes:**
- NV means "not verified", **not** "absent". Never present it as absence.
- Never add the codes up into vendor scores.

**Some cells hold several URLs or IDs.** URLs are separated by newlines and IDs by commas. Pair them by position.

## 4. Functional requirements

### 4.1 Search page (Google-like)

**Home page:**
- The Fleet Bharat logo (`frontend/public/fleet-bharat-logo.png`).
- A rounded search box with autocomplete.
- "Fleet Search" and "I'm Feeling Lucky" buttons. "I'm Feeling Lucky" opens the top result's source URL in a new tab.
- Clickable sample queries.
- A line saying whether AI answers are on.
- A "Latest fleet updates" news section (see §4.4).

**Results page:**
- A small logo that returns to the home page.
- A search box.
- Tabs: **All, News, Features, Evidence, OEMs & STP, Packages, Sources**. The workbook tabs filter results by sheet.
- A "About N results (x.xx seconds)" line.
- On the All tab, in this order:
  1. The AI Overview (when AI is on).
  2. The workbook answer box. When AI is on, it starts collapsed as "Quick facts from the workbook".
  3. A "Top stories" news strip.
  4. The ranked results.

**Each result shows:**
- A breadcrumb with the sheet name and the source host.
- A blue title, linked to the row's first URL if it has one.
- A snippet built from the row's fields, with the query terms in bold.
- An "All fields" toggle that expands a table of every column.

**Other page behavior:**
- **URL sync:** the query lives in `?q=`, and the browser back and forward buttons work.
- **Autocomplete:** suggests feature names, OEMs, segments, packages, gates, STP elements and sheet names after 2 typed characters. It supports keyboard navigation (↑ ↓ Enter Esc).
- **Light and dark mode:** follow the system's color scheme.
- **Mobile:** the layout must work at phone width (≥ 375 px).

### 4.2 Local search and workbook answers (no AI needed)

**Index:** every data row of every sheet.

**Ranking:**
- Use BM25 with k1 = 1.2 and b = 0.75.
- Count title tokens 3× in each row's term frequency.
- Add +3 when the whole query appears in the row's title.
- Multiply Evidence-sheet scores by 0.6, so its 600 rows don't crowd out the summary sheets.
- Return at most 50 results.

**Tokenizer:**
- Lowercase the text and split on non-letters and non-digits.
- Drop English stopwords and question words.
- Use light stemming: strip a plural "s", and turn "ies" into "y".

**Query synonyms:**

| Query word | Also search for |
|---|---|
| ev | electric |
| gps, tracking | location |
| battery | soc |
| charging | charge |
| repair, service | maintenance |
| fuel | efficiency |
| driver | coaching |
| api | integration |
| price, cost | pricing |

**OEM brand aliases:**

| Brand words in the query | OEM column |
|---|---|
| mercedes, bharatbenz, fleetboard, detroit, freightliner, truckonnect | Daimler Truck |
| man, scania, rio | TRATON |
| daf, kenworth, peterbilt | PACCAR |
| hino, archion | Hino / ARCHION |
| ashok, leyland | Ashok Leyland |

Each OEM's own name also maps to its column (for example "volvo" → Volvo Group).

**Answer rules, applied in this order:**
1. **Feature + OEM(s)** (e.g. "Volvo route planning"):
   - Show the feature name, ID and theme.
   - For each OEM, show its status label plus the evidence scope and note.
   - Add "Also documented by: …".
   - Cite the evidence source URLs.
2. **Feature only** (e.g. "which OEMs support geofence alerts?"):
   - Show the definition and priority.
   - Group the OEMs by status (Documented / Partner / Announced / Not verified).
   - Show the customer outcome and prerequisites.
3. **Two or more OEMs** (e.g. "Compare Volvo vs Tata Motors"):
   - A comparison table of segment emphasis, positioning, platform examples and the count of each status code.
   - A second table of up to 15 features where the OEMs differ, then "…and N more".
   - A caveat that the codes are evidence status, not scores.
4. **One OEM** (e.g. "Tell me about TRATON"):
   - The OEM's STP profile.
   - Counts per status across the 60 features.
   - Up to 12 documented feature names.
5. **Named entity** (e.g. "uptime package", "who is the target buyer"):
   - The row from Service Packages, Customer Segments, Project STP, Validation Checklist or Read Me whose first-column name appears in full in the query.
   - The longest name wins; break ties by search rank.
6. **Otherwise:** a summary of the top result.

**How to detect a feature:**
- Compare the query tokens with each feature name's tokens. Remove OEM aliases and generic domain words first: fleet, telematics, vehicle, truck, service, management, digital, connected, data.
- Score = matched + matched / name length.
- Accept a feature only when its score is ≥ 1.3.

**Answer format:**
- Light markdown: paragraphs, `- ` bullets, `**bold**`, `*italic*`, `[text](url)` links and pipe tables.
- Render status codes inside tables as colored chips.
- Never inject raw HTML.

### 4.3 AI Overview (Claude; on only when the env var `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN` is set)

**SDK and request:** use the official Anthropic Java SDK (`com.anthropic:anthropic-java`), not raw HTTP. Call it with:
- Model `claude-opus-5-5`.
- Streaming.
- `max_tokens` 16000.
- Effort set by config (default `medium`). Thinking is adaptive by default, so do not send a `thinking` parameter.
- The beta header `server-side-fallback-2026-07-01` with body field `fallbacks: "default"`, so a refusal is retried on a fallback model.

**System prompt:**
- An instruction block covering:
  - Scope: fleets and commercial vehicles, i.e. trucks, buses, vans, telematics, OEMs, specs, EVs, TCO, maintenance, safety, regulation and trends.
  - Answer style: lead with a direct answer, then short bullets, no headings.
  - Comparisons: a markdown table of at most 12 rows, then a takeaway.
  - Citations: cite feature or source IDs when the answer is grounded in the workbook.
  - The status-code caveats from §3.
  - Questions the workbook doesn't cover: use web search, or general knowledge labelled as such.
  - Off-topic questions: decline in one sentence.
  - The answer must end with a line `<<related>>` followed by 3 follow-up questions.
- Then a **cached** block (`cache_control: ephemeral`) holding all workbook sheets except Evidence, one line per row. This is about 100k characters.

**User message:**
1. Up to 8 news headlines that match the question (date · publisher · title — summary (url)).
2. The matching workbook rows: the top 25 search hits, plus every Evidence row for the detected feature and OEMs.
3. The last 4 conversation turns, as text with each answer cut at 1500 characters.
4. "Search: <question>".

Follow-ups are new single-turn requests with the history inside the user message. Do not replay earlier assistant turns.

**Tools:** the server-side web search tool `web_search_20260209`, with `max_uses` 3, on/off by config.

**Stream handling:**
- Feed every event into the SDK's `MessageAccumulator`.
- On each content-block start, send a status: thinking → "Thinking…", server tool use → "Searching the web…", web search result → "Reading search results…", text → "Writing…".
- Forward text deltas to the browser as they arrive.
- After each response, collect sources: URLs and titles from web search result blocks and from text citations.
- If `stop_reason` is `pause_turn`, append the assistant message unchanged and call again. Allow at most 3 continuations.
- If `stop_reason` is `refusal`, show a polite error.
- Errors:
  - Rate limit → "busy, try again".
  - Other API errors → a generic failure message.
  - Browser disconnected → stop quietly.

**Endpoint:** `POST /api/ai/answer` with body `{question, history:[{question, answer}]}`. It returns Server-Sent Events:
- `status` `{message}`
- `delta` `{text}`
- `sources` `[{label, url}]`
- `done`
- `error` `{message}`

Run each answer on a bounded thread pool, with a 180-second timeout.

**Frontend AI Overview box:**
- Read the stream with `fetch` and a `ReadableStream`. Don't use `EventSource`, because the request is a POST.
- Show a spinner with the status text, then the streaming text with a blinking caret.
- Hide the `<<related>>` section from the visible text, including a partially arrived marker.
- Show web sources as chips.
- Show "People also ask" with 3 questions. Clicking one asks it as a follow-up; a ↗ button runs it as a new search.
- Add a follow-up input with a Stop button.
- Show the conversation thread in the box.
- Show a disclaimer under the box.
- Keep the box mounted while the user switches tabs, so the answer isn't regenerated.

### 4.4 Latest fleet news (no API key needed)

**Fetching:**
- On startup and every 30 minutes (configurable), fetch RSS or Atom feeds in parallel, using the JDK `HttpClient` with a browser-like User-Agent.
- Cache the results in memory and serve all news requests from the cache.
- Keep the previous cache if every feed fails.

**Feeds, verified working** (config property `fleet.news.feeds`, as `Name|URL` entries):

| Publisher | URL |
|---|---|
| Trucking Info (HDT) | https://www.truckinginfo.com/rss |
| Transport Topics | https://www.ttnews.com/rss.xml |
| FreightWaves | https://www.freightwaves.com/news/feed |
| CCJ | https://www.ccjdigital.com/rss |
| Automotive Fleet | https://www.automotivefleet.com/rss |
| Work Truck | https://www.worktruckonline.com/rss |
| Motor India | https://www.motorindiaonline.in/feed/ |
| Sustainable Bus | https://www.sustainable-bus.com/feed/ |

These feeds were tested and are blocked or dead, so don't use them: ET Auto, Commercial Motor, Fleet News UK, TruckNews, FleetOwner.

**Parsing:**
- Parse with a **hardened** DOM parser: secure processing on, DOCTYPE declarations disallowed, external entities off. Feeds are untrusted input.
- Remove characters that are illegal in XML 1.0 and any BOM before parsing. Sustainable Bus includes `0x1E`.
- Support both RSS `item` and Atom `entry`.
- Extract:
  - title
  - link (in Atom, the `alternate` link)
  - a summary with HTML stripped, entities decoded and "The post … appeared first on …" removed, cut to 260 characters
  - the publish date
  - an image, from `media:content` or `media:thumbnail`, an image `enclosure`, or the first `<img>` in the content
- Take at most 40 items per feed.
- Remove duplicates by URL and by title.

**Date parsing:**
- ISO 8601.
- RFC 1123.
- Two-digit years with zone names, e.g. `Sat, 10 Oct 26 07:00:00 EDT` (Transport Topics). Use `Locale.ENGLISH`.

**Ordering:**
- **Latest, no query:** interleave by publisher, ranking each story within its publisher, then sorting by rank and then by date. Every publisher's newest story then comes before anyone's second.
- **With a query:** score title matches 3 and summary matches 1, after removing words like latest, news, update, recent, today and fleet. Sort by score, then by date. If no words remain, use the latest ordering.

**Endpoint:** `GET /api/news?q=&limit=12` (limit capped at 50). Returns `{updatedAt, sources, items:[{title, url, summary, source, publishedAt, imageUrl}]}`.

**UI:**
- **Home page:** a "Latest fleet updates" card grid showing 6 cards: image, publisher, title clamped to 3 lines, and relative time. Add "More updates", and an "✦ Summarize today's fleet news" button shown only when AI is on.
- **Results page:** a horizontal "Top stories" strip and a News tab list with thumbnails.
- **Images:** lazy-load them with `referrerPolicy="no-referrer"`. If an image fails, show a 🚚 placeholder. Some publisher image CDNs return 403. Don't proxy around a publisher's block.

### 4.5 Fleet Bharat AI+ assistant workspace (the landing page)

**Layout:**
- **Left sidebar:** the Fleet Bharat logo with an "AI+" badge and a one-line tagline; New chat; My fleet; AI agents; Custom agents; Saved; Schedules; Fleet apps; "Classic search" (the §4.1 page at `/search`); Help & support at the bottom.
- **Top bar:** a notifications bell with an unread count, and a profile chip showing initials, name and "Fleet Manager". The name is editable and stored in `localStorage`.
- **Mobile (below 900 px):** the sidebar becomes a slide-in menu.
- **Routing:** path-based with the History API (`/`, `/chat`, `/fleet?v=ID`, `/agents/:id`, `/custom-agents[/new|/:id|/:id/edit]`, `/saved[/:id]`, `/schedules`, `/apps`, `/help`, `/search`). No router library.

**Home page:**
- A greeting ("Hi <first name>, how can I help with your fleet today?") and a subtitle.
- The Fleet Bharat truck emblem (`frontend/public/fleet-bharat-emblem.png`). Brand colour: red `#C90F14`.
- A prompt box ("Ask anything about your fleet…") with:
  - 4 mode chips: **Search** (effort low, web search on), **Deep analysis** (effort high, web search on), **Create report** (structured markdown report, then propose saving it), **Take action** (propose up to 5 actions).
  - A microphone using the Web Speech API, hidden when unsupported.
  - A send button.
- "Try asking" chips:
  - What needs my attention today?
  - Show vehicles at risk of downtime
  - Create a weekly fleet report
  - Remind me of expiring contracts
- **Your AI agents:** 5 cards, each showing its count of high and open issues.
- **Your custom agents:** a "Create your first custom agent" card, plus existing custom agents.
- **Quick cards:** My schedules (active count), Saved (count), Fleet apps.

**Demo fleet:**
- **Generated in code:** a fixed seed, with dates relative to startup. Flag it clearly as demo data everywhere.
- **Vehicles:** 40, from Tata, Ashok Leyland, BharatBenz, Eicher, Volvo, Switch, EKA.
- **Mix:** heavy and medium trucks, tippers, LCVs, buses; diesel, CNG and electric. 8 Indian depots with Indian-style registrations.
- **Fields per vehicle:**
  - Identity and assignment: VIN, driver (licence expiry, safety score).
  - Live status: status (Moving, Idle, Stopped, Offline, In workshop), last seen.
  - Usage: odometer and the previous reading, km and idle % over 7 days, efficiency.
  - Health and maintenance: fault codes (code, description, Critical/Major/Minor, since), next service km and date, tyre-pressure warning.
  - Energy: fuel %, AdBlue % (diesel); EV charge %, battery health %, charging status, next departure, required charge %.
  - Documents (insurance, fitness, permit, PUC) and contracts (AMC, telematics subscription, lease), each with a reference and expiry.
- **Planted issues:** deliberately plant at least one issue of every kind the agents check, including:
  - a duplicate registration
  - an odometer rollback
  - expired insurance and an expired permit
  - an AMC contract expiring in 9 days
  - a critical fault
  - overdue service
  - AdBlue at 7%
  - an EV not ready to depart
  - a charger fault
  - battery health at 77%

**Agents (rule-based, no AI needed):**
- **Finding fields:** each check produces findings with a stable id `agent:check:vehicle`, a severity (HIGH, MEDIUM or LOW), the vehicle, a title, a detail and a recommended action.
- **Fleet Hygiene:** missing VIN; duplicate registration; odometer going backwards; no GPS for over 72 h; no driver; implausible efficiency; missing service date.
- **Compliance:**
  - expired document → HIGH
  - document expiring within 30 days → HIGH if within 7 days, otherwise MEDIUM
  - contract expiring within 60 days → HIGH if within 14 days, MEDIUM if within 30, otherwise LOW
  - driver licence expiring within 30 days
- **Vehicle Operations:** active faults (Critical → HIGH, Major → MEDIUM, Minor → LOW); service overdue (HIGH); service due within 7 days or 1,000 km; idling above 25%; fuel below 15%; AdBlue below 10% (HIGH); tyre pressure; offline over 24 h.
- **EV Operations:** charge below what the next departure needs while not charging (HIGH); charger fault (HIGH); battery health below 80%; charge below 25%.
- **Reporting:** a weekly markdown report with:
  - headline KPIs: distance, utilisation, moving, workshop, offline, average idling, open issues
  - efficiency by fuel type
  - open issues per agent
  - the top 5 downtime risks
  - an "Act this week" list
- **Pseudo-agent `attention`:** HIGH and MEDIUM findings across all agents, at most 25.
- **Downtime risk score (0–100):**

  | Signal | Points |
  |---|---|
  | Critical fault | 45 |
  | Major fault | 25 |
  | Minor fault | 8 |
  | Service overdue by km | 25 |
  | Service date passed | 20 |
  | Service due within 1,000 km | 8 |
  | AdBlue below 10% | 15 |
  | Tyre pressure warning | 10 |
  | EV battery health below 80% | 15 |
  | Charger fault | 15 |
  | Offline over 24 h | 10 |
  | Vehicle 6 years or older | 5 |

- **Resolving:** resolved finding ids are stored, and resolved findings are hidden from results.
- **Custom agents:** a name, description, optional AI instructions, any subset of checks, and a filter by fuel, type, depot or make.

**Chat (`POST /api/assistant/chat`, SSE):**
- **Events:** `status`, `delta`, `action` (a proposed action card), `sources`, `done`, `error`.
- **Request body:** `{message, mode, history:[{question, answer}], userName}`.
- **Without an API key:** route by keywords to a deterministic answer, each ending with `<<related>>` and 3 follow-up questions, plus action cards:

  | Question about | Answer |
  |---|---|
  | A vehicle id or registration | vehicle card |
  | Attention / today / urgent | attention table |
  | Downtime / risk / breakdown | risk table |
  | Report / weekly / summary | weekly report |
  | Contract / subscription / lease / AMC | expiring contracts, with up to 3 reminder cards (7 days before expiry) |
  | Expiry, renewals, documents | Compliance agent |
  | Data quality | Hygiene agent |
  | EV, charging, battery | EV agent |
  | Faults, idling, service | Operations agent |
  | News | headlines |
  | Overview | fleet counts |
  | Anything else | workbook answer plus help text |

- **With an API key:** a Claude tool loop with these settings:
  - Model `claude-opus-5-5` through the official Java SDK, streaming, `max_tokens` 32000.
  - The refusal fallback beta header `server-side-fallback-2026-07-01` and `fallbacks: "default"`.
  - Effort by mode: Deep analysis high, Search low, otherwise `fleet.ai.effort` (default medium). Thinking stays adaptive.
  - Client tools, each with `eager_input_streaming: true`:
    - `fleet_overview`
    - `list_vehicles(status, fuel, type, depot, make, query, limit)`
    - `get_vehicle(vehicle)`
    - `run_agent(agent enum: the 5 agents, attention, custom:<id>)`
    - `downtime_risk(limit)`
    - `search_workbook(query)`
    - `fleet_news(query)`
    - `propose_action(type: create_schedule | create_reminder | save_report | resolve_finding, title, description, agent, cadence, time, day_of_week, date, message, finding_id, content)`
  - The server-side `web_search_20260209` tool (`max_uses` 3) in general, Search and Deep analysis modes.
- **Each loop round:**
  1. Stream the response into a `MessageAccumulator` and forward text deltas. When a new round starts writing after earlier text, insert a blank line first.
  2. On content-block start, send status text for the tool in use.
  3. On `tool_use`: append the assistant message unchanged, run **every** tool call, and return **all** results in **one** user message, with matching `tool_use_id` and `is_error` for failures.
  4. Treat bad or truncated tool input as an error result. Never crash the turn.
  5. On `pause_turn`: append and continue.
  6. On `refusal`: send an error event.
  7. On `max_tokens`: add a truncation note.
  8. Allow at most 10 rounds.
- **History:** earlier turns go in the user message as text (the last 6, each answer cut to 2000 characters). Do not replay earlier assistant turns.
- **`propose_action` never changes anything.** It sends an `action` event and tells the model "NOT carried out; the user must press Confirm". `POST /api/actions/execute {type, title, payload}` performs the action only after the user confirms the card.
- **System prompt** covers:
  - Role, today's date, the user's name, and a note that the fleet is demo data.
  - The agents and the workbook codes.
  - Use tools for facts and never invent data; refer to vehicles by registration.
  - Answer style: answer first, tables for lists of vehicles.
  - Offer actions through `propose_action` and never claim an action is done.
  - Off-topic questions: decline briefly.
  - End with `<<related>>` and 3 follow-ups.
  - The current mode's instruction.

**Chat UI:**
- User bubbles show the mode tag.
- The assistant streams with status lines and a caret, renders markdown (headings, tables with severity chips, links), and shows action cards (Confirm/Dismiss, then Done with a link, or an error with Retry) and source chips.
- Below each answer: Copy, Save (to Saved), and the follow-up suggestion chips.
- The input box sticks to the bottom except on short screens.
- The conversation lives in a React context, so it survives page changes. "New chat" resets it.

**Schedules:**
- **Cadences:** ONCE (date and time), DAILY, WEEKLY (day and time), MONTHLY (1st), in the server's local time zone.
- **Tasks:** an agent (including `attention`), a custom agent, or a reminder.
- **Running:** a tick every 30 seconds runs due schedules. Agent results become a Saved item plus a notification linking to it; reminders become a notification. Then compute the next run; one-off schedules turn themselves off after running.
- **Controls:** run now, pause/resume, delete.
- **First start:** seed one schedule, "Weekly fleet report", every Monday at 08:00.

**Persistence:**
- Store saved items, schedules, custom agents, notifications (the last 100) and resolved finding ids in one JSON file (`fleet.store.file`, default `./data/fleetboard-store.json`).
- Synchronize access and write atomically (write a temp file, then move it).
- No database.

**Pages:**
- **AI agents:** an agent detail page lists the findings with severity filters. Each finding has Resolve and "Ask AI+", and the vehicle links to the vehicle drawer. Page actions: Run again, Save, Schedule (inline form), and Ask AI+. Reporting shows the report.
- **Custom agents:** a list, a builder form (checks grouped by agent; filter dropdowns from `/api/fleet/facets`), and a run page.
- **Saved:** a list, and a detail page with Copy, Print, Ask AI+ and Delete.
- **Schedules:** the list with toggles, plus a New schedule form.
- **My fleet:**
  - KPI tiles: vehicles, moving, idle/stopped, offline, workshop, km over 7 days, high-priority issues.
  - Search and filters.
  - A vehicle table with status pills and fuel/charge meters.
  - A vehicle drawer: facts, faults, documents with days left, open issues, and "Ask AI+ about this vehicle".
- **Fleet apps:** tiles linking to every area.
- **Help & support:** explains the modes, agents, schedules, data and AI status.

**New endpoints:**
- `/api/me` (name and role from config; the demo flag)
- `/api/fleet/summary`, `/api/fleet/vehicles?q&status&fuel`, `/api/fleet/vehicles/{id}` (vehicle plus findings), `/api/fleet/facets`
- `/api/agents` (cards with open-issue counts), `POST /api/agents/{id}/run`, `POST /api/findings/{id}/resolve`
- Custom-agent CRUD plus `/run`
- `/api/schedules` CRUD, `PATCH {enabled}`, `/run`
- `/api/saved` CRUD
- `/api/notifications`, `POST /api/notifications/read`
- `POST /api/assistant/chat`, `POST /api/actions/execute`

Return validation errors as 400 `{message}`. Configure CORS globally for `/api/**`.

## 5. Other API endpoints

| Endpoint | Returns |
|---|---|
| `GET /api/search?q=` | `{query, tookMs, total, answer:{text, sources:[{label,url}], generatedBy}, results:[{id, sheet, title, snippet, url, fields, score}], aiEnabled}` |
| `GET /api/suggest?q=` | up to 8 strings; prefix matches first, then substring matches |
| `GET /api/info` | `{records, sheets, oems, aiEnabled, webSearch}` |

Allow CORS from `http://localhost:5173`.

## 6. Technical constraints

**Backend:**
- Java 17 and Spring Boot 3.3.x, built with Maven.
- Apache POI `poi-ooxml` 5.2.x. Read cells with `DataFormatter`.
- `com.anthropic:anthropic-java` 2.34.0.
- No database.
- Run on port **8090**. Port 8080 was taken on the target machine; keep the port configurable.

**Frontend:**
- React 18 with Vite 5, plain JavaScript, no UI library.
- Vite dev server on port 5173, proxying `/api` to `localhost:8090`.

**Config in `application.properties`:**

| Property | Default |
|---|---|
| `server.port` | 8090 |
| `fleet.data.file` | (empty = bundled workbook) |
| `fleet.ai.enabled` | true |
| `fleet.ai.model` | claude-opus-5-5 |
| `fleet.ai.effort` | medium |
| `fleet.ai.web-search` | true |
| `fleet.news.refresh-minutes` | 30 |
| `fleet.news.feeds` | the feed list in §4.4 |

**Security and privacy:**
- Never log or expose the API key.
- Render model output and feed text as text, never as HTML.
- Use the hardened XML parsing described in §4.4.

**Performance:** local search should take under 50 ms per query. The AI answer streams progressively.

## 7. Acceptance criteria

1. The backend starts and logs "Loaded 828 records from 10 sheets" plus the 10 OEM names.
2. **"Which OEMs support geofence alerts?"** returns the F03 overview:
   - D: Daimler Truck, Volvo Group, TRATON, PACCAR, Isuzu, Hino / ARCHION, Ashok Leyland.
   - NV: IVECO, Tata Motors, Hyundai CV.
3. **"Volvo route planning"** returns F04 for Volvo Group as Documented, with the scope "Compatible Volvo BEVs; charging network availability by market." and source V04.
4. **"Tell me about TRATON"** returns the STP profile and "Of 60 features: 29 documented, 0 partner, 0 announced, 31 not verified." The OEM STP row ranks first.
5. **"Compare Volvo vs Tata Motors"** returns a comparison table (D counts 35 vs 15) and a differences table ("29 features").
6. **"Uptime package"** returns the Uptime row from Service Packages. "what is fleet telematics" must **not** match the "Telematics-linked insurance" feature.
7. **Autocomplete:** "idl" suggests "Idling analysis".
8. **News:**
   - `/api/news` returns stories from 8 of 8 feeds.
   - No story has a null date.
   - The first 8 items come from 8 different publishers.
   - "electric truck" returns relevant stories.
9. **AI streaming:** with a mock Messages API (point `ANTHROPIC_BASE_URL` at a local mock that streams `server_tool_use` → `web_search_tool_result` → `pause_turn`, then text on the continuation):
   - The SSE endpoint sends status, sources, then deltas, then done.
   - The continuation request contains the unchanged assistant turn.
   - Each request includes the model, the stream flag, `fallbacks: "default"`, the beta header, effort, the web search tool and the cached system block.
10. **With no API key:** the app works fully on workbook answers and news. The home page explains how to turn on AI.

11. **AI+ home:** it shows the greeting, prompt box, 4 modes, 4 "Try asking" chips, 5 agent cards with issue counts, the custom-agent card, and the 3 quick cards. It works at 375 px.
12. **"What needs my attention today?" without a key:** returns an attention table of HIGH findings plus cards for a "Daily attention briefing" schedule and "Mark resolved". Confirming the schedule card creates the schedule (shown as Done, with a link).
13. **"Remind me of expiring contracts":** lists the contracts expiring within 90 days and offers reminder cards. Confirming one creates a ONCE schedule.
14. **Custom agent:** one for electric buses with the checks not-departure-ready, charger-fault and expired-document returns only electric-bus findings. It can be scheduled.
15. **Schedules:** "Run now" on an agent schedule creates a Saved report and a notification. The bell shows the unread count.
16. **Tool loop:** with a mock Messages API that returns `tool_use` run_agent, then `tool_use` propose_action, then text:
    - the stream contains status, delta, action and done events;
    - request 2 carries the run_agent result and request 3 the propose_action result, each in one user message with the matching `tool_use_id`;
    - every client tool definition has `eager_input_streaming: true`.

## 8. Out of scope

- User accounts and authentication.
- Persisting conversations.
- Editing the workbook.
- Proxying or rehosting publisher images or article text.
- Deployment and containerization. Optionally, Spring Boot can serve the built frontend.

## 9. Deliverables

- `backend/`: the Maven project, with the workbook at `src/main/resources/data/fleet-data.xlsx`.
- `frontend/`: the Vite React app.
- `README.md` covering how answers work, the AI settings, the news feeds, how to run it and the API table.
- `.gitignore`.
- Run commands:
  - `mvn spring-boot:run` in `backend/`. To enable AI, set `ANTHROPIC_API_KEY` first.
  - `npm install && npm run dev` in `frontend/`, then open http://localhost:5173.
