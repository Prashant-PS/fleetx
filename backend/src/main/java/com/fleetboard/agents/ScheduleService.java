package com.fleetboard.agents;

import com.fleetboard.agents.AgentService.AgentResult;
import com.fleetboard.store.Store;
import com.fleetboard.store.Store.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Set;

/** Runs agents and reminders on their schedule and turns the output into notifications and saved reports. */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private static final Set<String> CADENCES = Set.of("ONCE", "DAILY", "WEEKLY", "MONTHLY");

    private final Store store;
    private final AgentService agents;
    private final CustomAgentService customAgents;
    private final ZoneId zone = ZoneId.systemDefault();

    public ScheduleService(Store store, AgentService agents, CustomAgentService customAgents) {
        this.store = store;
        this.agents = agents;
        this.customAgents = customAgents;
    }

    public record Request(String title, String taskType, String taskRef, String message, String cadence, String time,
                          String dayOfWeek, String date) {
    }

    /** Gives a new installation one useful schedule so the page is not empty. */
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!store.seeded()) {
            create(new Request("Weekly fleet report", "agent", AgentService.REPORTING, null, "WEEKLY", "08:00",
                    "MONDAY", null));
            store.markSeeded();
        }
    }

    public Schedule create(Request r) {
        validate(r);
        String title = r.title() == null || r.title().isBlank() ? defaultTitle(r) : r.title().trim();
        Schedule draft = new Schedule(Store.newId(), title, r.taskType(), r.taskRef(), r.message(),
                r.cadence().toUpperCase(Locale.ROOT), r.time() == null ? "08:00" : r.time(),
                r.dayOfWeek() == null ? "MONDAY" : r.dayOfWeek().toUpperCase(Locale.ROOT), r.date(), true, null, null,
                null, Instant.now());
        Instant next = nextRun(draft, Instant.now());
        if (next == null) {
            throw new IllegalArgumentException("That date and time is already in the past");
        }
        return store.addSchedule(withNext(draft, next, true));
    }

    public Schedule setEnabled(String id, boolean enabled) {
        return store.updateSchedule(id, s -> withNext(s, enabled ? nextRun(s, Instant.now()) : s.nextRun(),
                        enabled && nextRun(s, Instant.now()) != null))
                .orElseThrow(() -> new IllegalArgumentException("Unknown schedule"));
    }

    /** Runs a schedule immediately without changing its next regular run. */
    public Schedule runNow(String id) {
        Schedule s = store.schedule(id).orElseThrow(() -> new IllegalArgumentException("Unknown schedule"));
        execute(s, false);
        return store.schedule(id).orElseThrow();
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 30_000)
    public void tick() {
        Instant now = Instant.now();
        for (Schedule s : store.schedules()) {
            if (s.enabled() && s.nextRun() != null && !s.nextRun().isAfter(now)) {
                execute(s, true);
            }
        }
    }

    private void execute(Schedule s, boolean advance) {
        String summary;
        try {
            switch (s.taskType()) {
                case "reminder" -> {
                    summary = s.message() == null ? s.title() : s.message();
                    store.notify("Reminder: " + s.title(), summary, "/schedules");
                }
                case "agent", "custom-agent" -> {
                    AgentResult result = s.taskType().equals("agent") ? agents.run(s.taskRef()) : customAgents.run(s.taskRef());
                    summary = result.summary();
                    var saved = store.addSaved("scheduled-report", s.title() + " · " + LocalDate.now(zone),
                            "_" + result.summary() + "_\n\n" + result.markdown(), "schedule:" + s.id());
                    store.notify(s.title() + " is ready", result.summary(), "/saved/" + saved.id());
                }
                default -> throw new IllegalArgumentException("Unknown task type " + s.taskType());
            }
        } catch (RuntimeException e) {
            log.error("Schedule {} failed", s.id(), e);
            summary = "Failed: " + e.getMessage();
            store.notify(s.title() + " failed", summary, "/schedules");
        }
        String result = summary;
        Instant ranAt = Instant.now();
        store.updateSchedule(s.id(), old -> {
            Instant next = advance ? nextRun(old, ranAt.plusSeconds(60)) : old.nextRun();
            boolean enabled = old.enabled() && (!advance || next != null);
            return new Schedule(old.id(), old.title(), old.taskType(), old.taskRef(), old.message(), old.cadence(),
                    old.time(), old.dayOfWeek(), old.date(), enabled, next, ranAt, result, old.createdAt());
        });
    }

    /** Next run strictly after {@code from}, or null for a one-off whose time has passed. */
    Instant nextRun(Schedule s, Instant from) {
        LocalTime time = LocalTime.parse(s.time() == null ? "08:00" : s.time());
        LocalDateTime after = LocalDateTime.ofInstant(from, zone);
        LocalDateTime candidate;
        switch (s.cadence()) {
            case "ONCE" -> {
                candidate = LocalDate.parse(s.date()).atTime(time);
                return candidate.isAfter(after) ? candidate.atZone(zone).toInstant() : null;
            }
            case "DAILY" -> {
                candidate = after.toLocalDate().atTime(time);
                if (!candidate.isAfter(after)) {
                    candidate = candidate.plusDays(1);
                }
            }
            case "WEEKLY" -> {
                DayOfWeek day = DayOfWeek.valueOf(s.dayOfWeek());
                candidate = after.toLocalDate().with(TemporalAdjusters.nextOrSame(day)).atTime(time);
                if (!candidate.isAfter(after)) {
                    candidate = candidate.plusWeeks(1);
                }
            }
            case "MONTHLY" -> {
                candidate = after.toLocalDate().withDayOfMonth(1).atTime(time);
                if (!candidate.isAfter(after)) {
                    candidate = candidate.plusMonths(1);
                }
            }
            default -> throw new IllegalArgumentException("Unknown cadence " + s.cadence());
        }
        return candidate.atZone(zone).toInstant();
    }

    private static Schedule withNext(Schedule s, Instant next, boolean enabled) {
        return new Schedule(s.id(), s.title(), s.taskType(), s.taskRef(), s.message(), s.cadence(), s.time(),
                s.dayOfWeek(), s.date(), enabled, next, s.lastRun(), s.lastSummary(), s.createdAt());
    }

    private void validate(Request r) {
        if (r.cadence() == null || !CADENCES.contains(r.cadence().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Cadence must be ONCE, DAILY, WEEKLY or MONTHLY");
        }
        if (r.time() != null && !r.time().matches("\\d{2}:\\d{2}")) {
            throw new IllegalArgumentException("Time must be HH:mm");
        }
        if (r.cadence().equalsIgnoreCase("ONCE") && (r.date() == null || !r.date().matches("\\d{4}-\\d{2}-\\d{2}"))) {
            throw new IllegalArgumentException("A one-off schedule needs a date (YYYY-MM-DD)");
        }
        if (r.cadence().equalsIgnoreCase("WEEKLY") && r.dayOfWeek() != null) {
            DayOfWeek.valueOf(r.dayOfWeek().toUpperCase(Locale.ROOT));
        }
        switch (r.taskType() == null ? "" : r.taskType()) {
            case "agent" -> {
                if (!AgentService.ATTENTION.equals(r.taskRef())) {
                    agents.definition(r.taskRef());
                }
            }
            case "custom-agent" -> {
                // checked when it runs; the agent may be edited later
                if (r.taskRef() == null) {
                    throw new IllegalArgumentException("Pick a custom agent");
                }
            }
            case "reminder" -> {
                if ((r.message() == null || r.message().isBlank()) && (r.title() == null || r.title().isBlank())) {
                    throw new IllegalArgumentException("A reminder needs a title or message");
                }
            }
            default -> throw new IllegalArgumentException("Task type must be agent, custom-agent or reminder");
        }
    }

    private String defaultTitle(Request r) {
        return switch (r.taskType()) {
            case "agent" -> AgentService.ATTENTION.equals(r.taskRef()) ? "Daily attention briefing"
                    : agents.definition(r.taskRef()).name() + " run";
            case "custom-agent" -> "Custom agent run";
            default -> "Reminder";
        };
    }
}
