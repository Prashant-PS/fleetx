package com.fleetboard.tyres;

import com.fleetboard.fleet.FleetRepository;
import com.fleetboard.fleet.Tyre;
import com.fleetboard.fleet.TyreRepository;
import com.fleetboard.fleet.Vehicle;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tyre analytics: remaining life, tyre-wear and under-inflation emissions, and a costed action plan.
 * <p>
 * All figures are estimates from the stated assumptions (see {@link #ASSUMPTIONS}); prices are indicative
 * Indian market prices, not quotes.
 */
@Service
public class TyreAnalytics {

    public static final List<String> ASSUMPTIONS = List.of(
            "Removal limit: 4 mm on truck/bus steer tyres, 3 mm on drive tyres, 1.6 mm on LCV tyres.",
            "Tyre-wear particles: rubber lost per mm of tread is 0.6 kg (295/80 R22.5), 0.5 kg (10.00 R20), 0.1 kg (155 R13).",
            "Under-inflation: every 10% below the recommended pressure costs about 1% more fuel or energy.",
            "CO₂: 2.68 kg per litre of diesel, 2.75 kg per kg of CNG, 0.71 kg per kWh (India grid average).",
            "Energy prices: diesel ₹90/l, CNG ₹82/kg, electricity ₹9/kWh. Tyre prices are indicative.",
            "Retreading: drive-axle truck and bus tyres only, at most twice, casing under 6 years old.");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final int ALIGNMENT_COST = 1_800;
    private static final double IRREGULAR_WEAR_LIMIT_MM = 2.0;
    private static final int HOT_TYRE_C = 85;

    public record TyreView(Tyre tyre, String vehicleReg, double removalMm, double wearPer1000Km,
                           int treadLeftPct, long remainingKm, Long remainingDays, LocalDate replaceBy,
                           String status, int pressureDeltaPct, double wearParticlesGPerKm, List<String> flags) {
    }

    public record Recommendation(String vehicleId, String vehicle, String position, String action, String urgency,
                                 LocalDate dueBy, int costInr, int monthlySavingInr, String reason) {
    }

    public record Emissions(double wearParticlesKgPerWeek, double wearParticlesGPerKm, double extraCo2KgPerWeek,
                            double extraEnergyCostInrPerWeek, int underInflatedTyres) {
    }

    public record CostPlan(int next30DaysInr, int next90DaysInr, int replaceCount, int retreadCount,
                           int alignmentCount, int inflationFixCount, int monthlySavingInr, int retreadSavingInr) {
    }

    public record VehicleTyres(String vehicleId, String vehicle, String status, int tyres, double minTreadMm,
                               Long nextReplacementDays, double wearParticlesKgPerWeek, double extraCo2KgPerWeek,
                               int plannedCostInr, List<TyreView> detail, List<Recommendation> recommendations) {
    }

    public record FleetTyres(int vehicles, int tyres, Map<String, Long> statusCounts, int avgTreadLeftPct,
                             Emissions emissions, CostPlan costPlan, List<VehicleTyres> vehiclesAtRisk,
                             List<Recommendation> recommendations, List<String> assumptions) {
    }

    private final FleetRepository fleet;
    private final TyreRepository tyres;

    public TyreAnalytics(FleetRepository fleet, TyreRepository tyres) {
        this.fleet = fleet;
        this.tyres = tyres;
    }

    // ------------------------------------------------------------------ per tyre

    public TyreView view(Vehicle v, Tyre t) {
        double removal = v.type().equals("LCV") ? 1.6 : t.axle().equals("steer") ? 4.0 : 3.0;
        double wear = t.kmRun() == 0 ? 0 : (t.treadNewMm() - t.treadMm()) / t.kmRun() * 1000;
        long remainingKm = wear <= 0 ? 999_999 : Math.max(0, Math.round((t.treadMm() - removal) / wear * 1000));
        int dailyKm = Math.max(1, v.km7d() / 7);
        Long remainingDays = remainingKm == 999_999 ? null : remainingKm / dailyKm;
        LocalDate replaceBy = remainingDays == null ? null : LocalDate.now().plusDays(remainingDays);
        int treadLeft = (int) Math.round(Math.max(0, (t.treadMm() - removal) / (t.treadNewMm() - removal)) * 100);
        int pressureDelta = (int) Math.round((t.pressurePsi() - t.recommendedPsi()) * 100.0 / t.recommendedPsi());

        List<String> flags = new ArrayList<>();
        String status;
        if (t.treadMm() <= removal) {
            status = "REPLACE_NOW";
            flags.add("Tread " + t.treadMm() + " mm is at or below the " + removal + " mm limit");
        } else if (remainingDays != null && remainingDays <= 30) {
            status = "DUE_30";
        } else if (remainingDays != null && remainingDays <= 90) {
            status = "DUE_90";
        } else {
            status = "OK";
        }
        if (pressureDelta <= -10) {
            flags.add((-pressureDelta) + "% under-inflated (" + t.pressurePsi() + " of " + t.recommendedPsi() + " psi)");
        }
        if (t.irregularWearMm() > IRREGULAR_WEAR_LIMIT_MM) {
            flags.add("Irregular shoulder wear " + t.irregularWearMm() + " mm (check alignment)");
        }
        if (t.temperatureC() > HOT_TYRE_C) {
            flags.add("Running hot at " + t.temperatureC() + " °C");
        }
        double particles = wear * kgPerMm(t.size());   // g/km: (mm per 1000 km) x (kg per mm)
        return new TyreView(t, v.regNo(), removal, round2(wear), treadLeft, remainingKm, remainingDays, replaceBy,
                status, pressureDelta, round3(particles), flags);
    }

    // ------------------------------------------------------------------ per vehicle

    public VehicleTyres vehicle(Vehicle v) {
        List<TyreView> views = tyres.forVehicle(v.id()).stream().map(t -> view(v, t)).toList();
        List<Recommendation> recs = recommendations(v, views);
        double particlesGPerKm = views.stream().mapToDouble(TyreView::wearParticlesGPerKm).sum();
        double underPenalty = fuelPenalty(views);
        double energyPerWeek = v.km7d() / Math.max(0.1, v.efficiency());
        String status = views.stream().map(TyreView::status).min(Comparator.comparingInt(TyreAnalytics::statusRank)).orElse("OK");
        if (status.equals("OK") && views.stream().anyMatch(tv -> !tv.flags().isEmpty())) {
            status = "ATTENTION";
        }
        Long next = views.stream().map(TyreView::remainingDays).filter(d -> d != null).min(Long::compare).orElse(null);
        return new VehicleTyres(v.id(), v.label(), status, views.size(),
                views.stream().mapToDouble(tv -> tv.tyre().treadMm()).min().orElse(0), next,
                round2(particlesGPerKm * v.km7d() / 1000.0), round2(energyPerWeek * underPenalty * co2Factor(v)),
                recs.stream().filter(r -> r.dueBy() == null || !r.dueBy().isAfter(LocalDate.now().plusDays(90)))
                        .mapToInt(Recommendation::costInr).sum(),
                views, recs);
    }

    private List<Recommendation> recommendations(Vehicle v, List<TyreView> views) {
        List<Recommendation> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (TyreView tv : views) {
            Tyre t = tv.tyre();
            if (!tv.status().equals("OK")) {
                boolean retreadable = t.retreadPriceInr() > 0 && t.axle().equals("drive") && t.retreads() < 2
                        && ChronoUnit.YEARS.between(t.fitted(), today) < 6 && t.treadMm() >= 1.6
                        && t.temperatureC() <= HOT_TYRE_C;
                String urgency = switch (tv.status()) {
                    case "REPLACE_NOW" -> "HIGH";
                    case "DUE_30" -> "MEDIUM";
                    default -> "LOW";
                };
                LocalDate due = tv.status().equals("REPLACE_NOW") ? today : tv.replaceBy();
                if (retreadable) {
                    out.add(new Recommendation(v.id(), v.label(), t.position(), "Retread", urgency, due,
                            t.retreadPriceInr(), 0, "Tread " + t.treadMm() + " mm; casing is retreadable (saves ₹"
                            + fmt(t.newPriceInr() - t.retreadPriceInr()) + " against a new " + t.brand() + " tyre)."));
                } else {
                    out.add(new Recommendation(v.id(), v.label(), t.position(), "Replace", urgency, due,
                            t.newPriceInr(), 0, "Tread " + t.treadMm() + " mm"
                            + (t.axle().equals("steer") ? "; steer tyres should not be retreaded" : "")
                            + ". New " + t.brand() + " " + t.size() + "."));
                }
            }
            if (t.temperatureC() > HOT_TYRE_C) {
                out.add(new Recommendation(v.id(), v.label(), t.position(), "Inspect heat", "HIGH", today, 0, 0,
                        "Tyre at " + t.temperatureC() + " °C: check for overload, brake drag or a slow puncture."));
            }
        }
        List<TyreView> under = views.stream().filter(tv -> tv.pressureDeltaPct() <= -10).toList();
        if (!under.isEmpty()) {
            double penalty = fuelPenalty(views);
            int monthly = (int) Math.round(v.km7d() / Math.max(0.1, v.efficiency()) * penalty * energyPrice(v) * 4.33);
            boolean severe = under.stream().anyMatch(tv -> tv.pressureDeltaPct() <= -20);
            out.add(new Recommendation(v.id(), v.label(),
                    under.stream().map(tv -> tv.tyre().position()).reduce((a, b) -> a + ", " + b).orElse(""),
                    "Inflate to spec", severe ? "HIGH" : "MEDIUM", today, 0, monthly,
                    under.size() + " tyre" + (under.size() == 1 ? "" : "s") + " under-inflated; correct pressure cuts "
                            + "fuel use and uneven wear" + (severe ? ", and a tyre 20%+ low risks a blow-out." : ".")));
        }
        List<TyreView> irregular = views.stream().filter(tv -> tv.tyre().irregularWearMm() > IRREGULAR_WEAR_LIMIT_MM).toList();
        if (!irregular.isEmpty()) {
            int lifeSaving = (int) Math.round(irregular.stream().mapToInt(tv -> tv.tyre().newPriceInr()).sum() * 0.15);
            out.add(new Recommendation(v.id(), v.label(),
                    irregular.stream().map(tv -> tv.tyre().position()).reduce((a, b) -> a + ", " + b).orElse(""),
                    "Wheel alignment", "MEDIUM", today.plusDays(7), ALIGNMENT_COST, 0,
                    "Irregular shoulder wear; alignment typically extends tyre life about 15% (≈₹" + fmt(lifeSaving)
                            + " of tyre value)."));
        }
        out.sort(Comparator.comparingInt((Recommendation r) -> urgencyRank(r.urgency()))
                .thenComparing(Recommendation::dueBy, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    // ------------------------------------------------------------------ fleet

    public FleetTyres fleet() {
        List<VehicleTyres> all = fleet.all().stream().map(this::vehicle).toList();
        List<TyreView> views = all.stream().flatMap(vt -> vt.detail().stream()).toList();
        List<Recommendation> recs = all.stream().flatMap(vt -> vt.recommendations().stream())
                .sorted(Comparator.comparingInt((Recommendation r) -> urgencyRank(r.urgency()))
                        .thenComparing(Recommendation::dueBy, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of("REPLACE_NOW", "DUE_30", "DUE_90", "OK")) {
            counts.put(s, views.stream().filter(tv -> tv.status().equals(s)).count());
        }
        double kmWeek = fleet.all().stream().mapToLong(Vehicle::km7d).sum();
        double particlesKg = all.stream().mapToDouble(VehicleTyres::wearParticlesKgPerWeek).sum();
        double extraCo2 = all.stream().mapToDouble(VehicleTyres::extraCo2KgPerWeek).sum();
        double extraCost = fleet.all().stream()
                .mapToDouble(v -> v.km7d() / Math.max(0.1, v.efficiency()) * fuelPenalty(vehicle(v).detail()) * energyPrice(v))
                .sum();
        Emissions emissions = new Emissions(round2(particlesKg), round2(kmWeek == 0 ? 0 : particlesKg * 1000 / kmWeek),
                round2(extraCo2), Math.round(extraCost), (int) views.stream().filter(tv -> tv.pressureDeltaPct() <= -10).count());

        LocalDate in30 = LocalDate.now().plusDays(30);
        LocalDate in90 = LocalDate.now().plusDays(90);
        CostPlan plan = new CostPlan(
                recs.stream().filter(r -> r.dueBy() != null && !r.dueBy().isAfter(in30)).mapToInt(Recommendation::costInr).sum(),
                recs.stream().filter(r -> r.dueBy() != null && !r.dueBy().isAfter(in90)).mapToInt(Recommendation::costInr).sum(),
                (int) recs.stream().filter(r -> r.action().equals("Replace")).count(),
                (int) recs.stream().filter(r -> r.action().equals("Retread")).count(),
                (int) recs.stream().filter(r -> r.action().equals("Wheel alignment")).count(),
                (int) recs.stream().filter(r -> r.action().equals("Inflate to spec")).count(),
                recs.stream().mapToInt(Recommendation::monthlySavingInr).sum(),
                views.stream().filter(tv -> recs.stream().anyMatch(r -> r.action().equals("Retread")
                                && r.vehicleId().equals(tv.tyre().vehicleId()) && r.position().equals(tv.tyre().position())))
                        .mapToInt(tv -> tv.tyre().newPriceInr() - tv.tyre().retreadPriceInr()).sum());

        List<VehicleTyres> atRisk = all.stream().filter(vt -> !vt.status().equals("OK"))
                .sorted(Comparator.comparingInt((VehicleTyres vt) -> statusRank(vt.status()))
                        .thenComparing(VehicleTyres::nextReplacementDays, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(vt -> new VehicleTyres(vt.vehicleId(), vt.vehicle(), vt.status(), vt.tyres(), vt.minTreadMm(),
                        vt.nextReplacementDays(), vt.wearParticlesKgPerWeek(), vt.extraCo2KgPerWeek(), vt.plannedCostInr(),
                        List.of(), vt.recommendations()))
                .toList();
        int avgLeft = (int) Math.round(views.stream().mapToInt(TyreView::treadLeftPct).average().orElse(0));
        return new FleetTyres(all.size(), views.size(), counts, avgLeft, emissions, plan, atRisk, recs, ASSUMPTIONS);
    }

    // ------------------------------------------------------------------ markdown for the assistant

    public String fleetMarkdown() {
        FleetTyres f = fleet();
        Emissions e = f.emissions();
        CostPlan c = f.costPlan();
        StringBuilder sb = new StringBuilder();
        sb.append("**Tyre status: ").append(f.statusCounts().get("REPLACE_NOW")).append(" tyres need replacing now, ")
                .append(f.statusCounts().get("DUE_30")).append(" are due within 30 days.** ")
                .append(f.tyres()).append(" tyres on ").append(f.vehicles()).append(" vehicles have ")
                .append(f.avgTreadLeftPct()).append("% of usable tread left on average.\n\n");

        sb.append("### Tyre emissions (estimated, this week)\n\n| Measure | Value |\n|---|---|\n")
                .append("| Tyre-wear particles shed | ").append(e.wearParticlesKgPerWeek()).append(" kg (")
                .append(e.wearParticlesGPerKm()).append(" g per vehicle-km) |\n")
                .append("| Extra CO₂ from under-inflation | ").append(e.extraCo2KgPerWeek()).append(" kg |\n")
                .append("| Extra fuel/energy cost from under-inflation | ₹").append(fmt((int) e.extraEnergyCostInrPerWeek())).append(" |\n")
                .append("| Under-inflated tyres (10%+ low) | ").append(e.underInflatedTyres()).append(" |\n\n");

        sb.append("### Pending life: next replacements\n\n| Vehicle | Status | Lowest tread | Next replacement | Planned cost (90 days) |\n|---|---|---|---|---|\n");
        f.vehiclesAtRisk().stream().limit(10).forEach(vt -> sb.append("| ").append(vt.vehicle()).append(" | ")
                .append(statusLabel(vt.status())).append(" | ").append(vt.minTreadMm()).append(" mm | ")
                .append(vt.nextReplacementDays() == null ? "—" : vt.nextReplacementDays() == 0 ? "now" : "in " + vt.nextReplacementDays() + " days")
                .append(" | ₹").append(fmt(vt.plannedCostInr())).append(" |\n"));

        sb.append("\n### Suggested actions and cost\n\n| Urgency | Vehicle | Tyre | Action | Cost | Saving |\n|---|---|---|---|---|---|\n");
        f.recommendations().stream().limit(12).forEach(r -> sb.append("| ").append(r.urgency()).append(" | ")
                .append(r.vehicle()).append(" | ").append(r.position()).append(" | **").append(r.action()).append("** ")
                .append(r.reason()).append(" | ₹").append(fmt(r.costInr())).append(" | ")
                .append(r.monthlySavingInr() > 0 ? "₹" + fmt(r.monthlySavingInr()) + "/month" : "—").append(" |\n"));
        if (f.recommendations().size() > 12) {
            sb.append("\n…and ").append(f.recommendations().size() - 12).append(" more on the Tire Analytics page.\n");
        }

        sb.append("\n**Budget:** ₹").append(fmt(c.next30DaysInr())).append(" in the next 30 days, ₹")
                .append(fmt(c.next90DaysInr())).append(" in 90 days (").append(c.replaceCount()).append(" new tyres, ")
                .append(c.retreadCount()).append(" retreads saving ₹").append(fmt(c.retreadSavingInr())).append(", ")
                .append(c.alignmentCount()).append(" alignments). Fixing tyre pressures saves about ₹")
                .append(fmt(c.monthlySavingInr())).append(" a month.\n\n")
                .append("_Estimates from demo tyre data; prices are indicative. See the assumptions on the Tire Analytics page._");
        return sb.toString();
    }

    public String vehicleMarkdown(Vehicle v) {
        VehicleTyres vt = vehicle(v);
        StringBuilder sb = new StringBuilder("**Tyres on ").append(v.label()).append(": ").append(statusLabel(vt.status()))
                .append(".** Wear particles ").append(vt.wearParticlesKgPerWeek()).append(" kg/week, extra CO₂ from "
                        + "under-inflation ").append(vt.extraCo2KgPerWeek()).append(" kg/week.\n\n")
                .append("| Position | Brand | Tread | Life left | Replace by | Pressure | Notes |\n|---|---|---|---|---|---|---|\n");
        for (TyreView tv : vt.detail()) {
            Tyre t = tv.tyre();
            sb.append("| ").append(t.position()).append(" | ").append(t.brand()).append(" | ").append(t.treadMm())
                    .append(" mm (").append(tv.treadLeftPct()).append("%) | ")
                    .append(String.format(Locale.ENGLISH, "%,d km", tv.remainingKm())).append(" | ")
                    .append(tv.status().equals("REPLACE_NOW") ? "now" : tv.replaceBy() == null ? "—" : DATE.format(tv.replaceBy()))
                    .append(" | ").append(t.pressurePsi()).append("/").append(t.recommendedPsi()).append(" psi | ")
                    .append(tv.flags().isEmpty() ? "—" : String.join("; ", tv.flags())).append(" |\n");
        }
        if (!vt.recommendations().isEmpty()) {
            sb.append("\n**Suggested actions**\n\n");
            vt.recommendations().forEach(r -> sb.append("- **").append(r.action()).append("** (").append(r.position())
                    .append("): ₹").append(fmt(r.costInr()))
                    .append(r.monthlySavingInr() > 0 ? ", saves ≈₹" + fmt(r.monthlySavingInr()) + "/month" : "")
                    .append(". ").append(r.reason()).append('\n'));
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ helpers

    /** Fuel/energy penalty as a fraction: about 1% per 10% average under-inflation. */
    private static double fuelPenalty(List<TyreView> views) {
        double avgUnder = views.stream().mapToInt(tv -> Math.max(0, -tv.pressureDeltaPct())).average().orElse(0);
        return avgUnder / 10.0 * 0.01;
    }

    private static double kgPerMm(String size) {
        return switch (size) {
            case "295/80 R22.5" -> 0.6;
            case "10.00 R20" -> 0.5;
            default -> 0.1;
        };
    }

    private static double co2Factor(Vehicle v) {
        return switch (v.fuel()) {
            case "Electric" -> 0.71;
            case "CNG" -> 2.75;
            default -> 2.68;
        };
    }

    private static double energyPrice(Vehicle v) {
        return switch (v.fuel()) {
            case "Electric" -> 9;
            case "CNG" -> 82;
            default -> 90;
        };
    }

    public static String statusLabel(String status) {
        return switch (status) {
            case "REPLACE_NOW" -> "Replace now";
            case "DUE_30" -> "Due within 30 days";
            case "DUE_90" -> "Due within 90 days";
            case "ATTENTION" -> "Needs attention";
            default -> "OK";
        };
    }

    private static int statusRank(String s) {
        return switch (s) {
            case "REPLACE_NOW" -> 0;
            case "DUE_30" -> 1;
            case "ATTENTION" -> 2;
            case "DUE_90" -> 3;
            default -> 4;
        };
    }

    private static int urgencyRank(String u) {
        return switch (u) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    static String fmt(int inr) {
        // Indian digit grouping: 12,34,567
        String s = Integer.toString(Math.abs(inr));
        if (s.length() > 3) {
            String last3 = s.substring(s.length() - 3);
            String rest = s.substring(0, s.length() - 3).replaceAll("\\B(?=(\\d{2})+(?!\\d))", ",");
            s = rest + "," + last3;
        }
        return (inr < 0 ? "-" : "") + s;
    }

    private static double round2(double d) {
        return Math.round(d * 100) / 100.0;
    }

    private static double round3(double d) {
        return Math.round(d * 1000) / 1000.0;
    }
}
