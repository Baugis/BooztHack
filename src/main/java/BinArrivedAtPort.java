/**
 * EVENT: BinArrivedAtPort
 *
 * Fires when a bin physically arrives at a port after travelling from the grid.
 * The port can now start picking items from it.
 *
 * Schedules a BinPickCompleted event after the appropriate pick duration
 * (Standard: 140 units/hour, Fragile: 70 units/hour).
 */
public class BinArrivedAtPort extends Event {

    private static final double STANDARD_UNITS_PER_SECOND = 140.0 / 3600.0;
    private static final double FRAGILE_UNITS_PER_SECOND  =  70.0 / 3600.0;
    private static final double RANDOM_MIN = 0.8;
    private static final double RANDOM_MAX = 1.2;

    private final String portId;
    private final String shipmentId;
    private final String binId;
    private final String ean;
    private final int qty;
    private final String gridId;

    public BinArrivedAtPort(double simTime, long sequenceNumber,
                            String portId, String shipmentId,
                            String binId, String ean, int qty, String gridId) {
        super(simTime, sequenceNumber);
        this.portId     = portId;
        this.shipmentId = shipmentId;
        this.binId      = binId;
        this.ean        = ean;
        this.qty        = qty;
        this.gridId     = gridId;
    }

    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%.0fs] BinArrivedAtPort: bin=%s, port=%s, shipment=%s%n",
                sim.getCurrentTime(), binId, portId, shipmentId);

        Shipment shipment = sim.getShipment(shipmentId);
        if (shipment == null) {
            System.err.println("BinArrivedAtPort: unknown shipment " + shipmentId);
            return;
        }

        // Jei siunta jau pakuota — ignoruoti
        if (shipment.getStatus() == Shipment.ShipmentStatus.PACKED ||
            shipment.getStatus() == Shipment.ShipmentStatus.SHIPPED) {
            System.out.printf("[%.0fs] BinArrivedAtPort: ignoruojama — shipment %s jau %s%n",
                sim.getCurrentTime(), shipmentId, shipment.getStatus());
            return;
        }

        Grid grid = sim.getGrid(gridId);
        if (grid == null) {
            System.err.println("BinArrivedAtPort: unknown grid " + gridId);
            return;
        }

        Bin bin = grid.getBin(binId);
        if (bin == null) {
            System.err.println("BinArrivedAtPort: unknown bin " + binId);
            return;
        }

        // Bandyti rezervuoti biną — jei nepavyksta, laukti eilėje
        if (bin.getStatus() == Bin.Status.RESERVED && portId.equals(bin.getReservedByPortId())) {
            // Binas jau rezervuotas mums — tiesiog testi picking
        } else {
            boolean reserved = bin.reserve(portId);
            if (!reserved) {
                System.out.printf("[%.0fs] Bin %s already reserved, port %s added to waiting list%n",
                    sim.getCurrentTime(), binId, portId);
                return;
            }
        }




        // Apskaičiuoti picking trukmę
        boolean isFragile = shipment.getHandlingFlags().contains("fragile");
        double unitsPerSecond = isFragile ? FRAGILE_UNITS_PER_SECOND : STANDARD_UNITS_PER_SECOND;
        double baseDuration = qty / unitsPerSecond;
        double randomFactor = RANDOM_MIN + Math.random() * (RANDOM_MAX - RANDOM_MIN);
        double pickDuration = baseDuration * randomFactor;
        double completionTime = sim.getCurrentTime() + pickDuration;

        sim.schedule(new BinPickCompleted(
                completionTime,
                sim.nextSequence(),
                portId,
                shipmentId,
                binId,
                ean,
                qty,
                pickDuration,
                gridId
        ));

        System.out.printf("[%.0fs] Pick scheduled: %d x %s from bin %s, takes %.1fs%n",
                sim.getCurrentTime(), qty, ean, binId, pickDuration);
    }
}