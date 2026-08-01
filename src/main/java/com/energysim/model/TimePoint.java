package com.energysim.model;

/**
 * One aggregated data point in the neighbourhood's history, used to draw
 * the time-series chart on the frontend.
 */
public record TimePoint(
        long tick,
        int day,
        String timeLabel,
        double outdoorTempC,
        double totalDemandKw,
        double totalGenerationKw,
        double rawNetImportKw,
        double netImportKw
) {
}
