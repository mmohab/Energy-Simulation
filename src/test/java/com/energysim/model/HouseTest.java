package com.energysim.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HouseTest {

    @Test
    void totalLoadIsSumOfBaseHeatPumpAndEv() {
        House house = new House(1, "Test House", 1.0, true, 6.0, true, 4.0, true, 7.4);
        house.setCurrentBaseLoadKw(0.5);
        house.setCurrentHeatPumpLoadKw(2.0);
        house.setCurrentEvLoadKw(7.4);
        house.setCurrentPvGenerationKw(3.0);

        assertThat(house.totalLoadKw()).isEqualTo(0.5 + 2.0 + 7.4);
        assertThat(house.netKw()).isEqualTo(0.5 + 2.0 + 7.4 - 3.0);
    }

    @Test
    void netIsExportWhenGenerationExceedsLoad() {
        House house = new House(1, "Solar House", 1.0, false, 0.0, true, 6.0, false, 0.0);
        house.setCurrentBaseLoadKw(0.3);
        house.setCurrentPvGenerationKw(4.0);

        assertThat(house.netKw()).isLessThan(0);
    }

    @Test
    void netIsZeroWhenNoAssetsAndNoLoadSet() {
        House house = new House(1, "Empty House", 1.0, false, 0.0, false, 0.0, false, 0.0);

        assertThat(house.totalLoadKw()).isZero();
        assertThat(house.netKw()).isZero();
    }

    @Test
    void accumulateAddsPowerTimesTimeToCumulativeMeters() {
        House house = new House(1, "Test House", 1.0, false, 0.0, true, 4.0, false, 0.0);
        house.setCurrentBaseLoadKw(1.0);
        house.setCurrentPvGenerationKw(0.5);

        house.accumulate(0.5); // half an hour

        assertThat(house.getCumulativeConsumptionKwh()).isEqualTo(0.5);
        assertThat(house.getCumulativeGenerationKwh()).isEqualTo(0.25);

        house.accumulate(0.5); // another half hour, same power levels

        assertThat(house.getCumulativeConsumptionKwh()).isEqualTo(1.0);
        assertThat(house.getCumulativeGenerationKwh()).isEqualTo(0.5);
    }

    @Test
    void cumulativeMetersStartAtZero() {
        House house = new House(1, "Fresh House", 1.0, true, 5.0, true, 5.0, true, 7.4);

        assertThat(house.getCumulativeConsumptionKwh()).isZero();
        assertThat(house.getCumulativeGenerationKwh()).isZero();
    }

    @Test
    void assetFlagsAndCapacitiesMatchConstructorArguments() {
        House house = new House(7, "No. 7 Elm Way", 1.2, true, 6.5, false, 0.0, true, 3.7);

        assertThat(house.getId()).isEqualTo(7);
        assertThat(house.getName()).isEqualTo("No. 7 Elm Way");
        assertThat(house.isHasHeatPump()).isTrue();
        assertThat(house.getHeatPumpCapacityKw()).isEqualTo(6.5);
        assertThat(house.isHasPv()).isFalse();
        assertThat(house.getPvCapacityKw()).isZero();
        assertThat(house.isHasEvCharger()).isTrue();
        assertThat(house.getEvChargerPowerKw()).isEqualTo(3.7);
    }
}
