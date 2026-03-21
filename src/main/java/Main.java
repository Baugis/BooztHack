import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

public class Main {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public static void main(String[] args) {

        // -------------------------------------------------------------------------
        // 0. Load config
        // -------------------------------------------------------------------------
        ConfigLoader cfg = new ConfigLoader("config.json");

        // -------------------------------------------------------------------------
        // 1. Load data from JSON files
        // -------------------------------------------------------------------------
        DataLoader loader = new DataLoader(
                cfg.getShipmentsPath(),
                cfg.getGridsPath(),
                cfg.getBinsPath()
        );

        List<Shipment> shipments = loader.loadShipmentsJson();
        List<Bin>      bins      = loader.loadBinsJson();
        List<Grid>     grids     = loader.loadGridsJson();

        System.out.println("Loaded: " + shipments.size() + " shipments, "
                + bins.size() + " bins, "
                + grids.size() + " grids");

        if (shipments.isEmpty() || grids.isEmpty()) {
            System.err.println("No data loaded — check that the JSON files exist.");
            return;
        }

        // -------------------------------------------------------------------------
        // 2. Epoch & simulation duration from config
        // -------------------------------------------------------------------------
        Instant epoch = Instant.parse(cfg.getEpochDate());
        double simDurationSeconds = cfg.getSimDurationSeconds();

        System.out.println("Simulation epoch   : " + epoch);
        System.out.printf( "Simulation duration: %.0f s (%.1f days)%n",
                simDurationSeconds, simDurationSeconds / 86_400.0);

        Simulation sim = new Simulation(simDurationSeconds, epoch);

        // -------------------------------------------------------------------------
        // 3. Register grids
        // -------------------------------------------------------------------------
        for (Grid grid : grids) {
            sim.addGrid(grid);
        }

        // -------------------------------------------------------------------------
        // 4. Register bins into their grids
        // -------------------------------------------------------------------------
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
        // 7. Load truck schedules & conveyor delays from params.json
        // -------------------------------------------------------------------------
        ParamsLoader paramsLoader = new ParamsLoader(cfg.getParamsPath());
        List<TruckSchedule> truckSchedules = paramsLoader.loadTruckSchedules();
        System.out.println("Loaded: " + truckSchedules.size() + " truck schedule(s)");

        java.util.Map<String, Double> conveyors = paramsLoader.loadConveyors();
        sim.registerConveyors(conveyors);
        System.out.println("Loaded: " + conveyors.size() + " conveyor(s)");

        LocalDate epochDate = epoch.atZone(ZoneOffset.UTC).toLocalDate();
        String epochDayName = epochDate.getDayOfWeek()
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        System.out.println("Simulation day     : " + epochDayName);

        // Vietoj esamo truck scheduling bloko:
        int totalDays = (int) Math.ceil(simDurationSeconds / 86_400.0);

        for (int day = 0; day < totalDays; day++) {
            LocalDate currentDate = epochDate.plusDays(day);
            String dayName = currentDate.getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            double dayOffsetSecs = day * 86_400.0;

            for (TruckSchedule ts : truckSchedules) {
                if (!ts.weekdays.contains(dayName)) continue;
                for (String pullTimeStr : ts.pullTimes) {
                    double pullTimeSecs = LocalTime.parse(pullTimeStr, TIME_FMT).toSecondOfDay();
                    double absoluteTime = dayOffsetSecs + pullTimeSecs;
                    if (absoluteTime > simDurationSeconds) continue;

                    sim.schedule(new TruckArrived(
                            absoluteTime, sim.nextSequence(),
                            ts.sortingDirection, absoluteTime));
                    System.out.printf("Scheduled truck: dir=%s day=%s at %s (t=%.0fs)%n",
                            ts.sortingDirection, dayName, pullTimeStr, absoluteTime);
                }
            }
        }

        // -------------------------------------------------------------------------
        // 8. Router setup and first trigger at t=0
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
        printSummary(sim, truckSchedules, epochDayName);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String detectRouterPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String base = "Data/router/";
        if (os.contains("win")) return base + "router-windows-amd64.exe";
        if (os.contains("mac")) return base + "router-darwin-amd64";
        return base + "router-linux-amd64";
    }

    private static void printSummary(Simulation sim,
                                     List<TruckSchedule> truckSchedules,
                                     String epochDayName) {
        java.util.Map<String, Double> earliestPull = new java.util.HashMap<>();
        for (TruckSchedule ts : truckSchedules) {
            if (!ts.weekdays.contains(epochDayName)) continue;
            for (String pt : ts.pullTimes) {
                double secs = LocalTime.parse(pt, DateTimeFormatter.ofPattern("HH:mm"))
                        .toSecondOfDay();
                earliestPull.merge(ts.sortingDirection, secs, Math::min);
            }
        }

        int received = 0, routed = 0, consolidation = 0, ready = 0,
                picking = 0, packed = 0, shipped = 0;
        double totalPackTime = 0; int packCount = 0;
        double totalDwell = 0;   int dwellCount = 0;
        int onTime = 0, late = 0, noTruck = 0;

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

                    Double pull = earliestPull.get(s.getSortingDirection());
                    if (pull == null)                              noTruck++;
                    else if (s.getPackedAt() > 0
                            && s.getPackedAt() <= pull)            onTime++;
                    else                                           late++;
                }
            }
        }

        int totalPacked = packed + shipped;

        System.out.println("\n");
        System.out.println("SIMULATION SUMMARY\n");
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