package com.energysim.model;

public record PublicChargerSnapshot(
        int id,
        String name,
        double powerKw,
        boolean occupied,
        double currentLoadKw,
        double cumulativeEnergyKwh
) {
    public static PublicChargerSnapshot from(PublicCharger c) {
        return new PublicChargerSnapshot(
                c.getId(),
                c.getName(),
                c.getPowerKw(),
                c.isOccupied(),
                round(c.getCurrentLoadKw()),
                round(c.getCumulativeEnergyKwh())
        );
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
