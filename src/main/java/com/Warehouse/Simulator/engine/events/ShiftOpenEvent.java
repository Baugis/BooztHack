package com.Warehouse.Simulator.engine.events;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.model.*;
import com.Warehouse.Simulator.router.RouterDTOs;
/**
 * EVENT: ShiftOpenEvent
 *
 * Fires at the start time of a shift for a grid.
 * Opens all ports listed in the shift's portConfig, then schedules:
 *   - A ShiftCloseEvent at the shift end time.
 *   - A BreakStartEvent for each break window inside the shift.
 */
public class ShiftOpenEvent extends Event {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final String gridId;
    private final Shift shift;

    public ShiftOpenEvent(double simTime, long sequenceNumber, String gridId, Shift shift) {
        super(simTime, sequenceNumber);
        this.gridId = gridId;
        this.shift  = shift;
    }

    @Override
    public void execute(Simulation sim) {
        Grid grid = sim.getGrid(gridId);
        if (grid == null) {
            System.err.println("ShiftOpenEvent: unknown grid " + gridId);
            return;
        }

            System.out.printf("[%s] ShiftOpen: grid=%s shift=%s-%s%n",
                sim.getTimeLabel(), gridId, shift.getStartAt(), shift.getEndAt());

        // Open (or create) each port listed in this shift's config
            for (Shift.PortConfig cfg : shift.portConfig) {
                String portId = cfg.portId != null ? cfg.portId : "port-" + cfg.portIndex;
                Port port = grid.getPort(portId);
                if (port == null) {
        // Port doesn't exist yet — create it now from the config
                    Set<String> flags = new HashSet<>(cfg.handlingFlags);
                    port = new Port(portId, gridId, flags);
                    grid.addPort(port);
            }
    // Only open if currently CLOSED (may already be open from a prior shift)
            if (port.getStatus() == Port.Status.CLOSED) {
                port.open();
                System.out.printf("[%s] Port %s opened%n", sim.getTimeLabel(), portId);
            }

    // If an idle port has shipments waiting in the grid queue, pull one now
            if (port.getStatus() == Port.Status.IDLE && grid.hasQueuedShipments()) {
                tryAssignFromGridQueue(sim, grid, port);
            }
        }

        // Schedule breaks
        LocalTime shiftStart = LocalTime.parse(shift.getStartAt(), TIME_FMT);
        double shiftStartSec = sim.getCurrentTime(); // this IS the shift start moment

        for (Shift.BreakWindow brk : shift.getBreaks()) {
            LocalTime breakStart = LocalTime.parse(brk.startAt, TIME_FMT);
            LocalTime breakEnd   = LocalTime.parse(brk.endAt,   TIME_FMT);

            double breakStartOffset = secondsBetween(shiftStart, breakStart);
            double breakEndOffset   = secondsBetween(shiftStart, breakEnd);

            double breakStartTime = shiftStartSec + breakStartOffset;
            double breakEndTime   = shiftStartSec + breakEndOffset;

            long breakStartSeq = sim.nextSequence();
            long breakEndSeq   = sim.nextSequence();
            sim.schedule(new BreakStartEvent(breakStartTime, breakStartSeq, gridId, shift, brk));
            sim.schedule(new BreakEndEvent(breakEndTime,     breakEndSeq,   gridId, shift, brk));
        }

        // Schedule shift close
        LocalTime shiftEnd = LocalTime.parse(shift.getEndAt(), TIME_FMT);
        double shiftEndOffset = secondsBetween(shiftStart, shiftEnd);
        double closeTime = shiftStartSec + shiftEndOffset;
        sim.schedule(new ShiftCloseEvent(closeTime, sim.nextSequence(), gridId, shift));
    }

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
            grid.enqueueShipment(next); // put it back if incompatible
        }
    }

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

    /** Returns the wall-clock seconds between two LocalTime values (handles midnight wrap). */
    public static long secondsBetween(LocalTime from, LocalTime to) {
        long secs = to.toSecondOfDay() - from.toSecondOfDay();
        if (secs < 0) secs += 86400; // next-day wrap
        return secs;
    }
}