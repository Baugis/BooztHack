package com.Warehouse.Simulator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.engine.events.ShipmentReceived;
import com.Warehouse.Simulator.engine.events.ShipmentRouterTriggered;
import com.Warehouse.Simulator.engine.events.TruckArrived;
import com.Warehouse.Simulator.engine.events.TruckSchedule;
import com.Warehouse.Simulator.io.ConfigLoader;
import com.Warehouse.Simulator.io.DataLoader;
import com.Warehouse.Simulator.io.ParamsLoader;
import com.Warehouse.Simulator.model.Bin;
import com.Warehouse.Simulator.model.Grid;
import com.Warehouse.Simulator.model.Shipment;
import com.Warehouse.Simulator.router.RouterCaller;

/**
 * Simulation entry point. Wires together all subsystems and runs the
 * discrete-event loop from start to finish.
 *
 * <p>Startup sequence:
 * <ol>
 *   <li>Load {@code config.json} via {@link ConfigLoader}.</li>
 *   <li>Deserialise shipments, bins, and grids from the configured data files.</li>
 *   <li>Register grids and place bins into their starting grids.</li>
 *   <li>Schedule a {@link ShipmentReceived} event for every shipment.</li>
 *   <li>Schedule shift open/close/break events for the full simulation window.</li>
 *   <li>Load truck schedules and conveyor delays from {@code params.json};
 *       schedule a {@link TruckArrived} event for every pull time in the window.</li>
 *   <li>Schedule the first {@link ShipmentRouterTriggered} at {@code t=0}.</li>
 *   <li>Run the event loop.</li>
 *   <li>Print a post-run summary with shipment status counts and KPIs.</li>
 * </ol>
 */
public class Main {

    /** Formatter used to parse {@code "HH:mm"} pull times from truck schedules. */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public static void main(String[] args) {

        // -------------------------------------------------------------------------
        // 0. Load config
        // -------------------------------------------------------------------------
        String workingDir = System.getProperty("user.dir");
        System.out.println("Working dir: " + workingDir);
        ConfigLoader cfg = new ConfigLoader(workingDir + "/config.json");
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

        // Abort early — nothing useful can happen without shipments or grids
        if (shipments.isEmpty() || grids.isEmpty()) {
            System.err.println("No data loaded — check that the JSON files exist.");
            return;
        }

        // -------------------------------------------------------------------------
        // 2. Epoch & simulation duration from config
        // -------------------------------------------------------------------------
        // The epoch is simulation time t=0. All event timestamps are expressed
        // as seconds elapsed since this instant.
        Instant epoch             = Instant.parse(cfg.getEpochDate());
        double  simDurationSeconds = cfg.getSimDurationSeconds();

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
        // 4. Register bins into their starting grids
        // -------------------------------------------------------------------------
        // Bins reference their grid by ID; warn and skip if the grid is unknown.
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
        // Each shipment fires at its createdAt offset from epoch.
        // Negative offsets (shipments created before epoch) are clamped to t=0.
        for (Shipment shipment : shipments) {
            if (shipment.createdAt == null) continue;
            Instant createdAt     = Instant.parse(shipment.createdAt);
            double  offsetSeconds = Math.max(0,
                    (double) java.time.Duration.between(epoch, createdAt).toSeconds());
            sim.schedule(new ShipmentReceived(
                    offsetSeconds, sim.nextSequence(), shipment));
        }

        // -------------------------------------------------------------------------
        // 6. Schedule shift open/close/break events
        // -------------------------------------------------------------------------
        // Delegates to Simulation, which walks each grid's shifts and creates
        // ShiftStarted, ShiftEnded, BreakStartEvent, and BreakEndEvent instances.
        sim.scheduleAllShifts();

        // -------------------------------------------------------------------------
        // 7. Load truck schedules and conveyor delays from params.json
        // -------------------------------------------------------------------------
        ParamsLoader               paramsLoader   = new ParamsLoader(cfg.getParamsPath());
        List<TruckSchedule>        truckSchedules = paramsLoader.loadTruckSchedules();
        java.util.Map<String, Double> conveyors   = paramsLoader.loadConveyors();

        sim.registerConveyors(conveyors);
        System.out.println("Loaded: " + truckSchedules.size() + " truck schedule(s)");
        System.out.println("Loaded: " + conveyors.size() + " conveyor(s)");

        java.util.Map<String, Double> deliveryTimes = paramsLoader.loadDeliveryTimes();
        sim.registerDeliveryTimes(deliveryTimes);
        System.out.println("Loaded: " + deliveryTimes.size() + " delivery time(s)");

        // Determine the day-of-week name for the epoch date (used for truck scheduling)
        LocalDate epochDate    = epoch.atZone(ZoneOffset.UTC).toLocalDate();
        String    epochDayName = epochDate.getDayOfWeek()
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        System.out.println("Simulation day     : " + epochDayName);

        // Schedule a TruckArrived event for every pull time that falls within
        // the simulation window, across all simulated days and truck schedules.
        int totalDays = (int) Math.ceil(simDurationSeconds / 86_400.0);

        for (int day = 0; day < totalDays; day++) {
            LocalDate currentDate   = epochDate.plusDays(day);
            String    dayName       = currentDate.getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            double    dayOffsetSecs = day * 86_400.0;

            for (TruckSchedule ts : truckSchedules) {
                if (!ts.weekdays.contains(dayName)) continue;

                for (String pullTimeStr : ts.pullTimes) {
                    double pullTimeSecs  = LocalTime.parse(pullTimeStr, TIME_FMT).toSecondOfDay();
                    double absoluteTime  = dayOffsetSecs + pullTimeSecs;
                    if (absoluteTime > simDurationSeconds) continue; // outside window

                    sim.schedule(new TruckArrived(
                            absoluteTime, sim.nextSequence(),
                            ts.sortingDirection, absoluteTime));
                    System.out.printf("Scheduled truck: dir=%s day=%s at %s (t=%.0fs)%n",
                            ts.sortingDirection, dayName, pullTimeStr, absoluteTime);
                }
            }
        }

        // -------------------------------------------------------------------------
        // 8. Router setup — first trigger at t=0
        // -------------------------------------------------------------------------
        // The router binary path is OS-detected. The first ShipmentRouterTriggered
        // fires at t=0; subsequent triggers are re-scheduled by the event itself.
        String       routerPath   = detectRouterPath();
        RouterCaller routerCaller = new RouterCaller(routerPath);

        sim.schedule(new ShipmentRouterTriggered(
                0.0, sim.nextSequence(), routerCaller));

        // -------------------------------------------------------------------------
        // 9. Run the event loop
        // -------------------------------------------------------------------------
        System.out.println("\n========== SIMULATION START ==========\n");
        sim.run();
        System.out.println("\n========== SIMULATION END ==========\n");

        // -------------------------------------------------------------------------
        // 10. Post-run summary
        // -------------------------------------------------------------------------
        printSummary(sim, truckSchedules, epochDayName, epochDate, simDurationSeconds);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the correct router binary path for the current OS.
     *
     * <p>All three binaries are expected under {@code Data/router/}:
     * <ul>
     *   <li>{@code router-windows-amd64.exe} — Windows</li>
     *   <li>{@code router-darwin-amd64}      — macOS</li>
     *   <li>{@code router-linux-amd64}       — Linux (default)</li>
     * </ul>
     *
     * @return path to the router executable for the current platform
     */
    private static String detectRouterPath() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String base = "Data/router/";
        if (os.contains("win")) return base + "router-windows-amd64.exe";
        if (os.contains("mac")) return base + "router-darwin-amd64";
        return base + "router-linux-amd64";
    }

    /**
     * Prints a post-run summary to stdout covering shipment status counts
     * and two KPI groups: truck on-time rate and average timing metrics.
     *
     * <p>KPI definitions:
     * <ul>
     *   <li><b>On time</b>  — shipment was packed at or before the earliest
     *       truck pull time for its sorting direction.</li>
     *   <li><b>Late</b>     — shipment was packed after the earliest pull time.</li>
     *   <li><b>No truck</b> — no truck schedule exists for the shipment's
     *       sorting direction.</li>
     *   <li><b>Avg time to pack</b>  — mean seconds from {@code receivedTime}
     *       to {@code packedAt} across all packed/shipped shipments.</li>
     *   <li><b>Avg dwell at dock</b> — mean seconds from {@code packedAt} to
     *       {@code shippedAt} across all shipped shipments.</li>
     * </ul>
     *
     * @param sim               completed simulation instance
     * @param truckSchedules    truck schedules used to compute pull times
     * @param epochDayName      day-of-week name of the simulation start date
     * @param epochDate         calendar date of simulation time t=0
     * @param simDurationSeconds total simulated duration in seconds
     */
    private static void printSummary(Simulation sim,
                                     List<TruckSchedule> truckSchedules,
                                     String epochDayName,
                                     LocalDate epochDate,
                                     double simDurationSeconds) {

        int totalDays = (int) Math.ceil(simDurationSeconds / 86_400.0);

        // Build a map of sortingDirection → earliest pull time (seconds from epoch)
        // across the entire simulation window, used for on-time KPI calculation.
        java.util.Map<String, Double> earliestPull = new java.util.HashMap<>();
        for (int day = 0; day < totalDays; day++) {
            LocalDate currentDate   = epochDate.plusDays(day);
            String    dayName       = currentDate.getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            double    dayOffsetSecs = day * 86_400.0;

            for (TruckSchedule ts : truckSchedules) {
                if (!ts.weekdays.contains(dayName)) continue;
                for (String pt : ts.pullTimes) {
                    double secs = dayOffsetSecs
                            + LocalTime.parse(pt, DateTimeFormatter.ofPattern("HH:mm"))
                                       .toSecondOfDay();
                    earliestPull.merge(ts.sortingDirection, secs, Math::min);
                }
            }
        }

        // Counters for status breakdown and KPI accumulators
        int received = 0, routed = 0, consolidation = 0, ready = 0,
                picking = 0, packed = 0, shipped = 0;
        double totalPackTime = 0; int packCount  = 0;
        double totalDwell    = 0; int dwellCount = 0;
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

                    // Accumulate pack time (received → packed)
                    if (s.getPackedAt() > 0) {
                        totalPackTime += s.getPackedAt() - s.getReceivedTime();
                        packCount++;
                    }

                    // Accumulate dwell time (packed → shipped)
                    if (s.getStatus() == Shipment.ShipmentStatus.SHIPPED
                            && s.getShippedAt() > 0 && s.getPackedAt() > 0) {
                        totalDwell += s.getShippedAt() - s.getPackedAt();
                        dwellCount++;
                    }

                    // On-time classification against earliest truck pull
                    Double pull = earliestPull.get(s.getSortingDirection());
                    if (pull == null)                                    noTruck++;
                    else if (s.getPackedAt() > 0 && s.getPackedAt() <= pull) onTime++;
                    else                                                 late++;
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
            System.out.printf("  No truck scheduled: %d%n", noTruck);
        System.out.println();
        System.out.println("─── Timing ────────────────────────");
        if (packCount  > 0)
            System.out.printf("  Avg time to pack  : %.0fs%n", totalPackTime / packCount);
        if (dwellCount > 0)
            System.out.printf("  Avg dwell at dock : %.0fs%n", totalDwell    / dwellCount);
    }
}