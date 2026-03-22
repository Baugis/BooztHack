package com.Warehouse.Simulator.engine.events;
import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.model.*;
import com.Warehouse.Simulator.router.*;

import java.util.ArrayList;
import java.util.List;

/**
 * EVENT: ShipmentRouterTriggered
 *
 * Fires every 900 seconds to drive the routing cycle.
 * Collects all RECEIVED shipments, sends the full warehouse state to the
 * external router process, then applies the returned assignments to move
 * shipments forward in their lifecycle.
 *
 * Execution steps:
 *   1. Roll back any ROUTED/READY shipments still waiting in grid queues
 *      back to RECEIVED so the router sees a fresh, consistent backlog.
 *   2. Build the router input: shipment backlog, current bin stock, grid
 *      shift configs, and truck arrival schedules.
 *   3. Call the external router subprocess and receive assignments.
 *   4. Apply each assignment:
 *        - Single-grid shipment: ROUTED -> READY, assigned to a port
 *          (or the grid queue if no port is currently available).
 *        - Multi-grid shipment (consolidation): ROUTED -> CONSOLIDATION;
 *          foreign bins are scheduled for conveyor transfer via
 *          BinTransferStarted. The shipment becomes READY only after all
 *          transfers complete (tracked by pendingTransfers counter).
 *   5. Schedule the next ShipmentRouterTriggered event 900 seconds later.
 *
 * Rollback rule (spec 8.2):
 *   ROUTED and READY shipments that have not yet been picked up by a port
 *   are reset to RECEIVED before each router run so the router always works
 *   from a complete, up-to-date picture of unprocessed demand.
 */
public class ShipmentRouterTriggered extends Event {

    /** How often the router is triggered, in simulation seconds. */
    private static final double ROUTER_INTERVAL_SECONDS = 900.0;

    /** Subprocess wrapper used to send state to and receive assignments from the router. */
    private final RouterCaller routerCaller;

    /**
     * Creates a ShipmentRouterTriggered event.
     *
     * @param simTime        simulation time at which to trigger the router (seconds from epoch)
     * @param sequenceNumber tie-breaking sequence number for same-timestamp events
     * @param routerCaller   the router subprocess wrapper to call
     */
    public ShipmentRouterTriggered(double simTime, long sequenceNumber, RouterCaller routerCaller) {
        super(simTime, sequenceNumber);
        this.routerCaller = routerCaller;
    }

    /**
     * Executes the full routing cycle (see class Javadoc for the five steps).
     *
     * If the shipment backlog is empty the router call is skipped entirely and
     * the next trigger is scheduled immediately. If the router subprocess fails,
     * the error is logged and the cycle continues with the next scheduled trigger.
     *
     * @param sim the running simulation context
     */
    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%s] ShipmentRouterTriggered%n", sim.getTimeLabel());

        // --- Step 1: Roll back ROUTED/READY shipments still in grid queues ---
        // Shipments that were routed in a previous cycle but never picked up by
        // a port are returned to RECEIVED so this run can re-route them with
        // current stock levels and grid state.
        for (Grid grid : sim.getAllGrids()) {
            List<Shipment> requeue = new ArrayList<>();
            Shipment s;
            while ((s = grid.dequeueShipment()) != null) {
                if (s.getStatus() == Shipment.ShipmentStatus.ROUTED
                        || s.getStatus() == Shipment.ShipmentStatus.READY) {
                    s.rollbackToReceived();
                } else {
                    // CONSOLIDATION shipments already have transfers in flight — keep them.
                    requeue.add(s);
                }
            }
            for (Shipment rs : requeue) {
                grid.enqueueShipment(rs);
            }
        }

        // --- Step 2: Build router input ---
        RouterCaller.State state = new RouterCaller.State();
        state.now = sim.getCurrentTimestamp();

        // Collect every shipment still in RECEIVED status into the backlog.
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

        // Skip the router call entirely if there is nothing to route.
        if (state.shipmentsBacklog.isEmpty()) {
            System.out.printf("[%s] No shipments to route, skipping router call%n",
                    sim.getTimeLabel());
            scheduleNext(sim);
            return;
        }

        // Collect current bin stock across all grids (only non-empty bins are sent).
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

        state.grids                 = sim.getRouterGridDtos();
        state.truckArrivalSchedules = sim.getTruckScheduleWrapper();

        // --- Step 3: Call the external router subprocess ---
        RouterCaller.Response response;
        try {
            response = routerCaller.call(new RouterCaller.RouterInput(state));

        } catch (RouterCaller.RouterException e) {
            System.err.println("Router call failed: " + e.getMessage());
            scheduleNext(sim);
            return;
        }

        System.out.printf("[%s] Router returned %d assignments%n",
                sim.getTimeLabel(),
                response.assignments == null ? 0 : response.assignments.size());

        // --- Step 4: Apply assignments returned by the router ---
        if (response.assignments != null) {
            for (RouterDTOs.Assignment assignment : response.assignments) {

                Shipment shipment = sim.getShipment(assignment.shipmentId);
                if (shipment == null) {
                    System.err.println("Router returned unknown shipment ID: " + assignment.shipmentId);
                    continue;
                }

                // Record the router's decision on the shipment (packing grid, pick list, priority).
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

                // Detect picks whose bin lives in a grid other than the packing grid.
                List<RouterDTOs.Pick> foreignPicks = findForeignPicks(sim, assignment);

                if (!foreignPicks.isEmpty()) {
                    // --- Consolidation path (spec 4.4) ---
                    // The shipment cannot start picking until all foreign bins have
                    // been conveyed to the packing grid.
                    shipment.markAsConsolidation();
                    shipment.setPendingTransfers(foreignPicks.size());

                    System.out.printf("[%s] %s -> CONSOLIDATION (%d foreign bins to transfer)%n",
                            sim.getTimeLabel(), shipment.getId(), foreignPicks.size());

                    for (RouterDTOs.Pick pick : foreignPicks) {
                        Bin bin = sim.getBin(pick.binId);
                        if (bin == null) {
                            System.err.println("Foreign pick references unknown bin: " + pick.binId);
                            // Decrement to avoid the shipment stalling permanently
                            // if a bin can never be found.
                            shipment.decrementPendingTransfers();
                            continue;
                        }

                        String sourceGrid    = bin.getGridId();
                        double transferDelay = sim.getTransferDelay(sourceGrid, assignment.packingGrid);

                        sim.schedule(new BinTransferStarted(
                                sim.getCurrentTime(),
                                sim.nextSequence(),
                                pick.binId,
                                sourceGrid,
                                assignment.packingGrid,
                                shipment.getId(),
                                transferDelay
                        ));

                        System.out.printf("[%s] Transfer scheduled: bin=%s %s -> %s (delay=%.0fs)%n",
                                sim.getTimeLabel(), pick.binId, sourceGrid,
                                assignment.packingGrid, transferDelay);
                    }
                    // Port assignment is deferred — BinTransferCompleted will
                    // assign the shipment to a port once allTransfersDone() is true.

                } else {
                    // --- Normal single-grid path ---
                    // All bins are already in the packing grid; go straight to READY.
                    shipment.markAsReady();
                    assignToPortOrQueue(sim, targetGrid, shipment);
                }
            }
        }

        // --- Step 5: Schedule the next router run ---
        scheduleNext(sim);
    }

    /**
     * Returns the subset of picks in the assignment whose bin currently resides
     * in a grid other than the shipment's designated packing grid.
     * These bins must be conveyed to the packing grid before picking can start.
     *
     * @param sim        simulation context used to look up current bin locations
     * @param assignment the router assignment to inspect
     * @return list of picks that require an inter-grid transfer; empty if none
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
     * Attempts to assign a READY shipment directly to the best available port
     * on the target grid. If no port can accept the shipment right now (all
     * full, closed, or incompatible), the shipment is placed at the back of
     * the grid queue to wait for a port to free up.
     *
     * If a suitable IDLE port is found, the port immediately starts work and
     * the first bin is requested.
     *
     * @param sim      simulation context
     * @param grid     the packing grid to assign the shipment to
     * @param shipment the READY shipment to assign
     */
    private void assignToPortOrQueue(Simulation sim, Grid grid, Shipment shipment) {
        Port port = grid.findBestPortFor(shipment);

        if (port == null) {
            grid.enqueueShipment(shipment);
            System.out.printf("[%s] %s queued at grid %s (no port available)%n",
                    sim.getTimeLabel(), shipment.getId(), grid.getId());
            return;
        }

        port.enqueue(shipment);
        System.out.printf("[%s] %s assigned to port %s%n",
                sim.getTimeLabel(), shipment.getId(), port.getId());

        // If the port is idle it can start immediately; otherwise the shipment
        // waits in the port queue until the current shipment finishes.
        if (port.getStatus() == Port.Status.IDLE) {
            Shipment next = port.startNextShipment();
            if (next != null) {
                requestNextBin(sim, port, next);
            }
        }
    }

    /**
     * Requests the next bin in the shipment's pick list from the grid conveyor.
     * Marks the bin as OUTSIDE (in transit) and schedules a BinArrivedAtPort
     * event after the grid's delivery delay.
     *
     * Does nothing if the shipment has no more picks or the bin cannot be found.
     *
     * @param sim      simulation context
     * @param port     the port that will receive the bin
     * @param shipment the shipment whose next pick should be fetched
     */
    private void requestNextBin(Simulation sim, Port port, Shipment shipment) {
        RouterDTOs.Pick pick = shipment.nextPick();
        if (pick == null) return;

        Bin bin = sim.getBin(pick.binId);
        if (bin == null) {
            System.err.println("Unknown bin in pick list: " + pick.binId);
            return;
        }

        // Mark the bin as outside so no other port can reserve it while in transit.
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

        System.out.printf("[%s] Bin %s requested at port %s (arrives in %.1fs)%n",
                sim.getTimeLabel(), pick.binId, port.getId(), deliveryDelay);
    }

    /**
     * Schedules the next ShipmentRouterTriggered event exactly
     * ROUTER_INTERVAL_SECONDS (900s) after the current simulation time.
     *
     * @param sim simulation context used to schedule the event
     */
    private void scheduleNext(Simulation sim) {
        sim.schedule(new ShipmentRouterTriggered(
                sim.getCurrentTime() + ROUTER_INTERVAL_SECONDS,
                sim.nextSequence(),
                routerCaller
        ));
    }
}