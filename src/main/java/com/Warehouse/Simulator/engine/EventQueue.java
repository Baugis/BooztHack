package com.Warehouse.Simulator.engine;

import java.util.PriorityQueue;

import com.Warehouse.Simulator.engine.events.Event;

/**
 * Priority queue wrapper that drives the simulation's event loop.
 *
 * <p>Events are dequeued in ascending {@link Event#getSimTime()} order.
 * Equal-time events are broken by their {@code sequenceNumber} (FIFO),
 * which callers obtain via {@link #nextSequence()} before constructing
 * each event.
 *
 * <p>Typical usage inside the simulation loop:
 * <pre>{@code
 * while (!eventQueue.isEmpty()) {
 *     Event next = eventQueue.pollNext();
 *     next.execute(sim);
 * }
 * }</pre>
 */
public class EventQueue {

    /**
     * Underlying min-heap ordered by {@link Event#compareTo}.
     * Always yields the earliest (lowest simTime + sequenceNumber) event first.
     */
    private final PriorityQueue<Event> queue = new PriorityQueue<>();

    /**
     * Monotonically increasing counter. Incremented on every {@link #nextSequence()}
     * call to guarantee that events scheduled earlier always carry a lower number,
     * preserving FIFO order among same-time events (spec §8.3).
     */
    private long sequence = 0;

    /**
     * Adds an event to the queue. The event will be dequeued after all
     * earlier (or same-time, earlier-scheduled) events have been processed.
     *
     * @param event the event to schedule; must not be {@code null}
     */
    public void schedule(Event event) {
        queue.add(event);
    }

    /**
     * Removes and returns the next event to process — the one with the
     * lowest {@code simTime}, or the lowest {@code sequenceNumber} if times
     * are equal.
     *
     * @return the next event, or {@code null} if the queue is empty
     */
    public Event pollNext() {
        return queue.poll();
    }

    /**
     * @return {@code true} if no events remain to be processed
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Returns the next sequence number and advances the internal counter.
     * Call this once per event construction, immediately before passing
     * the number to the event's constructor — the returned value is not
     * reserved and will not be issued again.
     *
     * @return a unique, strictly increasing sequence number
     */
    public long nextSequence() {
        return sequence++;
    }
}