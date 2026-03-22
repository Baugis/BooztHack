package com.Warehouse.Simulator.engine;

import java.util.PriorityQueue;
import com.Warehouse.Simulator.engine.events.Event;

public class EventQueue {

    // PriorityQueue always gives you the smallest element first.
    // Since Event.compareTo sorts by simTime, the next event to fire
    // is always at the front.
    private final PriorityQueue<Event> queue = new PriorityQueue<>();

    // Counter to assign sequence numbers in scheduling order.
    // Every time we schedule an event, this increments.
    private long sequence = 0;

    public void schedule(Event event) {
        queue.add(event);
    }

    // Returns and removes the next event to process
    public Event pollNext() {
        return queue.poll();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    // Convenience method - lets other classes get the next sequence number
    // when constructing events
    public long nextSequence() {
        return sequence++;
    }
}