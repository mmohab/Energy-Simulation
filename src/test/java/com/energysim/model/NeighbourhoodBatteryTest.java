package com.energysim.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class NeighbourhoodBatteryTest {

    @Test
    void dischargeIsLimitedByAvailableEnergyAndOneWayEfficiency() {
        NeighbourhoodBattery battery = new NeighbourhoodBattery(10, 20, 20, 0.81, 100);

        double deliveredKw = battery.dispatch(20, 1.0);

        // sqrt(0.81) = 0.9, so 10 kWh stored can supply 9 kWh to the grid.
        assertThat(deliveredKw).isCloseTo(9.0, offset(0.0001));
        assertThat(battery.getStateOfChargeKwh()).isZero();
    }

    @Test
    void chargingStoresEnergyAfterEfficiencyLoss() {
        NeighbourhoodBattery battery = new NeighbourhoodBattery(100, 20, 20, 0.81, 0);

        battery.dispatch(-10, 1.0);

        assertThat(battery.getCurrentPowerKw()).isEqualTo(-10.0);
        assertThat(battery.getStateOfChargeKwh()).isCloseTo(9.0, offset(0.0001));
    }
}
