package com.fleetboard.web;

import com.fleetboard.fleet.FleetRepository;
import com.fleetboard.uptime.UptimeAnalytics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fleet uptime KPIs, downtime history and OEM uptime features. */
@RestController
@RequestMapping("/api/uptime")
public class UptimeController {

    private final UptimeAnalytics uptime;
    private final FleetRepository fleet;

    public UptimeController(UptimeAnalytics uptime, FleetRepository fleet) {
        this.uptime = uptime;
        this.fleet = fleet;
    }

    @GetMapping
    public UptimeAnalytics.FleetUptime fleetUptime(@RequestParam(value = "days", defaultValue = "30") int days) {
        return uptime.fleet(days);
    }

    @GetMapping("/vehicles/{id}")
    public ResponseEntity<UptimeAnalytics.VehicleUptime> vehicle(@PathVariable String id,
                                                                 @RequestParam(value = "days", defaultValue = "90") int days) {
        return fleet.find(id).map(v -> ResponseEntity.ok(uptime.vehicle(v, days))).orElse(ResponseEntity.notFound().build());
    }
}
