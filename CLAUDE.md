# CLAUDE.md

Context for Claude (or any contributor) working on this repo. Read this
before making changes — it captures decisions and assumptions that aren't
obvious from the code alone.

## What this is

A Spring Boot web app that simulates electricity use and generation across a
configurable neighbourhood (houses with optional heat pumps / PV / home EV
chargers, plus a configurable number of public EV chargers) and visualizes
it live in the browser as simulated time advances. No database — everything
lives in memory in one `SimulationEngine` singleton bean and resets when the
app restarts or `/api/simulation/reset` is called.

Stack: Java 17, Spring Boot 3.3.2, Maven, springdoc-openapi (Swagger UI),
plain HTML/CSS/JS frontend (no build step, no framework), Chart.js via CDN.

## Build / run

```bash
mvn spring-boot:run          # http://localhost:8080
mvn clean package && java -jar target/neighbourhood-energy-sim-1.0.0.jar
```

**Important — this repo has never actually been built in the environment it
was authored in.** The sandbox that generated this code has no route to
Maven Central (network allowlist blocks `repo.maven.apache.org`), so every
change was verified by manual review only: brace/paren balance checks,
tracing record-constructor argument order against field lists by hand, and
validating YAML/JSON config files parse. **The first thing to do in a real
environment is `mvn clean compile` and fix whatever that turns up** — treat
the code as "carefully reviewed but never compiled," not "known-working."
Past instances of this have already caught and fixed real bugs this way
(see "Known rough edges" below), so don't assume it's clean.

If a build fails on a springdoc/swagger import, see the note in
`EnergySimApplication.java`'s history: an earlier version built the OpenAPI
bean programmatically via `io.swagger.v3.oas.models.*`, which failed to
resolve in at least one real environment. It was replaced with the
annotation-based `@OpenAPIDefinition` on the main class, which only needs
`io.swagger.v3.oas.annotations.*`. If you reintroduce programmatic
`OpenAPI`/`Info`/`Contact` construction, expect that same class of failure
to be possible again.

## Architecture

```
com.energysim
├── EnergySimApplication      Main class. @ConfigurationPropertiesScan (binds
│                              NeighbourhoodConfig) + @OpenAPIDefinition (Swagger metadata).
├── config/
│   └── NeighbourhoodConfig   @ConfigurationProperties(prefix="neighbourhood").
│                              Mutable POJO — this is the single source of
│                              truth for how to generate the neighbourhood.
│                              Bound from application.yml at startup; also the
│                              target of runtime POST /api/simulation/config
│                              updates via Jackson's ObjectMapper.updateValue
│                              (partial merge — omitted fields keep their value).
├── model/                    Plain domain classes + JSON-facing DTOs (records).
│   ├── House                 Mutable — holds asset config + per-tick state +
│   │                          cumulative kWh meters. Recomputed every tick.
│   ├── PublicCharger          Same idea, for a public charging point.
│   ├── HouseSnapshot          record — House.from(House) JSON view for one tick.
│   ├── PublicChargerSnapshot  record — PublicCharger.from(...) JSON view.
│   ├── TimePoint              record — one point in the history time series.
│   └── SimulationSnapshot     record — the full API response. 33 positional
│                              fields; ORDER MATTERS (see "Records" below).
├── service/
│   └── SimulationEngine       @Service singleton. ALL simulation state and
│                              physics live here. synchronized on every public
│                              method (reset/step/applyConfigUpdate/etc) since
│                              it's a shared mutable singleton hit by concurrent
│                              HTTP requests.
└── controller/
    └── SimulationController   Thin REST layer over SimulationEngine. Swagger-
                                annotated. One @ExceptionHandler converts
                                IllegalArgumentException (bad config) to 400.
```

Frontend: `src/main/resources/static/{index.html,css/style.css,js/app.js}`.
Plain JS, no bundler. `app.js` polls the backend on a `setInterval` (the
"▶ Run" button) rather than using WebSockets/SSE — deliberately simple, no
push infra needed. Chart.js loaded from a CDN in `index.html`.

## Design decisions / assumptions worth knowing

- **Tick-based time, not wall-clock.** One tick = `stepMinutes` (config,
  default 10, clamped 1–60). `SimulationEngine.reset()` derives
  `ticksPerDay = round(1440 / stepMinutes)` and `tickHours = 24.0 /
  ticksPerDay` so a simulated day always sums to exactly 24h even for step
  sizes that don't evenly divide 1440 (e.g. 7 minutes). These are **instance
  fields, not constants** — they're recomputed on every `reset()`, which is
  why `hourOfDay()`/`currentDay()` are instance methods, not static.
- **Simulated calendar starts at `LocalDate.now()`** on every reset, so the
  simulated season matches whatever month the app happens to be started in.
  This was a deliberate choice for demo relatability, not a requirement —
  revisit if a fixed/configurable start date is ever wanted.
- **Random seed**: if `config.seed` is null, `reset()` generates one and
  **writes it back onto `config.seed`** so `GET /api/simulation/config`
  always reports the seed actually in use (reproducibility without the
  caller having to pre-choose a seed). Don't remove that write-back — the
  UI's footer and the "Configure" panel depend on reading it back.
- **Public chargers: dual generation mode.** `config.publicChargers`
  (explicit `{name, powerKw}` list) defaults to **empty**. If non-empty, it's
  used verbatim (advanced/API users). If empty, `publicChargerCount` +
  `publicChargerPowerOptionsKw` auto-generate a roster with procedural names
  (`PUBLIC_CHARGER_LOCATIONS` × a power-tier label in
  `SimulationEngine.publicChargerName()`). The UI's Configure panel always
  sends `publicChargers: []` when applying, so the simple count field
  reliably works even if an explicit roster was set earlier via the API —
  don't remove that or the count field will silently do nothing.
- **Heat pump uses a temperature-dependent COP** (`coefficientOfPerformance()`),
  not a flat linear model — electrical draw for the same heat demand rises
  faster in cold weather, matching how real air-source heat pumps behave.
  This was added specifically because "weather/season must influence PV
  production, heat pump consumption" was a stated requirement — if you
  simplify this model back to linear, that requirement regresses.
- **Cloud cover (`cloudFactor`) feeds two things**: PV output directly, and
  the *daily temperature swing amplitude* (clearer skies → bigger day/night
  swing). It is **not** literal cloud percentage — it's closer to "sky
  clarity," documented on the field itself. Don't rename/repurpose without
  checking both call sites.
- **EV home charging** re-plans once per simulated day at 10:00
  (`EV_HOME_REROLL_HOUR`), not at midnight — chosen because 10am is when
  cars are reliably away, so replanning doesn't interrupt a charging session
  that started the evening before and might run past midnight.
- **Cumulative energy meters** are tracked at three granularities
  simultaneously: per-house (`House.cumulativeConsumptionKwh/GenerationKwh`),
  per-public-charger (`PublicCharger.cumulativeEnergyKwh`), and
  neighbourhood-wide per asset class (`SimulationEngine.cumulativeXxxKwh`
  fields). All three need to stay in sync if you change how any load
  category is computed — search for `tickHours` multiplications in
  `computeTick()`.
- **`NeighbourhoodConfig.normalizeConfig()`** is a defensive clamp/repair
  pass run at the top of every `reset()` — it exists so a malformed external
  config file or a bad partial API update can't crash generation (e.g.
  min>max ranges get swapped, empty option lists get a fallback, counts get
  clamped). If you add a new config field with a min/max pair or a
  "must-not-be-empty" list, add the corresponding guard here.

## Records: argument order is load-bearing

`HouseSnapshot`, `PublicChargerSnapshot`, `TimePoint`, and especially
`SimulationSnapshot` (33 components) are Java records constructed
positionally in `SimulationEngine`/`HouseSnapshot.from()`/etc. **There is no
compiler check that a constructor call's argument order matches the record
declaration's field order beyond type-compatibility** — if two adjacent
fields have the same type (e.g. two `double`s), a transposition compiles
silently and produces wrong data at runtime. When editing a record:
1. Add/reorder the field in the record declaration.
2. Update every positional constructor call site (`grep -rn "new
   SimulationSnapshot("` etc.).
3. Manually recount that the argument list length and order match — this
   project has no unit tests to catch a transposition, so this is entirely
   on manual review right now (see "Known rough edges").

Also note: record accessors are **methods**, not fields —
`solar.sunrise()`, not `solar.sunrise`. This project's `SeasonSolar` local
record has bitten this once already (see git history / prior session
context) — the field-access-typo bug compiles fine as long as nothing
references the record wrongly elsewhere, but fails if you do.

## Configuration system

Three layers, increasing in immediacy — see README.md "Configuration"
section for full user-facing docs:
1. `src/main/resources/application.yml` (`neighbourhood.*` keys) — defaults.
2. External file via `--spring.config.additional-location=file:...` —
   `config/neighbourhood-example.yml` demonstrates count-based public
   chargers; `config/neighbourhood-example.json` demonstrates the explicit
   roster override. Both are meant to be read as usage examples, not
   dead files — keep them in sync if `NeighbourhoodConfig` fields change.
3. Runtime `POST /api/simulation/config` (partial JSON merge via
   `ObjectMapper.updateValue`, configured with
   `FAIL_ON_UNKNOWN_PROPERTIES=false` so stray keys like the `_comment` in
   the JSON example don't break it) — regenerates immediately.

The Configure panel in the UI (`index.html` `#configPanel` /
`app.js` `loadConfigIntoForm|applyConfigFromForm`) only exposes a curated
subset (seed, step size, house count, three probabilities, public charger
count) — full control (size ranges, EV power option lists, explicit
charger roster) is API/config-file only by design, per the panel's own
hint text. If you add a field to `NeighbourhoodConfig` that should be
end-user-facing, decide deliberately whether it belongs in this curated
panel or stays "advanced."

## Frontend notes

- No state management library — `app.js` keeps a few module-level `let`s
  (`latestSnapshot`, `stepMinutes`, `running`, etc.) and re-renders
  DOM/Chart.js on every snapshot via one `render(snapshot)` function that
  fans out to `updateSummary/updateHouseGrid/updateTable/updateChart/
  updatePublicChargers/updateCumulative/renderDetail`.
- `stepMinutes` is read back from **every** snapshot's `stepMinutes` field
  (not just fetched once) so the UI stays correct if the backend's step
  size changes for any reason.
- The house/public-charger grids are rebuilt (`ensureHouseGrid`/
  `ensurePublicChargerGrid`) only when the element count doesn't match the
  data — i.e. on house-count/charger-count change, not every tick — for
  performance. If you add a field that changes a house's *identity* (not
  just its live values) without changing the count, this diffing won't
  pick it up; you'll need to force a grid rebuild (see `doReset`/
  `applyConfigFromForm` clearing `innerHTML` before re-rendering).
- Design system: dark "grid-watch" aesthetic — CSS custom properties in
  `:root` (amber = import/demand, teal = export/generation, blue = neutral/
  grid). Space Grotesk (display) + JetBrains Mono (data), both Google
  Fonts. Keep new UI consistent with this rather than introducing new
  colors/fonts ad hoc.

## Known rough edges / things to double-check first

- **Never compiled in this sandbox** (see "Build / run" above) — the single
  biggest risk area. Run a real build before trusting any of this blindly.
- No test suite exists at all (`spring-boot-starter-test` is a pom
  dependency but nothing uses it yet). If you're doing substantial work
  here, adding tests for `SimulationEngine` (deterministic given a fixed
  seed — great for snapshot-style assertions) would pay for itself
  immediately, especially against the "records: argument order" risk above.
- `SimulationEngine` is a single ~600-line class doing config validation,
  neighbourhood generation, and all physics models. It works, but if it
  grows further, consider splitting the physics (`baseLoad`, `heatPumpLoad`,
  `pvGeneration`, `evHomeLoad`, `tickPublicCharger`, the weather/season
  helpers) into a separate collaborator class injected into the engine,
  which would also make unit testing individual models much easier.
- `.idea/` project files are checked in (see `.gitignore` for what's
  deliberately excluded — workspace/tasks/caches). If this is imported into
  IntelliJ and re-saved, expect IntelliJ to want to touch `misc.xml`/
  `modules.xml` — that's fine, they're meant to be shared/tracked.
- CORS/auth: none. This is a single-user local demo app; there's no
  security config at all. Don't assume any endpoint is protected if this
  ever gets deployed anywhere multi-tenant.
