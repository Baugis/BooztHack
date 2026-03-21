import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Main {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public static void main(String[] args) {

        // -------------------------------------------------------------------------
        // 1. Load data
        // -------------------------------------------------------------------------
        DataLoader loader = new DataLoader(
                "Data/sample-data/level7/shipments.json",
                "Data/sample-data/level7/grids.json",
                "Data/sample-data/level7/bins.json"
        );

        List<Shipment> shipments = loader.loadShipmentsJson();
        List<Bin>      bins      = loader.loadBinsJson();
        List<Grid>     grids     = loader.loadGridsJson();

        System.out.println("Loaded: " + shipments.size() + " shipments, "
                + bins.size() + " bins, "
                + grids.size() + " grids");

        if (shipments.isEmpty() || grids.isEmpty()) {
            System.err.println("No data loaded — check that Data/sample-data/level7/*.json files exist.");
            return;
        }

        // -------------------------------------------------------------------------
        // 2. Load truck schedules from params.json
        // -------------------------------------------------------------------------
        ParamsLoader paramsLoader = new ParamsLoader("Data/sample-data/level7/params.json");
        List<TruckSchedule> truckSchedules = paramsLoader.loadTruckSchedules();
        System.out.println("Loaded: " + truckSchedules.size() + " truck schedule(s)");

        // -------------------------------------------------------------------------
        // 3. Simulation setup
        // -------------------------------------------------------------------------
        Instant epoch = Instant.parse("2026-03-03T00:00:00Z");// Monday
        System.out.println("Simulation epoch: " + epoch);

        double simDurationSeconds = 86_400.0*20;
        Simulation sim = new Simulation(simDurationSeconds, epoch);

        // -------------------------------------------------------------------------
        // 4. Register grids and bins
        // -------------------------------------------------------------------------
        for (Grid grid : grids) {
            sim.addGrid(grid);
        }
        for (Bin bin : bins) {
            Grid grid = sim.getGrid(bin.getGridId());
            if (grid != null) {
                grid.addBin(bin);
            } else {
                System.err.println("Warning: bin " + bin.getBinId()
                        + " references unknown grid " + bin.getGridId() + " — skipped");
            }
        }

        // -------------------------------------------------------------------------
        // 5. Schedule ShipmentReceived events
        // -------------------------------------------------------------------------
        for (Shipment shipment : shipments) {
            if (shipment.createdAt == null) continue;
            Instant createdAt = Instant.parse(shipment.createdAt);
            double offsetSeconds = Math.max(0,
                    (double) java.time.Duration.between(epoch, createdAt).toSeconds());
            sim.schedule(new ShipmentReceived(
                    offsetSeconds, sim.nextSequence(), shipment));
        }

        // -------------------------------------------------------------------------
        // 6. Schedule shift open/close events
        // -------------------------------------------------------------------------
        sim.scheduleAllShifts();

        // -------------------------------------------------------------------------
        // 7. Schedule TruckArrived events from params.json
        //    For each schedule: filter to the epoch's weekday, then schedule one
        //    TruckArrived per pull time that falls within the simulation window.
        // -------------------------------------------------------------------------
        LocalDate epochDate = epoch.atZone(ZoneOffset.UTC).toLocalDate();
        DayOfWeek epochDayOfWeek = epochDate.getDayOfWeek();
        // English display name matches params.json weekday strings ("Monday", etc.)
        String epochDayName = epochDayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        int truckEventsScheduled = 0;

        for (TruckSchedule schedule : truckSchedules) {
            // Only schedule trucks that run on the epoch's day of week
            if (!schedule.weekdays.contains(epochDayName)) {
                System.out.printf("Skipping trucks for dir=%s (not active on %s)%n",
                        schedule.sortingDirection, epochDayName);
                continue;
            }

            for (String pullTimeStr : schedule.pullTimes) {
                LocalTime pullTime = LocalTime.parse(pullTimeStr, TIME_FMT);
                double pullTimeSecs = pullTime.toSecondOfDay(); // seconds from midnight

                if (pullTimeSecs > simDurationSeconds) {
                    System.out.printf("Skipping truck dir=%s at %s (outside sim window)%n",
                            schedule.sortingDirection, pullTimeStr);
                    continue;
                }

                sim.schedule(new TruckArrived(
                        pullTimeSecs,
                        sim.nextSequence(),
                        schedule.sortingDirection,
                        pullTimeSecs
                ));

                System.out.printf("Scheduled truck: dir=%s at %s (t=%.0fs)%n",
                        schedule.sortingDirection, pullTimeStr, pullTimeSecs);
                truckEventsScheduled++;
            }
        }

        System.out.println("Total truck events scheduled: " + truckEventsScheduled);

        // -------------------------------------------------------------------------
        // 8. Router setup and first trigger
        // -------------------------------------------------------------------------
        String routerPath = detectRouterPath();
        RouterCaller routerCaller = new RouterCaller(routerPath);

        sim.schedule(new ShipmentRouterTriggered(
                0.0, sim.nextSequence(), routerCaller));

        // -------------------------------------------------------------------------
        // 9. Run
        // -------------------------------------------------------------------------
        System.out.println("\n========== SIMULATION START ==========\n");
        sim.run();
        System.out.println("\n========== SIMULATION END ==========\n");

        // -------------------------------------------------------------------------
        // 10. Summary
        // -------------------------------------------------------------------------
        printSummary(sim, truckSchedules, epoch);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String detectRouterPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String base = "Data/router/";
        if (os.contains("win"))  return base + "router-windows-amd64.exe";
        if (os.contains("mac"))  return base + "router-darwin-amd64";
        return base + "router-linux-amd64";
    }

    /**
     * End-of-simulation report.
     *
     * "Packed on Time" = shipment was packed before the earliest truck pull
     * time for its sorting direction on the simulation day.
     * Unpacked shipments (not PACKED or SHIPPED) count as missed.
     */
    private static void printSummary(Simulation sim,
                                     List<TruckSchedule> truckSchedules,
                                     Instant epoch) {
        // Build a map: sortingDirection -> earliest pull time in seconds
        Map<String, Double> earliestPullTime = new HashMap<>();
        LocalDate epochDate = epoch.atZone(ZoneOffset.UTC).toLocalDate();
        String epochDayName = epochDate.getDayOfWeek()
                .getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);

        for (TruckSchedule ts : truckSchedules) {
            if (!ts.weekdays.contains(epochDayName)) continue;
            for (String pt : ts.pullTimes) {
                double secs = LocalTime.parse(pt, DateTimeFormatter.ofPattern("HH:mm"))
                        .toSecondOfDay();
                earliestPullTime.merge(ts.sortingDirection, secs, Math::min);
            }
        }

        // Count statuses
        int received = 0, routed = 0, consolidation = 0, ready = 0,
                picking = 0, packed = 0, shipped = 0;
        double totalPackTime = 0;
        int    packCount     = 0;
        double totalDwell    = 0;
        int    dwellCount    = 0;
        int    onTime        = 0;
        int    late          = 0;
        int    noTruck       = 0; // packed but no truck schedule for their direction

        for (Shipment s : sim.getAllShipments()) {
            switch (s.getStatus()) {
                case RECEIVED      -> received++;
                case ROUTED        -> routed++;
                case CONSOLIDATION -> consolidation++;
                case READY         -> ready++;
                case PICKING       -> picking++;
                case PACKED, SHIPPED -> {
                    if (s.getStatus() == Shipment.ShipmentStatus.PACKED) packed++;
                    else shipped++;

                    if (s.getPackedAt() > 0) {
                        totalPackTime += s.getPackedAt() - s.getReceivedTime();
                        packCount++;
                    }
                    if (s.getStatus() == Shipment.ShipmentStatus.SHIPPED
                            && s.getShippedAt() > 0 && s.getPackedAt() > 0) {
                        totalDwell += s.getShippedAt() - s.getPackedAt();
                        dwellCount++;
                    }

                    // On-time check
                    Double pullTime = earliestPullTime.get(s.getSortingDirection());
                    if (pullTime == null) {
                        noTruck++;
                    } else if (s.getPackedAt() > 0 && s.getPackedAt() <= pullTime) {
                        onTime++;
                    } else {
                        late++;
                    }
                }
            }
        }

        int totalPacked = packed + shipped;

        System.out.println("\n╔══════════════════════════════════╗");
        System.out.println("║       SIMULATION SUMMARY         ║");
        System.out.println("╚══════════════════════════════════╝");
        System.out.println("Total shipments     : " + sim.getAllShipments().size());
        System.out.println("  RECEIVED          : " + received);
        System.out.println("  ROUTED            : " + routed);
        System.out.println("  CONSOLIDATION     : " + consolidation);
        System.out.println("  READY             : " + ready);
        System.out.println("  PICKING           : " + picking);
        System.out.println("  PACKED            : " + packed);
        System.out.println("  SHIPPED           : " + shipped);
        System.out.println();
        System.out.println("─── Truck KPIs ────────────────────");
        System.out.printf( "  Packed on time    : %d / %d%s%n",
                onTime, totalPacked,
                totalPacked > 0
                        ? String.format(" (%.1f%%)", 100.0 * onTime / totalPacked)
                        : "");
        System.out.printf( "  Packed late       : %d%n", late);
        if (noTruck > 0)
            System.out.printf( "  No truck scheduled: %d%n", noTruck);
        System.out.println();
        System.out.println("─── Timing ────────────────────────");
        if (packCount > 0)
            System.out.printf("  Avg time to pack  : %.0fs%n", totalPackTime / packCount);
        if (dwellCount > 0)
            System.out.printf("  Avg dwell at dock : %.0fs%n", totalDwell / dwellCount);
    }
}