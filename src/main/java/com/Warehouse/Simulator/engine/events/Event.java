package com.Warehouse.Simulator.engine.events;

// Every event in the simulation extends this.
// 'implements Comparable' so the priority queue can order events by time.
import com.Warehouse.Simulator.engine.Simulation;
/**
 * Base class for every event in the discrete-event simulation.
 *
 * <p>Events are the sole mechanism for advancing simulation state. Each
 * concrete subclass represents one thing that happens at a specific point
 * in simulated time (e.g. {@link BinArrivedAtPort}, {@link BreakStartEvent}).
 *
 * <p>Implements {@link Comparable} so events can be ordered inside the
 * simulation's priority queue. Ordering rules (per spec §8.3):
 * <ol>
 *   <li>Earlier {@code simTime} fires first.</li>
 *   <li>Equal {@code simTime} → lower {@code sequenceNumber} fires first
 *       (FIFO scheduling order).</li>
 * </ol>
 */
public abstract class Event implements Comparable<Event> {

    /**
     * Simulated time at which this event should fire, expressed as seconds
     * elapsed since simulation start (epoch).
     */
    private final double simTime;

    /**
     * Monotonically increasing counter assigned at scheduling time.
     * Acts as a FIFO tiebreaker when two events share the same {@code simTime},
     * ensuring the event scheduled first is also processed first (spec §8.3).
     */
    private final long sequenceNumber;

    /**
     * @param simTime        seconds from simulation start at which this event fires
     * @param sequenceNumber scheduling-order tiebreaker; obtain from
     *                       {@link Simulation#nextSequence()}
     */
    public Event(double simTime, long sequenceNumber) {
        this.simTime        = simTime;
        this.sequenceNumber = sequenceNumber;
    }

    /**
     * Executes the event's logic at its scheduled simulation time.
     *
     * <p>Implementations may read and mutate simulation state, schedule new
     * events via {@link Simulation#schedule(Event)}, and write to stdout/stderr
     * for logging. Called exactly once by the simulation loop when this event
     * reaches the head of the priority queue.
     *
     * @param sim the running {@link Simulation} instance providing shared state
     *            and scheduling access
     */
    public abstract void execute(Simulation sim);
    /**
     * Orders events for the simulation priority queue.
     * Earlier time fires first; equal times are broken by {@code sequenceNumber}
     * (lower = scheduled earlier = fires first).
     *
     * @param other event to compare against
     * @return negative if {@code this} should fire before {@code other},
     *         positive if after, zero if identical priority (should not occur
     *         in practice given unique sequence numbers)
     */
    @Override
    public int compareTo(Event other) {
        int timeCompare = Double.compare(this.simTime, other.simTime);
        if (timeCompare != 0) return timeCompare;
        return Long.compare(this.sequenceNumber, other.sequenceNumber);
    }

    /**
     * @return the simulated fire time in seconds from epoch
     */
    public double getSimTime() { return simTime; }
}