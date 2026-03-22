package com.Warehouse.Simulator.model;

import com.Warehouse.Simulator.router.RouterDTOs;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class Shipment implements Serializable {

    private final String id;
    public final Map<String, Integer> items;
    public final String createdAt;       // ISO-8601 date string, e.g. "2026-03-01T09:00:00Z"
    public final String sortingDirection;

    Set<String> handlingFlags = new HashSet<>();  // e.g "fragile", "heavy"

    public enum ShipmentStatus {
        RECEIVED,
        ROUTED,
        CONSOLIDATION,
        READY,
        PICKING,
        PACKED,
        SHIPPED
    }

    private transient ShipmentStatus status;
    private transient double receivedTime;
    private transient double packedTime  = -1;
    private transient double shippedTime = -1;

    // -------------------------------------------------------------------------
    // Pick-progress tracking  (populated by applyRouterAssignment)
    // -------------------------------------------------------------------------

    // The ordered list of bins the router told us to pick from, one by one.
    private transient List<RouterDTOs.Pick> picks = new ArrayList<>();

    // Index of the pick we are currently working on (or about to start).
    private transient int pickIndex = 0;

    // Which grid this shipment will be packed in (set by the router).
    private transient String packingGrid = null;

    // Router-assigned priority (lower number = higher priority).
    private transient int priority = 0;

    // Number of bin transfers still in-flight for consolidation (section 4.4).
    // Set by applyRouterAssignment when bins span multiple grids.
    private transient int pendingTransfers = 0;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Master constructor — used when all JSON attributes are available. */
    public Shipment(String id, Map<String, Integer> items, String shipmentDate,
                    double simTime, Set<String> handlingFlags, String sortingDirection) {
        this.id            = id;
        this.items         = items;
        this.createdAt     = shipmentDate; // parameter name kept so callers don't break
        this.handlingFlags = (handlingFlags != null) ? handlingFlags : new HashSet<>();
        this.sortingDirection = (sortingDirection != null) ? sortingDirection : "NONE";
        this.status        = ShipmentStatus.RECEIVED;
        this.receivedTime  = simTime;
    }

    /** Level-1 convenience constructor — no handling flags or sorting direction. */
    public Shipment(String id, Map<String, Integer> items,
                    String shipmentDate, double simTime) {
        this(id, items, shipmentDate, simTime, new HashSet<>(), "DEFAULT-DIR");
    }

    // -------------------------------------------------------------------------
    // Status transitions
    // -------------------------------------------------------------------------

    public void markAsPicking(double simTime) {
        this.status = ShipmentStatus.PICKING;
    }

    public void markAsPacked(double simTime) {
        this.status     = ShipmentStatus.PACKED;
        this.packedTime = simTime;
    }

    public void markAsShipped(double simTime) {
        this.status      = ShipmentStatus.SHIPPED;
        this.shippedTime = simTime;
    }

    public void markAsConsolidation() {
        this.status = ShipmentStatus.CONSOLIDATION;
    }

    public void markAsReady() {
        this.status = ShipmentStatus.READY;
    }

    /** Sets number of pending bin transfers needed before this shipment becomes READY. */
    public void setPendingTransfers(int count) {
        this.pendingTransfers = count;
    }

    public void decrementPendingTransfers() {
        if (pendingTransfers > 0) pendingTransfers--;
    }

    public boolean allTransfersDone() {
        return pendingTransfers <= 0;
    }

    /**
     * Rolls the shipment back to RECEIVED so the router can re-assign it.
     * Called by ShipmentRouterTriggered before each router run for any
     * ROUTED shipment that hasn't been picked up by a port yet.
     */
    public void rollbackToReceived() {
        this.status      = ShipmentStatus.RECEIVED;
        this.packingGrid = null;
        this.picks       = new ArrayList<>();
        this.pickIndex   = 0;
        this.priority         = 0;
        this.pendingTransfers = 0;
    }

    // -------------------------------------------------------------------------
    // Router assignment
    // -------------------------------------------------------------------------

    /**
     * Applies the router's routing decision to this shipment.
     * Transitions status RECEIVED -> ROUTED and stores the pick list.
     *
     * @param packingGrid  which grid will pack this shipment
     * @param picks        ordered list of (binId, ean, qty) picks
     * @param priority     router-assigned priority
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

    // -------------------------------------------------------------------------
    // Pick-progress tracking
    // -------------------------------------------------------------------------

    /**
     * Returns the current pick (the one the port should be working on right now)
     * WITHOUT advancing the cursor. Returns null if all picks are done.
     */
    public RouterDTOs.Pick nextPick() {
        if (pickIndex >= picks.size()) return null;
        return picks.get(pickIndex);
    }

    /**
     * Marks the current pick as complete and advances to the next one.
     * Call this inside BinPickCompleted after stock has been deducted.
     */
    public void completeCurrentPick() {
        if (pickIndex < picks.size()) {
            pickIndex++;
        }
    }

    /**
     * Returns true when every pick in the router-assigned list has been completed.
     */
    public boolean allPicksDone() {
        return pickIndex >= picks.size();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public String getId()               { return id; }
    public ShipmentStatus getStatus()   { return status; }
    public double getReceivedTime()     { return receivedTime; }
    public double getPackedAt()         { return packedTime; }
    public double getShippedAt()        { return shippedTime; }
    public String getCreatedAt()        { return createdAt; }
    public Map<String, Integer> getItems() { return items; }
    public Set<String> getHandlingFlags()  { return handlingFlags; }
    public String getSortingDirection()    { return sortingDirection; }
    public String getPackingGrid()         { return packingGrid; }
    public int getPriority()               { return priority; }

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    public void setReceivedAt(double simTime) { this.receivedTime = simTime; }
}