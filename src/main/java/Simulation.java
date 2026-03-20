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

    // Simulation epoch — "time 0" in wall-clock terms
    private final Instant epochInstant;

    // All simulation state
    private final Map<String, Grid>     grids;
    private final Map<String, Shipment> shipments;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public Simulation(double endTime, Instant epochInstant) {
        this.eventQueue    = new EventQueue();
        this.currentTime   = 0;
        this.endTime       = endTime;
        this.epochInstant  = epochInstant;
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
    // Shift scheduling — call after all grids are loaded, before run()
    // -------------------------------------------------------------------------

    /**
     * Reads every grid's shift list and schedules ShiftOpenEvents so ports
     * open and close automatically during the simulation.
     *
     * The epoch instant is used as "time 0". Shift times like "07:00" are
     * resolved relative to the epoch's date.
     */
    public void scheduleAllShifts() {
        LocalTime epochTime = LocalTime.ofInstant(epochInstant,
                java.time.ZoneOffset.UTC);

        for (Grid grid : grids.values()) {
            for (Shift shift : grid.getShifts()) {
                LocalTime shiftStart = LocalTime.parse(shift.getStartAt(), TIME_FMT);
                long offsetSecs = shiftStart.toSecondOfDay() - epochTime.toSecondOfDay();
                if (offsetSecs < 0) offsetSecs += 86400; // next-day wrap

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

    // -------------------------------------------------------------------------
    // State accessors
    // -------------------------------------------------------------------------

    public Grid     getGrid(String id)     { return grids.get(id); }
    public Shipment getShipment(String id) { return shipments.get(id); }

    public void addGrid(Grid grid)         { grids.put(grid.getId(), grid); }
    public void addShipment(Shipment s)    { shipments.put(s.getId(), s); }

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
        return 60.0; // seconds — replace with per-grid lookup at higher levels
    }

    // -------------------------------------------------------------------------
    // Router DTO builders
    // -------------------------------------------------------------------------

    public List<RouterDTOs.GridDto> getRouterGridDtos() {
        List<RouterDTOs.GridDto> result = new ArrayList<>();
        for (Grid grid : grids.values()) {
            RouterDTOs.GridDto gDto = new RouterDTOs.GridDto();
            gDto.id = grid.getId();
            for (Shift shift : grid.getShifts()) {
                RouterDTOs.ShiftDto sDto = new RouterDTOs.ShiftDto();
                sDto.startAt = shift.getStartAt();
                sDto.endAt   = shift.getEndAt();
                for (Port port : grid.getAllPorts()) {
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

    public RouterDTOs.TruckArrivalWrapper getTruckScheduleWrapper() {
        return new RouterDTOs.TruckArrivalWrapper();
    }
}