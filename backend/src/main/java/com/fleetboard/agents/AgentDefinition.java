package com.fleetboard.agents;

import java.util.List;

/**
 * A built-in agent and the checks it runs.
 *
 * @param checks check id -> human description, used by the custom-agent builder
 */
public record AgentDefinition(String id, String name, String description, String icon, List<Check> checks) {

    public record Check(String id, String label) {
    }
}
