package com.fleetboard.assistant;

import com.fleetboard.agents.AgentService;
import com.fleetboard.agents.AgentService.AgentResult;
import com.fleetboard.agents.AgentService.RiskEntry;
import com.fleetboard.agents.Finding;
import com.fleetboard.fleet.FleetRepository;
import com.fleetboard.fleet.Vehicle;
import com.fleetboard.news.NewsService;
import com.fleetboard.search.Answer;
import com.fleetboard.search.LocalAnswerer;
import com.fleetboard.search.SearchEngine;
import com.fleetboard.store.Store;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The assistant without an AI model: recognises what the user is asking for, runs the matching agent or
 * lookup and suggests follow-up actions. Used when no API key is configured.
 */
@Component
public class LocalAssistant {

    private static final Pattern VEHICLE_ID = Pattern.compile("\\bV\\d{3}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REG_NO = Pattern.compile("\\b[A-Z]{2}\\s?\\d{2}\\s?[A-Z]{1,2}\\s?\\d{4}\\b",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public record Reply(String text, List<ProposedAction> actions) {
    }

    private final AgentService agents;
    private final FleetRepository fleet;
    private final SearchEngine search;
    private final LocalAnswerer workbook;
    private final NewsService news;

    public LocalAssistant(AgentService agents, FleetRepository fleet, SearchEngine search, LocalAnswerer workbook,
                          NewsService news) {
        this.agents = agents;
        this.fleet = fleet;
        this.search = search;
        this.workbook = workbook;
        this.news = news;
    }

    public void respond(String mode, String message, AssistantListener out) {
        out.status("Checking your fleet…");
        Reply reply = reply(mode == null ? "" : mode, message);
        out.text(reply.text());
        reply.actions().forEach(out::action);
    }

    Reply reply(String mode, String message) {
        String q = message.toLowerCase(Locale.ROOT);
        boolean report = mode.equals("report");

        Optional<Vehicle> vehicle = mentionedVehicle(message);
        if (vehicle.isPresent()) {
            return vehicleReply(vehicle.get());
        }
        if (has(q, "attention", "today", "urgent", "priorit", "what should i", "focus")) {
            return attention();
        }
        if (has(q, "downtime", "risk", "breakdown", "break down", "fail")) {
            return risk();
        }
        if (report || has(q, "report", "weekly", "summary", "summarise", "summarize", "kpi")) {
            if (has(q, "news")) {
                return newsReply(message);
            }
            return weeklyReport();
        }
        if (has(q, "contract", "subscription", "lease", "amc")) {
            return contracts();
        }
        if (has(q, "expir", "renew", "remind", "compliance", "insurance", "permit", "licen", "puc", "fitness", "document")) {
            return agentReply(AgentService.COMPLIANCE, "DAILY", "07:30");
        }
        if (has(q, "hygiene", "data quality", "duplicate", "missing", "vin", "odometer", "clean")) {
            return agentReply(AgentService.HYGIENE, "WEEKLY", "09:00");
        }
        if (has(q, " ev", "ev ", "electric", "charg", "battery", "soc", "departure")) {
            return agentReply(AgentService.EV, "DAILY", "20:00");
        }
        if (has(q, "fault", "idle", "idling", "service", "maintenance", "adblue", "tyre", "tire", "offline",
                "exception", "operation", "workshop")) {
            return agentReply(AgentService.OPERATIONS, "DAILY", "07:00");
        }
        if (has(q, "news", "latest", "headline", "industry")) {
            return newsReply(message);
        }
        if (has(q, "how many", "overview", "fleet size", "my fleet", "status", "vehicles")) {
            return overview();
        }
        return fallback(message);
    }

    // ------------------------------------------------------------------ intents

    private Reply attention() {
        AgentResult r = agents.attention(15);
        String text = "**" + r.summary() + "**\n\nHere is what needs your attention today, most urgent first:\n\n"
                + r.markdown() + related("Show vehicles at risk of downtime", "Remind me of expiring contracts",
                "Create a weekly fleet report");
        List<ProposedAction> actions = new ArrayList<>();
        actions.add(schedule("Daily attention briefing", "attention", "DAILY", "07:00", null,
                "Run this check every morning at 07:00 and notify me."));
        r.findings().stream().filter(f -> f.severity().equals("HIGH")).limit(2).forEach(f -> actions.add(resolve(f)));
        return new Reply(text, actions);
    }

    private Reply risk() {
        List<RiskEntry> risks = agents.downtimeRisk(10);
        String text = "**" + risks.size() + " vehicles show downtime risk.** The score adds up active faults, overdue "
                + "service, AdBlue, tyres, battery health, charger faults and connectivity.\n\n"
                + AgentService.riskTable(risks)
                + "\nStart with the HIGH ones: a planned workshop visit is far cheaper than a roadside breakdown."
                + related("What needs my attention today?", "Which vehicles have service overdue?",
                "Create a weekly fleet report");
        return new Reply(text, List.of(schedule("Daily downtime check", AgentService.OPERATIONS, "DAILY", "07:00",
                null, "Run Vehicle Operations every morning and notify me of new exceptions.")));
    }

    private Reply weeklyReport() {
        String report = agents.weeklyReport(AgentService.VehicleFilter.ALL);
        String text = report + related("What needs my attention today?", "Show vehicles at risk of downtime",
                "Which EVs are not ready for tomorrow?");
        return new Reply(text, List.of(
                save("Weekly fleet report · " + LocalDate.now(), report),
                schedule("Weekly fleet report", AgentService.REPORTING, "WEEKLY", "08:00", "MONDAY",
                        "Create this report every Monday at 08:00.")));
    }

    private Reply contracts() {
        LocalDate today = LocalDate.now();
        record Row(Vehicle v, Vehicle.FleetDocument d, long days) {
        }
        List<Row> rows = fleet.all().stream()
                .flatMap(v -> v.documents().stream().filter(Vehicle.FleetDocument::contract)
                        .map(d -> new Row(v, d, ChronoUnit.DAYS.between(today, d.expiry()))))
                .filter(r -> r.days() <= 90)
                .sorted((a, b) -> Long.compare(a.days(), b.days()))
                .toList();
        if (rows.isEmpty()) {
            return new Reply("No contracts or subscriptions expire in the next 90 days.", List.of());
        }
        StringBuilder sb = new StringBuilder("**" + rows.size() + " contracts expire in the next 90 days.**\n\n")
                .append("| Vehicle | Contract | Reference | Expires | Days left |\n|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append("| ").append(r.v().label()).append(" | ").append(r.d().type()).append(" | ")
                    .append(r.d().reference()).append(" | ").append(DATE.format(r.d().expiry())).append(" | ")
                    .append(r.days() < 0 ? "expired" : r.days()).append(" |\n");
        }
        sb.append("\nI can set a reminder 7 days before each of the nearest ones so you have time to renegotiate.")
                .append(related("Which documents have expired?", "What needs my attention today?",
                        "Create a weekly fleet report"));
        List<ProposedAction> actions = new ArrayList<>();
        for (Row r : rows.subList(0, Math.min(3, rows.size()))) {
            LocalDate remindOn = r.d().expiry().minusDays(7);
            if (!remindOn.isAfter(today)) {
                remindOn = today.plusDays(1);
            }
            actions.add(new ProposedAction(Store.newId(), "create_reminder",
                    "Renew " + r.d().type() + " for " + r.v().regNo(),
                    "Remind me on " + DATE.format(remindOn) + " at 09:00 (" + r.d().reference() + " expires "
                            + DATE.format(r.d().expiry()) + ").",
                    Map.of("date", remindOn.toString(), "time", "09:00", "message",
                            r.d().type() + " " + r.d().reference() + " for " + r.v().label() + " expires on "
                                    + DATE.format(r.d().expiry()) + ".")));
        }
        return new Reply(sb.toString(), actions);
    }

    private Reply agentReply(String agentId, String cadence, String time) {
        AgentResult r = agents.run(agentId);
        String text = "**" + r.name() + ":** " + r.summary() + "\n\n" + r.markdown()
                + related("What needs my attention today?", "Show vehicles at risk of downtime",
                "Create a weekly fleet report");
        List<ProposedAction> actions = new ArrayList<>();
        actions.add(schedule(r.name() + " check", agentId, cadence, time, cadence.equals("WEEKLY") ? "MONDAY" : null,
                "Run " + r.name() + " " + cadence.toLowerCase(Locale.ROOT) + " at " + time + " and notify me."));
        r.findings().stream().filter(f -> f.severity().equals("HIGH")).limit(2).forEach(f -> actions.add(resolve(f)));
        return new Reply(text, actions);
    }

    private Reply vehicleReply(Vehicle v) {
        StringBuilder sb = new StringBuilder("**").append(v.label()).append("** · ").append(v.type()).append(" · ")
                .append(v.fuel()).append(" · ").append(v.year()).append(" · ").append(v.depot()).append(" depot\n\n")
                .append("| | |\n|---|---|\n")
                .append("| Status | ").append(v.status()).append(" |\n")
                .append("| Driver | ").append(v.driver() == null ? "Not assigned" : v.driver().name()).append(" |\n")
                .append("| Odometer | ").append(String.format(Locale.ENGLISH, "%,d km", v.odometerKm())).append(" |\n")
                .append("| Last 7 days | ").append(String.format(Locale.ENGLISH, "%,d km", v.km7d())).append(", ")
                .append(v.idlePct7d()).append("% idling |\n")
                .append("| Efficiency | ").append(v.efficiency()).append(' ').append(v.efficiencyUnit()).append(" |\n");
        if (v.electric()) {
            sb.append("| Battery | ").append(v.socPct()).append("% charge, ").append(v.batteryHealthPct())
                    .append("% health, ").append(v.chargingStatus()).append(" |\n");
        }
        sb.append("| Next service | ").append(v.nextServiceDate() == null ? "not planned" : DATE.format(v.nextServiceDate()))
                .append(" |\n");
        AgentResult issues = agents.runChecks("vehicle", v.regNo(), allChecks(),
                new AgentService.VehicleFilter(null, null, null, null));
        List<Finding> mine = issues.findings().stream().filter(f -> v.id().equals(f.vehicleId())).toList();
        sb.append("\n").append(mine.isEmpty() ? "No open issues for this vehicle." : "**Open issues**\n\n" + AgentService.table(mine));
        return new Reply(sb + related("What needs my attention today?", "Show vehicles at risk of downtime",
                "Remind me of expiring contracts"), mine.stream().filter(f -> f.severity().equals("HIGH")).limit(2)
                .map(this::resolve).toList());
    }

    private Reply overview() {
        List<Vehicle> vs = fleet.all();
        Map<String, Long> byStatus = vs.stream().collect(Collectors.groupingBy(Vehicle::status, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> byFuel = vs.stream().collect(Collectors.groupingBy(Vehicle::fuel, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> byType = vs.stream().collect(Collectors.groupingBy(Vehicle::type, LinkedHashMap::new, Collectors.counting()));
        String text = "**Your fleet has " + vs.size() + " vehicles" + (FleetRepository.DEMO ? " (demo data)" : "") + ".**\n\n"
                + "- **Status**: " + join(byStatus) + "\n- **Fuel**: " + join(byFuel) + "\n- **Type**: " + join(byType)
                + "\n- **Open issues**: " + agents.attention(200).summary()
                + related("What needs my attention today?", "Show vehicles at risk of downtime", "Create a weekly fleet report");
        return new Reply(text, List.of());
    }

    private Reply newsReply(String message) {
        var page = news.latest(message, 8);
        if (page.items().isEmpty()) {
            return new Reply("No matching stories in the fleet news feeds right now.", List.of());
        }
        StringBuilder sb = new StringBuilder("**Latest fleet news**\n\n");
        page.items().forEach(n -> sb.append("- [").append(n.title()).append("](").append(n.url()).append(") · ")
                .append(n.source()).append(n.publishedAt() == null ? "" : ", " + n.publishedAt().toString().substring(0, 10))
                .append('\n'));
        return new Reply(sb + related("What needs my attention today?", "Electric truck news", "Create a weekly fleet report"),
                List.of());
    }

    private Reply fallback(String message) {
        List<SearchEngine.Hit> hits = search.search(message, 10);
        Answer a = workbook.answer(message, hits);
        String help = "I can check your fleet and the OEM research workbook. Try: what needs attention today, vehicles at "
                + "risk of downtime, a weekly report, expiring contracts, data issues, EV charging readiness, or a "
                + "vehicle number such as V003.";
        if (a == null) {
            return new Reply(help + related("What needs my attention today?", "Show vehicles at risk of downtime",
                    "Remind me of expiring contracts"), List.of());
        }
        return new Reply("From the OEM research workbook:\n\n" + a.text() + "\n\n_" + help + "_"
                + related("Compare Volvo vs Tata Motors", "Which OEMs support geofence alerts?", "What needs my attention today?"),
                List.of());
    }

    // ------------------------------------------------------------------ helpers

    private java.util.Set<String> allChecks() {
        return agents.definitions().stream().flatMap(d -> d.checks().stream()).map(c -> c.id())
                .filter(c -> !c.equals("weekly-report")).collect(Collectors.toSet());
    }

    private Optional<Vehicle> mentionedVehicle(String message) {
        Matcher m = VEHICLE_ID.matcher(message);
        if (m.find()) {
            return fleet.find(m.group());
        }
        m = REG_NO.matcher(message);
        return m.find() ? fleet.find(m.group()) : Optional.empty();
    }

    private static ProposedAction schedule(String title, String agent, String cadence, String time, String day, String desc) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agent", agent);
        payload.put("cadence", cadence);
        payload.put("time", time);
        if (day != null) {
            payload.put("dayOfWeek", day);
        }
        return new ProposedAction(Store.newId(), "create_schedule", title, desc, payload);
    }

    private static ProposedAction save(String title, String content) {
        return new ProposedAction(Store.newId(), "save_report", "Save this report", "Keep it in Saved as \"" + title + "\".",
                Map.of("content", content, "title", title));
    }

    private ProposedAction resolve(Finding f) {
        return new ProposedAction(Store.newId(), "resolve_finding", "Mark resolved: " + f.title(),
                f.vehicle() + ". Use this once the issue is handled.", Map.of("findingId", f.id()));
    }

    private static boolean has(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String join(Map<String, Long> m) {
        return m.entrySet().stream().map(e -> e.getValue() + " " + e.getKey().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }

    static String related(String... questions) {
        return "\n\n<<related>>\n" + String.join("\n", questions);
    }
}
