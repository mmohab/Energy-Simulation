package com.energysim.model;

/**
 * A public EV charging point (e.g. on-street, supermarket car park, station
 * forecourt). Unlike a home charger, it isn't tied to one house — random
 * vehicles arrive and occupy it for a session, then leave.
 */
public class PublicCharger {

    private final int id;
    private final String name;
    private final double powerKw;

    private boolean occupied;
    private int remainingTicks;
    private double currentLoadKw;
    private double cumulativeEnergyKwh;

    public PublicCharger(int id, String name, double powerKw) {
        this.id = id;
        this.name = name;
        this.powerKw = powerKw;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public double getPowerKw() { return powerKw; }

    public boolean isOccupied() { return occupied; }
    public void setOccupied(boolean occupied) { this.occupied = occupied; }

    public int getRemainingTicks() { return remainingTicks; }
    public void setRemainingTicks(int remainingTicks) { this.remainingTicks = remainingTicks; }

    public double getCurrentLoadKw() { return currentLoadKw; }
    public void setCurrentLoadKw(double currentLoadKw) { this.currentLoadKw = currentLoadKw; }

    public double getCumulativeEnergyKwh() { return cumulativeEnergyKwh; }
    public void addCumulativeEnergyKwh(double kwh) { this.cumulativeEnergyKwh += kwh; }
}
