import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Simulation {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final EventQueue eventQueue;
    private double currentTime;
    private final double endTime;

    private final Instant epochInstant;

    private final Map<String, Port>     ports;
    private final Map<String, Grid>     grids;
    private final Map<String, Shipment> shipments;

    public Simulation(double endTime, Instant epochInstant) {
        this.eventQueue    = new EventQueue();
        this.currentTime   = 0;
        this.endTime       = endTime;
        this.epochInstant  = epochInstant;
        this.ports         = new HashMap<>();
        this.grids         = new HashMap<>();
        this.shipments     = new HashMap<>();
    }

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
    // Shift scheduling
    // -------------------------------------------------------------------------

    public void scheduleAllShifts() {
        LocalTime epochTime = LocalTime.ofInstant(epochInstant, java.time.ZoneOffset.UTC);

        for (Grid grid : grids.values()) {
            for (Shift shift : grid.getShifts()) {
                LocalTime shiftStart = LocalTime.parse(shift.getStartAt(), TIME_FMT);
                long offsetSecs = shiftStart.toSecondOfDay() - epochTime.toSecondOfDay();
                if (offsetSecs < 0) offsetSecs += 86400;

                schedule(new ShiftOpenEvent(
                        offsetSecs,
                        nextSequence(),
                        grid.getId(),
                        shift
                ));
            }
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

    public Instant getEpochInstant() { return epochInstant; }

    public String getCurrentTimestamp() {
        Instant now = epochInstant.plus((long) currentTime, ChronoUnit.SECONDS);
        return now.toString();
    }

    /**
     * Converts a "HH:mm" shift time to a full ISO-8601 timestamp
     * relative to the epoch date. Router requires full timestamps.
     * e.g. "07:00" -> "2026-03-01T07:00:00Z"
     */
    private String shiftTimeToIso(String hhMm) {
        LocalTime t = LocalTime.parse(hhMm, TIME_FMT);
        Instant instant = epochInstant
                .truncatedTo(ChronoUnit.DAYS)
                .plus(t.toSecondOfDay(), ChronoUnit.SECONDS);
        return instant.toString();
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

    public Bin getBin(String binId) {
        for (Grid grid : grids.values()) {
            Bin bin = grid.getBin(binId);
            if (bin != null) return bin;
        }
        return null;
    }

    public double getDeliveryDelay(String gridId) {
        return 60.0;
    }

    // -------------------------------------------------------------------------
    // Router DTO builders — shift times converted to full ISO timestamps
    // -------------------------------------------------------------------------

    public List<RouterDTOs.GridDto> getRouterGridDtos() {
        List<RouterDTOs.GridDto> result = new ArrayList<>();
        for (Grid grid : grids.values()) {
            RouterDTOs.GridDto gDto = new RouterDTOs.GridDto();
            gDto.id = grid.getId();
            for (Shift shift : grid.getShifts()) {
                RouterDTOs.ShiftDto sDto = new RouterDTOs.ShiftDto();
                // Convert "HH:mm" -> full ISO timestamp so router can parse it
                sDto.startAt = shiftTimeToIso(shift.getStartAt());
                sDto.endAt   = shiftTimeToIso(shift.getEndAt());
                for (Shift.PortConfig cfg : shift.portConfig) {
                    RouterDTOs.PortConfigDto pDto = new RouterDTOs.PortConfigDto();
                    pDto.portId       = cfg.portId;
                    pDto.handlingFlags = new ArrayList<>(cfg.handlingFlags);
                    sDto.portConfig.add(pDto);
                }
                gDto.shifts.add(sDto);
            }
            result.add(gDto);
        }
        return result;
    }

    public RouterDTOs.TruckArrivalWrapper getTruckScheduleWrapper() {
        return new RouterDTOs.TruckArrivalWrapper();
    }
}