package com.energysim.model;

/**
 * State of the shared neighbourhood battery. Positive power means the battery
 * is discharging to the neighbourhood; negative power means it is charging.
 */
public class NeighbourhoodBattery {
    private final double capacityKwh;
    private final double maxChargePowerKw;
    private final double maxDischargePowerKw;
    private final double roundTripEfficiency;
    private double stateOfChargeKwh;
    private double currentPowerKw;
    private double cumulativeChargedKwh;
    private double cumulativeDischargedKwh;

    public NeighbourhoodBattery(double capacityKwh, double maxChargePowerKw,
                                double maxDischargePowerKw, double roundTripEfficiency,
                                double initialSocPercent) {
        this.capacityKwh = capacityKwh;
        this.maxChargePowerKw = maxChargePowerKw;
        this.maxDischargePowerKw = maxDischargePowerKw;
        this.roundTripEfficiency = roundTripEfficiency;
        this.stateOfChargeKwh = capacityKwh * initialSocPercent / 100.0;
    }

    /** Applies a requested grid-side power, constrained by power, energy and efficiency limits. */
    public double dispatch(double requestedPowerKw, double tickHours) {
        double oneWayEfficiency = Math.sqrt(roundTripEfficiency);
        if (requestedPowerKw > 0) {
            double actual = Math.min(requestedPowerKw,
                    Math.min(maxDischargePowerKw, stateOfChargeKwh * oneWayEfficiency / tickHours));
            stateOfChargeKwh -= actual * tickHours / oneWayEfficiency;
            cumulativeDischargedKwh += actual * tickHours;
            currentPowerKw = actual;
        } else if (requestedPowerKw < 0) {
            double requestedCharge = -requestedPowerKw;
            double actual = Math.min(requestedCharge,
                    Math.min(maxChargePowerKw, (capacityKwh - stateOfChargeKwh) / (oneWayEfficiency * tickHours)));
            stateOfChargeKwh += actual * tickHours * oneWayEfficiency;
            cumulativeChargedKwh += actual * tickHours;
            currentPowerKw = -actual;
        } else {
            currentPowerKw = 0.0;
        }
        return currentPowerKw;
    }

    public double getCapacityKwh() { return capacityKwh; }
    public double getMaxChargePowerKw() { return maxChargePowerKw; }
    public double getMaxDischargePowerKw() { return maxDischargePowerKw; }
    public double getRoundTripEfficiency() { return roundTripEfficiency; }
    public double getStateOfChargeKwh() { return stateOfChargeKwh; }
    public double getStateOfChargePercent() { return capacityKwh == 0 ? 0 : stateOfChargeKwh / capacityKwh * 100.0; }
    public double getCurrentPowerKw() { return currentPowerKw; }
    public double getCumulativeChargedKwh() { return cumulativeChargedKwh; }
    public double getCumulativeDischargedKwh() { return cumulativeDischargedKwh; }
}
