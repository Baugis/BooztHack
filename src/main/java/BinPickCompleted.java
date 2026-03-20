/**
 * EVENT: BinPickCompleted
 */
public class BinPickCompleted extends Event {

    private final String portId;
    private final String shipmentId;
    private final String binId;
    private final String ean;
    private final int qty;
    private final double pickDuration;
    private final String gridId;

    public BinPickCompleted(double simTime, long sequenceNumber,
                            String portId, String shipmentId,
                            String binId, String ean, int qty, double pickDuration, String gridId) {
        super(simTime, sequenceNumber);
        this.portId       = portId;
        this.shipmentId   = shipmentId;
        this.binId        = binId;
        this.ean          = ean;
        this.qty          = qty;
        this.pickDuration = pickDuration;
        this.gridId       = gridId;
    }

    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%.0fs] BinPickCompleted: bin=%s, %d x %s, port=%s, shipment=%s%n",
                sim.getCurrentTime(), binId, qty, ean, portId, shipmentId);

        Shipment shipment = sim.getShipment(shipmentId);
        if (shipment == null) {
            System.err.println("BinPickCompleted: unknown shipment " + shipmentId);
            return;
        }

        Grid grid = sim.getGrid(gridId);
        if (grid == null) {
            System.err.println("BinPickCompleted: unknown grid " + gridId);
            return;
        }

        Bin bin = grid.getBin(binId);
        if (bin == null) {
            System.err.println("BinPickCompleted: unknown bin " + binId);
            return;
        }

        Port port = null;
        for (Grid g : sim.getAllGrids()) {
            port = g.getPort(portId);
            if (port != null) break;
        }
        if (port == null) {
            System.err.println("BinPickCompleted: unknown port " + portId);
            return;
        }

        // --- 1. Deduct stock ---
        bin.deductStock(ean, qty);

        // --- 2. Atlaisvinti biną ir pranešti laukiančiam portui (jei yra) ---
        bin.markAvailable();
        if (bin.hasWaiting()) {
            String nextPortId = bin.pollNextWaiting();
            bin.reserve(nextPortId);
            Port nextPort = null;
            for (Grid g : sim.getAllGrids()) {
                nextPort = g.getPort(nextPortId);
                if (nextPort != null) break;
            }
            if (nextPort != null && nextPort.getActiveShipment() != null) {
                Shipment waiting = nextPort.getActiveShipment();
                RouterDTOs.Pick pick = waiting.nextPick();
                if (pick != null) {
                    double delay = sim.getDeliveryDelay(waiting.getPackingGrid());
                    sim.schedule(new BinArrivedAtPort(
                        sim.getCurrentTime() + delay,
                        sim.nextSequence(),
                        nextPortId,
                        waiting.getId(),
                        bin.getBinId(),
                        pick.ean,
                        pick.qty,
                        waiting.getPackingGrid()
                    ));
                    System.out.printf("[%.0fs] Bin %s perduotas laukiančiam port %s%n",
                        sim.getCurrentTime(), bin.getBinId(), nextPortId);
                }
            }
        }

        // --- 3. Advance pick progress ---
        shipment.completeCurrentPick();

        // --- 4. More picks? ---
        if (!shipment.allPicksDone()) {
            RouterDTOs.Pick nextPick = shipment.nextPick();
            requestNextBin(sim, port, shipment, nextPick);
            return;
        }

        // --- 5. All picks done — PACKED ---
        shipment.markAsPacked(sim.getCurrentTime());
        System.out.printf("[%.0fs] PACKED: shipment=%s (pack duration=%.1fs)%n",
                sim.getCurrentTime(), shipmentId, pickDuration);

        // --- 6. Free port ---
        Shipment nextShipment = port.finishCurrentShipment();
        if (nextShipment != null) {
            requestNextBin(sim, port, nextShipment, nextShipment.nextPick());
        } else {
            tryAssignFromGridQueue(sim, grid, port);
        }
    }

    private void requestNextBin(Simulation sim, Port port, Shipment shipment, RouterDTOs.Pick nextPick) {
        if (nextPick == null) return;

        Bin bin = sim.getBin(nextPick.binId);
        if (bin == null) {
            System.err.println("Next pick references unknown bin: " + nextPick.binId);
            return;
        }

        bin.markOutside();

        double deliveryDelay = sim.getDeliveryDelay(shipment.getPackingGrid());
        double arrivalTime = sim.getCurrentTime() + deliveryDelay;

        sim.schedule(new BinArrivedAtPort(
                arrivalTime,
                sim.nextSequence(),
                port.getId(),
                shipment.getId(),
                nextPick.binId,
                nextPick.ean,
                nextPick.qty,
                shipment.getPackingGrid()
        ));

        System.out.printf("[%.0fs] Next bin requested: %s (arrives in %.1fs)%n",
                sim.getCurrentTime(), nextPick.binId, deliveryDelay);
    }

    private void tryAssignFromGridQueue(Simulation sim, Grid grid, Port port) {
        if (grid == null) return;

        Shipment next = grid.dequeueShipment();
        if (next == null) return;

        if (port.canHandle(next)) {
            port.enqueue(next);
            Shipment started = port.startNextShipment();
            if (started != null) {
                requestNextBin(sim, port, started, started.nextPick());
                System.out.printf("[%.0fs] Port %s pulled %s from grid queue%n",
                        sim.getCurrentTime(), port.getId(), started.getId());
            }
        } else {
            grid.enqueueShipment(next);
        }
    }
}