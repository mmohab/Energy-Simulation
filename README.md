# Neighbourhood Energy Simulation

A Spring Boot web app that simulates electricity use and generation across a
neighbourhood of houses (30 by default) plus public EV chargers (6 by
default), and visualizes it live as time advances (10-minute steps by
default — configurable to 1/5/15/30/60 minutes).

Some houses have a **heat pump** 🔥, some have **PV / solar panels** ☀️, some
have a **home EV charger** 🔌 — and houses can have any combination (or none)
of the three. On every reset, assets are assigned randomly (heat pump ~30%,
PV ~40%, EV charger ~20% of houses) with randomised capacities per house.
Alongside the houses, **public EV charging points** 🅿️ (a mix of slow
kerbside AC and rapid DC hubs, auto-generated to the configured count) serve
randomly arriving vehicles throughout the day.

## Configuration

The neighbourhood is fully configurable — house count, the proportion of
houses with each asset, size ranges, the public charger roster, and a
random seed — rather than hard-coded. Three ways to set it, in increasing
order of immediacy:

1. **Default config file** — `src/main/resources/application.yml`, under
   the `neighbourhood:` key. This is what ships with the app (30 houses,
   30%/40%/20% heat pump/PV/EV proportions, etc).
2. **An external YAML/properties file**, without touching the jar — e.g.
   `config/neighbourhood-example.yml` in this repo. Run with:
   ```bash
   java -jar target/neighbourhood-energy-sim-1.0.0.jar \
        --spring.config.additional-location=file:./config/neighbourhood-example.yml
   ```
   Only the keys present in that file are overridden; everything else
   keeps its `application.yml` default. This is standard Spring Boot config
   layering — any `.yml`, `.yaml`, or `.properties` file works the same
   way, and the same values can equally be supplied as environment
   variables (`NEIGHBOURHOOD_HOUSECOUNT=15`) or `-D`/`--` JVM args.
3. **At runtime, via the API or the "⚙ Configure" panel in the UI** — `POST
   /api/simulation/config` with a JSON body containing any subset of the
   fields (see `config/neighbourhood-example.json` for a full example);
   omitted fields keep their current value. This regenerates the
   neighbourhood immediately. The in-app panel exposes the seed, house
   count, and the three asset proportions; for size ranges or the public
   charger roster, edit a config file or POST JSON directly.

**Fixed seed:** set `neighbourhood.seed` (file) or `"seed": 42` (API) to
get an identical neighbourhood *and* identical weather every run. Leave it
unset for a fresh random seed each time — the seed that was actually used
is always reported back via `GET /api/simulation/config` (and shown at the
bottom of the page), so any run can be reproduced later by plugging that
value back in.

**Start date/time:** by default the simulation starts "today, at
midnight" — set `neighbourhood.startDate` (`yyyy-MM-dd`) and/or
`neighbourhood.startTime` (`HH:mm`, 24h) to begin somewhere else instead,
e.g. a specific date for a chosen season, or a specific time of day (an
evening start immediately shows heat pumps/EV charging/peak demand rather
than the quiet overnight hours). Like the seed, whatever's actually in use
is always reported back via `GET /api/simulation/config`, and invalid or
blank values just fall back to the default rather than erroring.

Configurable fields (`NeighbourhoodConfig`):

| Field | Meaning |
|---|---|
| `seed` | Fixed random seed, or omit for a random one each reset |
| `stepMinutes` | Simulation tick size in minutes — 1, 5, 10, 15, 30, or 60 all work well |
| `startDate` | Simulation start date (`yyyy-MM-dd`), or omit for today |
| `startTime` | Simulation start time of day (`HH:mm`, 24h), or omit for 00:00 (midnight) |
| `houseCount` | Number of houses |
| `heatPumpProbability` / `pvProbability` / `evChargerProbability` | Proportion (0–1) of houses with each asset, assigned independently |
| `heatPumpKwMin` / `heatPumpKwMax` | Heat pump electrical capacity range |
| `pvKwMin` / `pvKwMax` | PV array capacity range |
| `baseLoadFactorMin` / `baseLoadFactorMax` | Household size/usage scale range |
| `homeEvChargerPowerOptionsKw` | Possible home EV charger power ratings (one picked at random per EV house) |
| `publicChargerCount` | Number of public EV chargers to auto-generate (ignored if `publicChargers` is non-empty) |
| `publicChargerPowerOptionsKw` | Possible rated power for auto-generated public chargers (one picked at random per charger) |
| `publicChargers` | Optional explicit roster of `{ name, powerKw }` — when non-empty, fully overrides `publicChargerCount`/`publicChargerPowerOptionsKw` with exactly these chargers |

## Running it

Requires JDK 17+ and internet access (to pull dependencies from Maven Central
the first time).

```bash
mvn spring-boot:run
```

or build a jar and run it:

```bash
mvn clean package
java -jar target/neighbourhood-energy-sim-1.0.0.jar
```

Then open **http://localhost:8080**.

## Testing

```bash
mvn test
```

- `HouseTest` / `PublicChargerTest` — plain unit tests for the domain
  models (load/net calculations, cumulative meter accumulation), no Spring
  context needed.
- `SimulationEngineTest` — the main coverage: neighbourhood generation
  against configured proportions/counts, seed reproducibility (same seed →
  identical trajectory; different seeds → different neighbourhoods), tick/
  day/step-size math (including step sizes that don't evenly divide 1440
  minutes), energy-accounting invariants (total demand = sum of parts, net
  import = demand − generation, cumulative meters never decrease), sanity
  bounds on the physical models, and config validation/normalization
  (clamping, range-swapping, partial-update merging).
- `SimulationControllerTest` — `@SpringBootTest` + MockMvc tests against
  the real REST API (state/step/reset/config endpoints, including the
  400-on-bad-config path).

## How the simulation works

Time advances in **fixed-size ticks** — 10 minutes by default (144/day),
configurable to 1, 5, 15, 30, or 60 minutes via `neighbourhood.stepMinutes`
(config file, runtime API, or the "⚙ Configure" panel's step-size dropdown).
By default the simulation starts today at midnight, so the simulated season
matches the month it's run in — both the start date and start time of day
are configurable too (`neighbourhood.startDate` / `neighbourhood.startTime`),
and take effect immediately at tick 0: weather, PV output, heat pump load,
and EV/public-charger activity all reflect whatever moment you start at,
not just the calendar day.
Each tick, every house's load and generation is derived from simple physical
models:

- **Base household load** — a randomised daily load-curve shape (morning and
  evening peaks) scaled per house.
- **Heat pump** — power draw is driven by a temperature-dependent
  coefficient of performance (COP): heat demand scales with the gap between
  outdoor temperature and the indoor setpoint (with a night-time thermostat
  setback), and that heat is converted to electrical draw via a COP that
  *falls* as it gets colder outside — so electrical consumption rises
  faster than heat demand alone in cold weather, just like a real
  air-source heat pump. Outdoor temperature itself follows the monthly
  seasonal baseline (season) plus a daily swing whose size depends on cloud
  cover (weather) — clearer skies swing further hot-to-cold than overcast
  ones, so both season and day-to-day weather directly affect heat pump
  load.
- **Home EV charger** — each EV-equipped house gets a randomised daily plan
  (whether it's plugged in, arrival time, energy needed) and charges at
  fixed power until that energy need is met or the charging window ends.
- **PV generation** — a season-aware solar-irradiance curve (zero outside
  sunrise/sunset, peak at midday, scaled down in winter) multiplied by each
  array's capacity and a slowly wandering, neighbourhood-wide cloud-cover
  factor.
- **Public EV chargers** — each configured public charger independently and randomly
  gets "arrivals" (more likely 07:00–22:00), then draws its rated power for
  a 30–90 minute session before freeing up again.

### Weather & season

Outdoor temperature follows a monthly seasonal baseline (temperate
NW-European climate) plus a daily sinusoidal swing and a slowly drifting
day-to-day weather offset. Day length and solar intensity vary with the
calendar day of year (shorter, weaker sun in winter; longer, stronger sun in
summer), so sunrise/sunset times and PV output shift naturally across the
year. The header shows the simulated date, time, season, month, outdoor
temperature, cloud cover, and sunrise/sunset.

### Cumulative energy meters

Every house has two running "smart meter" readings (kWh consumed, kWh
generated) since the simulation started, visible in its detail panel. The
engine also tracks neighbourhood-wide cumulative totals broken down **per
asset class** — base load, heat pumps, home EV charging, public EV
charging, and PV generation — shown as a bar comparison in the "Cumulative
energy since start" panel. Each public charger has its own cumulative kWh
meter too.

The backend also keeps a rolling history of neighbourhood-wide totals
(demand, generation, net grid import/export) for the time-series chart.

## API

Interactive docs (Swagger UI): **http://localhost:8080/swagger-ui.html**
Raw OpenAPI spec: **http://localhost:8080/v3/api-docs**

| Method | Path                     | Description                                   |
|--------|--------------------------|------------------------------------------------|
| GET    | `/api/simulation/state`  | Current state (no time advance)                |
| POST   | `/api/simulation/step`   | Advance by one tick (size set by `stepMinutes`) |
| POST   | `/api/simulation/step/{n}` | Advance by `n` ticks (fast-forward)          |
| POST   | `/api/simulation/reset`  | Regenerate the neighbourhood, restart at day 1  |
| GET    | `/api/simulation/config` | Current neighbourhood configuration, including the seed in use |
| POST   | `/api/simulation/config` | Apply a (partial) configuration and regenerate the neighbourhood |

## Frontend

Plain HTML/CSS/JS (no build step) under `src/main/resources/static/`, using
Chart.js (via CDN) for the demand/generation time-series chart. The
"street" view renders all 30 houses as tiles that glow amber when importing
power and teal when exporting, with brightness proportional to the flow —
a way of watching the neighbourhood's grid draw across a full day. Below it,
a public-charger panel shows every station's occupancy and live
load, and a cumulative-energy panel compares kWh totals per asset class
since the simulation started. The house registry table and click-to-inspect
detail panel show the load/generation breakdown — including cumulative
meter readings — for a single house.

## Known limitations

This is a demo-scale, single-instance app, not a production system. Known
limitations, and what I'd invest in next given more time:

- **In-memory only, single JVM instance.** All state — the neighbourhood,
  tick history, cumulative meters — lives in one `SimulationEngine`
  singleton bean, in memory. Restart the app and it's gone; there's no
  persistence. **Scaling this to thousands of homes would require investing
  in a proper database setup**: a time-series store (e.g. TimescaleDB,
  InfluxDB) for the tick-by-tick history and cumulative meters, and a
  relational or document store for neighbourhood/asset configuration —
  plus decoupling the simulation "tick" from the request/response cycle
  (a scheduled job advancing and persisting state, with the API reading
  from the store) instead of computing everything synchronously per
  HTTP call.
- **Rolling history window, not full history.** `maxHistory` caps in-memory
  history to ~3 simulated days specifically to bound memory growth; older
  ticks are discarded, not archived. Database-backed history would let the
  chart — and any future full-run export/analysis — go back further, or
  indefinitely.
- **Not horizontally scalable.** Because state is a singleton in one JVM,
  running multiple instances behind a load balancer isn't possible without
  either sticky sessions (fragile) or a shared external state store — the
  same database investment above is the prerequisite for this too.
- **Per-tick computation is O(houses + chargers), synchronous, single-
  threaded.** Fine for tens to low hundreds of houses at interactive
  polling rates. Thousands of houses (or many concurrent neighbourhoods)
  would need batching/parallelization of the physics calculations and
  likely a move away from recomputing the whole neighbourhood on every
  single tick request.
- **Polling, not push.** The frontend polls the backend on a `setInterval`.
  Simple and fine for one viewer; doesn't scale to many concurrent viewers
  of the same run. A WebSocket/SSE broadcast would be the next step once
  there's more than one client watching a given simulation.
- **No authentication or multi-tenancy.** Anyone who can reach the API can
  reset/reconfigure the one shared simulation. Fine for a local demo; real
  auth plus per-user/per-tenant simulation instances (which, again, wants
  state in a database rather than JVM memory) would be needed beyond that.
- **No CI pipeline.** For development and release automation
- **Physics are simplified, not calibrated.** The heat pump/PV/EV/weather
  models are built to be *directionally* realistic (season affects heat
  demand, cloud cover affects PV output, cold weather hurts heat pump
  efficiency, etc.) for a convincing demo — not validated against real
  smart-meter or weather datasets. A production version would want that
  calibration.

## Project layout

See [`docs/class-diagram.mermaid`](docs/class-diagram.mermaid) for a class
diagram of how these pieces relate (renders inline on GitHub/GitLab, or
paste it into the [Mermaid Live Editor](https://mermaid.live)).

```
src/main/java/com/energysim/
  EnergySimApplication.java
  config/         NeighbourhoodConfig (bound from application.yml; also the runtime config API's target)
  model/          House, HouseSnapshot, PublicCharger, PublicChargerSnapshot,
                  TimePoint, SimulationSnapshot
  service/        SimulationEngine   (all the simulation physics + state)
  controller/     SimulationController (REST API)
src/main/resources/
  application.yml   default neighbourhood configuration
  static/           index.html, css/style.css, js/app.js
src/test/java/com/energysim/
  model/          HouseTest, PublicChargerTest
  service/        SimulationEngineTest
  controller/     SimulationControllerTest
config/
  neighbourhood-example.yml   example external config override
  neighbourhood-example.json  example runtime /api/simulation/config payload
docs/
  class-diagram.mermaid   class diagram of the project's structure
```
