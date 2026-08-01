package com.energysim.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicChargerTest {

    @Test
    void startsUnoccupiedWithZeroLoadAndZeroCumulative() {
        PublicCharger charger = new PublicCharger(1, "Test Charger", 50.0);

        assertThat(charger.isOccupied()).isFalse();
        assertThat(charger.getCurrentLoadKw()).isZero();
        assertThat(charger.getCumulativeEnergyKwh()).isZero();
        assertThat(charger.getName()).isEqualTo("Test Charger");
        assertThat(charger.getPowerKw()).isEqualTo(50.0);
    }

    @Test
    void addCumulativeEnergyAccumulatesAcrossCalls() {
        PublicCharger charger = new PublicCharger(1, "Test Charger", 50.0);

        charger.addCumulativeEnergyKwh(5.0);
        charger.addCumulativeEnergyKwh(2.5);

        assertThat(charger.getCumulativeEnergyKwh()).isEqualTo(7.5);
    }

    @Test
    void occupiedAndLoadStateCanBeSetIndependently() {
        PublicCharger charger = new PublicCharger(2, "Rapid Hub", 50.0);

        charger.setOccupied(true);
        charger.setCurrentLoadKw(48.5);
        charger.setRemainingTicks(4);

        assertThat(charger.isOccupied()).isTrue();
        assertThat(charger.getCurrentLoadKw()).isEqualTo(48.5);
        assertThat(charger.getRemainingTicks()).isEqualTo(4);
    }
}
