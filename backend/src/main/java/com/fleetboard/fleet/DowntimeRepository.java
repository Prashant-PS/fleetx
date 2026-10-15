package com.fleetboard.fleet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Demo downtime history (last 90 days) for every vehicle, consistent with its current state: a vehicle in the
 * workshop or stopped with a critical fault has an ongoing unplanned event. Replace {@link #generate} with
 * workshop/job-card and breakdown-call data to use real history.
 */
@Component
public class DowntimeRepository {

    private static final Logger log = LoggerFactory.getLogger(DowntimeRepository.class);
    public static final int HISTORY_DAYS = 90;

    private record Cause(String name, String description, boolean ev, int minHours, int maxHours, int minCost, int maxCost) {
    }

    private static final List<Cause> CAUSES = List.of(
            new Cause("Engine", "Fuel injector / fuel system failure", false, 18, 72, 18_000, 85_000),
            new Cause("Aftertreatment", "DPF/SCR fault, engine derated", false, 10, 40, 12_000, 60_000),
            new Cause("Cooling", "Coolant leak, engine overheating", false, 8, 30, 6_000, 28_000),
            new Cause("Electrical", "Alternator / wiring fault, no start", true, 6, 20, 4_000, 18_000),
            new Cause("Battery & charging", "High-voltage battery or charger fault", true, 12, 60, 10_000, 90_000),
            new Cause("Tyres", "Tyre blow-out on the road", true, 4, 14, 26_000, 34_000),
            new Cause("Brakes", "Brake air leak / worn linings", true, 6, 24, 5_000, 22_000),
            new Cause("Transmission", "Clutch / gearbox failure", false, 24, 96, 25_000, 1_10_000));

    private final List<DowntimeEvent> events;

    public DowntimeRepository(FleetRepository fleet) {
        List<DowntimeEvent> all = new ArrayList<>();
        Instant now = Instant.now();
        for (Vehicle v : fleet.all()) {
            all.addAll(generate(v, now));
        }
        all.sort(Comparator.comparing(DowntimeEvent::start).reversed());
        this.events = Collections.unmodifiableList(all);
        log.info("Demo downtime history ready: {} events", events.size());
    }

    public List<DowntimeEvent> all() {
        return events;
    }

    public List<DowntimeEvent> forVehicle(String vehicleId) {
        return events.stream().filter(e -> e.vehicleId().equals(vehicleId)).toList();
    }

    private static List<DowntimeEvent> generate(Vehicle v, Instant now) {
        Random rnd = new Random(v.id().hashCode() * 17L + 3);
        List<DowntimeEvent> out = new ArrayList<>();
        int age = java.time.LocalDate.now().getYear() - v.year();
        List<Cause> causes = CAUSES.stream().filter(c -> c.ev() || !v.electric()).toList();
        if (v.electric()) {
            causes = new ArrayList<>(causes);
            causes.add(CAUSES.get(4));           // EVs: battery and charging issues are the most common
        }

        // Planned service: roughly one every 45 days, 6-14 hours in the workshop.
        for (int day = 10 + rnd.nextInt(30); day < HISTORY_DAYS; day += 40 + rnd.nextInt(15)) {
            Instant start = now.minus(Duration.ofDays(day)).minus(Duration.ofHours(rnd.nextInt(10)));
            Instant end = start.plus(Duration.ofHours(6 + rnd.nextInt(9)));
            out.add(new DowntimeEvent(v.id() + "-P" + day, v.id(), "PLANNED", "Service", "Scheduled service",
                    start, end, 0, v.depot() + " dealer workshop", 8_000 + rnd.nextInt(14_000)));
        }

        // Unplanned breakdowns: older and harder-working vehicles break down more often.
        int breakdowns = rnd.nextInt(2) + (age >= 5 ? 1 : 0) + (v.km7d() > 2_200 ? 1 : 0);
        for (int b = 0; b < breakdowns; b++) {
            Cause c = causes.get(rnd.nextInt(causes.size()));
            int day = 3 + rnd.nextInt(HISTORY_DAYS - 3);
            Instant start = now.minus(Duration.ofDays(day)).minus(Duration.ofHours(rnd.nextInt(20)));
            Instant end = start.plus(Duration.ofHours(c.minHours() + rnd.nextInt(c.maxHours() - c.minHours() + 1)));
            boolean roadside = rnd.nextBoolean();
            out.add(new DowntimeEvent(v.id() + "-U" + day, v.id(), "UNPLANNED", c.name(), c.description(), start, end,
                    1 + rnd.nextInt(roadside ? 10 : 5) + rnd.nextDouble(), roadside ? "Roadside" : v.depot() + " depot",
                    c.minCost() + rnd.nextInt(c.maxCost() - c.minCost() + 1)));
        }

        // Ongoing events that match the vehicle's current state.
        boolean critical = v.faults().stream().anyMatch(f -> f.severity().equals("Critical"));
        if (v.status().equals("In workshop") || critical) {
            Vehicle.FaultCode f = v.faults().stream().filter(x -> x.severity().equals("Critical")).findFirst()
                    .orElse(v.faults().isEmpty() ? null : v.faults().get(0));
            String cause = f == null ? "Engine" : f.code().startsWith("B") ? "Battery & charging"
                    : f.code().equals("P0217") ? "Cooling" : "Engine";
            Instant start = f == null ? now.minus(Duration.ofHours(30)) : f.since();
            out.add(new DowntimeEvent(v.id() + "-NOW", v.id(), "UNPLANNED", cause,
                    f == null ? "Under repair" : f.code() + " " + f.description(), start, null,
                    v.status().equals("In workshop") ? 6.5 : 0, v.status().equals("In workshop") ? v.depot()
                    + " dealer workshop" : "Roadside, awaiting workshop", 0));
        }
        return out;
    }
}
