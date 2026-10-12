package com.fleetboard.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Saved items, schedules, custom agents, notifications and resolved findings, kept in one JSON file
 * ({@code fleet.store.file}). All access is synchronized; every change is written straight to disk.
 */
@Component
public class Store {

    private static final Logger log = LoggerFactory.getLogger(Store.class);
    private static final int MAX_NOTIFICATIONS = 100;

    public record SavedItem(String id, String kind, String title, String content, String source, Instant createdAt) {
    }

    /**
     * @param taskType agent | custom-agent | reminder
     * @param taskRef  agent id or custom agent id; null for reminders
     * @param cadence  ONCE | DAILY | WEEKLY | MONTHLY
     * @param time     local time "HH:mm"
     * @param dayOfWeek for WEEKLY, e.g. MONDAY
     * @param date     for ONCE, ISO date
     */
    public record Schedule(String id, String title, String taskType, String taskRef, String message, String cadence,
                           String time, String dayOfWeek, String date, boolean enabled, Instant nextRun,
                           Instant lastRun, String lastSummary, Instant createdAt) {
    }

    public record CustomAgent(String id, String name, String description, String instructions, List<String> checks,
                              Filter filter, Instant createdAt) {

        public record Filter(String fuel, String type, String depot, String make) {
        }
    }

    public record Notification(String id, String title, String body, String link, Instant createdAt, boolean read) {
    }

    /** On-disk shape. */
    public static class Data {
        public boolean seeded;
        public List<SavedItem> saved = new ArrayList<>();
        public List<Schedule> schedules = new ArrayList<>();
        public List<CustomAgent> customAgents = new ArrayList<>();
        public List<Notification> notifications = new ArrayList<>();
        public Set<String> resolvedFindings = new LinkedHashSet<>();
    }

    private final Path file;
    private final ObjectMapper json;
    private Data data;

    public Store(@Value("${fleet.store.file:./data/fleetboard-store.json}") String file, ObjectMapper json) {
        this.file = Path.of(file).toAbsolutePath();
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.data = load();
    }

    private Data load() {
        if (Files.exists(file)) {
            try {
                Data d = json.readValue(file.toFile(), Data.class);
                log.info("Loaded store from {}", file);
                return d;
            } catch (IOException e) {
                log.error("Could not read {}; starting with an empty store", file, e);
            }
        }
        return new Data();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            json.writeValue(tmp.toFile(), data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Could not write {}", file, e);
        }
    }

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- seeding

    public synchronized boolean seeded() {
        return data.seeded;
    }

    public synchronized void markSeeded() {
        data.seeded = true;
        save();
    }

    // ---------------------------------------------------------------- saved

    public synchronized List<SavedItem> saved() {
        return data.saved.stream().sorted(Comparator.comparing(SavedItem::createdAt).reversed()).toList();
    }

    public synchronized SavedItem addSaved(String kind, String title, String content, String source) {
        SavedItem item = new SavedItem(newId(), kind, title, content, source, Instant.now());
        data.saved.add(item);
        save();
        return item;
    }

    public synchronized boolean deleteSaved(String id) {
        boolean removed = data.saved.removeIf(s -> s.id().equals(id));
        save();
        return removed;
    }

    // ---------------------------------------------------------------- schedules

    public synchronized List<Schedule> schedules() {
        return data.schedules.stream().sorted(Comparator.comparing(Schedule::createdAt)).toList();
    }

    public synchronized Optional<Schedule> schedule(String id) {
        return data.schedules.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public synchronized Schedule addSchedule(Schedule s) {
        data.schedules.add(s);
        save();
        return s;
    }

    public synchronized Optional<Schedule> updateSchedule(String id, UnaryOperator<Schedule> change) {
        for (int i = 0; i < data.schedules.size(); i++) {
            if (data.schedules.get(i).id().equals(id)) {
                Schedule updated = change.apply(data.schedules.get(i));
                data.schedules.set(i, updated);
                save();
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    public synchronized boolean deleteSchedule(String id) {
        boolean removed = data.schedules.removeIf(s -> s.id().equals(id));
        save();
        return removed;
    }

    // ---------------------------------------------------------------- custom agents

    public synchronized List<CustomAgent> customAgents() {
        return data.customAgents.stream().sorted(Comparator.comparing(CustomAgent::createdAt)).toList();
    }

    public synchronized Optional<CustomAgent> customAgent(String id) {
        return data.customAgents.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    public synchronized CustomAgent saveCustomAgent(CustomAgent agent) {
        data.customAgents.removeIf(a -> a.id().equals(agent.id()));
        data.customAgents.add(agent);
        save();
        return agent;
    }

    public synchronized boolean deleteCustomAgent(String id) {
        boolean removed = data.customAgents.removeIf(a -> a.id().equals(id));
        save();
        return removed;
    }

    // ---------------------------------------------------------------- notifications

    public synchronized List<Notification> notifications() {
        return data.notifications.stream().sorted(Comparator.comparing(Notification::createdAt).reversed()).toList();
    }

    public synchronized Notification notify(String title, String body, String link) {
        Notification n = new Notification(newId(), title, body, link, Instant.now(), false);
        data.notifications.add(n);
        if (data.notifications.size() > MAX_NOTIFICATIONS) {
            data.notifications.sort(Comparator.comparing(Notification::createdAt));
            data.notifications.subList(0, data.notifications.size() - MAX_NOTIFICATIONS).clear();
        }
        save();
        return n;
    }

    public synchronized void markAllRead() {
        data.notifications.replaceAll(n -> new Notification(n.id(), n.title(), n.body(), n.link(), n.createdAt(), true));
        save();
    }

    // ---------------------------------------------------------------- resolved findings

    public synchronized Set<String> resolvedFindingIds() {
        return Set.copyOf(data.resolvedFindings);
    }

    public synchronized void resolveFinding(String findingId) {
        data.resolvedFindings.add(findingId);
        save();
    }

    public synchronized void reopenAllFindings() {
        data.resolvedFindings.clear();
        save();
    }
}
