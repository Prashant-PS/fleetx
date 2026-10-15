package com.fleetboard.web;

import com.fleetboard.fleet.FleetRepository;
import com.fleetboard.tyres.TyreAnalytics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tire analytics: fleet summary (emissions, pending life, costed plan) and per-vehicle detail. */
@RestController
@RequestMapping("/api/tyres")
public class TyreController {

    private final TyreAnalytics tyres;
    private final FleetRepository fleet;

    public TyreController(TyreAnalytics tyres, FleetRepository fleet) {
        this.tyres = tyres;
        this.fleet = fleet;
    }

    @GetMapping
    public TyreAnalytics.FleetTyres fleetTyres() {
        return tyres.fleet();
    }

    @GetMapping("/vehicles/{id}")
    public ResponseEntity<TyreAnalytics.VehicleTyres> vehicle(@PathVariable String id) {
        return fleet.find(id).map(v -> ResponseEntity.ok(tyres.vehicle(v))).orElse(ResponseEntity.notFound().build());
    }
}
