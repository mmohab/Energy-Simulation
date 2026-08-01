package com.energysim.service;

import com.energysim.config.NeighbourhoodConfig;
import com.energysim.model.HouseSnapshot;
import com.energysim.model.PublicChargerSnapshot;
import com.energysim.model.SimulationSnapshot;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimulationEngineTest {

    private NeighbourhoodConfig configWithSeed(long seed) {
        NeighbourhoodConfig config = new NeighbourhoodConfig();
        config.setSeed(seed);
        return config;
    }

    // ------------------------------------------------------------------
    // Neighbourhood generation
    // ------------------------------------------------------------------

    @Nested
    class Generation {

        @Test
        void generatesConfiguredNumberOfHouses() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setHouseCount(12);

            SimulationEngine engine = new SimulationEngine(config);

            assertThat(engine.currentSnapshot().houses()).hasSize(12);
        }

        @Test
        void generatesConfiguredNumberOfPublicChargersByDefault() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setPublicChargerCount(9);

            SimulationEngine engine = new SimulationEngine(config);
            SimulationSnapshot snapshot = engine.currentSnapshot();

            assertThat(snapshot.publicChargers()).hasSize(9);
            assertThat(snapshot.publicChargerCount()).isEqualTo(9);
        }

        @Test
        void publicChargerCountOfZeroMeansNoPublicChargers() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setPublicChargerCount(0);

            SimulationEngine engine = new SimulationEngine(config);

            assertThat(engine.currentSnapshot().publicChargers()).isEmpty();
        }

        @Test
        void explicitPublicChargerRosterOverridesCount() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setPublicChargerCount(9); // should be ignored
            config.setPublicChargers(List.of(
                    new NeighbourhoodConfig.PublicChargerConfig("Test Charger A", 22.0),
                    new NeighbourhoodConfig.PublicChargerConfig("Test Charger B", 50.0)
            ));

            SimulationEngine engine = new SimulationEngine(config);
            SimulationSnapshot snapshot = engine.currentSnapshot();

            assertThat(snapshot.publicChargers()).hasSize(2);
            assertThat(snapshot.publicChargers().get(0).name()).isEqualTo("Test Charger A");
            assertThat(snapshot.publicChargers().get(1).powerKw()).isEqualTo(50.0);
        }

        @Test
        void zeroAssetProbabilitiesProduceNoAssetsRegardlessOfSeed() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setHouseCount(50);
            config.setHeatPumpProbability(0.0);
            config.setPvProbability(0.0);
            config.setEvChargerProbability(0.0);

            SimulationEngine engine = new SimulationEngine(config);
            SimulationSnapshot snapshot = engine.currentSnapshot();

            assertThat(snapshot.assetCountHeatPump()).isZero();
            assertThat(snapshot.assetCountPv()).isZero();
            assertThat(snapshot.assetCountEvCharger()).isZero();
            assertThat(snapshot.houses()).allSatisfy(h -> {
                assertThat(h.hasHeatPump()).isFalse();
                assertThat(h.hasPv()).isFalse();
                assertThat(h.hasEvCharger()).isFalse();
            });
        }

        @Test
        void fullAssetProbabilitiesGiveEveryHouseEveryAsset() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setHouseCount(20);
            config.setHeatPumpProbability(1.0);
            config.setPvProbability(1.0);
            config.setEvChargerProbability(1.0);

            SimulationEngine engine = new SimulationEngine(config);
            SimulationSnapshot snapshot = engine.currentSnapshot();

            assertThat(snapshot.assetCountHeatPump()).isEqualTo(20);
            assertThat(snapshot.assetCountPv()).isEqualTo(20);
            assertThat(snapshot.assetCountEvCharger()).isEqualTo(20);
        }
    }

    // ------------------------------------------------------------------
    // Reproducibility
    // ------------------------------------------------------------------

    @Nested
    class Reproducibility {

        @Test
        void sameSeedProducesIdenticalNeighbourhoodsAndTrajectories() {
            SimulationEngine engineA = new SimulationEngine(configWithSeed(777L));
            SimulationEngine engineB = new SimulationEngine(configWithSeed(777L));

            for (int i = 0; i < 20; i++) {
                engineA.step();
                engineB.step();
            }

            SimulationSnapshot snapshotA = engineA.currentSnapshot();
            SimulationSnapshot snapshotB = engineB.currentSnapshot();

            assertThat(snapshotA.totalDemandKw()).isEqualTo(snapshotB.totalDemandKw());
            assertThat(snapshotA.totalGenerationKw()).isEqualTo(snapshotB.totalGenerationKw());
            assertThat(namesAndNets(snapshotA)).isEqualTo(namesAndNets(snapshotB));
        }

        @Test
        void differentSeedsTypicallyProduceDifferentNeighbourhoods() {
            SimulationEngine engineA = new SimulationEngine(configWithSeed(1L));
            SimulationEngine engineB = new SimulationEngine(configWithSeed(2L));

            assertThat(namesAndNets(engineA.currentSnapshot()))
                    .isNotEqualTo(namesAndNets(engineB.currentSnapshot()));
        }

        @Test
        void unsetSeedIsRandomButRecordedBackOntoConfig() {
            NeighbourhoodConfig config = new NeighbourhoodConfig(); // no seed set
            assertThat(config.getSeed()).isNull();

            SimulationEngine engine = new SimulationEngine(config);

            assertThat(engine.getConfig().getSeed()).isNotNull();
        }

        private List<String> namesAndNets(SimulationSnapshot snapshot) {
            return snapshot.houses().stream()
                    .map(h -> h.name() + ":" + h.netKw())
                    .toList();
        }
    }

    // ------------------------------------------------------------------
    // Time advancement
    // ------------------------------------------------------------------

    @Nested
    class TimeAdvancement {

        @Test
        void stepAdvancesTickByOne() {
            SimulationEngine engine = new SimulationEngine(configWithSeed(1L));
            long before = engine.currentSnapshot().tick();

            engine.step();

            assertThat(engine.currentSnapshot().tick()).isEqualTo(before + 1);
        }

        @Test
        void tenMinuteStepsProduce144TicksPerDay() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setStepMinutes(10);
            SimulationEngine engine = new SimulationEngine(config);

            for (int i = 0; i < 143; i++) engine.step();
            assertThat(engine.currentSnapshot().day()).isEqualTo(1);

            engine.step(); // the 144th tick rolls over into day 2
            assertThat(engine.currentSnapshot().day()).isEqualTo(2);
        }

        @Test
        void oddStepSizeStillSumsToExactly24HoursPerDay() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setStepMinutes(7); // does not evenly divide 1440
            SimulationEngine engine = new SimulationEngine(config);

            int startDay = engine.currentSnapshot().day();
            int ticksTaken = 0;
            SimulationSnapshot snapshot;
            do {
                engine.step();
                snapshot = engine.currentSnapshot();
                ticksTaken++;
            } while (snapshot.day() == startDay);

            // round(1440 / 7) = round(205.71...) = 206
            assertThat(ticksTaken).isEqualTo(206);
        }

        @Test
        void stepMinutesIsClampedToOneToSixty() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setStepMinutes(0);
            SimulationEngine engineTooSmall = new SimulationEngine(config);
            assertThat(engineTooSmall.getConfig().getStepMinutes()).isEqualTo(1);

            NeighbourhoodConfig config2 = configWithSeed(1L);
            config2.setStepMinutes(500);
            SimulationEngine engineTooBig = new SimulationEngine(config2);
            assertThat(engineTooBig.getConfig().getStepMinutes()).isEqualTo(60);
        }

        @Test
        void resetRestartsAtDayOneMidnight() {
            SimulationEngine engine = new SimulationEngine(configWithSeed(1L));
            for (int i = 0; i < 50; i++) engine.step();
            assertThat(engine.currentSnapshot().tick()).isGreaterThan(0);

            SimulationSnapshot snapshot = engine.reset();

            assertThat(snapshot.tick()).isZero();
            assertThat(snapshot.day()).isEqualTo(1);
            assertThat(snapshot.timeLabel()).isEqualTo("00:00");
        }
    }

    // ------------------------------------------------------------------
    // Configurable start date / time
    // ------------------------------------------------------------------

    @Nested
    class StartDateAndTime {

        @Test
        void defaultsToTodayAtMidnight() {
            SimulationEngine engine = new SimulationEngine(configWithSeed(1L));
            SimulationSnapshot snapshot = engine.currentSnapshot();

            assertThat(snapshot.timeLabel()).isEqualTo("00:00");
            assertThat(snapshot.simulatedDate()).isEqualTo(java.time.LocalDate.now().toString());
        }

        @Test
        void honorsConfiguredStartDateAndTimeFromTickZero() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setStartDate("2026-06-15");
            config.setStartTime("08:30");

            SimulationEngine engine = new SimulationEngine(config);
            SimulationSnapshot snapshot = engine.currentSnapshot();

            assertThat(snapshot.simulatedDate()).isEqualTo("2026-06-15");
            assertThat(snapshot.timeLabel()).isEqualTo("08:30");
            assertThat(snapshot.day()).isEqualTo(1);
        }

        @Test
        void invalidStartDateFallsBackToToday() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setStartDate("not-a-date");

            SimulationEngine engine = new SimulationEngine(config);

            assertThat(engine.currentSnapshot().simulatedDate())
                    .isEqualTo(java.time.LocalDate.now().toString());
        }

        @Test
        void invalidStartTimeFallsBackToMidnight() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setStartTime("not-a-time");

            SimulationEngine engine = new SimulationEngine(config);

            assertThat(engine.currentSnapshot().timeLabel()).isEqualTo("00:00");
        }

        @Test
        void resolvedStartDateAndTimeAreEchoedBackOntoConfig() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setStartTime("14:00");
            // startDate left unset -> should resolve to today and be echoed back.

            SimulationEngine engine = new SimulationEngine(config);

            assertThat(engine.getConfig().getStartTime()).isEqualTo("14:00");
            assertThat(engine.getConfig().getStartDate())
                    .isEqualTo(java.time.LocalDate.now().toString());
        }

        @Test
        void dayAdvancesCorrectlyWhenStartingLateInTheDay() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setStepMinutes(60); // hourly ticks, easy to reason about
            config.setStartTime("23:00");

            SimulationEngine engine = new SimulationEngine(config);
            assertThat(engine.currentSnapshot().day()).isEqualTo(1);
            assertThat(engine.currentSnapshot().timeLabel()).isEqualTo("23:00");

            engine.step(); // one hour later -> rolls into 00:00 the next day

            assertThat(engine.currentSnapshot().timeLabel()).isEqualTo("00:00");
            assertThat(engine.currentSnapshot().day()).isEqualTo(2);
        }

        @Test
        void startTimeOffsetDoesNotAffectCumulativeEnergyAtTickZero() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setStartTime("18:00"); // evening start — heat pumps/EVs likely already drawing power

            SimulationEngine engine = new SimulationEngine(config);
            SimulationSnapshot snapshot = engine.currentSnapshot();

            // Regardless of how much *instantaneous* power is showing at tick 0,
            // no simulated time has elapsed yet, so cumulative meters must be zero.
            assertThat(snapshot.cumulativeDemandKwh()).isZero();
            assertThat(snapshot.cumulativeGenerationKwh()).isZero();
        }
    }

    // ------------------------------------------------------------------
    // Energy accounting
    // ------------------------------------------------------------------

    @Nested
    class EnergyAccounting {

        @Test
        void totalDemandEqualsSumOfHouseLoadsPlusPublicChargers() {
            SimulationEngine engine = new SimulationEngine(configWithSeed(42L));
            for (int i = 0; i < 30; i++) engine.step();
            SimulationSnapshot snapshot = engine.currentSnapshot();

            double expectedHouseLoad = snapshot.houses().stream()
                    .mapToDouble(HouseSnapshot::totalLoadKw)
                    .sum();
            double expectedPublicLoad = snapshot.publicChargers().stream()
                    .mapToDouble(PublicChargerSnapshot::currentLoadKw)
                    .sum();

            assertThat(snapshot.totalDemandKw())
                    .isCloseTo(expectedHouseLoad + expectedPublicLoad, offset(0.05));
        }

        @Test
        void netImportEqualsDemandMinusGeneration() {
            SimulationEngine engine = new SimulationEngine(configWithSeed(42L));
            for (int i = 0; i < 60; i++) engine.step();
            SimulationSnapshot snapshot = engine.currentSnapshot();

            assertThat(snapshot.netImportKw())
                    .isCloseTo(snapshot.totalDemandKw() - snapshot.totalGenerationKw(), offset(0.05));
        }

        @Test
        void cumulativeMetersNeverDecrease() {
            SimulationEngine engine = new SimulationEngine(configWithSeed(3L));
            double prevDemand = 0, prevGen = 0;

            for (int i = 0; i < 100; i++) {
                engine.step();
                SimulationSnapshot s = engine.currentSnapshot();
                assertThat(s.cumulativeDemandKwh()).isGreaterThanOrEqualTo(prevDemand);
                assertThat(s.cumulativeGenerationKwh()).isGreaterThanOrEqualTo(prevGen);
                prevDemand = s.cumulativeDemandKwh();
                prevGen = s.cumulativeGenerationKwh();
            }
        }

        @Test
        void houseLevelMetersSumCloseToNeighbourhoodHouseCumulative() {
            NeighbourhoodConfig config = configWithSeed(9L);
            config.setHouseCount(10);
            SimulationEngine engine = new SimulationEngine(config);
            for (int i = 0; i < 50; i++) engine.step();

            SimulationSnapshot snapshot = engine.currentSnapshot();
            double sumOfHouseMeters = snapshot.houses().stream()
                    .mapToDouble(HouseSnapshot::cumulativeConsumptionKwh)
                    .sum();
            double sumOfPublicChargerMeters = snapshot.publicChargers().stream()
                    .mapToDouble(PublicChargerSnapshot::cumulativeEnergyKwh)
                    .sum();
            double neighbourhoodHouseCumulative = snapshot.cumulativeDemandKwh() - sumOfPublicChargerMeters;

            assertThat(sumOfHouseMeters).isCloseTo(neighbourhoodHouseCumulative, offset(0.5));
        }

        @Test
        void cumulativeMetersStayAtZeroBeforeAnyStep() {
            SimulationEngine engine = new SimulationEngine(configWithSeed(1L));
            SimulationSnapshot snapshot = engine.currentSnapshot();

            assertThat(snapshot.cumulativeDemandKwh()).isZero();
            assertThat(snapshot.cumulativeGenerationKwh()).isZero();
        }
    }

    // ------------------------------------------------------------------
    // Physical model sanity bounds
    // ------------------------------------------------------------------

    @Nested
    class PhysicalModelBounds {

        @Test
        void heatPumpLoadStaysWithinSaneBoundsAcrossManyTicks() {
            NeighbourhoodConfig config = configWithSeed(5L);
            config.setHeatPumpProbability(1.0);
            config.setHouseCount(5);
            SimulationEngine engine = new SimulationEngine(config);

            for (int i = 0; i < 200; i++) {
                engine.step();
                for (HouseSnapshot h : engine.currentSnapshot().houses()) {
                    assertThat(h.heatPumpLoadKw()).isBetween(0.0, 15.0);
                }
            }
        }

        @Test
        void pvGenerationNeverExceedsRatedCapacityByMuch() {
            NeighbourhoodConfig config = configWithSeed(5L);
            config.setPvProbability(1.0);
            config.setHouseCount(5);
            SimulationEngine engine = new SimulationEngine(config);

            for (int i = 0; i < 200; i++) {
                engine.step();
                for (HouseSnapshot h : engine.currentSnapshot().houses()) {
                    assertThat(h.pvGenerationKw()).isLessThanOrEqualTo(h.pvCapacityKw() * 1.1);
                    assertThat(h.pvGenerationKw()).isGreaterThanOrEqualTo(0.0);
                }
            }
        }

        @Test
        void evHomeChargerNeverExceedsItsRatedPower() {
            NeighbourhoodConfig config = configWithSeed(5L);
            config.setEvChargerProbability(1.0);
            config.setHouseCount(5);
            config.setHomeEvChargerPowerOptionsKw(List.of(7.4));
            SimulationEngine engine = new SimulationEngine(config);

            for (int i = 0; i < 200; i++) {
                engine.step();
                for (HouseSnapshot h : engine.currentSnapshot().houses()) {
                    assertThat(h.evLoadKw()).isLessThanOrEqualTo(7.4 + 0.01);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Config validation / normalization
    // ------------------------------------------------------------------

    @Nested
    class ConfigValidation {

        @Test
        void clampsOutOfRangeProbabilities() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setHeatPumpProbability(5.0);  // invalid, > 1.0
            config.setPvProbability(-3.0);       // invalid, < 0.0

            SimulationEngine engine = new SimulationEngine(config);

            assertThat(engine.getConfig().getHeatPumpProbability()).isEqualTo(1.0);
            assertThat(engine.getConfig().getPvProbability()).isEqualTo(0.0);
        }

        @Test
        void clampsHouseCountToAtLeastOne() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setHouseCount(0);

            SimulationEngine engine = new SimulationEngine(config);

            assertThat(engine.getConfig().getHouseCount()).isEqualTo(1);
            assertThat(engine.currentSnapshot().houses()).hasSize(1);
        }

        @Test
        void clampsHouseCountToAtMostFiveHundred() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setHouseCount(10_000);

            SimulationEngine engine = new SimulationEngine(config);

            assertThat(engine.getConfig().getHouseCount()).isEqualTo(500);
        }

        @Test
        void swapsInvertedSizeRanges() {
            NeighbourhoodConfig config = configWithSeed(1L);
            config.setHeatPumpKwMin(9.0);
            config.setHeatPumpKwMax(3.0); // inverted

            new SimulationEngine(config);

            assertThat(config.getHeatPumpKwMax()).isGreaterThanOrEqualTo(config.getHeatPumpKwMin());
        }

        @Test
        void applyConfigUpdateMergesPartiallyAndRegenerates() {
            SimulationEngine engine = new SimulationEngine(configWithSeed(1L));

            SimulationSnapshot snapshot = engine.applyConfigUpdate(Map.of("houseCount", 7));

            assertThat(engine.getConfig().getHouseCount()).isEqualTo(7);
            assertThat(snapshot.houses()).hasSize(7);
            // Fields not mentioned in the update keep their previous value.
            assertThat(engine.getConfig().getStepMinutes()).isEqualTo(10);
        }

        @Test
        void applyConfigUpdateRejectsGarbageWithIllegalArgumentException() {
            SimulationEngine engine = new SimulationEngine(configWithSeed(1L));

            assertThrows(IllegalArgumentException.class, () ->
                    engine.applyConfigUpdate(Map.of("houseCount", "not-a-number")));
        }
    }
}
