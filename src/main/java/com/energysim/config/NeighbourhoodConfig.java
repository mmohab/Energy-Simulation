package com.energysim.config;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything needed to (re)generate the neighbourhood: how many houses,
 * what proportion own each asset, the size ranges for those assets, the
 * public charger roster, and an optional random seed for reproducible runs.
 *
 * <p>Bound from the {@code neighbourhood.*} keys in {@code application.yml}
 * (or any external YAML/properties file supplied via
 * {@code --spring.config.additional-location}), and can also be updated at
 * runtime via {@code POST /api/simulation/config} with a JSON body — only
 * the fields present in the request are changed, everything else keeps its
 * current value.
 */
@ConfigurationProperties(prefix = "neighbourhood")
public class NeighbourhoodConfig {

    /** Fixed seed for the random generator. Null = a fresh random seed each reset (but it is
     *  recorded back onto this field afterwards so GET /api/simulation/config can report the
     *  seed that was actually used, making that run reproducible). */
    @Schema(description = "Fixed random seed for reproducible runs. Omit/null for a fresh random seed each reset — "
            + "the seed actually used is always reported back here afterwards.", example = "42", nullable = true)
    private Long seed;

    @Schema(description = "Number of houses in the neighbourhood.", example = "30")
    private int houseCount = 30;

    /** Simulation tick size in minutes (e.g. 1, 5, 10, 15, 30, 60). */
    @Schema(description = "Simulation tick size in minutes.", example = "10", allowableValues = {"1", "5", "10", "15", "30", "60"})
    private int stepMinutes = 10;

    @Schema(description = "Simulation start date (yyyy-MM-dd). Omit/null for today's date — the date "
            + "actually used is always reported back here afterwards.", example = "2026-06-15", nullable = true)
    private String startDate;

    @Schema(description = "Simulation start time of day (HH:mm, 24h). Omit/null for 00:00 (midnight) — "
            + "the time actually used is always reported back here afterwards.", example = "08:00", nullable = true)
    private String startTime;

    @Schema(description = "Proportion (0.0-1.0) of houses with a heat pump.", example = "0.30")
    private double heatPumpProbability = 0.30;
    @Schema(description = "Proportion (0.0-1.0) of houses with PV/solar panels.", example = "0.40")
    private double pvProbability = 0.40;
    @Schema(description = "Proportion (0.0-1.0) of houses with a home EV charger.", example = "0.20")
    private double evChargerProbability = 0.20;

    private double heatPumpKwMin = 3.0;
    private double heatPumpKwMax = 9.0;

    private double pvKwMin = 3.0;
    private double pvKwMax = 8.0;

    private double baseLoadFactorMin = 0.6;
    private double baseLoadFactorMax = 1.6;

    private List<Double> homeEvChargerPowerOptionsKw = new ArrayList<>(List.of(3.7, 7.4));

    @Schema(description = "Number of public EV charging points around the neighbourhood. Ignored if "
            + "publicChargers is explicitly given (non-empty) — that list takes precedence.", example = "6")
    private int publicChargerCount = 6;

    @Schema(description = "Possible rated power (kW) for auto-generated public chargers; one is picked "
            + "at random per charger.", example = "[11.0, 22.0, 50.0]")
    private List<Double> publicChargerPowerOptionsKw = new ArrayList<>(List.of(11.0, 22.0, 50.0));

    /**
     * Optional explicit roster ({@code { name, powerKw }} per entry). When non-empty this fully
     * overrides {@link #publicChargerCount} / {@link #publicChargerPowerOptionsKw} — the neighbourhood
     * gets exactly these chargers, in this order, with these names. Leave empty (the default) to have
     * the given count auto-generated instead.
     */
    private List<PublicChargerConfig> publicChargers = new ArrayList<>();

    @Schema(description = "Whether the shared neighbourhood battery is active.", example = "true")
    private boolean batteryEnabled = true;
    @Schema(description = "Usable neighbourhood battery capacity in kWh.", example = "100.0")
    private double batteryCapacityKwh = 100.0;
    @Schema(description = "Maximum battery charging power in kW.", example = "50.0")
    private double batteryMaxChargePowerKw = 50.0;
    @Schema(description = "Maximum battery discharging power in kW.", example = "50.0")
    private double batteryMaxDischargePowerKw = 50.0;
    @Schema(description = "Battery round-trip efficiency (0.0-1.0).", example = "0.90")
    private double batteryRoundTripEfficiency = 0.90;
    @Schema(description = "Initial battery state of charge as a percentage.", example = "80.0")
    private double batteryInitialSocPercent = 80.0;
    @Schema(description = "Peak-shaving grid-import target in kW. The battery discharges above this level.", example = "50.0")
    private double batteryPeakThresholdKw = 50.0;

    // --- getters / setters -------------------------------------------------

    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }

    public int getHouseCount() { return houseCount; }
    public void setHouseCount(int houseCount) { this.houseCount = houseCount; }

    public int getStepMinutes() { return stepMinutes; }
    public void setStepMinutes(int stepMinutes) { this.stepMinutes = stepMinutes; }

    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public double getHeatPumpProbability() { return heatPumpProbability; }
    public void setHeatPumpProbability(double heatPumpProbability) { this.heatPumpProbability = heatPumpProbability; }

    public double getPvProbability() { return pvProbability; }
    public void setPvProbability(double pvProbability) { this.pvProbability = pvProbability; }

    public double getEvChargerProbability() { return evChargerProbability; }
    public void setEvChargerProbability(double evChargerProbability) { this.evChargerProbability = evChargerProbability; }

    public double getHeatPumpKwMin() { return heatPumpKwMin; }
    public void setHeatPumpKwMin(double heatPumpKwMin) { this.heatPumpKwMin = heatPumpKwMin; }

    public double getHeatPumpKwMax() { return heatPumpKwMax; }
    public void setHeatPumpKwMax(double heatPumpKwMax) { this.heatPumpKwMax = heatPumpKwMax; }

    public double getPvKwMin() { return pvKwMin; }
    public void setPvKwMin(double pvKwMin) { this.pvKwMin = pvKwMin; }

    public double getPvKwMax() { return pvKwMax; }
    public void setPvKwMax(double pvKwMax) { this.pvKwMax = pvKwMax; }

    public double getBaseLoadFactorMin() { return baseLoadFactorMin; }
    public void setBaseLoadFactorMin(double baseLoadFactorMin) { this.baseLoadFactorMin = baseLoadFactorMin; }

    public double getBaseLoadFactorMax() { return baseLoadFactorMax; }
    public void setBaseLoadFactorMax(double baseLoadFactorMax) { this.baseLoadFactorMax = baseLoadFactorMax; }

    public List<Double> getHomeEvChargerPowerOptionsKw() { return homeEvChargerPowerOptionsKw; }
    public void setHomeEvChargerPowerOptionsKw(List<Double> homeEvChargerPowerOptionsKw) { this.homeEvChargerPowerOptionsKw = homeEvChargerPowerOptionsKw; }

    public int getPublicChargerCount() { return publicChargerCount; }
    public void setPublicChargerCount(int publicChargerCount) { this.publicChargerCount = publicChargerCount; }

    public List<Double> getPublicChargerPowerOptionsKw() { return publicChargerPowerOptionsKw; }
    public void setPublicChargerPowerOptionsKw(List<Double> publicChargerPowerOptionsKw) { this.publicChargerPowerOptionsKw = publicChargerPowerOptionsKw; }

    public List<PublicChargerConfig> getPublicChargers() { return publicChargers; }
    public void setPublicChargers(List<PublicChargerConfig> publicChargers) { this.publicChargers = publicChargers; }

    public boolean isBatteryEnabled() { return batteryEnabled; }
    public void setBatteryEnabled(boolean batteryEnabled) { this.batteryEnabled = batteryEnabled; }
    public double getBatteryCapacityKwh() { return batteryCapacityKwh; }
    public void setBatteryCapacityKwh(double batteryCapacityKwh) { this.batteryCapacityKwh = batteryCapacityKwh; }
    public double getBatteryMaxChargePowerKw() { return batteryMaxChargePowerKw; }
    public void setBatteryMaxChargePowerKw(double batteryMaxChargePowerKw) { this.batteryMaxChargePowerKw = batteryMaxChargePowerKw; }
    public double getBatteryMaxDischargePowerKw() { return batteryMaxDischargePowerKw; }
    public void setBatteryMaxDischargePowerKw(double batteryMaxDischargePowerKw) { this.batteryMaxDischargePowerKw = batteryMaxDischargePowerKw; }
    public double getBatteryRoundTripEfficiency() { return batteryRoundTripEfficiency; }
    public void setBatteryRoundTripEfficiency(double batteryRoundTripEfficiency) { this.batteryRoundTripEfficiency = batteryRoundTripEfficiency; }
    public double getBatteryInitialSocPercent() { return batteryInitialSocPercent; }
    public void setBatteryInitialSocPercent(double batteryInitialSocPercent) { this.batteryInitialSocPercent = batteryInitialSocPercent; }
    public double getBatteryPeakThresholdKw() { return batteryPeakThresholdKw; }
    public void setBatteryPeakThresholdKw(double batteryPeakThresholdKw) { this.batteryPeakThresholdKw = batteryPeakThresholdKw; }

    /** One entry in the public-charger roster: a name and a rated power. */
    public static class PublicChargerConfig {
        private String name;
        private double powerKw;

        public PublicChargerConfig() {}

        public PublicChargerConfig(String name, double powerKw) {
            this.name = name;
            this.powerKw = powerKw;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public double getPowerKw() { return powerKw; }
        public void setPowerKw(double powerKw) { this.powerKw = powerKw; }
    }
}
