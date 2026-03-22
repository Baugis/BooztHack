package com.Warehouse.Simulator.model;
import com.Warehouse.Simulator.router.RouterDTOs;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * MODEL: Shipment
 *
 * Represents a single customer order moving through the warehouse.
 * A shipment consists of one or more items (EAN -> quantity) and travels
 * through the following lifecycle:
 *
 *   RECEIVED -> ROUTED -> CONSOLIDATION -> READY -> PICKING -> PACKED -> SHIPPED
 *
 * Not every shipment passes through CONSOLIDATION — that status is only
 * entered when required items span multiple grids and bins must be conveyed
 * to a single packing grid before picking can begin (spec section 4.4).
 *
 * Implements Serializable to allow the simulation state to be snaphotted,
 * but pick-progress and routing fields are marked transient because they
 * are rebuilt from events rather than persisted directly.
 */
public class Shipment implements Serializable {

    /** Unique shipment identifier, e.g. "SHIP-001". */
    private final String id;

    /**
     * Items in this order: EAN barcode -> quantity required.
     * Public for direct read access by events and the router converter;
     * never mutated after construction.
     */
    public final Map<String, Integer> items;

    /**
     * ISO-8601 creation timestamp, e.g. "2026-03-01T09:00:00Z".
     * Used by the router for FIFO ordering and deadline calculations.
     */
    public final String createdAt;

    /**
     * Sorting direction that determines which truck will carry this shipment.
     * Must match a direction in the truck arrival schedule (e.g. "dir-1").
     */
    public final String sortingDirection;

    /**
     * Special handling requirements for this shipment (e.g. "fragile", "heavy").
     * A port can only process this shipment if its own handling flags are a
     * superset of these flags.
     */
    Set<String> handlingFlags = new HashSet<>();

    /**
     * The full set of statuses a shipment can occupy during simulation.
     *
     * RECEIVED     — entered the system, waiting for the next router cycle.
     * ROUTED       — router has assigned a packing grid and pick list.
     * CONSOLIDATION— waiting for bins from foreign grids to arrive via conveyor.
     * READY        — all bins are at the packing grid; waiting for a free port.
     * PICKING      — a port is actively picking items from bins.
     * PACKED       — all items picked and packed; waiting for the truck.
     * SHIPPED      — loaded onto a truck and dispatched.
     */
    public enum ShipmentStatus {
        RECEIVED,
        ROUTED,
        CONSOLIDATION,
        READY,
        PICKING,
        PACKED,
        SHIPPED
    }

    /** Current position in the shipment lifecycle. */
    private transient ShipmentStatus status;

    /** Simulation time (seconds from epoch) when this shipment was received. */
    private transient double receivedTime;

    /** Simulation time when this shipment was packed; -1 if not yet packed. */
    private transient double packedTime  = -1;

    /** Simulation time when this shipment was shipped; -1 if not yet shipped. */
    private transient double shippedTime = -1;

    /**
     * Ordered list of bin picks assigned by the router.
     * Ports work through this list one entry at a time — the next bin is not
     * requested until the current pick is fully completed.
     */
    private transient List<RouterDTOs.Pick> picks = new ArrayList<>();

    /**
     * Index of the pick currently being processed (or about to start).
     * Advances by one each time completeCurrentPick() is called.
     */
    private transient int pickIndex = 0;

    /**
     * ID of the grid where this shipment will be packed, as assigned by the router.
     * Null until applyRouterAssignment() is called.
     */
    private transient String packingGrid = null;

    /**
     * Router-assigned priority score. Higher values are processed sooner.
     * Reset to 0 on rollback.
     */
    private transient int priority = 0;

    /**
     * Number of inter-grid bin transfers still in flight for this shipment.
     * Decremented by BinTransferCompleted as each bin arrives at the packing grid.
     * When this reaches zero the shipment transitions CONSOLIDATION -> READY.
     */
    private transient int pendingTransfers = 0;

    /**
     * Master constructor — used when all order attributes are available.
     * Sets initial status to RECEIVED and records the sim-time of arrival.
     *
     * @param id               unique shipment identifier
     * @param items            map of EAN -> quantity required
     * @param shipmentDate     ISO-8601 creation timestamp (stored as {@code createdAt})
     * @param simTime          simulation time of arrival in seconds from epoch
     * @param handlingFlags    special handling requirements; null is treated as empty
     * @param sortingDirection truck sorting direction; null defaults to "NONE"
     */
    public Shipment(String id, Map<String, Integer> items, String shipmentDate,
                    double simTime, Set<String> handlingFlags, String sortingDirection) {
        this.id               = id;
        this.items            = items;
        this.createdAt        = shipmentDate; // parameter name kept so callers don't break
        this.handlingFlags    = (handlingFlags != null) ? handlingFlags : new HashSet<>();
        this.sortingDirection = (sortingDirection != null) ? sortingDirection : "NONE";
        this.status           = ShipmentStatus.RECEIVED;
        this.receivedTime     = simTime;
    }

    /**
     * Convenience constructor for simple scenarios with no handling flags
     * or sorting direction (defaults to "DEFAULT-DIR").
     *
     * @param id           unique shipment identifier
     * @param items        map of EAN -> quantity required
     * @param shipmentDate ISO-8601 creation timestamp
     * @param simTime      simulation time of arrival in seconds from epoch
     */
    public Shipment(String id, Map<String, Integer> items,
                    String shipmentDate, double simTime) {
        this(id, items, shipmentDate, simTime, new HashSet<>(), "DEFAULT-DIR");
    }

    /**
     * Transitions the shipment to PICKING status.
     * Called when a port begins actively picking items for this shipment.
     *
     * @param simTime current simulation time (reserved for future timestamp tracking)
     */
    public void markAsPicking(double simTime) {
        this.status = ShipmentStatus.PICKING;
    }

    /**
     * Transitions the shipment to PACKED status and records the completion time.
     * Called by BinPickCompleted when the last pick in the list is finished.
     *
     * @param simTime simulation time at which packing completed
     */
    public void markAsPacked(double simTime) {
        this.status     = ShipmentStatus.PACKED;
        this.packedTime = simTime;
    }

    /**
     * Transitions the shipment to SHIPPED status and records the dispatch time.
     * Called by TruckArrived when the shipment is loaded onto a truck.
     *
     * @param simTime simulation time at which the shipment was shipped
     */
    public void markAsShipped(double simTime) {
        this.status      = ShipmentStatus.SHIPPED;
        this.shippedTime = simTime;
    }

    /**
     * Transitions the shipment to CONSOLIDATION status.
     * Called by ShipmentRouterTriggered when at least one required bin must be
     * conveyed from a foreign grid before picking can start.
     */
    public void markAsConsolidation() {
        this.status = ShipmentStatus.CONSOLIDATION;
    }

    /**
     * Transitions the shipment to READY status.
     * Called when all required bins are available at the packing grid and the
     * shipment is waiting for a free port.
     */
    public void markAsReady() {
        this.status = ShipmentStatus.READY;
    }

    /**
     * Sets the number of inter-grid bin transfers that must complete before
     * this shipment can transition from CONSOLIDATION to READY.
     *
     * @param count number of pending transfers (must be >= 0)
     */
    public void setPendingTransfers(int count) {
        this.pendingTransfers = count;
    }

    /**
     * Decrements the pending transfer count by one.
     * Guards against going below zero to prevent erroneous READY transitions.
     * Called by BinTransferCompleted each time a foreign bin arrives.
     */
    public void decrementPendingTransfers() {
        if (pendingTransfers > 0) pendingTransfers--;
    }

    /**
     * Returns true when all required inter-grid bin transfers have completed
     * and the shipment is eligible to transition to READY.
     */
    public boolean allTransfersDone() {
        return pendingTransfers <= 0;
    }

    /**
     * Resets the shipment fully back to RECEIVED status so the router can
     * re-assign it in the next routing cycle.
     *
     * Clears the packing grid, pick list, pick cursor, priority, and pending
     * transfer count. Called by ShipmentRouterTriggered for any ROUTED or READY
     * shipment that has not yet been picked up by a port (spec section 8.2).
     */
    public void rollbackToReceived() {
        this.status           = ShipmentStatus.RECEIVED;
        this.packingGrid      = null;
        this.picks            = new ArrayList<>();
        this.pickIndex        = 0;
        this.priority         = 0;
        this.pendingTransfers = 0;
    }

    /**
     * Applies the router's routing decision to this shipment.
     * Transitions status RECEIVED -> ROUTED and stores the pick list and
     * packing grid returned by the router.
     *
     * @param packingGrid the grid ID where this shipment will be packed
     * @param picks       ordered list of (binId, ean, qty) picks; null is treated as empty
     * @param priority    router-assigned priority score (higher = sooner)
     */
    public void applyRouterAssignment(String packingGrid,
                                      List<RouterDTOs.Pick> picks,
                                      int priority) {
        this.packingGrid = packingGrid;
        this.picks       = (picks != null) ? picks : new ArrayList<>();
        this.priority    = priority;
        this.pickIndex   = 0;
        this.status      = ShipmentStatus.ROUTED;
    }

    /**
     * Returns the current pick the port should be working on, without
     * advancing the cursor. Returns null if all picks are complete.
     * Call completeCurrentPick() to move to the next entry.
     */
    public RouterDTOs.Pick nextPick() {
        if (pickIndex >= picks.size()) return null;
        return picks.get(pickIndex);
    }

    /**
     * Marks the current pick as complete and advances the cursor to the next one.
     * Should be called inside BinPickCompleted after stock has been deducted
     * from the bin.
     */
    public void completeCurrentPick() {
        if (pickIndex < picks.size()) {
            pickIndex++;
        }
    }

    /**
     * Returns true when every pick in the router-assigned list has been completed,
     * indicating the shipment is ready to be marked as PACKED.
     */
    public boolean allPicksDone() {
        return pickIndex >= picks.size();
    }

    /** Returns the unique shipment identifier. */
    public String getId()               { return id; }

    /** Returns the current lifecycle status. */
    public ShipmentStatus getStatus()   { return status; }

    /** Returns the simulation time at which this shipment was received. */
    public double getReceivedTime()     { return receivedTime; }

    /** Returns the simulation time at which this shipment was packed, or -1 if not yet packed. */
    public double getPackedAt()         { return packedTime; }

    /** Returns the simulation time at which this shipment was shipped, or -1 if not yet shipped. */
    public double getShippedAt()        { return shippedTime; }

    /** Returns the ISO-8601 creation timestamp. */
    public String getCreatedAt()        { return createdAt; }

    /** Returns the map of EAN -> quantity for this order. */
    public Map<String, Integer> getItems() { return items; }

    /** Returns the set of special handling flags required by this shipment. */
    public Set<String> getHandlingFlags()  { return handlingFlags; }

    /** Returns the sorting direction used to select the correct truck. */
    public String getSortingDirection()    { return sortingDirection; }

    /** Returns the ID of the grid assigned for packing, or null if not yet routed. */
    public String getPackingGrid()         { return packingGrid; }

    /** Returns the router-assigned priority score. */
    public int getPriority()               { return priority; }

    /**
     * Records the simulation time at which this shipment entered the system.
     * Called by ShipmentReceived when the event fires.
     *
     * @param simTime simulation time of arrival in seconds from epoch
     */
    public void setReceivedAt(double simTime) { this.receivedTime = simTime; }
}