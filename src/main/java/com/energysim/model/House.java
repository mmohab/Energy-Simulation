package com.energysim.model;

/**
 * A single household in the neighbourhood. Holds its static asset
 * configuration (does it own a heat pump / PV / EV charger, and how big
 * are they) plus the small amount of running state needed to simulate an
 * EV charging session across a day (arrival time, energy still needed).
 */
public class House {

    private final int id;
    private final String name;

    // Base load variability so not every house behaves identically.
    private final double baseLoadFactor; // multiplier around 1.0

    private final boolean hasHeatPump;
    private final double heatPumpCapacityKw;

    private final boolean hasPv;
    private final double pvCapacityKw;

    private final boolean hasEvCharger;
    private final double evChargerPowerKw;

    // EV daily session state (reset once per simulated day)
    private boolean evPluggedInToday;
    private double evArrivalHour;
    private double evDepartureHour;
    private double evEnergyNeededKwh;
    private double evEnergyDeliveredKwh;

    // Latest computed snapshot values (kW), refreshed every simulation tick.
    private double currentBaseLoadKw;
    private double currentHeatPumpLoadKw;
    private double currentEvLoadKw;
    private double currentPvGenerationKw;

    // Cumulative "smart meter" readings (kWh) since the simulation started.
    private double cumulativeConsumptionKwh;
    private double cumulativeGenerationKwh;

    public House(int id, String name, double baseLoadFactor,
                 boolean hasHeatPump, double heatPumpCapacityKw,
                 boolean hasPv, double pvCapacityKw,
                 boolean hasEvCharger, double evChargerPowerKw) {
        this.id = id;
        this.name = name;
        this.baseLoadFactor = baseLoadFactor;
        this.hasHeatPump = hasHeatPump;
        this.heatPumpCapacityKw = heatPumpCapacityKw;
        this.hasPv = hasPv;
        this.pvCapacityKw = pvCapacityKw;
        this.hasEvCharger = hasEvCharger;
        this.evChargerPowerKw = evChargerPowerKw;
    }

    public double totalLoadKw() {
        return currentBaseLoadKw + currentHeatPumpLoadKw + currentEvLoadKw;
    }

    public double netKw() {
        return totalLoadKw() - currentPvGenerationKw;
    }

    /** Rolls the current tick's power values into the cumulative kWh meters. */
    public void accumulate(double tickHours) {
        cumulativeConsumptionKwh += totalLoadKw() * tickHours;
        cumulativeGenerationKwh += currentPvGenerationKw * tickHours;
    }

    // --- getters / setters -------------------------------------------------

    public int getId() { return id; }
    public String getName() { return name; }
    public double getBaseLoadFactor() { return baseLoadFactor; }

    public boolean isHasHeatPump() { return hasHeatPump; }
    public double getHeatPumpCapacityKw() { return heatPumpCapacityKw; }

    public boolean isHasPv() { return hasPv; }
    public double getPvCapacityKw() { return pvCapacityKw; }

    public boolean isHasEvCharger() { return hasEvCharger; }
    public double getEvChargerPowerKw() { return evChargerPowerKw; }

    public boolean isEvPluggedInToday() { return evPluggedInToday; }
    public void setEvPluggedInToday(boolean evPluggedInToday) { this.evPluggedInToday = evPluggedInToday; }

    public double getEvArrivalHour() { return evArrivalHour; }
    public void setEvArrivalHour(double evArrivalHour) { this.evArrivalHour = evArrivalHour; }

    public double getEvDepartureHour() { return evDepartureHour; }
    public void setEvDepartureHour(double evDepartureHour) { this.evDepartureHour = evDepartureHour; }

    public double getEvEnergyNeededKwh() { return evEnergyNeededKwh; }
    public void setEvEnergyNeededKwh(double evEnergyNeededKwh) { this.evEnergyNeededKwh = evEnergyNeededKwh; }

    public double getEvEnergyDeliveredKwh() { return evEnergyDeliveredKwh; }
    public void setEvEnergyDeliveredKwh(double evEnergyDeliveredKwh) { this.evEnergyDeliveredKwh = evEnergyDeliveredKwh; }

    public double getCurrentBaseLoadKw() { return currentBaseLoadKw; }
    public void setCurrentBaseLoadKw(double v) { this.currentBaseLoadKw = v; }

    public double getCurrentHeatPumpLoadKw() { return currentHeatPumpLoadKw; }
    public void setCurrentHeatPumpLoadKw(double v) { this.currentHeatPumpLoadKw = v; }

    public double getCurrentEvLoadKw() { return currentEvLoadKw; }
    public void setCurrentEvLoadKw(double v) { this.currentEvLoadKw = v; }

    public double getCurrentPvGenerationKw() { return currentPvGenerationKw; }
    public void setCurrentPvGenerationKw(double v) { this.currentPvGenerationKw = v; }

    public double getCumulativeConsumptionKwh() { return cumulativeConsumptionKwh; }
    public double getCumulativeGenerationKwh() { return cumulativeGenerationKwh; }
}
