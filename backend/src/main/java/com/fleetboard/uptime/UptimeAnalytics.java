package com.fleetboard.uptime;

import com.fleetboard.agents.AgentService;
import com.fleetboard.data.KnowledgeBase;
import com.fleetboard.data.SheetRecord;
import com.fleetboard.fleet.DowntimeEvent;
import com.fleetboard.fleet.DowntimeRepository;
import com.fleetboard.fleet.FleetRepository;
import com.fleetboard.fleet.Vehicle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fleet uptime: availability, planned vs unplanned downtime, the Uptime package KPIs from the workbook
 * (unplanned stops per 100,000 km, time to triage, closure rate), MTBF/MTTR, downtime cost, causes,
 * vehicles at risk, the OEM uptime features available to this fleet, and costed improvement ideas.
 */
@Service
public class UptimeAnalytics {

    public static final String UPTIME_THEME = "Uptime & remote service";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final double CLOSURE_TARGET_HOURS = 48;

    public static final List<String> ASSUMPTIONS = List.of(
            "Uptime = 1 − downtime hours ÷ (vehicles × hours in the period); planned services count as downtime.",
            "Downtime cost = lost revenue + repair and service bills. Lost revenue per vehicle-day off the road: heavy truck ₹9,000, tipper ₹8,000, medium truck ₹6,000, bus ₹12,000, LCV ₹2,500.",
            "Closure rate = unplanned stops fixed within 48 hours; triage = hours from the stop to a diagnosis.",
            "OEM uptime features come from the research workbook (D = documented, P = partner, NV = not verified); BharatBenz is Daimler Truck. "
                    + "Eicher, Switch Mobility and EKA are not covered by the workbook.",
            "Savings estimates: remote diagnostics and dealer triage cut triage time to about 2 hours; off-peak servicing cuts planned downtime by about 30%.");

    /** Fleet make -> OEM family column in the workbook; makes not covered map to null. */
    private static final Map<String, String> MAKE_TO_OEM = Map.of(
            "Tata Motors", "Tata Motors", "Ashok Leyland", "Ashok Leyland", "BharatBenz", "Daimler Truck",
            "Volvo", "Volvo Group");

    public record Kpis(double uptimePct, double plannedHours, double unplannedHours, int availableNow, int vehicles,
                       double unplannedStopsPer100kKm, long mtbfKm, double mttrHours, double meanTriageHours,
                       int closureRatePct, long downtimeCostInr, long lostRevenueInr, long repairCostInr,
                       int unplannedStops, int plannedStops) {
    }

    public record OffRoad(String vehicleId, String vehicle, String cause, String description, String location,
                          Instant since, double hours, long lostRevenuePerDayInr) {
    }

    public record CauseShare(String cause, int events, double hours, int pct) {
    }

    public record VehicleUptime(String vehicleId, String vehicle, double uptimePct, double downtimeHours,
                                int unplannedStops, int plannedStops, long costInr, List<DowntimeEvent> events) {
    }

    public record WeekPoint(String weekOf, double uptimePct) {
    }

    public record AtRisk(String vehicleId, String vehicle, int score, List<String> reasons) {
    }

    public record Idea(String title, String detail, double hoursSavedPerMonth, long valuePerMonthInr, String feature) {
    }

    public record UptimeFeature(String id, String feature, String priority, String definition,
                                Map<String, String> statusByFleetOem, int vehiclesCovered, int vehiclesInFleet) {
    }

    public record FleetUptime(int windowDays, Kpis kpis, List<OffRoad> offRoadNow, List<CauseShare> causes,
                              List<VehicleUptime> worstVehicles, List<WeekPoint> weeklyTrend, List<AtRisk> atRisk,
                              List<Idea> improvements, List<UptimeFeature> features, List<String> assumptions) {
    }

    private final FleetRepository fleet;
    private final DowntimeRepository downtime;
    private final AgentService agents;
    private final KnowledgeBase kb;

    public UptimeAnalytics(FleetRepository fleet, DowntimeRepository downtime, AgentService agents, KnowledgeBase kb) {
        this.fleet = fleet;
        this.downtime = downtime;
        this.agents = agents;
        this.kb = kb;
    }

    // ------------------------------------------------------------------ fleet

    public FleetUptime fleet(int windowDays) {
        int days = Math.max(7, Math.min(DowntimeRepository.HISTORY_DAYS, windowDays));
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(days));
        List<Vehicle> vs = fleet.all();
        List<DowntimeEvent> inWindow = downtime.all().stream().filter(e -> e.hoursWithin(from, now) > 0).toList();

        Kpis kpis = kpis(vs, inWindow, from, now, days);

        List<OffRoad> offRoad = downtime.all().stream().filter(DowntimeEvent::ongoing).map(e -> {
            Vehicle v = fleet.find(e.vehicleId()).orElseThrow();
            return new OffRoad(v.id(), v.label(), e.cause(), e.description(), e.location(), e.start(),
                    Math.round(e.hours(now) * 10) / 10.0, dailyRevenue(v));
        }).sorted(Comparator.comparing(OffRoad::since)).toList();

        double totalHours = inWindow.stream().mapToDouble(e -> e.hoursWithin(from, now)).sum();
        List<CauseShare> causes = inWindow.stream().collect(Collectors.groupingBy(DowntimeEvent::cause)).entrySet().stream()
                .map(en -> {
                    double h = en.getValue().stream().mapToDouble(e -> e.hoursWithin(from, now)).sum();
                    return new CauseShare(en.getKey(), en.getValue().size(), Math.round(h * 10) / 10.0,
                            (int) Math.round(totalHours == 0 ? 0 : h * 100 / totalHours));
                })
                .sorted(Comparator.comparingDouble(CauseShare::hours).reversed()).toList();

        List<VehicleUptime> perVehicle = vs.stream().map(v -> vehicleUptime(v, from, now, days)).toList();
        List<VehicleUptime> worst = perVehicle.stream().filter(vu -> vu.downtimeHours() > 0)
                .sorted(Comparator.comparingDouble(VehicleUptime::uptimePct)).limit(8)
                .map(vu -> new VehicleUptime(vu.vehicleId(), vu.vehicle(), vu.uptimePct(), vu.downtimeHours(),
                        vu.unplannedStops(), vu.plannedStops(), vu.costInr(), List.of()))
                .toList();

        List<WeekPoint> trend = new ArrayList<>();
        for (int w = Math.min(12, DowntimeRepository.HISTORY_DAYS / 7); w >= 1; w--) {
            Instant ws = now.minus(Duration.ofDays(7L * w));
            Instant we = ws.plus(Duration.ofDays(7));
            double h = downtime.all().stream().mapToDouble(e -> e.hoursWithin(ws, we)).sum();
            trend.add(new WeekPoint(DATE.format(LocalDate.ofInstant(ws, ZoneId.systemDefault())),
                    pct(1 - h / (vs.size() * 168.0))));
        }

        List<AtRisk> atRisk = agents.downtimeRisk(8).stream().filter(r -> r.score() >= 35)
                .filter(r -> offRoad.stream().noneMatch(o -> o.vehicleId().equals(r.vehicle().id())))
                .map(r -> new AtRisk(r.vehicle().id(), r.vehicle().label(), r.score(), r.reasons())).toList();

        List<UptimeFeature> features = features();
        return new FleetUptime(days, kpis, offRoad, causes, worst, trend, atRisk,
                improvements(kpis, offRoad, causes, atRisk, days, features), features, ASSUMPTIONS);
    }

    private Kpis kpis(List<Vehicle> vs, List<DowntimeEvent> events, Instant from, Instant now, int days) {
        double planned = events.stream().filter(e -> !e.unplanned()).mapToDouble(e -> e.hoursWithin(from, now)).sum();
        double unplanned = events.stream().filter(DowntimeEvent::unplanned).mapToDouble(e -> e.hoursWithin(from, now)).sum();
        double fleetHours = vs.size() * days * 24.0;
        List<DowntimeEvent> stops = events.stream().filter(DowntimeEvent::unplanned).toList();
        List<DowntimeEvent> closed = stops.stream().filter(e -> !e.ongoing()).toList();
        double km = vs.stream().mapToDouble(v -> v.km7d() / 7.0 * days).sum();
        long lost = 0;
        for (DowntimeEvent e : events) {
            Vehicle v = fleet.find(e.vehicleId()).orElseThrow();
            lost += Math.round(e.hoursWithin(from, now) / 24.0 * dailyRevenue(v));
        }
        long repairs = events.stream().mapToLong(DowntimeEvent::repairCostInr).sum();
        int available = (int) vs.stream().filter(v -> downtime.forVehicle(v.id()).stream().noneMatch(DowntimeEvent::ongoing)).count();
        return new Kpis(pct(1 - (planned + unplanned) / fleetHours), round1(planned), round1(unplanned), available, vs.size(),
                round1(km == 0 ? 0 : stops.size() * 100_000 / km), stops.isEmpty() ? 0 : Math.round(km / stops.size()),
                round1(closed.stream().mapToDouble(e -> e.hours(now)).average().orElse(0)),
                round1(stops.stream().filter(e -> e.triageHours() > 0).mapToDouble(DowntimeEvent::triageHours).average().orElse(0)),
                stops.isEmpty() ? 100 : (int) Math.round(closed.stream().filter(e -> e.hours(now) <= CLOSURE_TARGET_HOURS).count() * 100.0 / stops.size()),
                lost + repairs, lost, repairs, stops.size(), events.size() - stops.size());
    }

    private VehicleUptime vehicleUptime(Vehicle v, Instant from, Instant now, int days) {
        List<DowntimeEvent> events = downtime.forVehicle(v.id()).stream().filter(e -> e.hoursWithin(from, now) > 0).toList();
        double hours = events.stream().mapToDouble(e -> e.hoursWithin(from, now)).sum();
        long cost = events.stream().mapToLong(e -> Math.round(e.hoursWithin(from, now) / 24.0 * dailyRevenue(v)) + e.repairCostInr()).sum();
        return new VehicleUptime(v.id(), v.label(), pct(1 - hours / (days * 24.0)), round1(hours),
                (int) events.stream().filter(DowntimeEvent::unplanned).count(),
                (int) events.stream().filter(e -> !e.unplanned()).count(), cost, events);
    }

    public VehicleUptime vehicle(Vehicle v, int windowDays) {
        int days = Math.max(7, Math.min(DowntimeRepository.HISTORY_DAYS, windowDays));
        Instant now = Instant.now();
        return vehicleUptime(v, now.minus(Duration.ofDays(days)), now, days);
    }

    // ------------------------------------------------------------------ OEM uptime features (workbook)

    public List<UptimeFeature> features() {
        Map<String, Long> vehiclesPerOem = fleet.all().stream().filter(v -> MAKE_TO_OEM.containsKey(v.make()))
                .collect(Collectors.groupingBy(v -> MAKE_TO_OEM.get(v.make()), LinkedHashMap::new, Collectors.counting()));
        List<UptimeFeature> out = new ArrayList<>();
        for (SheetRecord row : kb.sheet(KnowledgeBase.FEATURE_MATRIX)) {
            if (!UPTIME_THEME.equals(row.field("Theme"))) {
                continue;
            }
            Map<String, String> status = new LinkedHashMap<>();
            int covered = 0;
            for (Map.Entry<String, Long> e : vehiclesPerOem.entrySet()) {
                String code = row.field(e.getKey()) == null ? "NV" : row.field(e.getKey());
                status.put(e.getKey(), code);
                if (code.equals("D") || code.equals("P")) {
                    covered += e.getValue();
                }
            }
            String definition = kb.dictionaryRow(row.field("ID")).map(d -> d.field("Acceptance definition")).orElse("");
            out.add(new UptimeFeature(row.field("ID"), row.field("Feature"), row.field("Proposed project priority"),
                    definition, status, covered, fleet.all().size()));
        }
        return out;
    }

    // ------------------------------------------------------------------ improvement ideas

    private List<Idea> improvements(Kpis k, List<OffRoad> offRoad, List<CauseShare> causes, List<AtRisk> atRisk,
                                    int days, List<UptimeFeature> features) {
        List<Idea> out = new ArrayList<>();
        double monthFactor = 30.0 / days;
        long avgDaily = Math.round(fleet.all().stream().mapToLong(this::dailyRevenue).average().orElse(6_000));

        if (!offRoad.isEmpty()) {
            long perDay = offRoad.stream().mapToLong(OffRoad::lostRevenuePerDayInr).sum();
            out.add(new Idea("Get " + offRoad.size() + " off-road vehicle" + (offRoad.size() == 1 ? "" : "s") + " back to work",
                    offRoad.stream().map(o -> o.vehicle().split(" ")[0] + " (" + o.cause().toLowerCase(Locale.ROOT) + ", "
                            + Math.round(o.hours()) + " h)").collect(Collectors.joining(", "))
                            + ". Together they cost ₹" + inr(perDay) + " for every day off the road: escalate with the "
                            + "dealer and cover their routes with a spare or hired vehicle.",
                    0, 0, null));
        }
        if (k.meanTriageHours() > 3) {
            double saved = (k.meanTriageHours() - 2) * k.unplannedStops() * monthFactor;
            String f = featureLine(features, "F21", "F24");
            out.add(new Idea("Diagnose breakdowns faster", "Triage takes " + k.meanTriageHours() + " h on average. Remote "
                    + "diagnostics and dealer-connected triage get a diagnosis to the workshop before the vehicle arrives. "
                    + f, round1(saved), Math.round(saved / 24 * avgDaily), "F21, F24"));
        }
        CauseShare top = causes.stream().filter(c -> !c.cause().equals("Service")).findFirst().orElse(null);
        if (top != null) {
            String advice = switch (top.cause()) {
                case "Engine", "Aftertreatment", "Cooling", "Transmission" -> "Act on fault codes before they become "
                        + "breakdowns: predictive / condition-based maintenance flags failing components early. "
                        + featureLine(features, "F23");
                case "Tyres" -> "Follow the Tire Analytics plan: replace worn tyres and fix under-inflation before trips.";
                case "Battery & charging" -> "Use EV Operations to catch charger faults and low battery health before departure.";
                case "Brakes" -> "Make digital pre-trip inspections mandatory so brake defects are caught at the depot. "
                        + featureLine(features, "F30");
                default -> "Review the repeat failures with the dealer.";
            };
            double saved = top.hours() * 0.35 * monthFactor;
            out.add(new Idea("Reduce " + top.cause().toLowerCase(Locale.ROOT) + " downtime (" + top.pct() + "% of hours)",
                    advice, round1(saved), Math.round(saved / 24 * avgDaily), null));
        }
        if (k.plannedHours() > 0 && k.plannedHours() / Math.max(1, k.plannedHours() + k.unplannedHours()) > 0.3) {
            double saved = k.plannedHours() * 0.3 * monthFactor;
            out.add(new Idea("Service vehicles off-peak", "Planned service is " + Math.round(k.plannedHours()) + " h in the "
                    + "period. Book night or weekend slots, use express service bays, and combine jobs with tyre and "
                    + "compliance work. " + featureLine(features, "F22"), round1(saved), Math.round(saved / 24 * avgDaily), "F22"));
        }
        if (!atRisk.isEmpty()) {
            out.add(new Idea("Fix " + atRisk.size() + " at-risk vehicle" + (atRisk.size() == 1 ? "" : "s") + " before they fail",
                    atRisk.stream().map(r -> r.vehicle().split(" ")[0] + " (" + r.reasons().get(0) + ")")
                            .collect(Collectors.joining(", ")) + ".",
                    atRisk.size() * 24.0, atRisk.size() * avgDaily, null));
        }
        return out;
    }

    private static String featureLine(List<UptimeFeature> features, String... ids) {
        List<String> parts = new ArrayList<>();
        for (String id : ids) {
            features.stream().filter(f -> f.id().equals(id)).findFirst().ifPresent(f -> parts.add(f.feature() + " (" + f.id()
                    + ") is documented for " + f.vehiclesCovered() + " of " + f.vehiclesInFleet() + " vehicles' OEMs"));
        }
        return parts.isEmpty() ? "" : String.join("; ", parts) + ".";
    }

    // ------------------------------------------------------------------ markdown for the assistant

    public String fleetMarkdown(int windowDays) {
        FleetUptime u = fleet(windowDays);
        Kpis k = u.kpis();
        StringBuilder sb = new StringBuilder();
        sb.append("**Fleet uptime was ").append(k.uptimePct()).append("% over the last ").append(u.windowDays())
                .append(" days; ").append(k.availableNow()).append(" of ").append(k.vehicles())
                .append(" vehicles are available now.** Downtime cost about ₹").append(inr(k.downtimeCostInr()))
                .append(" (lost revenue ₹").append(inr(k.lostRevenueInr())).append(", repair and service bills ₹").append(inr(k.repairCostInr()))
                .append(").\n\n### Uptime KPIs\n\n| KPI | Value |\n|---|---|\n")
                .append("| Uptime | ").append(k.uptimePct()).append("% |\n")
                .append("| Unplanned / planned downtime | ").append(Math.round(k.unplannedHours())).append(" h / ")
                .append(Math.round(k.plannedHours())).append(" h |\n")
                .append("| Unplanned stops per 100,000 km | ").append(k.unplannedStopsPer100kKm()).append(" (")
                .append(k.unplannedStops()).append(" stops) |\n")
                .append("| Mean distance between failures | ").append(String.format(Locale.ENGLISH, "%,d", k.mtbfKm())).append(" km |\n")
                .append("| Mean time to triage | ").append(k.meanTriageHours()).append(" h |\n")
                .append("| Mean time to repair | ").append(k.mttrHours()).append(" h |\n")
                .append("| Closure rate (fixed within 48 h) | ").append(k.closureRatePct()).append("% |\n");

        if (!u.offRoadNow().isEmpty()) {
            sb.append("\n### Off the road now\n\n| Vehicle | Cause | Where | Down for | Lost per day |\n|---|---|---|---|---|\n");
            u.offRoadNow().forEach(o -> sb.append("| ").append(o.vehicle()).append(" | ").append(o.description()).append(" | ")
                    .append(o.location()).append(" | ").append(Math.round(o.hours())).append(" h | ₹")
                    .append(inr(o.lostRevenuePerDayInr())).append(" |\n"));
        }
        sb.append("\n### Downtime by cause\n\n| Cause | Events | Hours | Share |\n|---|---|---|---|\n");
        u.causes().forEach(c -> sb.append("| ").append(c.cause()).append(" | ").append(c.events()).append(" | ")
                .append(Math.round(c.hours())).append(" | ").append(c.pct()).append("% |\n"));
        sb.append("\n### Lowest uptime\n\n| Vehicle | Uptime | Downtime | Unplanned stops | Cost |\n|---|---|---|---|---|\n");
        u.worstVehicles().stream().limit(5).forEach(w -> sb.append("| ").append(w.vehicle()).append(" | ").append(w.uptimePct())
                .append("% | ").append(Math.round(w.downtimeHours())).append(" h | ").append(w.unplannedStops()).append(" | ₹")
                .append(inr(w.costInr())).append(" |\n"));
        if (!u.atRisk().isEmpty()) {
            sb.append("\n**At risk of the next breakdown:** ").append(u.atRisk().stream()
                    .map(r -> r.vehicle().split(" ")[0] + " (" + String.join(", ", r.reasons().subList(0, Math.min(2, r.reasons().size()))) + ")")
                    .collect(Collectors.joining("; "))).append(".\n");
        }
        sb.append("\n### How to raise uptime\n\n");
        u.improvements().forEach(i -> sb.append("- **").append(i.title()).append(".** ").append(i.detail())
                .append(i.hoursSavedPerMonth() > 0 ? " Saves about " + Math.round(i.hoursSavedPerMonth()) + " vehicle-hours a month (≈₹" + inr(i.valuePerMonthInr()) + ")."
                        : i.valuePerMonthInr() > 0 ? " Worth about ₹" + inr(i.valuePerMonthInr()) + " a month." : "")
                .append('\n'));
        sb.append("\n").append(featuresMarkdown(u.features()))
                .append("\n_Demo downtime history; revenue and savings are estimates (assumptions on the Uptime page)._");
        return sb.toString();
    }

    public String featuresMarkdown(List<UptimeFeature> features) {
        if (features.isEmpty()) {
            return "";
        }
        List<String> oems = new ArrayList<>(features.get(0).statusByFleetOem().keySet());
        StringBuilder sb = new StringBuilder("### OEM uptime features for your fleet's brands\n\n| Feature | ")
                .append(String.join(" | ", oems)).append(" | Vehicles covered |\n|---|")
                .append("---|".repeat(oems.size() + 1)).append('\n');
        for (UptimeFeature f : features) {
            sb.append("| ").append(f.id()).append(' ').append(f.feature()).append(" |");
            oems.forEach(o -> sb.append(' ').append(f.statusByFleetOem().get(o)).append(" |"));
            sb.append(' ').append(f.vehiclesCovered()).append(" of ").append(f.vehiclesInFleet()).append(" |\n");
        }
        sb.append("\nD = documented, P = partner, NV = not verified (not the same as absent). Eicher, Switch Mobility and EKA "
                + "vehicles are not covered by the OEM workbook.\n");
        return sb.toString();
    }

    public String vehicleMarkdown(Vehicle v) {
        VehicleUptime vu = vehicle(v, DowntimeRepository.HISTORY_DAYS);
        StringBuilder sb = new StringBuilder("**").append(v.label()).append(": ").append(vu.uptimePct())
                .append("% uptime over 90 days** (").append(Math.round(vu.downtimeHours())).append(" h down, ")
                .append(vu.unplannedStops()).append(vu.unplannedStops() == 1 ? " breakdown, " : " breakdowns, ")
                .append(vu.plannedStops()).append(vu.plannedStops() == 1 ? " service; ₹" : " services; ₹")
                .append(inr(vu.costInr())).append(" incl. lost revenue).\n\n");
        if (!vu.events().isEmpty()) {
            sb.append("| From | Type | Cause | Where | Hours | Repair cost |\n|---|---|---|---|---|---|\n");
            Instant now = Instant.now();
            vu.events().forEach(e -> sb.append("| ").append(DATE.format(LocalDate.ofInstant(e.start(), ZoneId.systemDefault())))
                    .append(" | ").append(e.unplanned() ? "Breakdown" : "Service").append(" | ").append(e.description())
                    .append(" | ").append(e.location()).append(" | ").append(Math.round(e.hours(now)))
                    .append(e.ongoing() ? " (ongoing)" : "").append(" | ₹").append(inr(e.repairCostInr())).append(" |\n"));
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ helpers

    public long dailyRevenue(Vehicle v) {
        return switch (v.type()) {
            case "Heavy truck" -> 9_000;
            case "Tipper" -> 8_000;
            case "Bus" -> 12_000;
            case "LCV" -> 2_500;
            default -> 6_000;
        };
    }

    public static String inr(long n) {
        String s = Long.toString(Math.abs(n));
        if (s.length() > 3) {
            String last3 = s.substring(s.length() - 3);
            s = s.substring(0, s.length() - 3).replaceAll("\\B(?=(\\d{2})+(?!\\d))", ",") + "," + last3;
        }
        return (n < 0 ? "-" : "") + s;
    }

    private static double pct(double fraction) {
        return Math.round(Math.max(0, fraction) * 1000) / 10.0;
    }

    private static double round1(double d) {
        return Math.round(d * 10) / 10.0;
    }
}
