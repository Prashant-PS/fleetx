package com.fleetboard.agents;

import com.fleetboard.agents.AgentService.AgentResult;
import com.fleetboard.agents.AgentService.VehicleFilter;
import com.fleetboard.store.Store;
import com.fleetboard.store.Store.CustomAgent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** User-defined agents: a name, instructions, a chosen set of checks and an optional vehicle filter. */
@Service
public class CustomAgentService {

    private final Store store;
    private final AgentService agents;

    public CustomAgentService(Store store, AgentService agents) {
        this.store = store;
        this.agents = agents;
    }

    public record Request(String name, String description, String instructions, List<String> checks,
                          CustomAgent.Filter filter) {
    }

    public CustomAgent create(Request r) {
        return store.saveCustomAgent(build(Store.newId(), r, Instant.now()));
    }

    public CustomAgent update(String id, Request r) {
        CustomAgent existing = store.customAgent(id).orElseThrow(() -> new IllegalArgumentException("Unknown agent"));
        return store.saveCustomAgent(build(id, r, existing.createdAt()));
    }

    private CustomAgent build(String id, Request r, Instant createdAt) {
        if (r.name() == null || r.name().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        Set<String> known = agents.definitions().stream().flatMap(a -> a.checks().stream())
                .map(AgentDefinition.Check::id).collect(Collectors.toSet());
        List<String> checks = r.checks() == null ? List.of()
                : r.checks().stream().filter(known::contains).distinct().toList();
        if (checks.isEmpty()) {
            throw new IllegalArgumentException("Pick at least one check");
        }
        return new CustomAgent(id, r.name().trim(), blankToNull(r.description()), blankToNull(r.instructions()),
                checks, r.filter(), createdAt);
    }

    public AgentResult run(String id) {
        CustomAgent a = store.customAgent(id).orElseThrow(() -> new IllegalArgumentException("Unknown agent"));
        CustomAgent.Filter f = a.filter();
        VehicleFilter filter = f == null ? VehicleFilter.ALL : new VehicleFilter(f.fuel(), f.type(), f.depot(), f.make());
        AgentResult result = agents.runChecks(a.id(), a.name(), new LinkedHashSet<>(a.checks()), filter);
        return a.instructions() == null ? result
                : new AgentResult(result.agentId(), result.name(), result.summary(), result.counts(), result.findings(),
                result.generatedAt(), "> " + a.instructions().replace("\n", " ") + "\n\n" + result.markdown());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
