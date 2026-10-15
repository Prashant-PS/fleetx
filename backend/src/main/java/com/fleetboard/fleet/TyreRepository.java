package com.fleetboard.fleet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Demo tyre data for every vehicle in {@link FleetRepository}: realistic axle layouts, sizes, Indian market
 * brands and prices, with some deliberately worn, under-inflated, hot and misaligned tyres.
 * Replace {@link #generate} with a TPMS / tyre-inspection feed to use real data.
 */
@Component
public class TyreRepository {

    private static final Logger log = LoggerFactory.getLogger(TyreRepository.class);

    private record Spec(String size, double treadSteer, double treadDrive, int psiSteer, int psiDrive,
                        int retreadPrice, double wearSteer, double wearDrive) {
    }

    private record Brand(String name, double priceFactor) {
    }

    private static final Spec HEAVY = new Spec("295/80 R22.5", 16, 22, 120, 110, 7_500, 0.14, 0.19);
    private static final Spec MEDIUM = new Spec("10.00 R20", 15, 19, 115, 105, 6_000, 0.13, 0.17);
    private static final Spec BUS = new Spec("295/80 R22.5", 16, 18, 115, 110, 7_500, 0.12, 0.16);
    private static final Spec LCV = new Spec("155 R13 LT", 8, 8, 50, 55, 0, 0.11, 0.13);

    private static final List<Brand> BRANDS = List.of(new Brand("Apollo", 1.0), new Brand("MRF", 1.04),
            new Brand("JK Tyre", 0.97), new Brand("CEAT", 0.95), new Brand("Bridgestone", 1.16),
            new Brand("Michelin", 1.24));

    /** Base price of a new tyre (₹) for the reference brand, by size. */
    private static final Map<String, Integer> BASE_PRICE = Map.of(
            "295/80 R22.5", 27_500, "10.00 R20", 22_000, "155 R13 LT", 4_200);

    private final Map<String, List<Tyre>> byVehicle;

    public TyreRepository(FleetRepository fleet) {
        Map<String, List<Tyre>> map = new LinkedHashMap<>();
        for (Vehicle v : fleet.all()) {
            map.put(v.id(), Collections.unmodifiableList(generate(v)));
        }
        this.byVehicle = Collections.unmodifiableMap(map);
        log.info("Demo tyres ready: {} tyres on {} vehicles", all().size(), map.size());
    }

    public List<Tyre> forVehicle(String vehicleId) {
        return byVehicle.getOrDefault(vehicleId, List.of());
    }

    public List<Tyre> all() {
        return byVehicle.values().stream().flatMap(List::stream).toList();
    }

    private static List<Tyre> generate(Vehicle v) {
        Random rnd = new Random(v.id().hashCode() * 31L + 7);
        Spec spec = switch (v.type()) {
            case "Heavy truck", "Tipper" -> HEAVY;
            case "Bus" -> BUS;
            case "LCV" -> LCV;
            default -> MEDIUM;
        };
        List<String[]> layout = new ArrayList<>();
        layout.add(new String[]{"Steer L", "steer"});
        layout.add(new String[]{"Steer R", "steer"});
        int driveAxles = v.type().equals("Heavy truck") || v.type().equals("Tipper") ? 2 : 1;
        boolean dual = !v.type().equals("LCV");
        for (int a = 1; a <= driveAxles; a++) {
            String axleName = driveAxles > 1 ? "Drive " + a : "Drive";
            for (String side : List.of("L", "R")) {
                if (dual) {
                    layout.add(new String[]{axleName + " " + side + " outer", "drive"});
                    layout.add(new String[]{axleName + " " + side + " inner", "drive"});
                } else {
                    layout.add(new String[]{axleName + " " + side, "drive"});
                }
            }
        }

        Brand fleetBrand = BRANDS.get(rnd.nextInt(BRANDS.size()));
        int dailyKm = Math.max(40, v.km7d() / 7);
        List<Tyre> out = new ArrayList<>();
        int index = 0;
        for (String[] pos : layout) {
            boolean steer = pos[1].equals("steer");
            Brand brand = rnd.nextInt(5) == 0 ? BRANDS.get(rnd.nextInt(BRANDS.size())) : fleetBrand;
            double treadNew = steer ? spec.treadSteer() : spec.treadDrive();
            double wear = (steer ? spec.wearSteer() : spec.wearDrive()) * (0.85 + rnd.nextDouble() * 0.35);
            long kmRun = 4_000 + rnd.nextInt((int) Math.min(95_000, Math.max(8_000, dailyKm * 300L)));
            int retreads = spec == LCV || steer ? 0 : rnd.nextInt(3) == 0 ? 1 : 0;
            double irregular = Math.round(rnd.nextDouble() * 1.2 * 10) / 10.0;
            int recommended = steer ? spec.psiSteer() : spec.psiDrive();
            int pressure = recommended - rnd.nextInt(6) + 2;
            int temperature = v.status().equals("Moving") ? 48 + rnd.nextInt(20) : 30 + rnd.nextInt(8);

            // ---- planted issues, keyed on vehicle and position so every check has examples ----
            int n = Integer.parseInt(v.id().substring(1));
            if (n % 7 == 3 && index == 2) {
                kmRun = (long) ((treadNew - 2.4) / wear * 1000);           // worn below the removal limit
            }
            if (n % 9 == 4 && steer) {
                kmRun = (long) ((treadNew - 5.2) / wear * 1000);           // due within weeks
            }
            if (n % 6 == 1 && index == 3) {
                pressure = (int) Math.round(recommended * 0.76);            // badly under-inflated
            }
            if (n % 5 == 2 && !steer && index % 2 == 0) {
                pressure = (int) Math.round(recommended * 0.87);            // moderately under-inflated
            }
            if (n % 8 == 5 && steer) {
                irregular = 2.6 + rnd.nextDouble();                         // misalignment
                wear *= 1.35;
            }
            if (n == 11 && index == 4) {
                temperature = 92;                                           // running hot
            }

            double tread = Math.max(1.2, treadNew - wear * kmRun / 1000.0);
            int newPrice = (int) Math.round(BASE_PRICE.get(spec.size()) * brand.priceFactor() / 100.0) * 100;
            String serial = brand.name().substring(0, 2).toUpperCase() + String.format("%02d%05d", v.year() % 100,
                    Math.abs((v.id() + pos[0]).hashCode()) % 100_000);
            LocalDate fitted = LocalDate.now().minusDays(Math.max(14, kmRun / dailyKm));
            out.add(new Tyre(v.id(), pos[0], pos[1], serial, brand.name(), spec.size(), fitted, retreads, treadNew,
                    Math.round(tread * 10) / 10.0, Math.round(irregular * 10) / 10.0, kmRun, pressure, recommended,
                    temperature, newPrice, spec.retreadPrice()));
            index++;
        }
        return out;
    }
}
