package com.fleetboard.fleet;

import java.time.LocalDate;

/**
 * One tyre on a vehicle, as a tyre-pressure/tread inspection system would report it.
 *
 * @param position          e.g. "Steer L", "Drive 1 R outer"
 * @param axle              steer | drive | trailing
 * @param treadNewMm        tread depth when new
 * @param treadMm           shallowest current tread depth
 * @param irregularWearMm   inner vs outer shoulder difference (alignment indicator)
 * @param kmRun             km since fitted
 * @param newPriceInr       indicative price of a new tyre of this size and brand
 * @param retreadPriceInr   indicative retread price, or 0 when the size is not retreadable
 */
public record Tyre(
        String vehicleId,
        String position,
        String axle,
        String serial,
        String brand,
        String size,
        LocalDate fitted,
        int retreads,
        double treadNewMm,
        double treadMm,
        double irregularWearMm,
        long kmRun,
        int pressurePsi,
        int recommendedPsi,
        int temperatureC,
        int newPriceInr,
        int retreadPriceInr) {
}
