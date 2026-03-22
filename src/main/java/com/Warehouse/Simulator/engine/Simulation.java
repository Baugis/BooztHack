package com.Warehouse.Simulator.engine;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.Warehouse.Simulator.engine.events.Event;
import com.Warehouse.Simulator.engine.events.ShiftOpenEvent;
import com.Warehouse.Simulator.engine.events.TruckSchedule;
import com.Warehouse.Simulator.model.Bin;
import com.Warehouse.Simulator.model.Grid;
import com.Warehouse.Simulator.model.Port;
import com.Warehouse.Simulator.model.Shift;
import com.Warehouse.Simulator.model.Shipment;
import com.Warehouse.Simulator.router.RouterDTOs;

/**
 * Central simulation engine.
 *
 * Owns all mutable warehouse state (grids, ports, shipments, bins) and the
 * event queue. Events call back into this class to read and modify state and
 * to schedule further events.
 *
 * Lifecycle:
 *   1. Construct with a duration and an epoch instant.
 *   2. Register grids, bins, conveyor delays, and initial events via the
 *      addGrid/addBin/schedule/registerConveyors methods.
 *   3. Call {@link #scheduleAllShifts()} to expand shift templates into
 *      concrete {@link ShiftOpenEvent} entries.
 *   4. Call {@link #run()} to process the event queue until it is empty or
 *      the end time is reached.
 */

public class Simulation {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final EventQueue eventQueue;

    /** Simulation clock — seconds elapsed since the epoch instant. */
    private double currentTime;

    /** The simulation stops processing events whose simTime exceeds this value. */
    private final double endTime;

    /**
     * Wall-clock anchor for the simulation.
     * simTime=0 corresponds to this instant; all ISO timestamps are derived
     * by adding {@code currentTime} seconds to it.
     */
    private final Instant epochInstant;

    /** All ports registered in the simulation, keyed by port ID. */
    private final Map<String, Port> ports;

    /** All grids registered in the simulation, keyed by grid ID. */
    private final Map<String, Grid> grids;

    /** All shipments that have entered the system, keyed by shipment ID. */
    private final Map<String, Shipment> shipments;

    /**
     * Inter-grid conveyor transfer times in seconds.
     * Key format: "fromGridId->toGridId" (e.g. "AS1->AS2").
     * Populated via {@link #registerConveyors(Map)} before the simulation runs.
     */
    private final Map<String, Double> conveyorDelays = new HashMap<>();
    private final List<TruckSchedule> truckSchedules = new ArrayList<>();

    private final Map<String, Double> deliveryTimes = new HashMap<>();
    private com.Warehouse.Simulator.io.EventLogger eventLogger;


    /**
     * Fallback transfer delay used when no conveyor is configured for a
     * given grid pair. Value: 300 seconds (5 minutes).
     */
    private static final double DEFAULT_TRANSFER_DELAY = 300.0;
    /**
     * Full constructor.
     *
     * @param endTime       simulation duration in seconds; events beyond this are discarded
     * @param epochInstant  wall-clock origin; simTime=0 maps to this instant
     */
    public Simulation(double endTime, Instant epochInstant) {
        this.eventQueue   = new EventQueue();
        this.currentTime  = 0;
        this.endTime      = endTime;
        this.epochInstant = epochInstant;
        this.ports        = new HashMap<>();
        this.grids        = new HashMap<>();
        this.shipments    = new HashMap<>();
    }

    /**
     * Convenience constructor that uses a fixed epoch of 2026-03-01T00:00:00Z.
     *
     * @param endTime simulation duration in seconds
     */
    public Simulation(double endTime) {
        this(endTime, Instant.parse("2026-03-01T00:00:00Z"));
    }

    /**
     * Runs the simulation to completion.
     *
     * Polls events from the priority queue in chronological order and executes
     * each one. Stops when the queue is empty or the next event's simTime
     * exceeds {@code endTime}.
     */
    public void run() {
        while (!eventQueue.isEmpty()) {
            Event next = eventQueue.pollNext();
            if (next.getSimTime() > endTime) break;
            currentTime = next.getSimTime();
            next.execute(this);
        }
    }

    /**
     * Expands every shift defined in every grid into one {@link ShiftOpenEvent}
     * per simulation day and schedules them all upfront.
     *
     * The method iterates over {@code totalDays} (derived from {@code endTime})
     * and, for each grid/shift/day combination, computes the absolute simTime
     * of the shift start — accounting for the epoch's wall-clock offset so that
     * a shift starting at "07:00" fires at the correct second each day.
     * Shifts whose computed start time exceeds {@code endTime} are skipped.
     */
    public void scheduleAllShifts() {
        LocalTime epochTime = LocalTime.ofInstant(epochInstant, java.time.ZoneOffset.UTC);
        int totalDays = (int) Math.ceil(endTime / 86_400.0);

        for (Grid grid : grids.values()) {
            for (Shift shift : grid.getShifts()) {
                for (int day = 0; day < totalDays; day++) {
                    double dayOffset = day * 86_400.0;

                    LocalTime shiftStart = LocalTime.parse(shift.getStartAt(), TIME_FMT);

                    long startSecs = shiftStart.toSecondOfDay() - epochTime.toSecondOfDay();
                    if (startSecs < 0) startSecs += 86_400; // wrap past midnight

                    double absoluteStart = dayOffset + startSecs;
                    if (absoluteStart > endTime) continue;

                    schedule(new ShiftOpenEvent(
                            absoluteStart,
                            nextSequence(),
                            grid.getId(),
                            shift
                    ));
                }
            }
        }
    }

    /** Adds an event to the priority queue for future processing. */
    public void schedule(Event event) {
        eventQueue.schedule(event);
    }

    /** Returns the next monotonically increasing sequence number for event tie-breaking. */
    public long nextSequence() {
        return eventQueue.nextSequence();
    }

    public void setEventLogger(com.Warehouse.Simulator.io.EventLogger logger) {
        this.eventLogger = logger;
    }

    public void logEvent(String eventName, Object dataPayload) {
        if (eventLogger != null) {
            eventLogger.log(currentTime, getCurrentTimestamp(), eventName, dataPayload);
        }
    }

    /** Returns the current simulation clock value in seconds from epoch. */
    public double getCurrentTime() { return currentTime; }

    /** Returns the wall-clock instant that corresponds to simTime=0. */
    public Instant getEpochInstant() { return epochInstant; }

    /**
     * Returns the current simulation time as a full ISO-8601 UTC string,
     * e.g. "2026-03-01T09:15:00Z". Used when building router payloads.
     */
    public String getCurrentTimestamp() {
        Instant now = epochInstant.plus((long) currentTime, ChronoUnit.SECONDS);
        return now.toString();
    }

    /**
     * Converts a "HH:mm" shift time to a full ISO-8601 UTC timestamp anchored
     * to the epoch date. The router requires full timestamps rather than bare
     * time strings.
     *
     * Example: "07:00" → "2026-03-01T07:00:00Z"
     *
     * @param hhMm time string in "HH:mm" format
     * @return ISO-8601 UTC timestamp string
     */
    private String shiftTimeToIso(String hhMm) {
    LocalTime t = LocalTime.parse(hhMm, TIME_FMT);
    Instant currentSimInstant = epochInstant.plusSeconds((long) this.currentTime);
    LocalDate currentDate = currentSimInstant.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    
    return currentDate.atTime(t).atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
    }

    /**
     * Overload that handles overnight shifts: if {@code hhMm} is not after
     * {@code startHhMm} (e.g. end time "02:00" with start "22:00"), the
     * returned timestamp is advanced by one day so the shift end always falls
     * after the shift start.
     *
     * @param hhMm       the time to convert, in "HH:mm" format
     * @param startHhMm  the shift's start time, used to detect midnight wrap
     * @return ISO-8601 UTC timestamp string, possibly on the following day
     */
        private String shiftTimeToIso(String hhMm, String startHhMm) {
            LocalTime t     = LocalTime.parse(hhMm,      TIME_FMT);
            LocalTime start = LocalTime.parse(startHhMm, TIME_FMT);
            Instant currentSimInstant = epochInstant.plusSeconds((long) this.currentTime);
            java.time.LocalDate currentDate = currentSimInstant.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        
            if (!t.isAfter(start)) {
                currentDate = currentDate.plusDays(1);
            }
        
            return currentDate.atTime(t).atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
        }

    /**
     * Returns a human-readable simulation time label for console logging.
     * Format: "HH:MM:SS [Xs]", where X is the raw second count.
     * Example: "09:15:03 [33303s]"
     */
    public String getTimeLabel() {
        long secs = (long) currentTime;
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        return String.format("%02d:%02d:%02d [%.0fs]", h, m, s, currentTime);
    }

    /** Looks up a port by ID; returns null if not found. */
    public Port getPort(String id)     { return ports.get(id); }

    /** Looks up a grid by ID; returns null if not found. */
    public Grid getGrid(String id)     { return grids.get(id); }

    /** Looks up a shipment by ID; returns null if not found. */
    public Shipment getShipment(String id) { return shipments.get(id); }

    /** Registers a port in the global port registry. */
    public void addPort(Port port)     { ports.put(port.getId(), port); }

    /** Registers a grid in the global grid registry. */
    public void addGrid(Grid grid)     { grids.put(grid.getId(), grid); }

    /** Registers a shipment in the global shipment registry. */
    public void addShipment(Shipment s) { shipments.put(s.getId(), s); }

    /** Returns a live view of all registered ports. */
    public Collection<Port>     getAllPorts()     { return ports.values(); }

    /** Returns a live view of all registered grids. */
    public Collection<Grid>     getAllGrids()     { return grids.values(); }

    /** Returns a live view of all registered shipments. */
    public Collection<Shipment> getAllShipments() { return shipments.values(); }

    /**
     * Searches all grids for a bin with the given ID.
     * Bins are stored inside their owning grid, so this performs a linear
     * scan across grids until a match is found.
     *
     * @param binId the bin identifier to look up
     * @return the matching {@link Bin}, or null if not found in any grid
     */
    public Bin getBin(String binId) {
        for (Grid grid : grids.values()) {
            Bin bin = grid.getBin(binId);
            if (bin != null) return bin;
        }
        return null;
    }

    /**
     * Returns the bin delivery delay for the given grid in seconds.
     * @param gridId the grid from which a bin is being delivered
     * @return delivery delay in seconds (currently hardcoded to 60s)
     */
    public double getDeliveryDelay(String gridId) {
        return deliveryTimes.getOrDefault(gridId, 60.0);
    }

    /**
     * Bulk-registers conveyor delays from a pre-built map.
     * Must be called before {@link #run()} so that transfer events use the
     * correct durations rather than the default fallback.
     *
     * @param delays map of "fromGridId->toGridId" keys to transfer times in seconds
     */
    public void registerConveyors(Map<String, Double> delays) {
        conveyorDelays.putAll(delays);
    }
    public void registerTruckSchedules(List<TruckSchedule> schedules) {
        truckSchedules.addAll(schedules);
    }

    public void registerDeliveryTimes(Map<String, Double> times) {
        deliveryTimes.putAll(times);
    }


    /**
     * Returns the conveyor transfer time in seconds between two grids.
     * If no conveyor is registered for the given pair, falls back to
     * 300 seconds (DEFAULT_TRANSFER_DELAY).
     *
     * @param fromGridId source grid identifier
     * @param toGridId   destination grid identifier
     * @return transfer duration in seconds
     */
    public double getTransferDelay(String fromGridId, String toGridId) {
        String key = fromGridId + "->" + toGridId;
        return conveyorDelays.getOrDefault(key, DEFAULT_TRANSFER_DELAY);
    }

    /**
     * Builds the list of {@link RouterDTOs.GridDto} objects sent to the external
     * router on each routing call.
     *
     * Shift times stored internally as "HH:mm" strings are converted to full
     * ISO-8601 timestamps here because the router requires absolute times.
     * Overnight shifts (end time before start time) are handled by the
     * two-argument {@link #shiftTimeToIso(String, String)} overload.
     *
     * @return list of grid DTOs ready for JSON serialisation
     */
    public List<RouterDTOs.GridDto> getRouterGridDtos() {
        List<RouterDTOs.GridDto> result = new ArrayList<>();
        for (Grid grid : grids.values()) {
            RouterDTOs.GridDto gDto = new RouterDTOs.GridDto();
            gDto.id = grid.getId();
            for (Shift shift : grid.getShifts()) {
                RouterDTOs.ShiftDto sDto = new RouterDTOs.ShiftDto();   
                sDto.startAt = shiftTimeToIso(shift.getStartAt());
                sDto.endAt   = shiftTimeToIso(shift.getEndAt(), shift.getStartAt());
                for (Shift.PortConfig cfg : shift.portConfig) {
                    RouterDTOs.PortConfigDto pDto = new RouterDTOs.PortConfigDto();
                    pDto.portId        = cfg.portId;
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
        RouterDTOs.TruckArrivalWrapper wrapper = new RouterDTOs.TruckArrivalWrapper();
        for (TruckSchedule ts : truckSchedules) {
            RouterDTOs.TruckScheduleDto dto = new RouterDTOs.TruckScheduleDto();
            dto.sortingDirection = ts.sortingDirection;
            dto.pullTimes        = new ArrayList<>(ts.pullTimes);
            dto.weekdays         = new ArrayList<>(ts.weekdays);
            wrapper.schedules.add(dto);
        }
        return wrapper;
    }
}