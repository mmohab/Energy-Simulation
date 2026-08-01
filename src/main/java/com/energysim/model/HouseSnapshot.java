package com.energysim.model;

/**
 * Immutable, JSON-friendly view of a house's current state. Sent to the
 * frontend on every tick.
 */
public record HouseSnapshot(
        int id,
        String name,
        boolean hasHeatPump,
        boolean hasPv,
        boolean hasEvCharger,
        double pvCapacityKw,
        boolean evCharging,
        double baseLoadKw,
        double heatPumpLoadKw,
        double evLoadKw,
        double pvGenerationKw,
        double totalLoadKw,
        double netKw,
        double cumulativeConsumptionKwh,
        double cumulativeGenerationKwh
) {
    public static HouseSnapshot from(House h) {
        boolean charging = h.isHasEvCharger() && h.getCurrentEvLoadKw() > 0.001;
        return new HouseSnapshot(
                h.getId(),
                h.getName(),
                h.isHasHeatPump(),
                h.isHasPv(),
                h.isHasEvCharger(),
                h.getPvCapacityKw(),
                charging,
                round(h.getCurrentBaseLoadKw()),
                round(h.getCurrentHeatPumpLoadKw()),
                round(h.getCurrentEvLoadKw()),
                round(h.getCurrentPvGenerationKw()),
                round(h.totalLoadKw()),
                round(h.netKw()),
                round(h.getCumulativeConsumptionKwh()),
                round(h.getCumulativeGenerationKwh())
        );
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
