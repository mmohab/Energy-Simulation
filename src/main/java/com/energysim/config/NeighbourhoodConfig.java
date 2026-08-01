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

    @Schema(description = "Proportion (0.0-1.0) of houses with a heat pump.", example = "0.45")
    private double heatPumpProbability = 0.45;
    @Schema(description = "Proportion (0.0-1.0) of houses with PV/solar panels.", example = "0.55")
    private double pvProbability = 0.55;
    @Schema(description = "Proportion (0.0-1.0) of houses with a home EV charger.", example = "0.40")
    private double evChargerProbability = 0.40;

    private double heatPumpKwMin = 3.0;
    private double heatPumpKwMax = 9.0;

    private double pvKwMin = 3.0;
    private double pvKwMax = 8.0;

    private double baseLoadFactorMin = 0.6;
    private double baseLoadFactorMax = 1.6;

    private List<Double> homeEvChargerPowerOptionsKw = new ArrayList<>(List.of(3.7, 7.4));

    private List<PublicChargerConfig> publicChargers = defaultPublicChargers();

    public static List<PublicChargerConfig> defaultPublicChargers() {
        List<PublicChargerConfig> list = new ArrayList<>();
        list.add(new PublicChargerConfig("Village Green - Kerbside 1", 11.0));
        list.add(new PublicChargerConfig("Village Green - Kerbside 2", 11.0));
        list.add(new PublicChargerConfig("Supermarket Car Park - Rapid A", 50.0));
        list.add(new PublicChargerConfig("Supermarket Car Park - Rapid B", 50.0));
        list.add(new PublicChargerConfig("Train Station - Kerbside", 22.0));
        list.add(new PublicChargerConfig("Community Hall - Kerbside", 22.0));
        return list;
    }

    // --- getters / setters -------------------------------------------------

    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }

    public int getHouseCount() { return houseCount; }
    public void setHouseCount(int houseCount) { this.houseCount = houseCount; }

    public int getStepMinutes() { return stepMinutes; }
    public void setStepMinutes(int stepMinutes) { this.stepMinutes = stepMinutes; }

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

    public List<PublicChargerConfig> getPublicChargers() { return publicChargers; }
    public void setPublicChargers(List<PublicChargerConfig> publicChargers) { this.publicChargers = publicChargers; }

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
