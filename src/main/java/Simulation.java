import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Simulation {

    private final EventQueue eventQueue;
    private double currentTime;
    private final double endTime;

    // Simulation epoch — "time 0" in wall-clock terms, used to produce ISO timestamps
    private final Instant epochInstant;

    // All simulation state
    private final Map<String, Port>     ports;
    private final Map<String, Grid>     grids;
    private final Map<String, Shipment> shipments;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param endTime       simulation end time in seconds
     * @param epochInstant  real-world instant that corresponds to simTime=0
     */
    public Simulation(double endTime, Instant epochInstant) {
        this.eventQueue    = new EventQueue();
        this.currentTime   = 0;
        this.endTime       = endTime;
        this.epochInstant  = epochInstant;
        this.ports         = new HashMap<>();
        this.grids         = new HashMap<>();
        this.shipments     = new HashMap<>();
    }

    /** Convenience constructor — defaults epoch to 2026-03-01T00:00:00Z. */
    public Simulation(double endTime) {
        this(endTime, Instant.parse("2026-03-01T00:00:00Z"));
    }

    // -------------------------------------------------------------------------
    // Main loop
    // -------------------------------------------------------------------------

    public void run() {
        while (!eventQueue.isEmpty()) {
            Event next = eventQueue.pollNext();
            if (next.getSimTime() > endTime) break;
            currentTime = next.getSimTime();
            next.execute(this);
        }
    }

    // -------------------------------------------------------------------------
    // Event scheduling
    // -------------------------------------------------------------------------

    public void schedule(Event event) {
        eventQueue.schedule(event);
    }

    public long nextSequence() {
        return eventQueue.nextSequence();
    }

    // -------------------------------------------------------------------------
    // Time helpers
    // -------------------------------------------------------------------------

    public double getCurrentTime() { return currentTime; }

    /**
     * Returns the current simulation time as an ISO-8601 string.
     * Used when building the router input payload.
     */
    public String getCurrentTimestamp() {
        Instant now = epochInstant.plus((long) currentTime, ChronoUnit.SECONDS);
        return now.toString(); // e.g. "2026-03-01T00:15:00Z"
    }

    // -------------------------------------------------------------------------
    // State accessors
    // -------------------------------------------------------------------------

    public Port     getPort(String id)     { return ports.get(id); }
    public Grid     getGrid(String id)     { return grids.get(id); }
    public Shipment getShipment(String id) { return shipments.get(id); }

    public void addPort(Port port)         { ports.put(port.getId(), port); }
    public void addGrid(Grid grid)         { grids.put(grid.getId(), grid); }
    public void addShipment(Shipment s)    { shipments.put(s.getId(), s); }

    public Collection<Port>     getAllPorts()     { return ports.values(); }
    public Collection<Grid>     getAllGrids()     { return grids.values(); }
    public Collection<Shipment> getAllShipments() { return shipments.values(); }

    /**
     * Searches all grids for a bin with the given ID.
     * Returns null if no bin is found.
     */
    public Bin getBin(String binId) {
        for (Grid grid : grids.values()) {
            Bin bin = grid.getBin(binId);
            if (bin != null) return bin;
        }
        return null;
    }

    /**
     * Returns the grid-delivery delay in seconds for the given grid.
     * This is the time a bin takes to travel from the grid to a port.
     *
     * For now this is a fixed constant (60 s). When the spec defines
     * per-grid throughput, replace this with a lookup from a config map.
     */
    public double getDeliveryDelay(String gridId) {
        // TODO: load per-grid throughput from params.json at higher levels
        return 60.0; // seconds
    }

    // -------------------------------------------------------------------------
    // Router DTO builders  (used by ShipmentRouterTriggered)
    // -------------------------------------------------------------------------

    /**
     * Builds the list of GridDto objects the router needs to know about
     * (grid IDs, shift windows, port configs with handling flags).
     */
    public List<RouterDTOs.GridDto> getRouterGridDtos() {
        List<RouterDTOs.GridDto> result = new ArrayList<>();
        for (Grid grid : grids.values()) {
            RouterDTOs.GridDto gDto = new RouterDTOs.GridDto();
            gDto.id = grid.getId();
            for (Shift shift : grid.getShifts()) {
                RouterDTOs.ShiftDto sDto = new RouterDTOs.ShiftDto();
                sDto.startAt = shift.getStartAt();
                sDto.endAt   = shift.getEndAt();
                for (Port port : getAllPorts()) {
                    RouterDTOs.PortConfigDto pDto = new RouterDTOs.PortConfigDto();
                    pDto.portId = port.getPortId();
                    pDto.handlingFlags = new ArrayList<>(port.getHandlingFlags());
                    sDto.portConfig.add(pDto);
                }
                gDto.shifts.add(sDto);
            }
            result.add(gDto);
        }
        return result;
    }

    /**
     * Returns an empty truck-arrival wrapper for now.
     * Level 7+ will populate this from the loaded truck schedule data.
     */
    public RouterDTOs.TruckArrivalWrapper getTruckScheduleWrapper() {
        return new RouterDTOs.TruckArrivalWrapper();
    }
}