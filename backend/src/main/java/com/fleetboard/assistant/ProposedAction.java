package com.fleetboard.assistant;

import java.util.Map;

/**
 * An action the assistant suggests. Nothing happens until the user presses Confirm, which posts it to
 * {@code /api/actions/execute}.
 *
 * @param type    create_schedule | create_reminder | save_report | resolve_finding
 * @param payload type-specific fields (agent, cadence, time, dayOfWeek, date, message, findingId, content)
 */
public record ProposedAction(String id, String type, String title, String description, Map<String, Object> payload) {
}
