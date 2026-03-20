import java.util.ArrayList;
import java.util.List;

/**
 * EVENT: ShipmentRouterTriggered
 *
 * Fires every 900 seconds. Collects all RECEIVED shipments, sends the full
 * warehouse state to the external router process, then applies the returned
 * assignments.
 *
 * After applying assignments:
 *   - Single-grid shipments: transition ROUTED -> READY, assigned to a port
 *     (or the grid queue if no port is free).
 *   - Multi-grid shipments (4.4): transition ROUTED -> CONSOLIDATION;
 *     bins from foreign grids are scheduled for transfer via BinTransferCompleted.
 *     The shipment only becomes READY once all transfers finish.
 *
 * Per spec 8.2 (Router Rollback): ROUTED shipments still in the grid queue
 * (not yet picked up by a port) are rolled back to RECEIVED before each router run.
 */
public class ShipmentRouterTriggered extends Event {

    private static final double ROUTER_INTERVAL_SECONDS = 900.0;

    // Transfer time in seconds per grid hop (placeholder — replace with
    // per-conveyor config at higher levels).
    private static final double TRANSFER_DELAY_SECONDS = 300.0;

    private final RouterCaller routerCaller;

    public ShipmentRouterTriggered(double simTime, long sequenceNumber, RouterCaller routerCaller) {
        super(simTime, sequenceNumber);
        this.routerCaller = routerCaller;
    }

    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%.0fs] ShipmentRouterTriggered%n", sim.getCurrentTime());

        // --- Step 1: Rollback ROUTED shipments still waiting in grid queues ---
        for (Grid grid : sim.getAllGrids()) {
            List<Shipment> requeue = new ArrayList<>();
            Shipment s;
            while ((s = grid.dequeueShipment()) != null) {
                if (s.getStatus() == Shipment.ShipmentStatus.ROUTED
                        || s.getStatus() == Shipment.ShipmentStatus.READY) {
                    s.rollbackToReceived();
                } else {
                    requeue.add(s); // CONSOLIDATION etc — already progressing
                }
            }
            for (Shipment rs : requeue) {
                grid.enqueueShipment(rs);
            }
        }

        // --- Step 2: Build router input ---
        RouterCaller.State state = new RouterCaller.State();
        state.now = sim.getCurrentTimestamp();

        state.shipmentsBacklog = new ArrayList<>();
        for (Shipment shipment : sim.getAllShipments()) {
            if (shipment.getStatus() == Shipment.ShipmentStatus.RECEIVED) {
                RouterCaller.ShipmentDto dto = new RouterCaller.ShipmentDto();
                dto.id               = shipment.getId();
                dto.createdAt        = shipment.getCreatedAt();
                dto.items            = shipment.getItems();
                dto.handlingFlags    = new ArrayList<>(shipment.getHandlingFlags());
                dto.sortingDirection = shipment.getSortingDirection();
                state.shipmentsBacklog.add(dto);
            }
        }

        if (state.shipmentsBacklog.isEmpty()) {
            System.out.printf("[%.0fs] No shipments to route, skipping router call%n",
                    sim.getCurrentTime());
            scheduleNext(sim);
            return;
        }

        state.stockBins = new ArrayList<>();
        for (Grid grid : sim.getAllGrids()) {
            for (Bin bin : grid.getAllBins()) {
                if (!bin.getStock().isEmpty()) {
                    RouterCaller.StockBinDto dto = new RouterCaller.StockBinDto();
                    dto.binId  = bin.getBinId();
                    dto.gridId = bin.getGridId();
                    dto.items  = bin.getStock();
                    state.stockBins.add(dto);
                }
            }
        }

        state.grids                = sim.getRouterGridDtos();
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
            for (RouterDTOs.Assignment assignment : response.assignments) {
                Shipment shipment = sim.getShipment(assignment.shipmentId);
                if (shipment == null) {
                    System.err.println("Router returned unknown shipment ID: " + assignment.shipmentId);
                    continue;
                }

                shipment.applyRouterAssignment(
                        assignment.packingGrid,
                        assignment.picks,
                        assignment.priority
                );

                Grid targetGrid = sim.getGrid(assignment.packingGrid);
                if (targetGrid == null) {
                    System.err.println("Router assigned unknown grid: " + assignment.packingGrid);
                    continue;
                }

                // Determine if any picks come from a different grid (consolidation case)
                List<RouterDTOs.Pick> foreignPicks = findForeignPicks(sim, assignment);

                if (!foreignPicks.isEmpty()) {
                    // --- 4.4 Consolidation path ---
                    shipment.markAsConsolidation();
                    shipment.setPendingTransfers(foreignPicks.size());

                    System.out.printf("[%.0fs] %s -> CONSOLIDATION (%d foreign bins to transfer)%n",
                            sim.getCurrentTime(), shipment.getId(), foreignPicks.size());

                    for (RouterDTOs.Pick pick : foreignPicks) {
                        Bin bin = sim.getBin(pick.binId);
                        if (bin == null) {
                            System.err.println("Foreign pick references unknown bin: " + pick.binId);
                            shipment.decrementPendingTransfers(); // avoid permanent stall
                            continue;
                        }

                        String sourceGrid = bin.getGridId();
                        bin.markOutside();

                        double arrivalTime = sim.getCurrentTime() + TRANSFER_DELAY_SECONDS;
                        sim.schedule(new BinTransferCompleted(
                                arrivalTime,
                                sim.nextSequence(),
                                pick.binId,
                                sourceGrid,
                                assignment.packingGrid,
                                shipment.getId(),
                                TRANSFER_DELAY_SECONDS
                        ));

                        System.out.printf("[%.0fs] Transfer scheduled: bin=%s %s -> %s%n",
                                sim.getCurrentTime(), pick.binId, sourceGrid, assignment.packingGrid);
                    }
                    // Shipment will be port-assigned inside BinTransferCompleted
                    // once allTransfersDone() becomes true.

                } else {
                    // --- Normal single-grid path: ROUTED -> READY -> port ---
                    shipment.markAsReady();
                    assignToPortOrQueue(sim, targetGrid, shipment);
                }
            }
        }

        // --- Step 5: Schedule next router run ---
        scheduleNext(sim);
    }

    /**
     * Returns picks whose bin currently lives in a grid other than the
     * shipment's packingGrid. These need a conveyor transfer before picking.
     */
    private List<RouterDTOs.Pick> findForeignPicks(Simulation sim,
                                                     RouterDTOs.Assignment assignment) {
        List<RouterDTOs.Pick> foreign = new ArrayList<>();
        if (assignment.picks == null) return foreign;
        for (RouterDTOs.Pick pick : assignment.picks) {
            Bin bin = sim.getBin(pick.binId);
            if (bin != null && !bin.getGridId().equals(assignment.packingGrid)) {
                foreign.add(pick);
            }
        }
        return foreign;
    }

    /**
     * Tries to assign the shipment directly to an idle/available port.
     * Falls back to the grid queue if no port can take it right now.
     */
    private void assignToPortOrQueue(Simulation sim, Grid grid, Shipment shipment) {
        Port port = grid.findBestPortFor(shipment);

        if (port == null) {
            grid.enqueueShipment(shipment);
            System.out.printf("[%.0fs] %s queued at grid %s (no port available)%n",
                    sim.getCurrentTime(), shipment.getId(), grid.getId());
            return;
        }

        port.enqueue(shipment);
        System.out.printf("[%.0fs] %s assigned to port %s%n",
                sim.getCurrentTime(), shipment.getId(), port.getId());

        if (port.getStatus() == Port.Status.IDLE) {
            Shipment next = port.startNextShipment();
            if (next != null) {
                requestNextBin(sim, port, next);
            }
        }
    }

    private void requestNextBin(Simulation sim, Port port, Shipment shipment) {
        RouterDTOs.Pick pick = shipment.nextPick();
        if (pick == null) return;

        Bin bin = sim.getBin(pick.binId);
        if (bin == null) {
            System.err.println("Unknown bin in pick list: " + pick.binId);
            return;
        }

        bin.markOutside();

        double deliveryDelay = sim.getDeliveryDelay(shipment.getPackingGrid());
        double arrivalTime   = sim.getCurrentTime() + deliveryDelay;

        sim.schedule(new BinArrivedAtPort(
                arrivalTime,
                sim.nextSequence(),
                port.getId(),
                shipment.getId(),
                pick.binId, pick.ean, pick.qty,
                shipment.getPackingGrid()
        ));

        System.out.printf("[%.0fs] Bin %s requested at port %s (arrives in %.1fs)%n",
                sim.getCurrentTime(), pick.binId, port.getId(), deliveryDelay);
    }

    private void scheduleNext(Simulation sim) {
        sim.schedule(new ShipmentRouterTriggered(
                sim.getCurrentTime() + ROUTER_INTERVAL_SECONDS,
                sim.nextSequence(),
                routerCaller
        ));
    }
}