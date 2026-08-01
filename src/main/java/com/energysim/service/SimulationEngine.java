package com.energysim.service;

import com.energysim.config.NeighbourhoodConfig;
import com.energysim.model.House;
import com.energysim.model.HouseSnapshot;
import com.energysim.model.PublicCharger;
import com.energysim.model.PublicChargerSnapshot;
import com.energysim.model.SimulationSnapshot;
import com.energysim.model.TimePoint;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Simulates electricity consumption and generation for a neighbourhood of
 * houses (plus a handful of public EV chargers) over time.
 *
 * <p>Time advances in fixed-size ticks (default 10 minutes, 144/day —
 * configurable to 1/5/10/15/30/60 minutes via {@code neighbourhood.stepMinutes}).
 * By default the simulation starts today at midnight, so the simulated
 * season lines up with the month it's run in; both the start date and the
 * start time of day are configurable ({@code neighbourhood.startDate} /
 * {@code neighbourhood.startTime}) if a specific starting moment is wanted
 * instead. Each tick every house's load is derived from:
 * <ul>
 *   <li>a base household load curve (random per-house scale + daily shape),</li>
 *   <li>an optional heat pump, driven by a seasonal + daily outdoor-temperature model,</li>
 *   <li>an optional home EV charger, driven by a randomised daily arrival/energy-need plan,</li>
 *   <li>an optional PV array, driven by a season-aware solar-irradiance curve
 *       and a slowly wandering cloud-cover factor.</li>
 * </ul>
 * Six public EV chargers sit outside any single house and serve randomly
 * arriving vehicles throughout the day. The engine keeps a rolling history
 * of aggregated neighbourhood totals for charting, plus cumulative kWh
 * meters (per house, per public charger, and per asset class) since the
 * simulation started.
 *
 * <p>The neighbourhood itself is fully configurable: house count, asset
 * proportions, size ranges, the public charger roster, and an optional
 * fixed random seed all come from {@link NeighbourhoodConfig}, which is
 * bound from {@code application.yml} (or an external YAML/properties file)
 * at startup and can be updated at runtime via
 * {@code POST /api/simulation/config}.
 */
@Service
public class SimulationEngine {

    private static final double EV_HOME_REROLL_HOUR = 10.0; // cars are away, safe to plan the day's charging

    // Base household load shape: (hour, relative kW) control points, piecewise-linear.
    private static final double[][] LOAD_PROFILE = {
            {0, 0.25}, {5, 0.20}, {6, 0.30}, {8, 0.90}, {10, 0.50},
            {12, 0.45}, {14, 0.40}, {17, 0.60}, {19, 1.20}, {21, 0.90},
            {22, 0.50}, {24, 0.25}
    };

    // Average monthly outdoor temperature (°C), temperate NW-European climate, Jan..Dec.
    private static final double[] MONTHLY_MEAN_TEMP = {
            3.5, 3.8, 6.5, 9.8, 13.5, 16.0, 18.5, 18.3, 15.3, 11.3, 7.0, 4.0
    };

    private static final String[] STREET_NAMES = {
            "Elm", "Oak", "Maple", "Willow", "Birch", "Cedar", "Poplar", "Ash",
            "Chestnut", "Hazel", "Rowan", "Larch", "Beech", "Alder", "Linden"
    };

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final NeighbourhoodConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Random rng = new Random();

    // Tick resolution — derived from config.stepMinutes on every reset() so
    // the step size (1/5/10/15/30/60 minutes) can be changed at runtime.
    private int ticksPerDay = 144;
    private double tickHours = 24.0 / 144;
    private int maxHistory = 3 * 144; // 3 rolling days

    private List<House> houses = new ArrayList<>();
    private List<PublicCharger> publicChargers = new ArrayList<>();
    private final Deque<TimePoint> history = new ArrayDeque<>();

    private long tick;
    // Sky-clarity factor for the day: near 1.0 = clear/sunny, low = heavily
    // overcast. Feeds both PV output (directly) and the daily temperature
    // swing (clear skies swing further hot-to-cold than overcast ones).
    private double cloudFactor = 0.85;
    private double tempOffset = 0.0; // day-to-day weather noise around the seasonal mean
    private LocalDate startDate = LocalDate.now();
    // How many ticks into "day 1" the simulation begins — derived from the
    // configured start time (e.g. 08:00) so tick 0 doesn't have to mean
    // midnight. Applied via effectiveTick(), never to the raw tick counter
    // itself (that stays "steps taken since simulation start").
    private int startTickOffset = 0;

    // Neighbourhood-wide cumulative energy meters (kWh) since simulation start.
    private double cumulativeBaseLoadKwh;
    private double cumulativeHeatPumpKwh;
    private double cumulativeEvHomeKwh;
    private double cumulativeEvPublicKwh;
    private double cumulativePvKwh;

    public SimulationEngine(NeighbourhoodConfig config) {
        this.config = config;
        reset();
    }

    public NeighbourhoodConfig getConfig() {
        return config;
    }

    /**
     * Merges the given (partial) values onto the current configuration —
     * fields not present are left untouched — then regenerates the
     * neighbourhood from the result.
     */
    public synchronized SimulationSnapshot applyConfigUpdate(Map<String, Object> updates) {
        try {
            objectMapper.updateValue(config, updates);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid neighbourhood configuration: " + e.getMessage(), e);
        }
        return reset();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public synchronized SimulationSnapshot reset() {
        normalizeConfig();

        houses = new ArrayList<>(config.getHouseCount());
        publicChargers = new ArrayList<>();
        tick = 0;
        cloudFactor = 0.85;
        tempOffset = 0.0;
        history.clear();

        cumulativeBaseLoadKwh = 0;
        cumulativeHeatPumpKwh = 0;
        cumulativeEvHomeKwh = 0;
        cumulativeEvPublicKwh = 0;
        cumulativePvKwh = 0;

        // Use the configured seed if given, otherwise pick one and record it
        // back onto the config so this run can be reproduced later.
        long effectiveSeed = config.getSeed() != null ? config.getSeed() : new Random().nextLong();
        config.setSeed(effectiveSeed);
        rng = new Random(effectiveSeed);

        List<Double> evOptions = config.getHomeEvChargerPowerOptionsKw();

        for (int i = 1; i <= config.getHouseCount(); i++) {
            boolean hasHeatPump = rng.nextDouble() < config.getHeatPumpProbability();
            boolean hasPv = rng.nextDouble() < config.getPvProbability();
            boolean hasEv = rng.nextDouble() < config.getEvChargerProbability();

            double baseLoadFactor = lerp(config.getBaseLoadFactorMin(), config.getBaseLoadFactorMax(), rng.nextDouble());
            double heatPumpKw = hasHeatPump ? lerp(config.getHeatPumpKwMin(), config.getHeatPumpKwMax(), rng.nextDouble()) : 0.0;
            double pvKw = hasPv ? lerp(config.getPvKwMin(), config.getPvKwMax(), rng.nextDouble()) : 0.0;
            double evChargerKw = hasEv ? evOptions.get(rng.nextInt(evOptions.size())) : 0.0;

            String street = STREET_NAMES[(i - 1) % STREET_NAMES.length];
            String name = "No. " + i + " " + street + " Way";

            House house = new House(i, name, baseLoadFactor,
                    hasHeatPump, heatPumpKw, hasPv, pvKw, hasEv, evChargerKw);

            if (hasEv) {
                rollEvHomePlan(house);
            }
            houses.add(house);
        }

        int chargerId = 1;
        List<NeighbourhoodConfig.PublicChargerConfig> explicitRoster = config.getPublicChargers();
        if (!explicitRoster.isEmpty()) {
            // An explicit roster was given — use it exactly as configured.
            for (NeighbourhoodConfig.PublicChargerConfig cc : explicitRoster) {
                publicChargers.add(new PublicCharger(chargerId++, cc.getName(), cc.getPowerKw()));
            }
        } else {
            // No explicit roster — auto-generate publicChargerCount chargers with a
            // random power rating per charger, drawn from publicChargerPowerOptionsKw.
            List<Double> powerOptions = config.getPublicChargerPowerOptionsKw();
            for (int i = 1; i <= config.getPublicChargerCount(); i++) {
                double powerKw = powerOptions.get(rng.nextInt(powerOptions.size()));
                publicChargers.add(new PublicCharger(chargerId, publicChargerName(chargerId, powerKw), powerKw));
                chargerId++;
            }
        }

        return computeTick(false); // initial state at tick 0 — no time has elapsed yet, don't accumulate energy
    }

    // Location descriptors used to auto-generate public charger names.
    private static final String[] PUBLIC_CHARGER_LOCATIONS = {
            "Village Green", "Supermarket Car Park", "Train Station", "Community Hall",
            "High Street", "Retail Park", "Sports Centre", "Library",
            "Town Hall", "Park & Ride", "Leisure Centre", "Market Square"
    };

    /** Builds a realistic-sounding charger name from its position and power tier. */
    private String publicChargerName(int index, double powerKw) {
        String location = PUBLIC_CHARGER_LOCATIONS[(index - 1) % PUBLIC_CHARGER_LOCATIONS.length];
        String tier = powerKw >= 40 ? "Rapid Hub" : (powerKw >= 20 ? "Fast Charger" : "Kerbside Point");
        return location + " - " + tier + " " + index;
    }

    /** Clamps/repairs configuration values so a bad external file can't crash generation. */
    private void normalizeConfig() {
        if (config.getHouseCount() < 1) config.setHouseCount(1);
        if (config.getHouseCount() > 500) config.setHouseCount(500);

        // Step size: clamp to something sane, then derive the tick count so a
        // day always adds up to exactly 24h regardless of the requested size
        // (e.g. an odd value like 7 minutes rounds to the nearest tick count
        // rather than drifting the calendar).
        int stepMinutes = clampInt(config.getStepMinutes(), 1, 60);
        config.setStepMinutes(stepMinutes);
        ticksPerDay = Math.max(1, Math.round(1440f / stepMinutes));
        tickHours = 24.0 / ticksPerDay;
        maxHistory = 3 * ticksPerDay; // 3 rolling days, whatever the resolution

        // Start date: parse the configured value, falling back to today on
        // anything blank or unparseable. Echoed back onto the config
        // afterwards (same pattern as the random seed) so GET /config always
        // reports exactly what's in use.
        LocalDate resolvedStartDate;
        String configuredDate = config.getStartDate();
        try {
            resolvedStartDate = (configuredDate == null || configuredDate.isBlank())
                    ? LocalDate.now()
                    : LocalDate.parse(configuredDate.trim());
        } catch (DateTimeParseException e) {
            resolvedStartDate = LocalDate.now();
        }
        startDate = resolvedStartDate;
        config.setStartDate(startDate.format(DATE_FMT));

        // Start time: parse "HH:mm", falling back to 00:00. Converted to a
        // tick offset applied via effectiveTick() everywhere calendar
        // date/time (and therefore weather/physics) is derived from the
        // tick counter — the raw tick counter itself stays untouched so
        // cumulative energy accounting and history indexing are unaffected.
        double resolvedStartHour;
        String configuredTime = config.getStartTime();
        try {
            if (configuredTime == null || configuredTime.isBlank()) {
                resolvedStartHour = 0.0;
            } else {
                LocalTime parsed = LocalTime.parse(configuredTime.trim());
                resolvedStartHour = parsed.getHour() + parsed.getMinute() / 60.0;
            }
        } catch (DateTimeParseException e) {
            resolvedStartHour = 0.0;
        }
        config.setStartTime(formatTime(resolvedStartHour));
        startTickOffset = (int) Math.round(resolvedStartHour / 24.0 * ticksPerDay);

        config.setHeatPumpProbability(clamp(config.getHeatPumpProbability(), 0.0, 1.0));
        config.setPvProbability(clamp(config.getPvProbability(), 0.0, 1.0));
        config.setEvChargerProbability(clamp(config.getEvChargerProbability(), 0.0, 1.0));

        if (config.getHeatPumpKwMax() < config.getHeatPumpKwMin()) {
            config.setHeatPumpKwMax(config.getHeatPumpKwMin());
        }
        if (config.getPvKwMax() < config.getPvKwMin()) {
            config.setPvKwMax(config.getPvKwMin());
        }
        if (config.getBaseLoadFactorMax() < config.getBaseLoadFactorMin()) {
            config.setBaseLoadFactorMax(config.getBaseLoadFactorMin());
        }
        if (config.getHomeEvChargerPowerOptionsKw() == null || config.getHomeEvChargerPowerOptionsKw().isEmpty()) {
            config.setHomeEvChargerPowerOptionsKw(new ArrayList<>(List.of(7.4)));
        }

        config.setPublicChargerCount(clampInt(config.getPublicChargerCount(), 0, 200));
        if (config.getPublicChargerPowerOptionsKw() == null || config.getPublicChargerPowerOptionsKw().isEmpty()) {
            config.setPublicChargerPowerOptionsKw(new ArrayList<>(List.of(11.0)));
        }
        if (config.getPublicChargers() == null) {
            config.setPublicChargers(new ArrayList<>());
        }
    }

    /** Advances the simulation by one tick (size set by config.stepMinutes) and returns the new state. */
    public synchronized SimulationSnapshot step() {
        tick++;
        return computeTick(true); // a tick's worth of time just elapsed — accumulate its energy
    }

    public synchronized SimulationSnapshot currentSnapshot() {
        return buildSnapshot();
    }

    // ------------------------------------------------------------------
    // Per-tick computation
    // ------------------------------------------------------------------

    private SimulationSnapshot computeTick(boolean accumulate) {
        long effTick = effectiveTick();
        int tickOfDay = (int) (effTick % ticksPerDay);
        double hour = hourOfDay(effTick);

        if (tickOfDay == 0) {
            // Daily random walk on the weather offset around the seasonal mean.
            tempOffset += (rng.nextDouble() - 0.5) * 3.0;
            tempOffset = clamp(tempOffset, -6.0, 6.0);
        }

        // Cloud cover wanders slowly and reverts toward a moderately sunny mean.
        cloudFactor += (rng.nextDouble() - 0.5) * 0.08 + (0.8 - cloudFactor) * 0.03;
        cloudFactor = clamp(cloudFactor, 0.25, 1.0);

        LocalDate date = currentDate();
        int dayOfYear = date.getDayOfYear();
        int month = date.getMonthValue();

        double outdoorTemp = outdoorTemperature(hour, month);
        SeasonSolar solar = seasonSolar(dayOfYear);
        double irradiance = solarIrradiance(hour, solar);
        int evRerollTickOfDay = (int) Math.round(EV_HOME_REROLL_HOUR / 24.0 * ticksPerDay);

        for (House house : houses) {
            if (house.isHasEvCharger() && tickOfDay == evRerollTickOfDay) {
                rollEvHomePlan(house);
            }

            double base = baseLoad(house, hour);
            double heatPump = heatPumpLoad(house, outdoorTemp, hour);
            double ev = evHomeLoad(house, hour);
            double pv = pvGeneration(house, irradiance);

            house.setCurrentBaseLoadKw(base);
            house.setCurrentHeatPumpLoadKw(heatPump);
            house.setCurrentEvLoadKw(ev);
            house.setCurrentPvGenerationKw(pv);

            if (accumulate) {
                house.accumulate(tickHours);
                cumulativeBaseLoadKwh += base * tickHours;
                cumulativeHeatPumpKwh += heatPump * tickHours;
                cumulativeEvHomeKwh += ev * tickHours;
                cumulativePvKwh += pv * tickHours;
            }
        }

        for (PublicCharger charger : publicChargers) {
            // tickPublicCharger returns the load (kW) that actually applied during
            // this tick, captured BEFORE any end-of-session reset — reading
            // charger.getCurrentLoadKw() afterwards would silently miss the final
            // tick of a session that just ended (it gets zeroed out as part of
            // freeing the charger).
            double load = tickPublicCharger(charger, hour);
            if (accumulate) {
                charger.addCumulativeEnergyKwh(load * tickHours);
                cumulativeEvPublicKwh += load * tickHours;
            }
        }

        SimulationSnapshot snapshot = buildSnapshot();

        history.addLast(new TimePoint(tick, snapshot.day(), snapshot.timeLabel(),
                round(outdoorTemp), snapshot.totalDemandKw(), snapshot.totalGenerationKw(),
                snapshot.netImportKw()));
        while (history.size() > maxHistory) {
            history.removeFirst();
        }

        return snapshot;
    }

    private SimulationSnapshot buildSnapshot() {
        long effTick = effectiveTick();
        double hour = hourOfDay(effTick);
        int day = currentDay(effTick);
        LocalDate date = currentDate();
        int dayOfYear = date.getDayOfYear();
        int month = date.getMonthValue();

        double outdoorTemp = outdoorTemperature(hour, month);
        SeasonSolar solar = seasonSolar(dayOfYear);

        List<HouseSnapshot> houseSnapshots = new ArrayList<>(houses.size());
        double totalDemand = 0, totalGeneration = 0;
        int importing = 0, exporting = 0;
        int countHeatPump = 0, countPv = 0, countEv = 0;

        for (House house : houses) {
            totalDemand += house.totalLoadKw();
            totalGeneration += house.getCurrentPvGenerationKw();
            if (house.netKw() > 0.01) importing++;
            else if (house.netKw() < -0.01) exporting++;
            if (house.isHasHeatPump()) countHeatPump++;
            if (house.isHasPv()) countPv++;
            if (house.isHasEvCharger()) countEv++;

            houseSnapshots.add(HouseSnapshot.from(house));
        }

        double publicLoad = 0;
        List<PublicChargerSnapshot> chargerSnapshots = new ArrayList<>(publicChargers.size());
        for (PublicCharger charger : publicChargers) {
            publicLoad += charger.getCurrentLoadKw();
            chargerSnapshots.add(PublicChargerSnapshot.from(charger));
        }
        totalDemand += publicLoad;

        double cumulativeDemand = cumulativeBaseLoadKwh + cumulativeHeatPumpKwh
                + cumulativeEvHomeKwh + cumulativeEvPublicKwh;

        return new SimulationSnapshot(
                tick, day, config.getStepMinutes(), date.format(DATE_FMT), formatTime(hour), seasonLabel(month),
                monthName(month), dayOfYear,
                round(outdoorTemp), round(cloudFactor),
                formatTime(solar.sunrise()), formatTime(solar.sunset()),
                round(totalDemand), round(totalGeneration), round(totalDemand - totalGeneration),
                round(publicLoad),
                importing, exporting,
                countHeatPump, countPv, countEv, publicChargers.size(),
                round(cumulativeBaseLoadKwh), round(cumulativeHeatPumpKwh),
                round(cumulativeEvHomeKwh), round(cumulativeEvPublicKwh), round(cumulativePvKwh),
                round(cumulativeDemand), round(cumulativePvKwh), round(cumulativeDemand - cumulativePvKwh),
                houseSnapshots, chargerSnapshots, new ArrayList<>(history)
        );
    }

    // ------------------------------------------------------------------
    // Household physical models
    // ------------------------------------------------------------------

    private double baseLoad(House house, double hour) {
        double shape = interpolate(LOAD_PROFILE, hour);
        double noise = 1.0 + (rng.nextDouble() - 0.5) * 0.2;
        return Math.max(0, shape * house.getBaseLoadFactor() * noise);
    }

    /** Design condition for sizing: indoor 20°C against a -5°C winter outdoor temp. */
    private static final double DESIGN_DELTA_T = 25.0;
    private static final double DESIGN_OUTDOOR_TEMP = -5.0;

    /**
     * Coefficient of performance of an air-source heat pump: it delivers less
     * heat per kWh of electricity as it gets colder outside. This is what
     * makes electrical draw rise faster than heat demand alone in cold
     * weather.
     */
    private double coefficientOfPerformance(double outdoorTemp) {
        return clamp(2.2 + 0.06 * outdoorTemp, 1.6, 4.5);
    }

    private double heatPumpLoad(House house, double outdoorTemp, double hour) {
        if (!house.isHasHeatPump()) return 0.0;
        boolean nightSetback = hour < 6 || hour >= 23;
        double setpoint = nightSetback ? 17.0 : 20.0;
        double deltaT = Math.max(0, setpoint - outdoorTemp);

        // Thermal output the heat pump could deliver at its rated electrical
        // capacity under design (coldest) conditions...
        double thermalCapacityAtDesign = house.getHeatPumpCapacityKw() * coefficientOfPerformance(DESIGN_OUTDOOR_TEMP);
        // ...scaled down for how much heat is actually needed right now.
        double thermalDemandKw = (deltaT / DESIGN_DELTA_T) * thermalCapacityAtDesign;

        // Convert heat demand to electrical draw via today's (temperature-dependent) COP.
        double electricalDemandKw = thermalDemandKw / coefficientOfPerformance(outdoorTemp);

        double noise = 1.0 + (rng.nextDouble() - 0.5) * 0.1;
        return clamp(electricalDemandKw * noise, 0, house.getHeatPumpCapacityKw());
    }

    private double evHomeLoad(House house, double hour) {
        if (!house.isHasEvCharger() || !house.isEvPluggedInToday()) return 0.0;
        if (house.getEvEnergyDeliveredKwh() >= house.getEvEnergyNeededKwh()) return 0.0;
        if (!withinChargingWindow(hour, house.getEvArrivalHour(), house.getEvDepartureHour())) return 0.0;

        double remainingKwh = house.getEvEnergyNeededKwh() - house.getEvEnergyDeliveredKwh();
        double maxPowerForRemaining = remainingKwh / tickHours;
        double power = Math.min(house.getEvChargerPowerKw(), maxPowerForRemaining);
        house.setEvEnergyDeliveredKwh(house.getEvEnergyDeliveredKwh() + power * tickHours);
        return power;
    }

    private double pvGeneration(House house, double irradiance) {
        if (!house.isHasPv()) return 0.0;
        double noise = 1.0 + (rng.nextDouble() - 0.5) * 0.06;
        double gen = irradiance * house.getPvCapacityKw() * cloudFactor * noise;
        return clamp(gen, 0, house.getPvCapacityKw() * 1.02);
    }

    private void rollEvHomePlan(House house) {
        boolean pluggedIn = rng.nextDouble() < 0.7;
        house.setEvPluggedInToday(pluggedIn);
        if (!pluggedIn) return;

        double arrival = 16.0 + rng.nextDouble() * 4.0; // 16:00 - 20:00
        double windowHours = 6.0 + rng.nextDouble() * 3.0; // 6 - 9 hours available
        double needed = 6.0 + rng.nextDouble() * 22.0; // 6 - 28 kWh

        house.setEvArrivalHour(arrival);
        house.setEvDepartureHour(arrival + windowHours); // may exceed 24, handled as wrap
        house.setEvEnergyNeededKwh(needed);
        house.setEvEnergyDeliveredKwh(0.0);
    }

    private boolean withinChargingWindow(double hour, double start, double end) {
        if (end <= 24.0) {
            return hour >= start && hour < end;
        }
        return hour >= start || hour < (end - 24.0);
    }

    // ------------------------------------------------------------------
    // Public charger model
    // ------------------------------------------------------------------

    /**
     * Advances one public charger's occupancy/load state by one tick and
     * returns the load (kW) that applied *during* this tick — captured
     * before any end-of-session reset, so the caller can account energy
     * correctly even on the tick a session ends (see the comment at the
     * call site in {@link #computeTick}).
     */
    private double tickPublicCharger(PublicCharger charger, double hour) {
        if (charger.isOccupied()) {
            double load = charger.getPowerKw() * (1.0 + (rng.nextDouble() - 0.5) * 0.05);
            charger.setCurrentLoadKw(load);
            charger.setRemainingTicks(charger.getRemainingTicks() - 1);
            if (charger.getRemainingTicks() <= 0) {
                charger.setOccupied(false);
                charger.setCurrentLoadKw(0.0); // charger reads as free again from next tick
            }
            return load;
        }

        double arrivalsPerHour = (hour >= 7 && hour < 22) ? 0.35 : 0.05;
        double probThisTick = arrivalsPerHour * tickHours;
        if (rng.nextDouble() < probThisTick) {
            charger.setOccupied(true);
            int minTicks = 3, maxTicks = 9; // 30 - 90 minutes at 10-minute ticks
            charger.setRemainingTicks(minTicks + rng.nextInt(maxTicks - minTicks + 1));
            double load = charger.getPowerKw() * (0.85 + rng.nextDouble() * 0.15);
            charger.setCurrentLoadKw(load);
            return load;
        }

        charger.setCurrentLoadKw(0.0);
        return 0.0;
    }

    // ------------------------------------------------------------------
    // Weather & season models
    // ------------------------------------------------------------------

    private double outdoorTemperature(double hour, int month) {
        double seasonalMean = MONTHLY_MEAN_TEMP[month - 1];
        // Clear skies swing further between day and night than overcast ones
        // (less cloud insulation), so cloud cover directly affects the
        // temperature curve the heat pump reacts to, not just PV output.
        double dailyAmplitude = 2.0 + 3.5 * cloudFactor;
        double dailyCycle = dailyAmplitude * Math.cos(2 * Math.PI * (hour - 15.0) / 24.0);
        return seasonalMean + tempOffset + dailyCycle;
    }

    /** Bundles the day's sunrise/sunset hour and peak solar intensity factor. */
    private record SeasonSolar(double sunrise, double sunset, double peakFactor) {}

    private SeasonSolar seasonSolar(int dayOfYear) {
        double seasonCos = Math.cos(2 * Math.PI * (dayOfYear - 172) / 365.0); // +1 at midsummer
        double daylightHours = clamp(12 + 4.5 * seasonCos, 7.0, 17.0);
        double sunrise = 12 - daylightHours / 2.0;
        double sunset = 12 + daylightHours / 2.0;
        double peakFactor = clamp(0.5 + 0.45 * seasonCos, 0.1, 0.98);
        return new SeasonSolar(sunrise, sunset, peakFactor);
    }

    private double solarIrradiance(double hour, SeasonSolar solar) {
        if (hour < solar.sunrise() || hour > solar.sunset()) return 0.0;
        double span = solar.sunset() - solar.sunrise();
        return Math.max(0.0, Math.sin(Math.PI * (hour - solar.sunrise()) / span)) * solar.peakFactor();
    }

    private static String seasonLabel(int month) {
        return switch (month) {
            case 12, 1, 2 -> "Winter";
            case 3, 4, 5 -> "Spring";
            case 6, 7, 8 -> "Summer";
            default -> "Autumn";
        };
    }

    private static String monthName(int month) {
        return java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static double interpolate(double[][] points, double x) {
        for (int i = 0; i < points.length - 1; i++) {
            double x0 = points[i][0], y0 = points[i][1];
            double x1 = points[i + 1][0], y1 = points[i + 1][1];
            if (x >= x0 && x <= x1) {
                double t = (x - x0) / (x1 - x0);
                return y0 + t * (y1 - y0);
            }
        }
        return points[points.length - 1][1];
    }

    private double hourOfDay(long tick) {
        return (tick % ticksPerDay) * tickHours;
    }

    private int currentDay(long tick) {
        return (int) (tick / ticksPerDay) + 1;
    }

    /**
     * The tick counter used for all calendar/time-of-day purposes (hour of
     * day, day count, date, and therefore weather/season/physics). Differs
     * from the raw {@code tick} field by {@link #startTickOffset}, so a
     * configured start time other than midnight is reflected everywhere
     * immediately — including at tick 0 — without the raw tick counter
     * itself (used for cumulative accounting and history indexing) having
     * to pretend any time has elapsed before the simulation actually began.
     */
    private long effectiveTick() {
        return tick + startTickOffset;
    }

    private LocalDate currentDate() {
        return startDate.plusDays(currentDay(effectiveTick()) - 1L);
    }

    private static String formatTime(double hour) {
        double h24 = ((hour % 24) + 24) % 24;
        int h = (int) h24;
        int m = (int) Math.round((h24 - h) * 60);
        if (m == 60) { m = 0; h = (h + 1) % 24; }
        return String.format(Locale.ROOT, "%02d:%02d", h, m);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double lerp(double min, double max, double t) {
        return min + t * (max - min);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
