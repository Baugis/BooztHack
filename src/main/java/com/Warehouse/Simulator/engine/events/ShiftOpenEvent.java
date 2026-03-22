package com.Warehouse.Simulator.engine.events;

import com.Warehouse.Simulator.model.Bin;
import com.Warehouse.Simulator.model.Grid;
import com.Warehouse.Simulator.model.Port;
import com.Warehouse.Simulator.model.Shift;
import com.Warehouse.Simulator.model.Shipment;
import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.router.RouterDTOs;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

/**
 * EVENT: ShiftOpenEvent
 *
 * Fires at the start time of a shift for a grid.
 * Responsible for bringing the grid's ports online and scheduling all
 * time-bound sub-events that belong to this shift window.
 *
 * On execution:
 *   1. Opens every port listed in the shift's portConfig. Ports that do not
 *      yet exist in the grid are created on the fly from the config entry.
 *      Ports already open (e.g. from an overlapping prior shift) are left
 *      unchanged.
 *   2. If a newly opened port is immediately IDLE and the grid queue already
 *      holds waiting shipments, one shipment is pulled and started right away.
 *   3. Schedules a BreakStartEvent and BreakEndEvent for each break window
 *      defined within this shift.
 *   4. Schedules a ShiftCloseEvent at the shift's end time.
 *
 * Break and close times are computed as offsets from the shift start so that
 * midnight-spanning shifts are handled correctly.
 */
public class ShiftOpenEvent extends Event {

    /** Formatter used to parse "HH:mm" shift and break time strings. */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** ID of the grid whose ports this event opens. */
    private final String gridId;

    /** The shift definition containing port configs, break windows, and end time. */
    private final Shift shift;

    /**
     * Creates a ShiftOpenEvent.
     *
     * @param simTime        simulation time at which the shift starts (seconds from epoch)
     * @param sequenceNumber tie-breaking sequence number for same-timestamp events
     * @param gridId         ID of the grid whose ports should be opened
     * @param shift          shift definition to apply
     */
    public ShiftOpenEvent(double simTime, long sequenceNumber, String gridId, Shift shift) {
        super(simTime, sequenceNumber);
        this.gridId = gridId;
        this.shift  = shift;
    }

    /**
     * Opens ports, drains any waiting shipments into newly idle ports, and
     * schedules all break and close events for this shift (see class Javadoc).
     *
     * @param sim the running simulation context
     */
    @Override
    public void execute(Simulation sim) {
        Grid grid = sim.getGrid(gridId);
        if (grid == null) {
            System.err.println("ShiftOpenEvent: unknown grid " + gridId);
            return;
        }

        System.out.printf("[%s] ShiftOpen: grid=%s shift=%s-%s%n",
                sim.getTimeLabel(), gridId, shift.getStartAt(), shift.getEndAt());

        for (Shift.PortConfig cfg : shift.portConfig) {
            Port port = grid.getPort(cfg.portId);
            if (port == null) {
                Set<String> flags = new HashSet<>(cfg.handlingFlags);
                port = new Port(cfg.portId, gridId, flags);
                grid.addPort(port);
            }

            if (port.getStatus() == Port.Status.CLOSED) {
                port.open();
                System.out.printf("[%s] Port %s opened%n", sim.getTimeLabel(), cfg.portId);
            }

            if (port.getStatus() == Port.Status.IDLE && grid.hasQueuedShipments()) {
                tryAssignFromGridQueue(sim, grid, port);
            }
        }

        LocalTime shiftStart    = LocalTime.parse(shift.getStartAt(), TIME_FMT);
        double    shiftStartSec = sim.getCurrentTime();

        for (Shift.BreakWindow brk : shift.getBreaks()) {
            LocalTime breakStart = LocalTime.parse(brk.startAt, TIME_FMT);
            LocalTime breakEnd   = LocalTime.parse(brk.endAt,   TIME_FMT);

            double breakStartTime = shiftStartSec + secondsBetween(shiftStart, breakStart);
            double breakEndTime   = shiftStartSec + secondsBetween(shiftStart, breakEnd);

            sim.schedule(new BreakStartEvent(breakStartTime, sim.nextSequence(), gridId, shift, brk));
            sim.schedule(new BreakEndEvent(breakEndTime,     sim.nextSequence(), gridId, shift, brk));
        }

        LocalTime shiftEnd    = LocalTime.parse(shift.getEndAt(), TIME_FMT);
        double    closeTime   = shiftStartSec + secondsBetween(shiftStart, shiftEnd);
        sim.schedule(new ShiftCloseEvent(closeTime, sim.nextSequence(), gridId, shift));
        java.util.Map<String, Object> logData = new java.util.HashMap<>();

        logData.put("gridId", gridId);
        logData.put("shiftStart", shift.getStartAt());
        logData.put("shiftEnd", shift.getEndAt());

        sim.logEvent("ShiftOpen", logData);
    }

    /**
     * Attempts to pull the next shipment from the grid queue and start it on
     * the given port. If the shipment is incompatible with the port or the port
     * has no queue capacity, the shipment is returned to the back of the grid queue.
     *
     * @param sim  simulation context
     * @param grid the grid whose queue to drain
     * @param port the idle port to assign work to
     */
    private void tryAssignFromGridQueue(Simulation sim, Grid grid, Port port) {
        Shipment next = grid.dequeueShipment();
        if (next == null) return;

        if (port.canHandle(next) && port.hasQueueCapacity()) {
            port.enqueue(next);
            Shipment started = port.startNextShipment();
            if (started != null) {
                requestFirstBin(sim, port, started);
            }
        } else {
            // Shipment is incompatible with this port — put it back for the next candidate.
            grid.enqueueShipment(next);
        }
    }

    /**
     * Requests the first bin in the shipment's pick list from the grid conveyor.
     * Marks the bin as OUTSIDE (in transit) and schedules a BinArrivedAtPort
     * event after the grid's delivery delay.
     *
     * Does nothing if the shipment has no picks or the bin cannot be found.
     *
     * @param sim      simulation context
     * @param port     the port that will receive the bin
     * @param shipment the shipment whose first pick should be fetched
     */
    private void requestFirstBin(Simulation sim, Port port, Shipment shipment) {
        RouterDTOs.Pick pick = shipment.nextPick();
        if (pick == null) return;

        Bin bin = sim.getBin(pick.binId);
        if (bin == null) return;

        bin.markOutside();

        double delay = sim.getDeliveryDelay(shipment.getPackingGrid());
        sim.schedule(new BinArrivedAtPort(
                sim.getCurrentTime() + delay,
                sim.nextSequence(),
                port.getId(),
                shipment.getId(),
                pick.binId, pick.ean, pick.qty,
                shipment.getPackingGrid()
        ));
    }

    /**
     * Returns the number of wall-clock seconds from {@code from} to {@code to}.
     * If {@code to} is earlier in the day than {@code from} (i.e. the interval
     * crosses midnight), 86400 seconds are added to produce a positive duration.
     *
     * @param from the start time
     * @param to   the end time
     * @return non-negative seconds between the two times, wrapping at midnight
     */
    public static long secondsBetween(LocalTime from, LocalTime to) {
        long secs = to.toSecondOfDay() - from.toSecondOfDay();
        if (secs < 0) secs += 86_400;
        return secs;
    }
}