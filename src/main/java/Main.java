import java.time.Instant;
import java.util.List;

public class Main {

    public static void main(String[] args) {

        // -------------------------------------------------------------------------
        // 1. Load data from JSON files
        // -------------------------------------------------------------------------
        DataLoader loader = new DataLoader();

        List<Shipment> shipments = loader.loadShipmentsJson();
        List<Bin>      bins      = loader.loadBinsJson();
        List<Grid>     grids     = loader.loadGridsJson();

        System.out.println("Loaded: " + shipments.size() + " shipments, "
                + bins.size() + " bins, "
                + grids.size() + " grids");

        if (shipments.isEmpty() || grids.isEmpty()) {
            System.err.println("No data loaded — check that Data/sample-data/level1/*.json files exist.");
            return;
        }

        // -------------------------------------------------------------------------
        // 2. Determine simulation epoch from the earliest shipment's createdAt
        //    All event times are measured in seconds from this instant.
        // -------------------------------------------------------------------------
        Instant epoch = Instant.parse("2026-03-01T00:00:00Z");

        System.out.println("Simulation epoch: " + epoch);

        // Run for 24 hours (86 400 seconds)
        double simDurationSeconds = 86_400.0;
        Simulation sim = new Simulation(simDurationSeconds, epoch);

        // -------------------------------------------------------------------------
        // 3. Register grids into the simulation
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
        // 5. Schedule ShipmentReceived events for every shipment.
        //    The event fires at the offset (in seconds) between the epoch and the
        //    shipment's createdAt timestamp.
        // -------------------------------------------------------------------------
        for (Shipment shipment : shipments) {
            if (shipment.createdAt == null) continue;
            Instant createdAt = Instant.parse(shipment.createdAt);
            double offsetSeconds = Math.max(0,
                    (double) java.time.Duration.between(epoch, createdAt).toSeconds());

            sim.schedule(new ShipmentReceived(
                    offsetSeconds,
                    sim.nextSequence(),
                    shipment
            ));
        }

        // -------------------------------------------------------------------------
        // 6. Schedule shift open/close events for all grids
        // -------------------------------------------------------------------------
        sim.scheduleAllShifts();

        // Truck atvažiuoja 10:00 (36000s nuo vidurnakčio) REIKES PAKEIST KAD VEIKTU SU JSON FAILU
        sim.schedule(new TruckArrived(
            36000.0,
            sim.nextSequence(),
            "DEFAULT-DIR"
        ));

        // -------------------------------------------------------------------------
        // 7. Set up the router caller
        //    Change the path to match your OS:
        //      Windows : "Data\\router\\router-windows-amd64.exe"
        //      Linux   : "Data/router/router-linux-amd64"
        //      macOS   : "Data/router/router-darwin-amd64"
        // -------------------------------------------------------------------------
        String routerPath = detectRouterPath();
        RouterCaller routerCaller = new RouterCaller(routerPath);

        // -------------------------------------------------------------------------
        // 8. Schedule the first router trigger at t=0 so shipments that arrive
        //    at the start of the simulation are routed immediately.
        // -------------------------------------------------------------------------
        sim.schedule(new ShipmentRouterTriggered(
                0.0,
                sim.nextSequence(),
                routerCaller
        ));

        // -------------------------------------------------------------------------
        // 9. Run the simulation
        // -------------------------------------------------------------------------
        System.out.println("\n========== SIMULATION START ==========\n");
        sim.run();
        System.out.println("\n========== SIMULATION END ==========\n");

        // -------------------------------------------------------------------------
        // 10. Print summary statistics
        // -------------------------------------------------------------------------
        printSummary(sim);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Detects which router binary to use based on the current OS.
     * You can override this by passing --router=<path> as a JVM argument or
     * simply hardcoding the path directly.
     */
    private static String detectRouterPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String base = "Data/router/";

        if (os.contains("win")) {
            return base + "router-windows-amd64.exe";
        } else if (os.contains("mac")) {
            return base + "router-darwin-amd64";
        } else {
            return base + "router-linux-amd64";
        }
    }

    /**
     * Prints a short end-of-simulation report: counts per status and
     * timing stats for packed/shipped shipments.
     */
    private static void printSummary(Simulation sim) {
    int received      = 0;
    int routed        = 0;
    int consolidation = 0;
    int ready         = 0;
    int picking       = 0;
    int packed        = 0;
    int shipped       = 0;

    double totalPackTime  = 0;
    double totalDwellTime = 0;
    int    packCount      = 0;
    int    dwellCount     = 0;

    for (Shipment s : sim.getAllShipments()) {
        switch (s.getStatus()) {
            case RECEIVED      -> received++;
            case ROUTED        -> routed++;
            case CONSOLIDATION -> consolidation++;
            case READY         -> ready++;
            case PICKING       -> picking++;
            case PACKED -> {
                packed++;
                if (s.getPackedAt() > 0) {
                    totalPackTime += s.getPackedAt() - s.getReceivedTime();
                    packCount++;
                }
            }
            case SHIPPED -> {
                shipped++;
                if (s.getPackedAt() > 0) {
                    totalPackTime += s.getPackedAt() - s.getReceivedTime();
                    packCount++;
                }
                if (s.getShippedAt() > 0 && s.getPackedAt() > 0) {
                    totalDwellTime += s.getShippedAt() - s.getPackedAt();
                    dwellCount++;
                }
            }
        }
    }

    System.out.println("\nSIMULATION SUMMARY");
    System.out.println("Total shipments     : " + sim.getAllShipments().size());
    System.out.println("RECEIVED (unrouted) : " + received);
    System.out.println("ROUTED              : " + routed);
    System.out.println("CONSOLIDATION       : " + consolidation);
    System.out.println("READY               : " + ready);
    System.out.println("PICKING             : " + picking);
    System.out.println("PACKED              : " + packed);
    System.out.println("SHIPPED             : " + shipped);
    if (packCount > 0)
        System.out.printf("Avg time to pack    : %.0fs%n", totalPackTime / packCount);
    if (dwellCount > 0)
        System.out.printf("Avg dwell           : %.0fs%n", totalDwellTime / dwellCount);
    }
}