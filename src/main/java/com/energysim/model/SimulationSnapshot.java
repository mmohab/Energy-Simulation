package com.energysim.model;

import java.util.List;

/**
 * Full state of the simulation returned to the frontend: current
 * simulated date/time, weather & season context, live neighbourhood
 * totals, cumulative energy meters since the simulation started, and a
 * rolling window of history for charting.
 */
public record SimulationSnapshot(
        long tick,
        int day,
        int stepMinutes,
        String simulatedDate,
        String timeLabel,
        String season,
        String monthName,
        int dayOfYear,

        double outdoorTempC,
        double cloudFactor,
        String sunriseLabel,
        String sunsetLabel,

        double totalDemandKw,
        double totalGenerationKw,
        double netImportKw,
        double publicChargerLoadKw,

        int housesImporting,
        int housesExporting,

        int assetCountHeatPump,
        int assetCountPv,
        int assetCountEvCharger,
        int publicChargerCount,

        double cumulativeBaseLoadKwh,
        double cumulativeHeatPumpKwh,
        double cumulativeEvHomeKwh,
        double cumulativeEvPublicKwh,
        double cumulativePvKwh,
        double cumulativeDemandKwh,
        double cumulativeGenerationKwh,
        double cumulativeNetImportKwh,

        List<HouseSnapshot> houses,
        List<PublicChargerSnapshot> publicChargers,
        List<TimePoint> history
) {
}
