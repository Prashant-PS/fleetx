package com.fleetboard.assistant;

import com.fleetboard.agents.ScheduleService;
import com.fleetboard.store.Store;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/** Carries out an action after the user confirmed it. */
@Service
public class ActionService {

    public record Result(boolean ok, String message, String link) {
    }

    private final ScheduleService schedules;
    private final Store store;

    public ActionService(ScheduleService schedules, Store store) {
        this.schedules = schedules;
        this.store = store;
    }

    public Result execute(String type, String title, Map<String, Object> p) {
        Map<String, Object> payload = p == null ? Map.of() : p;
        return switch (type == null ? "" : type) {
            case "create_schedule" -> {
                String agent = str(payload, "agent");
                boolean custom = agent != null && agent.startsWith("custom:");
                var s = schedules.create(new ScheduleService.Request(title,
                        custom ? "custom-agent" : "agent", custom ? agent.substring(7) : agent, null,
                        orDefault(str(payload, "cadence"), "WEEKLY"), orDefault(str(payload, "time"), "08:00"),
                        str(payload, "dayOfWeek"), str(payload, "date")));
                yield new Result(true, "Scheduled \"" + s.title() + "\" (" + s.cadence().toLowerCase() + ").", "/schedules");
            }
            case "create_reminder" -> {
                String date = orDefault(str(payload, "date"), LocalDate.now().plusDays(1).toString());
                var s = schedules.create(new ScheduleService.Request(title, "reminder", null, str(payload, "message"),
                        "ONCE", orDefault(str(payload, "time"), "09:00"), null, date));
                yield new Result(true, "Reminder set for " + date + " at " + s.time() + ".", "/schedules");
            }
            case "save_report" -> {
                String content = str(payload, "content");
                if (content == null || content.isBlank()) {
                    throw new IllegalArgumentException("Nothing to save");
                }
                var item = store.addSaved("report", orDefault(str(payload, "title"), title), content, "assistant");
                yield new Result(true, "Saved \"" + item.title() + "\".", "/saved/" + item.id());
            }
            case "resolve_finding" -> {
                String id = str(payload, "findingId");
                if (id == null) {
                    throw new IllegalArgumentException("Missing finding id");
                }
                store.resolveFinding(id);
                yield new Result(true, "Marked as resolved; it will no longer appear in agent results.", null);
            }
            default -> throw new IllegalArgumentException("Unknown action type: " + type);
        };
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static String orDefault(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
