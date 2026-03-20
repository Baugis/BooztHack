import java.util.HashMap;
import java.util.Map;

public class Simulation {

    private final EventQueue eventQueue;
    private double currentTime;
    private final double endTime;

    // All simulation state lives here - grids, bins, shipments, ports
    // Events reach into this to read and modify state
    private final Map<String, Grid> grids;
    private final Map<String, Shipment> shipments;

    public Simulation(double endTime) {
        this.eventQueue = new EventQueue();
        this.currentTime = 0;
        this.endTime = endTime;
        this.grids = new HashMap<>();
        this.shipments = new HashMap<>();
    }

    // The main loop - keeps processing events until none are left
    // or we've passed the end time
    public void run() {
        while (!eventQueue.isEmpty()) {
            Event next = eventQueue.pollNext();

            // Don't process events scheduled after simulation ends
            if (next.getSimTime() > endTime) break;

            // Advance the simulation clock to this event's time
            currentTime = next.getSimTime();

            // Let the event do its work
            next.execute(this);
        }
    }

    // Called by events to schedule future events
    public void schedule(Event event) {
        eventQueue.schedule(event);
    }

    public long nextSequence() {
        return eventQueue.nextSequence();
    }

    public double getCurrentTime() { return currentTime; }

    // State accessors for events to use
    public Grid getGrid(String id) { return grids.get(id); }
    public Shipment getShipment(String id) { return shipments.get(id); }
    public void addGrid(Grid grid) { grids.put(grid.getId(), grid); }
    public void addShipment(Shipment s) { shipments.put(s.getId(), s); }
        public java.util.Collection<Shipment> getAllShipments() {
        return shipments.values();
    }

    
}