// Every event in the simulation extends this.
// 'implements Comparable' so the priority queue can order events by time.
public abstract class Event implements Comparable<Event> {

    // When this event should fire, in seconds from simulation start
    private final double simTime;

    // Used as a tiebreaker when two events have the exact same simTime.
    // The spec says same-time events are processed in scheduling order (FIFO).
    // (section 8.3)
    private final long sequenceNumber;

    public Event(double simTime, long sequenceNumber) {
        this.simTime = simTime;
        this.sequenceNumber = sequenceNumber;
    }

    // Each event subclass implements this to define what actually happens
    // when the event fires. It receives the simulation context so it can
    // read/modify state and schedule new events.
    public abstract void execute(Simulation sim);

    @Override
    public int compareTo(Event other) {
        // First sort by time
        int timeCompare = Double.compare(this.simTime, other.simTime);
        if (timeCompare != 0) return timeCompare;
        // If same time, preserve scheduling order (FIFO)
        return Long.compare(this.sequenceNumber, other.sequenceNumber);
    }

    public double getSimTime() { return simTime; }
}