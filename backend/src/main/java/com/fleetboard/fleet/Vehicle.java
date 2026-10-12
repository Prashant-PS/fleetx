package com.fleetboard.fleet;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One vehicle as the assistant and agents see it: identity, live status, health, maintenance,
 * energy and compliance documents.
 */
public record Vehicle(
        String id,
        String regNo,
        String vin,
        String make,
        String model,
        String type,
        String fuel,
        int year,
        String depot,
        Driver driver,
        String status,
        Instant lastSeen,
        String lastLocation,
        long odometerKm,
        long previousOdometerKm,
        int km7d,
        double efficiency,
        int idlePct7d,
        List<FaultCode> faults,
        long nextServiceDueKm,
        LocalDate nextServiceDate,
        LocalDate lastServiceDate,
        boolean tyrePressureWarning,
        Integer fuelLevelPct,
        Integer adBlueLevelPct,
        Integer socPct,
        Integer batteryHealthPct,
        String chargingStatus,
        LocalDateTime nextDeparture,
        Integer requiredSocPct,
        List<FleetDocument> documents) {

    public boolean electric() {
        return "Electric".equals(fuel);
    }

    /** Efficiency unit for this vehicle's fuel type. */
    public String efficiencyUnit() {
        return electric() ? "km/kWh" : "CNG".equals(fuel) ? "km/kg" : "km/l";
    }

    public String label() {
        return regNo + " (" + make + " " + model + ")";
    }

    public record Driver(String id, String name, LocalDate licenceExpiry, int safetyScore) {
    }

    public record FaultCode(String code, String description, String severity, Instant since) {
    }

    /** A compliance document or commercial contract with an expiry date. */
    public record FleetDocument(String type, String reference, LocalDate expiry, boolean contract) {
    }
}
