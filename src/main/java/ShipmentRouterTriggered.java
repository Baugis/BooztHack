import java.util.ArrayList;
import java.util.List;

/**
 * EVENT: ShipmentRouterTriggered
 *
 * Fires every 900 seconds (spec section 7.2).
 * Collects all RECEIVED shipments, sends the full warehouse state to the
 * external router process, then applies the returned assignments.
 *
 * After applying assignments:
 * - Each assigned shipment is ROUTED and placed in its target grid's queue
 * - A new ShipmentRouterTriggered is scheduled 900s later
 * - For each newly queued shipment, we immediately try to assign it to a port
 *
 * Per spec 8.2 (Router Rollback): shipments that are ROUTED/READY/CONSOLIDATION
 * but not yet in a port queue are rolled back to RECEIVED before each router run.
 * For Level 1 this means any ROUTED shipments still sitting in a grid queue
 * get rolled back and re-submitted.
 */
public class ShipmentRouterTriggered extends Event {

    private static final double ROUTER_INTERVAL_SECONDS = 900.0;

    private final RouterCaller routerCaller;

    public ShipmentRouterTriggered(double simTime, long sequenceNumber, RouterCaller routerCaller) {
        super(simTime, sequenceNumber);
        this.routerCaller = routerCaller;
    }

    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%.0fs] ShipmentRouterTriggered%n", sim.getCurrentTime());

        // --- Step 1: Rollback any ROUTED shipments still in grid queues ---
        // (they haven't been picked up by a port yet, so re-submit them)
        for (Grid grid : sim.getAllGrids()) {
            Shipment s;
            while ((s = grid.dequeueShipment()) != null) {
                if (s.getStatus() == Shipment.ShipmentStatus.ROUTED) {
                    s.rollbackToReceived();
                    // Now RECEIVED — the router below will re-assign it, don't re-queue
                } else {
                    // READY / CONSOLIDATION etc — already progressing, put back
                    grid.enqueueShipment(s);
                }
            }
        }

        // --- Step 2: Build the router input from current simulation state ---
        RouterCaller.State state = new RouterCaller.State();
        state.now = sim.getCurrentTimestamp(); // ISO 8601 string

        // Collect all RECEIVED shipments for the backlog
        state.shipmentsBacklog = new ArrayList<>();
        for (Shipment shipment : sim.getAllShipments()) {
            if (shipment.getStatus() == Shipment.ShipmentStatus.RECEIVED) {
                RouterCaller.ShipmentDto dto = new RouterCaller.ShipmentDto();
                dto.id = shipment.getId();
                dto.createdAt = shipment.getCreatedAt();
                dto.items = shipment.getItems();
                dto.handlingFlags = new java.util.ArrayList<>(shipment.getHandlingFlags());
                dto.sortingDirection = shipment.getSortingDirection();
                state.shipmentsBacklog.add(dto);
            }
        }

        // If nothing to route, skip the router call and just reschedule
        if (state.shipmentsBacklog.isEmpty()) {
            System.out.printf("[%.0fs] No shipments to route, skipping router call%n",
                    sim.getCurrentTime());
            scheduleNext(sim);
            return;
        }

        // Collect all bin stock
        state.stockBins = new ArrayList<>();
        for (Grid grid : sim.getAllGrids()) {
            for (Bin bin : grid.getAllBins()) {
                if (!bin.getStock().isEmpty()) {
                    RouterCaller.StockBinDto dto = new RouterCaller.StockBinDto();
                    dto.binId = bin.getBinId();
                    dto.gridId = bin.getGridId();
                    dto.items = bin.getStock();
                    state.stockBins.add(dto);
                }
            }
        }

        // Grids with their shift/port configs
        state.grids = sim.getRouterGridDtos();

        // Truck schedules
        state.truckArrivalSchedules = sim.getTruckScheduleWrapper();

        // --- Step 3: Call the router ---
        RouterCaller.Response response;
        try {
            response = routerCaller.call(new RouterCaller.RouterInput(state));
        } catch (RouterCaller.RouterException e) {
            System.err.println("Router call failed: " + e.getMessage());
            scheduleNext(sim);
            return;
        }

        System.out.printf("[%.0fs] Router returned %d assignments%n",
                sim.getCurrentTime(),
                response.assignments == null ? 0 : response.assignments.size());

        // --- Step 4: Apply assignments ---
        if (response.assignments != null) {
            for (RouterCaller.Assignment assignment : response.assignments) {
                Shipment shipment = sim.getShipment(assignment.shipmentId);
                if (shipment == null) {
                    System.err.println("Router returned unknown shipment ID: " + assignment.shipmentId);
                    continue;
                }

                // Apply the router's decision to the shipment
                shipment.applyRouterAssignment(
                        assignment.packingGrid,
                        assignment.picks,
                        assignment.priority
                );

                // Put it in the target grid's queue
                Grid targetGrid = sim.getGrid(assignment.packingGrid);
                if (targetGrid == null) {
                    System.err.println("Router assigned unknown grid: " + assignment.packingGrid);
                    continue;
                }

                // Try to assign directly to a port, otherwise queue it
                assignToPortOrQueue(sim, targetGrid, shipment);
            }
        }

        // --- Step 5: Schedule the next router run ---
        scheduleNext(sim);
    }

    /**
     * Tries to assign the shipment directly to an idle port.
     * If no port is free, adds it to the grid queue.
     * If a port accepts it and is IDLE, starts the first bin request immediately.
     */
    private void assignToPortOrQueue(Simulation sim, Grid grid, Shipment shipment) {
        Port port = grid.findBestPortFor(shipment);

        if (port == null) {
            // No port available right now — wait in grid queue
            grid.enqueueShipment(shipment);
            System.out.printf("[%.0fs] %s queued at grid %s (no port available)%n",
                    sim.getCurrentTime(), shipment.getId(), grid.getId());
            return;
        }

        port.enqueue(shipment);
        System.out.printf("[%.0fs] %s assigned to port %s%n",
                sim.getCurrentTime(), shipment.getId(), port.getPortId());

        // If the port was idle, kick it off immediately
        if (port.getStatus() == Port.Status.IDLE) {
            Shipment next = port.startNextShipment();
            if (next != null) {
                requestNextBin(sim, port, next);
            }
        }
    }

    /**
     * Schedules a BinRequestedAtPort event for the next pick in a shipment.
     * This is the moment a port "asks" for a bin to arrive.
     */
    private void requestNextBin(Simulation sim, Port port, Shipment shipment) {
        RouterCaller.Pick pick = shipment.nextPick();
        if (pick == null) return; // shouldn't happen here

        Bin bin = sim.getBin(pick.binId);
        if (bin == null) {
            System.err.println("Unknown bin in pick list: " + pick.binId);
            return;
        }

        // Transition bin: AVAILABLE -> OUTSIDE (it's now en route to the port)
        bin.markOutside();

        // Schedule it to arrive after the grid's delivery delay
        double deliveryDelay = sim.getDeliveryDelay(shipment.getPackingGrid());
        double arrivalTime = sim.getCurrentTime() + deliveryDelay;

        sim.schedule(new BinArrivedAtPort(
                arrivalTime,
                sim.nextSequence(),
                port.getPortId(),
                shipment.getId(),
                pick.binId,
                pick.ean,
                pick.qty,
                shipment.getPackingGrid()
        ));

        System.out.printf("[%.0fs] Bin %s requested at port %s (arrives in %.1fs)%n",
                sim.getCurrentTime(), pick.binId, port.getPortId(), deliveryDelay);
    }

    private void scheduleNext(Simulation sim) {
        double nextTime = sim.getCurrentTime() + ROUTER_INTERVAL_SECONDS;
        sim.schedule(new ShipmentRouterTriggered(
                nextTime,
                sim.nextSequence(),
                routerCaller
        ));
    }
}