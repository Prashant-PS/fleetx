package com.fleetboard.web;

import com.fleetboard.agents.AgentDefinition;
import com.fleetboard.agents.AgentService;
import com.fleetboard.agents.AgentService.AgentResult;
import com.fleetboard.agents.CustomAgentService;
import com.fleetboard.fleet.FleetRepository;
import com.fleetboard.fleet.Vehicle;
import com.fleetboard.store.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Fleet data, built-in agents and custom agents. */
@RestController
@RequestMapping("/api")
public class FleetController {

    private final FleetRepository fleet;
    private final AgentService agents;
    private final CustomAgentService customAgents;
    private final Store store;
    private final String userName;
    private final String userRole;

    public FleetController(FleetRepository fleet, AgentService agents, CustomAgentService customAgents, Store store,
                           @Value("${fleet.user.name:}") String userName,
                           @Value("${fleet.user.role:Fleet Manager}") String userRole) {
        this.fleet = fleet;
        this.agents = agents;
        this.customAgents = customAgents;
        this.store = store;
        this.userName = userName;
        this.userRole = userRole;
    }

    public record Me(String name, String role, boolean demoFleet) {
    }

    public record AgentCard(String id, String name, String description, String icon,
                            List<AgentDefinition.Check> checks, Map<String, Long> openIssues) {
    }

    @GetMapping("/me")
    public Me me() {
        return new Me(userName, userRole, FleetRepository.DEMO);
    }

    // ------------------------------------------------------------------ fleet

    @GetMapping("/fleet/summary")
    public Map<String, Object> summary() {
        List<Vehicle> vs = fleet.all();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("demo", FleetRepository.DEMO);
        m.put("vehicles", vs.size());
        m.put("byStatus", group(vs, Vehicle::status));
        m.put("byFuel", group(vs, Vehicle::fuel));
        m.put("byType", group(vs, Vehicle::type));
        m.put("byDepot", group(vs, Vehicle::depot));
        m.put("km7d", vs.stream().mapToLong(Vehicle::km7d).sum());
        m.put("attention", agents.attention(200).counts());
        return m;
    }

    @GetMapping("/fleet/vehicles")
    public List<Vehicle> vehicles(@RequestParam(value = "q", required = false) String q,
                                  @RequestParam(value = "status", required = false) String status,
                                  @RequestParam(value = "fuel", required = false) String fuel) {
        String query = q == null ? "" : q.toLowerCase(Locale.ROOT).trim();
        return fleet.all().stream()
                .filter(v -> status == null || status.isBlank() || status.equalsIgnoreCase(v.status()))
                .filter(v -> fuel == null || fuel.isBlank() || fuel.equalsIgnoreCase(v.fuel()))
                .filter(v -> query.isEmpty() || (v.id() + " " + v.regNo() + " " + v.make() + " " + v.model() + " "
                        + v.depot() + " " + (v.driver() == null ? "" : v.driver().name())).toLowerCase(Locale.ROOT)
                        .contains(query))
                .toList();
    }

    @GetMapping("/fleet/vehicles/{id}")
    public ResponseEntity<Map<String, Object>> vehicle(@PathVariable String id) {
        return fleet.find(id).map(v -> {
            AgentResult all = agents.runChecks("vehicle", v.regNo(), allChecks(), AgentService.VehicleFilter.ALL);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vehicle", v);
            m.put("findings", all.findings().stream().filter(f -> v.id().equals(f.vehicleId())).toList());
            return ResponseEntity.ok(m);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ------------------------------------------------------------------ agents

    @GetMapping("/agents")
    public List<AgentCard> agentCards() {
        return agents.definitions().stream().map(d -> new AgentCard(d.id(), d.name(), d.description(), d.icon(),
                d.checks(), d.id().equals(AgentService.REPORTING) ? Map.of() : agents.run(d.id()).counts())).toList();
    }

    @PostMapping("/agents/{id}/run")
    public AgentResult runAgent(@PathVariable String id) {
        return agents.run(id);
    }

    @PostMapping("/findings/{id}/resolve")
    public Map<String, String> resolve(@PathVariable String id) {
        store.resolveFinding(id);
        return Map.of("message", "Marked resolved");
    }

    @PostMapping("/findings/reopen-all")
    public Map<String, String> reopenAll() {
        store.reopenAllFindings();
        return Map.of("message", "All findings reopened");
    }

    @GetMapping("/custom-agents")
    public List<Store.CustomAgent> customAgents() {
        return store.customAgents();
    }

    @PostMapping("/custom-agents")
    public Store.CustomAgent createCustomAgent(@RequestBody CustomAgentService.Request request) {
        return customAgents.create(request);
    }

    @PutMapping("/custom-agents/{id}")
    public Store.CustomAgent updateCustomAgent(@PathVariable String id, @RequestBody CustomAgentService.Request request) {
        return customAgents.update(id, request);
    }

    @DeleteMapping("/custom-agents/{id}")
    public Map<String, Boolean> deleteCustomAgent(@PathVariable String id) {
        return Map.of("deleted", store.deleteCustomAgent(id));
    }

    @PostMapping("/custom-agents/{id}/run")
    public AgentResult runCustomAgent(@PathVariable String id) {
        return customAgents.run(id);
    }

    /** Values for the custom-agent filter dropdowns. */
    @GetMapping("/fleet/facets")
    public Map<String, List<String>> facets() {
        List<Vehicle> vs = fleet.all();
        return Map.of(
                "fuel", vs.stream().map(Vehicle::fuel).distinct().sorted().toList(),
                "type", vs.stream().map(Vehicle::type).distinct().sorted().toList(),
                "depot", vs.stream().map(Vehicle::depot).distinct().sorted().toList(),
                "make", vs.stream().map(Vehicle::make).distinct().sorted().toList());
    }

    private java.util.Set<String> allChecks() {
        return agents.definitions().stream().flatMap(d -> d.checks().stream()).map(AgentDefinition.Check::id)
                .filter(c -> !c.equals("weekly-report")).collect(Collectors.toSet());
    }

    private static Map<String, Long> group(List<Vehicle> vs, java.util.function.Function<Vehicle, String> key) {
        return vs.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.counting()));
    }
}
