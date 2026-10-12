package com.fleetboard.agents;

/**
 * One issue an agent found.
 *
 * @param id        stable id (agent:check:vehicle) so a finding can be marked resolved
 * @param agent     agent id that produced it
 * @param check     check id within the agent, e.g. "expired-document"
 * @param severity  HIGH, MEDIUM or LOW
 * @param vehicleId vehicle the finding is about, or null for fleet-wide findings
 * @param vehicle   display label of the vehicle
 * @param title     short headline
 * @param detail    what was found
 * @param action    recommended next step
 */
public record Finding(String id, String agent, String check, String severity, String vehicleId, String vehicle,
                      String title, String detail, String action) {

    public int severityRank() {
        return switch (severity) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }
}
