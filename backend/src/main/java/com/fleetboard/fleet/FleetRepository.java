package com.fleetboard.fleet;

import com.fleetboard.fleet.Vehicle.Driver;
import com.fleetboard.fleet.Vehicle.FaultCode;
import com.fleetboard.fleet.Vehicle.FleetDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * The fleet the assistant works on. This is a generated <b>demo fleet</b> (fixed seed, dates relative to
 * startup) with deliberate data, compliance, maintenance and EV issues so every agent has work to show.
 * Replace {@link #generate()} with a telematics/ERP integration to use real vehicles.
 */
@Component
public class FleetRepository {

    private static final Logger log = LoggerFactory.getLogger(FleetRepository.class);

    public static final boolean DEMO = true;

    private record ModelSpec(String make, String model, String type, String fuel, double efficiency) {
    }

    private static final List<ModelSpec> MODELS = List.of(
            new ModelSpec("Tata Motors", "Prima 4028.S", "Heavy truck", "Diesel", 3.4),
            new ModelSpec("Tata Motors", "Signa 4825.TK", "Tipper", "Diesel", 2.9),
            new ModelSpec("Tata Motors", "Ultra T.7", "Medium truck", "Diesel", 6.8),
            new ModelSpec("Tata Motors", "Ace EV", "LCV", "Electric", 6.5),
            new ModelSpec("Ashok Leyland", "AVTR 4220", "Heavy truck", "Diesel", 3.6),
            new ModelSpec("Ashok Leyland", "Boss 1415", "Medium truck", "Diesel", 6.2),
            new ModelSpec("Ashok Leyland", "Ecomet 1615 CNG", "Medium truck", "CNG", 4.1),
            new ModelSpec("BharatBenz", "2828R", "Heavy truck", "Diesel", 3.5),
            new ModelSpec("BharatBenz", "1917R", "Medium truck", "Diesel", 5.6),
            new ModelSpec("Eicher", "Pro 3015", "Medium truck", "Diesel", 5.9),
            new ModelSpec("Volvo", "FM 420", "Heavy truck", "Diesel", 3.3),
            new ModelSpec("Switch Mobility", "EiV 12", "Bus", "Electric", 1.1),
            new ModelSpec("EKA Mobility", "EKA 9", "Bus", "Electric", 1.4));

    private static final String[] DEPOTS = {"Pune", "Mumbai", "Chennai", "Bengaluru", "Delhi NCR", "Hyderabad",
            "Ahmedabad", "Kolkata"};
    private static final String[] STATE_CODES = {"MH12", "MH04", "TN09", "KA01", "DL01", "TS09", "GJ01", "WB02"};
    private static final String[] DRIVERS = {"Ravi Kumar", "Suresh Patil", "Imran Shaikh", "Arjun Nair",
            "Manoj Yadav", "Prakash Rao", "Vikram Singh", "Anil Deshmukh", "Sanjay Gupta", "Rahul Verma",
            "Deepak Joshi", "Mohan Das", "Karthik Iyer", "Abdul Rahman", "Sunil Pawar", "Gopal Reddy",
            "Naveen Shetty", "Harish Chauhan", "Amit Mishra", "Rajesh Pillai", "Farhan Qureshi", "Vijay Thakur",
            "Lokesh Gowda", "Santosh Jadhav", "Ramesh Babu", "Ajay Saxena", "Kiran More", "Dinesh Solanki",
            "Pradeep Sahu", "Ganesh Kale", "Ashok Bhat", "Yusuf Khan", "Balaji Murthy", "Nitin Kulkarni",
            "Sachin Shinde", "Tarun Bose", "Hemant Rathod", "Rakesh Meena"};
    private static final String[][] FAULTS = {
            {"P0087", "Fuel rail pressure too low", "Critical"},
            {"P20EE", "SCR NOx catalyst efficiency below threshold", "Major"},
            {"P0299", "Turbocharger underboost", "Major"},
            {"U0100", "Lost communication with engine control module", "Major"},
            {"P0217", "Engine coolant over-temperature", "Critical"},
            {"C1234", "ABS wheel speed sensor intermittent", "Minor"},
            {"P0520", "Oil pressure sensor circuit", "Minor"},
            {"B1A01", "Battery thermal management warning", "Major"}};

    private final List<Vehicle> vehicles;

    public FleetRepository() {
        this.vehicles = Collections.unmodifiableList(generate());
        log.info("Demo fleet ready: {} vehicles", vehicles.size());
    }

    public List<Vehicle> all() {
        return vehicles;
    }

    /** By id ("V007") or registration number, ignoring case and spaces. */
    public Optional<Vehicle> find(String idOrReg) {
        if (idOrReg == null) {
            return Optional.empty();
        }
        String key = normalise(idOrReg);
        return vehicles.stream()
                .filter(v -> normalise(v.id()).equals(key) || normalise(v.regNo()).equals(key))
                .findFirst();
    }

    public static String normalise(String s) {
        return s.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static List<Vehicle> generate() {
        Random rnd = new Random(2026);
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();
        List<Vehicle> out = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            ModelSpec spec = MODELS.get((i * 7) % MODELS.size());
            int depot = (i * 3) % DEPOTS.length;
            String id = String.format("V%03d", i);
            String reg = STATE_CODES[depot] + (char) ('A' + rnd.nextInt(26)) + (char) ('A' + rnd.nextInt(26))
                    + String.format("%04d", 1000 + rnd.nextInt(9000));
            String vin = "MA" + Long.toString(Math.abs(rnd.nextLong()), 36).toUpperCase(Locale.ROOT)
                    .substring(0, 10) + String.format("%05d", i);
            int year = 2019 + rnd.nextInt(7);
            boolean ev = spec.fuel().equals("Electric");

            Driver driver = new Driver("D" + String.format("%03d", i), DRIVERS[(i - 1) % DRIVERS.length],
                    today.plusDays(40 + rnd.nextInt(900)), 62 + rnd.nextInt(37));

            String status = pick(rnd, "Moving", "Moving", "Moving", "Idle", "Stopped", "Stopped");
            Instant lastSeen = now.minus(Duration.ofMinutes(1 + rnd.nextInt(40)));
            long odometer = 18_000L + (long) (today.getYear() - year) * 70_000L + rnd.nextInt(40_000);
            int km7d = status.equals("Moving") ? 1_200 + rnd.nextInt(2_000) : 300 + rnd.nextInt(900);
            if (spec.type().equals("LCV")) {
                km7d /= 3;
            }
            double efficiency = Math.round(spec.efficiency() * (0.85 + rnd.nextDouble() * 0.3) * 100) / 100.0;
            int idle = 6 + rnd.nextInt(18);

            List<FaultCode> faults = new ArrayList<>();
            long serviceInterval = spec.type().equals("LCV") ? 15_000 : 40_000;
            long nextServiceKm = odometer + 2_000 + rnd.nextInt((int) serviceInterval - 2_000);
            LocalDate lastService = today.minusDays(20 + rnd.nextInt(120));
            LocalDate nextService = today.plusDays(10 + rnd.nextInt(80));
            boolean tyreWarning = false;
            Integer fuelLevel = ev ? null : 25 + rnd.nextInt(70);
            Integer adBlue = spec.fuel().equals("Diesel") ? 30 + rnd.nextInt(65) : null;
            Integer soc = ev ? 45 + rnd.nextInt(50) : null;
            Integer soh = ev ? 86 + rnd.nextInt(12) : null;
            String charging = ev ? pick(rnd, "Not plugged", "Charging", "Plugged in") : null;
            LocalDateTime departure = ev ? LocalDateTime.of(today.plusDays(1), LocalTime.of(5 + rnd.nextInt(4), 30)) : null;
            Integer requiredSoc = ev ? 35 + rnd.nextInt(30) : null;

            List<FleetDocument> docs = new ArrayList<>();
            docs.add(new FleetDocument("Insurance", "POL-" + (480000 + i * 37), today.plusDays(45 + rnd.nextInt(300)), false));
            docs.add(new FleetDocument("Fitness certificate", "FC-" + reg, today.plusDays(60 + rnd.nextInt(600)), false));
            if (!spec.type().equals("Bus") && !spec.type().equals("LCV")) {
                docs.add(new FleetDocument("National permit", "NP-" + (2200 + i), today.plusDays(50 + rnd.nextInt(500)), false));
            }
            if (!ev) {
                docs.add(new FleetDocument("PUC certificate", "PUC-" + (9100 + i), today.plusDays(35 + rnd.nextInt(170)), false));
            }
            docs.add(new FleetDocument("AMC / service contract", "AMC-" + spec.make().substring(0, 3).toUpperCase(Locale.ROOT)
                    + "-" + (300 + i), today.plusDays(40 + rnd.nextInt(400)), true));
            docs.add(new FleetDocument("Telematics subscription", "TLM-" + (7000 + i), today.plusDays(45 + rnd.nextInt(330)), true));
            if (i % 4 == 0) {
                docs.add(new FleetDocument("Lease agreement", "LSE-" + (5100 + i), today.plusDays(90 + rnd.nextInt(700)), true));
            }

            long previousOdometer = odometer - km7d;

            // ---- Deliberate issues so each agent has something to report ----
            switch (i) {
                case 3 -> { faults.add(fault(FAULTS[0], now, 2)); status = "Stopped"; }
                case 5 -> { nextServiceKm = odometer - 3_400; faults.add(fault(FAULTS[2], now, 5)); }
                case 8 -> { vin = ""; }
                case 9 -> { docs.set(0, new FleetDocument("Insurance", "POL-" + (480000 + i * 37), today.minusDays(3), false)); }
                case 11 -> { idle = 34; fuelLevel = 11; }
                case 12 -> { previousOdometer = odometer + 5_800; }
                case 14 -> { docs.replaceAll(d -> d.type().equals("AMC / service contract")
                        ? new FleetDocument(d.type(), d.reference(), today.plusDays(9), true) : d); }
                case 15 -> { status = "Offline"; lastSeen = now.minus(Duration.ofHours(79)); }
                case 17 -> { faults.add(fault(FAULTS[1], now, 9)); adBlue = 7; }
                case 18 -> { docs.replaceAll(d -> d.type().equals("Telematics subscription")
                        ? new FleetDocument(d.type(), d.reference(), today.plusDays(21), true) : d); }
                case 20 -> { nextService = today.minusDays(6); faults.add(fault(FAULTS[5], now, 12)); tyreWarning = true; }
                case 21 -> { driver = null; }
                case 22 -> { docs.replaceAll(d -> d.type().equals("PUC certificate")
                        ? new FleetDocument(d.type(), d.reference(), today.plusDays(4), false) : d); }
                case 24 -> { status = "In workshop"; faults.add(fault(FAULTS[4], now, 1)); }
                case 26 -> { efficiency = 14.8; }
                case 27 -> { faults.add(fault(FAULTS[3], now, 3)); status = "Offline"; lastSeen = now.minus(Duration.ofHours(30)); }
                case 29 -> { driver = new Driver(driver.id(), driver.name(), today.plusDays(12), driver.safetyScore()); }
                case 31 -> { idle = 29; }
                case 33 -> { docs.replaceAll(d -> d.type().equals("National permit")
                        ? new FleetDocument(d.type(), d.reference(), today.minusDays(11), false) : d); }
                case 35 -> { nextServiceKm = odometer + 600; nextService = today.plusDays(3); }
                case 36 -> { tyreWarning = true; }
                case 38 -> { nextService = null; }
                default -> { }
            }
            out.add(new Vehicle(id, reg, vin, spec.make(), spec.model(), spec.type(), spec.fuel(), year,
                    DEPOTS[depot], driver, status, lastSeen, DEPOTS[depot] + " region", odometer, previousOdometer,
                    km7d, efficiency, idle, List.copyOf(faults), nextServiceKm, nextService, lastService,
                    tyreWarning, fuelLevel, adBlue, soc, soh, charging, departure, requiredSoc, List.copyOf(docs)));
        }

        // EV issues and a duplicated registration (a classic master-data error).
        List<Vehicle> evs = out.stream().filter(Vehicle::electric).toList();
        if (evs.size() >= 4) {
            replace(out, withEnergy(evs.get(0), 22, 91, "Not plugged", 80));
            replace(out, withEnergy(evs.get(1), 64, 77, "Plugged in", 70));
            replace(out, withEnergy(evs.get(2), 38, 90, "Charger fault", 75));
            replace(out, withFault(evs.get(3), fault(FAULTS[7], now, 2)));
        }
        Vehicle original = out.get(1);
        Vehicle duplicate = out.get(32);
        replace(out, withReg(duplicate, original.regNo()));
        return out;
    }

    private static FaultCode fault(String[] f, Instant now, int daysAgo) {
        return new FaultCode(f[0], f[1], f[2], now.minus(Duration.ofDays(daysAgo)));
    }

    private static String pick(Random rnd, String... options) {
        return options[rnd.nextInt(options.length)];
    }

    private static void replace(List<Vehicle> list, Vehicle v) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(v.id())) {
                list.set(i, v);
                return;
            }
        }
    }

    private static Vehicle withEnergy(Vehicle v, int soc, int soh, String charging, int requiredSoc) {
        return new Vehicle(v.id(), v.regNo(), v.vin(), v.make(), v.model(), v.type(), v.fuel(), v.year(), v.depot(),
                v.driver(), v.status(), v.lastSeen(), v.lastLocation(), v.odometerKm(), v.previousOdometerKm(),
                v.km7d(), v.efficiency(), v.idlePct7d(), v.faults(), v.nextServiceDueKm(), v.nextServiceDate(),
                v.lastServiceDate(), v.tyrePressureWarning(), v.fuelLevelPct(), v.adBlueLevelPct(), soc, soh,
                charging, v.nextDeparture(), requiredSoc, v.documents());
    }

    private static Vehicle withFault(Vehicle v, FaultCode f) {
        List<FaultCode> faults = new ArrayList<>(v.faults());
        faults.add(f);
        return new Vehicle(v.id(), v.regNo(), v.vin(), v.make(), v.model(), v.type(), v.fuel(), v.year(), v.depot(),
                v.driver(), v.status(), v.lastSeen(), v.lastLocation(), v.odometerKm(), v.previousOdometerKm(),
                v.km7d(), v.efficiency(), v.idlePct7d(), List.copyOf(faults), v.nextServiceDueKm(), v.nextServiceDate(),
                v.lastServiceDate(), v.tyrePressureWarning(), v.fuelLevelPct(), v.adBlueLevelPct(), v.socPct(),
                v.batteryHealthPct(), v.chargingStatus(), v.nextDeparture(), v.requiredSocPct(), v.documents());
    }

    private static Vehicle withReg(Vehicle v, String reg) {
        return new Vehicle(v.id(), reg, v.vin(), v.make(), v.model(), v.type(), v.fuel(), v.year(), v.depot(),
                v.driver(), v.status(), v.lastSeen(), v.lastLocation(), v.odometerKm(), v.previousOdometerKm(),
                v.km7d(), v.efficiency(), v.idlePct7d(), v.faults(), v.nextServiceDueKm(), v.nextServiceDate(),
                v.lastServiceDate(), v.tyrePressureWarning(), v.fuelLevelPct(), v.adBlueLevelPct(), v.socPct(),
                v.batteryHealthPct(), v.chargingStatus(), v.nextDeparture(), v.requiredSocPct(), v.documents());
    }
}
