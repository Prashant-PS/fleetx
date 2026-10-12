package com.fleetboard.web;

import com.fleetboard.agents.ScheduleService;
import com.fleetboard.store.Store;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Saved items, schedules and notifications. */
@RestController
@RequestMapping("/api")
public class WorkspaceController {

    private final Store store;
    private final ScheduleService schedules;

    public WorkspaceController(Store store, ScheduleService schedules) {
        this.store = store;
        this.schedules = schedules;
    }

    public record SaveRequest(String kind, String title, String content, String source) {
    }

    public record EnableRequest(boolean enabled) {
    }

    // ------------------------------------------------------------------ saved

    @GetMapping("/saved")
    public List<Store.SavedItem> saved() {
        return store.saved();
    }

    @GetMapping("/saved/{id}")
    public ResponseEntity<Store.SavedItem> savedItem(@PathVariable String id) {
        return store.saved().stream().filter(s -> s.id().equals(id)).findFirst()
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/saved")
    public Store.SavedItem save(@RequestBody SaveRequest r) {
        if (r.content() == null || r.content().isBlank()) {
            throw new IllegalArgumentException("Nothing to save");
        }
        String title = r.title() == null || r.title().isBlank() ? "Saved answer" : r.title().trim();
        return store.addSaved(r.kind() == null ? "chat" : r.kind(), title.length() > 120 ? title.substring(0, 117) + "…" : title,
                r.content(), r.source());
    }

    @DeleteMapping("/saved/{id}")
    public Map<String, Boolean> deleteSaved(@PathVariable String id) {
        return Map.of("deleted", store.deleteSaved(id));
    }

    // ------------------------------------------------------------------ schedules

    @GetMapping("/schedules")
    public List<Store.Schedule> schedules() {
        return store.schedules();
    }

    @PostMapping("/schedules")
    public Store.Schedule createSchedule(@RequestBody ScheduleService.Request r) {
        return schedules.create(r);
    }

    @PatchMapping("/schedules/{id}")
    public Store.Schedule enable(@PathVariable String id, @RequestBody EnableRequest r) {
        return schedules.setEnabled(id, r.enabled());
    }

    @PostMapping("/schedules/{id}/run")
    public Store.Schedule runNow(@PathVariable String id) {
        return schedules.runNow(id);
    }

    @DeleteMapping("/schedules/{id}")
    public Map<String, Boolean> deleteSchedule(@PathVariable String id) {
        return Map.of("deleted", store.deleteSchedule(id));
    }

    // ------------------------------------------------------------------ notifications

    @GetMapping("/notifications")
    public List<Store.Notification> notifications() {
        return store.notifications();
    }

    @PostMapping("/notifications/read")
    public Map<String, String> markRead() {
        store.markAllRead();
        return Map.of("message", "ok");
    }
}
