package com.fleetboard.agents;

import com.fleetboard.agents.AgentDefinition.Check;
import com.fleetboard.fleet.FleetRepository;
import com.fleetboard.fleet.Vehicle;
import com.fleetboard.fleet.Vehicle.FleetDocument;
import com.fleetboard.store.Store;
import com.fleetboard.tyres.TyreAnalytics;
import com.fleetboard.tyres.TyreAnalytics.TyreView;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Rule-based fleet agents. They work without any AI model; the assistant uses them as tools and
 * schedules run them unattended.
 */
@Service
public class AgentService {

    public static final String HYGIENE = "fleet-hygiene";
    public static final String COMPLIANCE = "compliance";
    public static final String OPERATIONS = "vehicle-operations";
    public static final String EV = "ev-operations";
    public static final String REPORTING = "reporting";
    public static final String TYRES = "tyre-analytics";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public static final List<AgentDefinition> AGENTS = List.of(
            new AgentDefinition(HYGIENE, "Fleet Hygiene", "Finds and fixes data issues.", "hygiene", List.of(
                    new Check("missing-vin", "Missing VIN"),
                    new Check("duplicate-registration", "Duplicate registration numbers"),
                    new Check("odometer-rollback", "Odometer readings going backwards"),
                    new Check("stale-gps", "No GPS position for over 72 hours"),
                    new Check("no-driver", "Vehicle without an assigned driver"),
                    new Check("implausible-efficiency", "Implausible fuel/energy efficiency"),
                    new Check("missing-service-date", "Missing next service date"))),
            new AgentDefinition(COMPLIANCE, "Compliance", "Tracks expirations and deadlines.", "compliance", List.of(
                    new Check("expired-document", "Expired documents"),
                    new Check("expiring-document", "Documents expiring within 30 days"),
                    new Check("expiring-contract", "Contracts and subscriptions expiring within 60 days"),
                    new Check("licence-expiry", "Driver licences expiring within 30 days"))),
            new AgentDefinition(OPERATIONS, "Vehicle Operations", "Monitors exceptions and recommends actions.", "operations", List.of(
                    new Check("active-fault", "Active fault codes"),
                    new Check("service-overdue", "Service overdue"),
                    new Check("service-due", "Service due within 7 days or 1,000 km"),
                    new Check("high-idle", "Idling above 25% of engine time"),
                    new Check("low-fuel", "Fuel below 15%"),
                    new Check("low-adblue", "AdBlue below 10%"),
                    new Check("tyre-pressure", "Tyre pressure warnings"),
                    new Check("offline", "Vehicle offline for over 24 hours"))),
            new AgentDefinition(EV, "EV Operations", "Supports charging readiness and interventions.", "ev", List.of(
                    new Check("not-departure-ready", "Not enough charge for next departure"),
                    new Check("charger-fault", "Charger faults"),
                    new Check("battery-health", "Battery health below 80%"),
                    new Check("low-soc", "State of charge below 25%"))),
            new AgentDefinition(TYRES, "Tire Analytics", "Tracks tread life, pressure, emissions and tyre costs.", "tyre", List.of(
                    new Check("tyre-replace-now", "Tyres worn to the removal limit"),
                    new Check("tyre-due-soon", "Tyres to replace within 30 days"),
                    new Check("tyre-under-inflated", "Tyres 10% or more below recommended pressure"),
                    new Check("tyre-irregular-wear", "Irregular wear (alignment)"),
                    new Check("tyre-hot", "Tyres running hot"))),
            new AgentDefinition(REPORTING, "Reporting", "Creates recurring reports and insights.", "reporting", List.of(
                    new Check("weekly-report", "Weekly fleet performance report"))));

    /** Optional narrowing used by custom agents. Null or blank fields match everything. */
    public record VehicleFilter(String fuel, String type, String depot, String make) {

        public static final VehicleFilter ALL = new VehicleFilter(null, null, null, null);

        Predicate<Vehicle> predicate() {
            return v -> matches(fuel, v.fuel()) && matches(type, v.type()) && matches(depot, v.depot())
                    && matches(make, v.make());
        }

        private static boolean matches(String wanted, String actual) {
            return wanted == null || wanted.isBlank() || wanted.equalsIgnoreCase(actual);
        }
    }

    public record AgentResult(String agentId, String name, String summary, Map<String, Long> counts,
                              List<Finding> findings, Instant generatedAt, String markdown) {
    }

    public record RiskEntry(Vehicle vehicle, int score, List<String> reasons) {
    }

    private final FleetRepository fleet;
    private final Store store;
    private final TyreAnalytics tyres;

    public AgentService(FleetRepository fleet, Store store, TyreAnalytics tyres) {
        this.fleet = fleet;
        this.store = store;
        this.tyres = tyres;
    }

    public List<AgentDefinition> definitions() {
        return AGENTS;
    }

    public AgentDefinition definition(String id) {
        return AGENTS.stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + id));
    }

    /** Pseudo-agent: the most urgent findings from every agent. */
    public static final String ATTENTION = "attention";

    public AgentResult run(String agentId) {
        if (agentId.equals(ATTENTION)) {
            return attention(25);
        }
        AgentDefinition def = definition(agentId);
        if (agentId.equals(REPORTING)) {
            String report = weeklyReport(VehicleFilter.ALL);
            return new AgentResult(agentId, def.name(), "Weekly fleet report for " + fleet.all().size() + " vehicles.",
                    Map.of(), List.of(), Instant.now(), report);
        }
        Set<String> checks = def.checks().stream().map(Check::id).collect(Collectors.toSet());
        return result(agentId, def.name(), findings(checks, VehicleFilter.ALL));
    }

    /** Runs any mix of checks (from any agents) over a filtered set of vehicles. */
    public AgentResult runChecks(String id, String name, Set<String> checks, VehicleFilter filter) {
        if (checks.contains("weekly-report")) {
            Set<String> others = checks.stream().filter(c -> !c.equals("weekly-report")).collect(Collectors.toSet());
            List<Finding> found = findings(others, filter);
            String md = weeklyReport(filter) + (found.isEmpty() ? "" : "\n\n## Findings\n\n" + table(found));
            return new AgentResult(id, name, summary(found), counts(found), found, Instant.now(), md);
        }
        return result(id, name, findings(checks, filter));
    }

    /** Everything HIGH severity across agents, then MEDIUM, capped. */
    public AgentResult attention(int limit) {
        Set<String> all = AGENTS.stream().flatMap(a -> a.checks().stream()).map(Check::id)
                .filter(c -> !c.equals("weekly-report")).collect(Collectors.toSet());
        List<Finding> found = findings(all, VehicleFilter.ALL).stream()
                .filter(f -> !f.severity().equals("LOW"))
                .limit(limit)
                .toList();
        return result("attention", "Needs attention today", found);
    }

    private AgentResult result(String id, String name, List<Finding> found) {
        return new AgentResult(id, name, summary(found), counts(found), found, Instant.now(),
                found.isEmpty() ? "No issues found." : table(found));
    }

    private static Map<String, Long> counts(List<Finding> found) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of("HIGH", "MEDIUM", "LOW")) {
            counts.put(s, found.stream().filter(f -> f.severity().equals(s)).count());
        }
        return counts;
    }

    private static String summary(List<Finding> found) {
        if (found.isEmpty()) {
            return "No issues found.";
        }
        Map<String, Long> c = counts(found);
        long vehicles = found.stream().map(Finding::vehicleId).filter(v -> v != null).distinct().count();
        return found.size() + " issue" + (found.size() == 1 ? "" : "s") + " across " + vehicles + " vehicle"
                + (vehicles == 1 ? "" : "s") + ": " + c.get("HIGH") + " high, " + c.get("MEDIUM") + " medium, "
                + c.get("LOW") + " low.";
    }

    public static String table(List<Finding> found) {
        StringBuilder sb = new StringBuilder("| Severity | Vehicle | Issue | Recommended action |\n|---|---|---|---|\n");
        for (Finding f : found) {
            sb.append("| ").append(f.severity()).append(" | ").append(cell(f.vehicle())).append(" | **")
                    .append(cell(f.title())).append("** ").append(cell(f.detail())).append(" | ")
                    .append(cell(f.action())).append(" |\n");
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s == null ? "—" : s.replace('|', '/').replace('\n', ' ');
    }

    // ------------------------------------------------------------------ checks

    public List<Finding> findings(Set<String> checks, VehicleFilter filter) {
        List<Vehicle> vehicles = fleet.all().stream().filter(filter.predicate()).toList();
        Set<String> resolved = store.resolvedFindingIds();
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();
        List<Finding> out = new ArrayList<>();

        Map<String, List<Vehicle>> byReg = new HashMap<>();
        fleet.all().forEach(v -> byReg.computeIfAbsent(FleetRepository.normalise(v.regNo()), k -> new ArrayList<>()).add(v));

        for (Vehicle v : vehicles) {
            // Fleet hygiene
            if (checks.contains("missing-vin") && (v.vin() == null || v.vin().isBlank())) {
                out.add(f(HYGIENE, "missing-vin", "MEDIUM", v, "Missing VIN",
                        "No chassis/VIN recorded, so OEM data and warranty claims cannot be matched.",
                        "Copy the VIN from the RC book or chassis plate into the vehicle record."));
            }
            if (checks.contains("duplicate-registration") && byReg.get(FleetRepository.normalise(v.regNo())).size() > 1) {
                String others = byReg.get(FleetRepository.normalise(v.regNo())).stream()
                        .map(Vehicle::id).filter(id -> !id.equals(v.id())).collect(Collectors.joining(", "));
                out.add(f(HYGIENE, "duplicate-registration", "HIGH", v, "Duplicate registration",
                        "Registration " + v.regNo() + " is also used by " + others + ".",
                        "Check the RC documents and correct the wrong record; trips and costs may be mixed up."));
            }
            if (checks.contains("odometer-rollback") && v.odometerKm() < v.previousOdometerKm()) {
                out.add(f(HYGIENE, "odometer-rollback", "HIGH", v, "Odometer went backwards",
                        "Reading fell from " + km(v.previousOdometerKm()) + " to " + km(v.odometerKm()) + ".",
                        "Verify the cluster reading; a swapped telematics unit or tampering is likely."));
            }
            if (checks.contains("stale-gps") && Duration.between(v.lastSeen(), now).toHours() > 72) {
                out.add(f(HYGIENE, "stale-gps", "MEDIUM", v, "Stale GPS position",
                        "Last position " + Duration.between(v.lastSeen(), now).toHours() + " hours ago.",
                        "Check the telematics unit power and SIM; reports for this vehicle are incomplete."));
            }
            if (checks.contains("no-driver") && v.driver() == null) {
                out.add(f(HYGIENE, "no-driver", "LOW", v, "No driver assigned",
                        "Trips cannot be attributed for safety scoring or hours-of-service.",
                        "Assign the regular driver in the vehicle record."));
            }
            if (checks.contains("implausible-efficiency") && implausible(v)) {
                out.add(f(HYGIENE, "implausible-efficiency", "MEDIUM", v, "Implausible efficiency",
                        v.efficiency() + " " + v.efficiencyUnit() + " is far above what a " + v.type().toLowerCase(Locale.ROOT)
                                + " can achieve.",
                        "Check fuel card mapping and tank-level calibration before using this in reports."));
            }
            if (checks.contains("missing-service-date") && v.nextServiceDate() == null) {
                out.add(f(HYGIENE, "missing-service-date", "LOW", v, "Missing next service date",
                        "No service plan date recorded.", "Add the service plan from the dealer or AMC schedule."));
            }

            // Compliance
            for (FleetDocument d : v.documents()) {
                long days = ChronoUnit.DAYS.between(today, d.expiry());
                if (checks.contains("expired-document") && days < 0) {
                    out.add(f(COMPLIANCE, "expired-document:" + d.type(), "HIGH", v, d.type() + " expired",
                            d.reference() + " expired on " + DATE.format(d.expiry()) + " (" + (-days) + " days ago).",
                            d.contract() ? "Renew the contract with the provider." : "Stop dispatching the vehicle until renewed; renew immediately."));
                } else if (!d.contract() && checks.contains("expiring-document") && days <= 30) {
                    out.add(f(COMPLIANCE, "expiring-document:" + d.type(), days <= 7 ? "HIGH" : "MEDIUM", v,
                            d.type() + " expiring in " + days + " days",
                            d.reference() + " expires on " + DATE.format(d.expiry()) + ".",
                            "Start the renewal now and upload the new document."));
                } else if (d.contract() && checks.contains("expiring-contract") && days <= 60) {
                    out.add(f(COMPLIANCE, "expiring-contract:" + d.type(), days <= 14 ? "HIGH" : days <= 30 ? "MEDIUM" : "LOW", v,
                            d.type() + " expiring in " + days + " days",
                            d.reference() + " ends on " + DATE.format(d.expiry()) + ".",
                            "Decide renew, renegotiate or end; set a reminder before the notice period."));
                }
            }
            if (checks.contains("licence-expiry") && v.driver() != null
                    && ChronoUnit.DAYS.between(today, v.driver().licenceExpiry()) <= 30) {
                long days = ChronoUnit.DAYS.between(today, v.driver().licenceExpiry());
                out.add(f(COMPLIANCE, "licence-expiry", days <= 7 ? "HIGH" : "MEDIUM", v,
                        "Driver licence expiring in " + days + " days",
                        v.driver().name() + "'s licence expires on " + DATE.format(v.driver().licenceExpiry()) + ".",
                        "Ask the driver to renew; plan a relief driver if it lapses."));
            }

            // Vehicle operations
            if (checks.contains("active-fault")) {
                for (Vehicle.FaultCode fc : v.faults()) {
                    String sev = switch (fc.severity()) {
                        case "Critical" -> "HIGH";
                        case "Major" -> "MEDIUM";
                        default -> "LOW";
                    };
                    out.add(f(OPERATIONS, "active-fault:" + fc.code(), sev, v, fc.severity() + " fault " + fc.code(),
                            fc.description() + ", active for " + Duration.between(fc.since(), now).toDays() + " days.",
                            sev.equals("HIGH") ? "Stop and send to the nearest dealer workshop." : "Book a workshop slot this week."));
                }
            }
            if (checks.contains("service-overdue") && (v.odometerKm() > v.nextServiceDueKm()
                    || (v.nextServiceDate() != null && v.nextServiceDate().isBefore(today)))) {
                String detail = v.odometerKm() > v.nextServiceDueKm()
                        ? km(v.odometerKm() - v.nextServiceDueKm()) + " past the service interval."
                        : "Service was due on " + DATE.format(v.nextServiceDate()) + ".";
                out.add(f(OPERATIONS, "service-overdue", "HIGH", v, "Service overdue", detail,
                        "Book service immediately; overdue service raises breakdown and warranty risk."));
            } else if (checks.contains("service-due") && (v.nextServiceDueKm() - v.odometerKm() <= 1_000
                    || (v.nextServiceDate() != null && !v.nextServiceDate().isAfter(today.plusDays(7))))) {
                out.add(f(OPERATIONS, "service-due", "LOW", v, "Service due soon",
                        "Due in " + km(Math.max(0, v.nextServiceDueKm() - v.odometerKm())) + " or by "
                                + (v.nextServiceDate() == null ? "—" : DATE.format(v.nextServiceDate())) + ".",
                        "Plan the workshop visit around its next trips."));
            }
            if (checks.contains("high-idle") && v.idlePct7d() > 25) {
                out.add(f(OPERATIONS, "high-idle", "MEDIUM", v, "High idling",
                        v.idlePct7d() + "% of engine time idling over 7 days.",
                        "Coach the driver and check loading-bay waiting times."));
            }
            if (checks.contains("low-fuel") && v.fuelLevelPct() != null && v.fuelLevelPct() < 15) {
                out.add(f(OPERATIONS, "low-fuel", "MEDIUM", v, "Low fuel", "Tank at " + v.fuelLevelPct() + "%.",
                        "Route the driver to a partner fuel station."));
            }
            if (checks.contains("low-adblue") && v.adBlueLevelPct() != null && v.adBlueLevelPct() < 10) {
                out.add(f(OPERATIONS, "low-adblue", "HIGH", v, "AdBlue almost empty", "AdBlue at " + v.adBlueLevelPct() + "%.",
                        "Refill now: the engine will derate when AdBlue runs out."));
            }
            if (checks.contains("tyre-pressure") && v.tyrePressureWarning()) {
                out.add(f(OPERATIONS, "tyre-pressure", "MEDIUM", v, "Tyre pressure warning",
                        "TPMS reports low pressure on at least one tyre.", "Inspect and inflate at the next stop."));
            }
            if (checks.contains("offline") && Duration.between(v.lastSeen(), now).toHours() > 24) {
                out.add(f(OPERATIONS, "offline", "MEDIUM", v, "Vehicle offline",
                        "No data for " + Duration.between(v.lastSeen(), now).toHours() + " hours.",
                        "Call the driver to confirm the vehicle's status."));
            }

            // Tyres
            if (checks.stream().anyMatch(c -> c.startsWith("tyre-"))) {
                for (TyreView tv : tyres.vehicle(v).detail()) {
                    String pos = tv.tyre().position();
                    if (checks.contains("tyre-replace-now") && tv.status().equals("REPLACE_NOW")) {
                        out.add(f(TYRES, "tyre-replace-now:" + pos, "HIGH", v, "Tyre worn out: " + pos,
                                "Tread " + tv.tyre().treadMm() + " mm, at or below the " + tv.removalMm() + " mm limit.",
                                "Replace or retread before the next trip; worn tyres raise blow-out and braking risk."));
                    } else if (checks.contains("tyre-due-soon") && tv.status().equals("DUE_30")) {
                        out.add(f(TYRES, "tyre-due-soon:" + pos, "MEDIUM", v, "Tyre due in " + tv.remainingDays() + " days: " + pos,
                                "Tread " + tv.tyre().treadMm() + " mm, about " + String.format(Locale.ENGLISH, "%,d", tv.remainingKm())
                                        + " km of life left.", "Order the tyre now and fit it at the next planned stop."));
                    }
                    if (checks.contains("tyre-under-inflated") && tv.pressureDeltaPct() <= -10) {
                        out.add(f(TYRES, "tyre-under-inflated:" + pos, tv.pressureDeltaPct() <= -20 ? "HIGH" : "MEDIUM", v,
                                "Under-inflated tyre: " + pos, tv.tyre().pressurePsi() + " psi against "
                                        + tv.tyre().recommendedPsi() + " psi (" + tv.pressureDeltaPct() + "%).",
                                "Inflate to spec and check for a slow puncture; it wastes fuel and wears the shoulders."));
                    }
                    if (checks.contains("tyre-irregular-wear") && tv.tyre().irregularWearMm() > 2.0) {
                        out.add(f(TYRES, "tyre-irregular-wear:" + pos, "LOW", v, "Irregular wear: " + pos,
                                tv.tyre().irregularWearMm() + " mm difference between shoulders.",
                                "Book a wheel alignment (about ₹1,800)."));
                    }
                    if (checks.contains("tyre-hot") && tv.tyre().temperatureC() > 85) {
                        out.add(f(TYRES, "tyre-hot:" + pos, "HIGH", v, "Tyre running hot: " + pos,
                                tv.tyre().temperatureC() + " °C.", "Stop and check for overload, brake drag or a slow puncture."));
                    }
                }
            }

            // EV operations
            if (v.electric()) {
                if (checks.contains("not-departure-ready") && v.socPct() < v.requiredSocPct()
                        && !"Charging".equals(v.chargingStatus())) {
                    out.add(f(EV, "not-departure-ready", "HIGH", v, "Not ready for next departure",
                            "At " + v.socPct() + "% but the " + v.nextDeparture().toLocalTime() + " trip needs "
                                    + v.requiredSocPct() + "%; status " + v.chargingStatus().toLowerCase(Locale.ROOT) + ".",
                            "Plug in now or swap the route to a charged vehicle."));
                }
                if (checks.contains("charger-fault") && "Charger fault".equals(v.chargingStatus())) {
                    out.add(f(EV, "charger-fault", "HIGH", v, "Charger fault",
                            "The charging session failed at the " + v.depot() + " depot.",
                            "Move to another charger and raise a ticket with the charger operator."));
                }
                if (checks.contains("battery-health") && v.batteryHealthPct() < 80) {
                    out.add(f(EV, "battery-health", "MEDIUM", v, "Battery health low",
                            "State of health " + v.batteryHealthPct() + "%.",
                            "Request a battery diagnostic under warranty; reduce fast-charging."));
                }
                if (checks.contains("low-soc") && v.socPct() < 25) {
                    out.add(f(EV, "low-soc", "MEDIUM", v, "Low state of charge", "Battery at " + v.socPct() + "%.",
                            "Prioritise this vehicle on the next free charger."));
                }
            }
        }
        return out.stream()
                .filter(f -> !resolved.contains(f.id()))
                .sorted(Comparator.comparingInt(Finding::severityRank).thenComparing(Finding::vehicleId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static boolean implausible(Vehicle v) {
        double max = switch (v.type()) {
            case "Heavy truck", "Tipper" -> 6;
            case "Medium truck" -> 10;
            case "Bus" -> 3;
            default -> 20;
        };
        return v.efficiency() > max;
    }

    private static Finding f(String agent, String check, String severity, Vehicle v, String title, String detail,
                             String action) {
        String checkId = check.contains(":") ? check.substring(0, check.indexOf(':')) : check;
        return new Finding(agent + ":" + check + ":" + v.id(), agent, checkId, severity, v.id(), v.label(), title,
                detail, action);
    }

    private static String km(long km) {
        return String.format(Locale.ENGLISH, "%,d km", km);
    }

    // ------------------------------------------------------------------ downtime risk

    /** Vehicles ranked by a simple downtime-risk score built from faults, service, battery and connectivity. */
    public List<RiskEntry> downtimeRisk(int limit) {
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();
        List<RiskEntry> out = new ArrayList<>();
        for (Vehicle v : fleet.all()) {
            int score = 0;
            List<String> reasons = new ArrayList<>();
            for (Vehicle.FaultCode fc : v.faults()) {
                int pts = switch (fc.severity()) {
                    case "Critical" -> 45;
                    case "Major" -> 25;
                    default -> 8;
                };
                score += pts;
                reasons.add(fc.severity().toLowerCase(Locale.ROOT) + " fault " + fc.code() + " (" + fc.description() + ")");
            }
            if (v.odometerKm() > v.nextServiceDueKm()) {
                score += 25;
                reasons.add("service overdue by " + km(v.odometerKm() - v.nextServiceDueKm()));
            } else if (v.nextServiceDate() != null && v.nextServiceDate().isBefore(today)) {
                score += 20;
                reasons.add("service date passed");
            } else if (v.nextServiceDueKm() - v.odometerKm() <= 1_000) {
                score += 8;
                reasons.add("service due within 1,000 km");
            }
            if (v.adBlueLevelPct() != null && v.adBlueLevelPct() < 10) {
                score += 15;
                reasons.add("AdBlue at " + v.adBlueLevelPct() + "% (engine derate risk)");
            }
            if (v.tyrePressureWarning()) {
                score += 10;
                reasons.add("tyre pressure warning");
            }
            if (v.electric() && v.batteryHealthPct() < 80) {
                score += 15;
                reasons.add("battery health " + v.batteryHealthPct() + "%");
            }
            if (v.electric() && "Charger fault".equals(v.chargingStatus())) {
                score += 15;
                reasons.add("charger fault");
            }
            List<TyreView> tyreViews = tyres.vehicle(v).detail();
            long wornTyres = tyreViews.stream().filter(tv -> tv.status().equals("REPLACE_NOW")).count();
            if (wornTyres > 0) {
                score += 20;
                reasons.add(wornTyres + " tyre" + (wornTyres == 1 ? "" : "s") + " worn to the limit");
            }
            if (tyreViews.stream().anyMatch(tv -> tv.tyre().temperatureC() > 85)) {
                score += 15;
                reasons.add("tyre running hot");
            }
            if (tyreViews.stream().anyMatch(tv -> tv.pressureDeltaPct() <= -20)) {
                score += 10;
                reasons.add("tyre 20%+ under-inflated");
            }
            if (Duration.between(v.lastSeen(), now).toHours() > 24) {
                score += 10;
                reasons.add("offline " + Duration.between(v.lastSeen(), now).toHours() + " h");
            }
            int age = today.getYear() - v.year();
            if (age >= 6) {
                score += 5;
                reasons.add(age + " years old");
            }
            if (score > 0) {
                out.add(new RiskEntry(v, Math.min(100, score), reasons));
            }
        }
        out.sort(Comparator.comparingInt(RiskEntry::score).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    public static String riskTable(List<RiskEntry> risks) {
        StringBuilder sb = new StringBuilder("| Vehicle | Status | Risk | Why |\n|---|---|---|---|\n");
        for (RiskEntry r : risks) {
            String level = r.score() >= 50 ? "HIGH" : r.score() >= 25 ? "MEDIUM" : "LOW";
            sb.append("| ").append(cell(r.vehicle().label())).append(" | ").append(r.vehicle().status())
                    .append(" | ").append(level).append(" (").append(r.score()).append(") | ")
                    .append(cell(String.join("; ", r.reasons()))).append(" |\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ reporting

    public String weeklyReport(VehicleFilter filter) {
        List<Vehicle> vs = fleet.all().stream().filter(filter.predicate()).toList();
        LocalDate today = LocalDate.now();
        long moving = vs.stream().filter(v -> v.status().equals("Moving")).count();
        long active = vs.stream().filter(v -> v.km7d() > 500).count();
        long workshop = vs.stream().filter(v -> v.status().equals("In workshop")).count();
        long offline = vs.stream().filter(v -> v.status().equals("Offline")).count();
        long totalKm = vs.stream().mapToLong(Vehicle::km7d).sum();
        double idle = vs.stream().mapToInt(Vehicle::idlePct7d).average().orElse(0);

        Set<String> allChecks = AGENTS.stream().flatMap(a -> a.checks().stream()).map(Check::id).collect(Collectors.toSet());
        List<Finding> found = findings(allChecks, filter);
        Map<String, Long> perAgent = found.stream().collect(Collectors.groupingBy(Finding::agent, LinkedHashMap::new,
                Collectors.counting()));

        StringBuilder sb = new StringBuilder();
        sb.append("# Weekly fleet report\n\n")
                .append("Week ending ").append(DATE.format(today)).append(" · ").append(vs.size()).append(" vehicles")
                .append(FleetRepository.DEMO ? " · demo data" : "").append("\n\n")
                .append("## Headline numbers\n\n")
                .append("| Metric | Value |\n|---|---|\n")
                .append("| Distance driven (7 days) | ").append(km(totalKm)).append(" |\n")
                .append("| Utilisation (vehicles over 500 km) | ").append(pct(active, vs.size())).append(" |\n")
                .append("| Moving now | ").append(moving).append(" |\n")
                .append("| In workshop | ").append(workshop).append(" |\n")
                .append("| Offline | ").append(offline).append(" |\n")
                .append("| Average idling | ").append(String.format(Locale.ENGLISH, "%.1f%%", idle)).append(" |\n")
                .append("| Open issues | ").append(found.size()).append(" (")
                .append(found.stream().filter(f -> f.severity().equals("HIGH")).count()).append(" high) |\n\n");

        sb.append("## Efficiency by fuel type\n\n| Fuel | Vehicles | Average efficiency | Distance (7 days) |\n|---|---|---|---|\n");
        vs.stream().collect(Collectors.groupingBy(Vehicle::fuel, LinkedHashMap::new, Collectors.toList()))
                .forEach((fuel, list) -> sb.append("| ").append(fuel).append(" | ").append(list.size()).append(" | ")
                        .append(String.format(Locale.ENGLISH, "%.2f %s", list.stream().mapToDouble(Vehicle::efficiency)
                                .average().orElse(0), list.get(0).efficiencyUnit()))
                        .append(" | ").append(km(list.stream().mapToLong(Vehicle::km7d).sum())).append(" |\n"));

        sb.append("\n## Open issues by agent\n\n| Agent | Issues |\n|---|---|\n");
        for (AgentDefinition a : AGENTS) {
            if (!a.id().equals(REPORTING)) {
                sb.append("| ").append(a.name()).append(" | ").append(perAgent.getOrDefault(a.id(), 0L)).append(" |\n");
            }
        }

        if (filter == VehicleFilter.ALL) {
            var t = tyres.fleet();
            sb.append("\n## Tyres\n\n| Measure | Value |\n|---|---|\n")
                    .append("| Replace now / within 30 days | ").append(t.statusCounts().get("REPLACE_NOW")).append(" / ")
                    .append(t.statusCounts().get("DUE_30")).append(" |\n")
                    .append("| Tyre budget, next 30 days | ₹").append(String.format(Locale.ENGLISH, "%,d", t.costPlan().next30DaysInr())).append(" |\n")
                    .append("| Tyre-wear particles this week | ").append(t.emissions().wearParticlesKgPerWeek()).append(" kg |\n")
                    .append("| Extra CO₂ from under-inflation | ").append(t.emissions().extraCo2KgPerWeek()).append(" kg |\n");
        }

        List<RiskEntry> risks = downtimeRisk(5).stream().filter(r -> filter.predicate().test(r.vehicle())).toList();
        if (!risks.isEmpty()) {
            sb.append("\n## Highest downtime risk\n\n").append(riskTable(risks));
        }

        List<Finding> high = found.stream().filter(f -> f.severity().equals("HIGH")).limit(8).toList();
        if (!high.isEmpty()) {
            sb.append("\n## Act this week\n\n");
            high.forEach(f -> sb.append("- **").append(f.vehicle()).append("**: ").append(f.title()).append(". ")
                    .append(f.action()).append('\n'));
        }
        return sb.toString().trim();
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "—" : Math.round(100.0 * part / whole) + "%";
    }
}
