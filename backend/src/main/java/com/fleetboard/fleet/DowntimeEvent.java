package com.fleetboard.fleet;

import java.time.Duration;
import java.time.Instant;

/**
 * A period when a vehicle could not work.
 *
 * @param type        UNPLANNED (breakdown or defect) or PLANNED (scheduled service)
 * @param cause       Engine, Aftertreatment, Cooling, Electrical, Battery & charging, Tyres, Brakes, Transmission, Service
 * @param end         null while the vehicle is still off the road
 * @param triageHours hours from the stop until the fault was diagnosed (unplanned only)
 */
public record DowntimeEvent(String id, String vehicleId, String type, String cause, String description,
                            Instant start, Instant end, double triageHours, String location, int repairCostInr) {

    public boolean ongoing() {
        return end == null;
    }

    public boolean unplanned() {
        return "UNPLANNED".equals(type);
    }

    /** Hours off the road, up to {@code now} for ongoing events. */
    public double hours(Instant now) {
        return Duration.between(start, end == null ? now : end).toMinutes() / 60.0;
    }

    /** Hours that fall inside [from, to]. */
    public double hoursWithin(Instant from, Instant to) {
        Instant s = start.isBefore(from) ? from : start;
        Instant e = end == null || end.isAfter(to) ? to : end;
        return e.isAfter(s) ? Duration.between(s, e).toMinutes() / 60.0 : 0;
    }
}
