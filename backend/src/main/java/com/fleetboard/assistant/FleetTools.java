package com.fleetboard.assistant;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetboard.agents.AgentDefinition;
import com.fleetboard.agents.AgentService;
import com.fleetboard.agents.AgentService.AgentResult;
import com.fleetboard.agents.CustomAgentService;
import com.fleetboard.fleet.FleetRepository;
import com.fleetboard.fleet.Vehicle;
import com.fleetboard.news.NewsService;
import com.fleetboard.search.SearchEngine;
import com.fleetboard.store.Store;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Tools the AI assistant can call. Read tools return JSON; {@code propose_action} never changes anything,
 * it only shows the user a confirmation card.
 */
@Component
public class FleetTools {

    private static final Set<String> ACTION_TYPES = Set.of("create_schedule", "create_reminder", "save_report",
            "resolve_finding");
    private static final Set<String> CADENCES = Set.of("ONCE", "DAILY", "WEEKLY", "MONTHLY");
    private static final int MAX_FINDINGS = 40;

    private final FleetRepository fleet;
    private final AgentService agents;
    private final CustomAgentService customAgents;
    private final Store store;
    private final SearchEngine search;
    private final NewsService news;
    private final ObjectMapper json;

    public FleetTools(FleetRepository fleet, AgentService agents, CustomAgentService customAgents, Store store,
                      SearchEngine search, NewsService news, ObjectMapper json) {
        this.fleet = fleet;
        this.agents = agents;
        this.customAgents = customAgents;
        this.store = store;
        this.search = search;
        this.news = news;
        this.json = json;
    }

    /** Thrown for bad tool input; the message goes back to the model as an error result. */
    public static class ToolInputException extends RuntimeException {
        public ToolInputException(String message) {
            super(message);
        }
    }

    public List<Tool> definitions() {
        List<String> agentIds = new ArrayList<>(agents.definitions().stream().map(AgentDefinition::id).toList());
        agentIds.add(AgentService.ATTENTION);
        store.customAgents().forEach(a -> agentIds.add("custom:" + a.id()));
        String customList = store.customAgents().isEmpty() ? "none yet"
                : store.customAgents().stream().map(a -> "custom:" + a.id() + " = " + a.name()).collect(Collectors.joining(", "));

        return List.of(
                tool("fleet_overview", "Counts of the fleet's vehicles by status, fuel, type and depot, plus open issue "
                        + "counts per agent. Call this first for any general question about the fleet.", Map.of(), List.of()),
                tool("list_vehicles", "List vehicles, optionally filtered. Returns one compact row per vehicle "
                        + "(id, registration, model, status, distance and idling over 7 days, efficiency, charge, fault count).",
                        Map.of("status", str("Moving, Idle, Stopped, Offline or In workshop"),
                                "fuel", str("Diesel, CNG or Electric"),
                                "type", str("Heavy truck, Medium truck, Tipper, LCV or Bus"),
                                "depot", str("Depot city, e.g. Pune"),
                                "make", str("Manufacturer, e.g. Tata Motors"),
                                "query", str("Free text matched against registration, model and driver"),
                                "limit", Map.of("type", "integer", "description", "Max rows, default 50")),
                        List.of()),
                tool("get_vehicle", "Everything about one vehicle: driver, status, odometer, faults, service plan, "
                        + "fuel/AdBlue or battery, and documents with expiry dates.",
                        Map.of("vehicle", str("Vehicle id (e.g. V003) or registration number")), List.of("vehicle")),
                tool("run_agent", "Run an agent and get its findings (severity, vehicle, issue, recommended action, "
                        + "finding id). 'attention' returns the most urgent findings across all agents; 'reporting' returns "
                        + "the weekly fleet report as markdown. Custom agents: " + customList + ".",
                        Map.of("agent", Map.of("type", "string", "enum", agentIds)), List.of("agent")),
                tool("downtime_risk", "Vehicles ranked by downtime risk score (0-100) with the reasons.",
                        Map.of("limit", Map.of("type", "integer", "description", "Max vehicles, default 10")), List.of()),
                tool("search_workbook", "Search the OEM research workbook: which of 10 truck OEM families offer 60 "
                        + "connected-fleet features (D documented, P partner, A announced, NV not verified), OEM STP, "
                        + "customer segments, service packages and sources.",
                        Map.of("query", str("What to look for")), List.of("query")),
                tool("fleet_news", "Latest headlines from fleet and commercial-vehicle news publishers, optionally "
                        + "matching a topic.", Map.of("query", str("Topic, e.g. electric trucks; empty for the latest")),
                        List.of()),
                tool("propose_action", "Suggest an action to the user as a confirmation card. It is NOT carried out "
                        + "until the user confirms, so never say it is done. Types: create_schedule (run an agent on a "
                        + "cadence; needs agent, cadence, time, and day_of_week for WEEKLY or date for ONCE), "
                        + "create_reminder (one-off reminder; needs date YYYY-MM-DD, time HH:mm, message), save_report "
                        + "(needs content in markdown), resolve_finding (needs finding_id from run_agent).",
                        Map.ofEntries(
                                Map.entry("type", Map.of("type", "string", "enum", List.copyOf(ACTION_TYPES))),
                                Map.entry("title", str("Short label, e.g. 'Weekly compliance check'")),
                                Map.entry("description", str("One sentence explaining what will happen")),
                                Map.entry("agent", Map.of("type", "string", "enum", agentIds)),
                                Map.entry("cadence", Map.of("type", "string", "enum", List.copyOf(CADENCES))),
                                Map.entry("time", str("Local time HH:mm")),
                                Map.entry("day_of_week", Map.of("type", "string", "enum", List.of("MONDAY", "TUESDAY",
                                        "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"))),
                                Map.entry("date", str("YYYY-MM-DD")),
                                Map.entry("message", str("Reminder text")),
                                Map.entry("finding_id", str("Finding id from run_agent")),
                                Map.entry("content", str("Markdown to save"))),
                        List.of("type", "title", "description")));
    }

    public String execute(String name, Map<String, Object> in, AssistantListener out) {
        return switch (name) {
            case "fleet_overview" -> toJson(overview());
            case "list_vehicles" -> toJson(listVehicles(in));
            case "get_vehicle" -> toJson(fleet.find(string(in, "vehicle"))
                    .map(v -> (Object) v)
                    .orElseThrow(() -> new ToolInputException("No vehicle " + string(in, "vehicle"))));
            case "run_agent" -> toJson(runAgent(required(in, "agent")));
            case "downtime_risk" -> toJson(agents.downtimeRisk(integer(in, "limit", 10)).stream()
                    .map(r -> Map.of("vehicle", r.vehicle().label(), "id", r.vehicle().id(), "status", r.vehicle().status(),
                            "score", r.score(), "reasons", r.reasons()))
                    .toList());
            case "search_workbook" -> toJson(search.search(required(in, "query"), 8).stream()
                    .map(h -> h.record().toContextLine()).toList());
            case "fleet_news" -> toJson(news.latest(string(in, "query"), 8).items().stream()
                    .map(n -> Map.of("title", n.title(), "source", n.source(), "url", n.url(), "summary", n.summary(),
                            "date", n.publishedAt() == null ? "" : n.publishedAt().toString().substring(0, 10)))
                    .toList());
            case "propose_action" -> propose(in, out);
            default -> throw new ToolInputException("Unknown tool " + name);
        };
    }

    // ------------------------------------------------------------------ tools

    private Map<String, Object> overview() {
        List<Vehicle> vs = fleet.all();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("demoData", FleetRepository.DEMO);
        m.put("vehicles", vs.size());
        m.put("byStatus", count(vs, Vehicle::status));
        m.put("byFuel", count(vs, Vehicle::fuel));
        m.put("byType", count(vs, Vehicle::type));
        m.put("byDepot", count(vs, Vehicle::depot));
        m.put("km7d", vs.stream().mapToLong(Vehicle::km7d).sum());
        Map<String, Object> issues = new LinkedHashMap<>();
        for (AgentDefinition a : agents.definitions()) {
            if (!a.id().equals(AgentService.REPORTING)) {
                AgentResult r = agents.run(a.id());
                issues.put(a.name(), r.counts());
            }
        }
        m.put("openIssuesByAgent", issues);
        return m;
    }

    private List<Map<String, Object>> listVehicles(Map<String, Object> in) {
        Predicate<Vehicle> p = v -> eq(in, "status", v.status()) && eq(in, "fuel", v.fuel()) && eq(in, "type", v.type())
                && eq(in, "depot", v.depot()) && eq(in, "make", v.make());
        String query = string(in, "query");
        if (query != null && !query.isBlank()) {
            String q = query.toLowerCase(Locale.ROOT);
            p = p.and(v -> (v.regNo() + " " + v.model() + " " + v.make() + " " + (v.driver() == null ? "" : v.driver().name()))
                    .toLowerCase(Locale.ROOT).contains(q));
        }
        Instant now = Instant.now();
        return fleet.all().stream().filter(p).limit(integer(in, "limit", 50)).map(v -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", v.id());
            row.put("reg", v.regNo());
            row.put("model", v.make() + " " + v.model());
            row.put("type", v.type());
            row.put("fuel", v.fuel());
            row.put("depot", v.depot());
            row.put("status", v.status());
            row.put("lastSeenHoursAgo", Duration.between(v.lastSeen(), now).toHours());
            row.put("km7d", v.km7d());
            row.put("idlePct7d", v.idlePct7d());
            row.put("efficiency", v.efficiency() + " " + v.efficiencyUnit());
            row.put("faults", v.faults().size());
            if (v.electric()) {
                row.put("socPct", v.socPct());
                row.put("charging", v.chargingStatus());
            }
            return row;
        }).toList();
    }

    private Map<String, Object> runAgent(String agent) {
        AgentResult r = agent.startsWith("custom:") ? customAgents.run(agent.substring(7)) : agents.run(agent);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent", r.name());
        m.put("summary", r.summary());
        if (agent.equals(AgentService.REPORTING)) {
            m.put("reportMarkdown", r.markdown());
            return m;
        }
        m.put("findings", r.findings().stream().limit(MAX_FINDINGS).map(f -> Map.of(
                "findingId", f.id(), "severity", f.severity(), "vehicle", f.vehicle(), "issue", f.title(),
                "detail", f.detail(), "action", f.action())).toList());
        if (r.findings().size() > MAX_FINDINGS) {
            m.put("note", "Showing " + MAX_FINDINGS + " of " + r.findings().size() + " findings, most severe first.");
        }
        return m;
    }

    private String propose(Map<String, Object> in, AssistantListener out) {
        String type = required(in, "type");
        if (!ACTION_TYPES.contains(type)) {
            throw new ToolInputException("type must be one of " + ACTION_TYPES);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (type) {
            case "create_schedule" -> {
                String cadence = required(in, "cadence").toUpperCase(Locale.ROOT);
                if (!CADENCES.contains(cadence)) {
                    throw new ToolInputException("cadence must be one of " + CADENCES);
                }
                payload.put("agent", required(in, "agent"));
                payload.put("cadence", cadence);
                payload.put("time", time(in));
                putIfPresent(payload, "dayOfWeek", string(in, "day_of_week"));
                putIfPresent(payload, "date", string(in, "date"));
            }
            case "create_reminder" -> {
                payload.put("date", required(in, "date"));
                payload.put("time", time(in));
                payload.put("message", required(in, "message"));
            }
            case "save_report" -> payload.put("content", required(in, "content"));
            case "resolve_finding" -> payload.put("findingId", required(in, "finding_id"));
            default -> {
            }
        }
        out.action(new ProposedAction(Store.newId(), type, required(in, "title"), required(in, "description"), payload));
        return "Shown to the user as a confirmation card. It has NOT been carried out; the user must press Confirm.";
    }

    // ------------------------------------------------------------------ helpers

    private static Tool tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(required).build())
                // Stream tool arguments as they are generated; inputs are validated before use.
                .eagerInputStreaming(true)
                .build();
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Long> count(List<Vehicle> vs, java.util.function.Function<Vehicle, String> key) {
        return vs.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.counting()));
    }

    private static boolean eq(Map<String, Object> in, String key, String actual) {
        String wanted = string(in, key);
        return wanted == null || wanted.isBlank() || wanted.equalsIgnoreCase(actual);
    }

    private static String string(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v == null ? null : v.toString().trim();
    }

    private static String required(Map<String, Object> in, String key) {
        String v = string(in, key);
        if (v == null || v.isEmpty()) {
            throw new ToolInputException("Missing required field: " + key);
        }
        return v;
    }

    private static String time(Map<String, Object> in) {
        String t = string(in, "time");
        if (t == null) {
            return "08:00";
        }
        if (!t.matches("\\d{1,2}:\\d{2}")) {
            throw new ToolInputException("time must be HH:mm");
        }
        return t.length() == 4 ? "0" + t : t;
    }

    private static int integer(Map<String, Object> in, String key, int fallback) {
        Object v = in.get(key);
        if (v instanceof Number n) {
            return Math.max(1, Math.min(100, n.intValue()));
        }
        return fallback;
    }

    private static void putIfPresent(Map<String, Object> m, String key, String value) {
        if (value != null && !value.isBlank()) {
            m.put(key, value);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
