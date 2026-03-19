/**
 * EVENT: TruckArrived
 *
 * Fires when a truck arrives for a specific sorting direction.
 * All PACKED shipments assigned to that direction are marked SHIPPED.
 *
 * For Level 1 this is simple — the router doesn't produce truck schedules
 * in the level 1 sample data, so this event may not fire often.
 * It's included here so the full lifecycle (up to SHIPPED) is wired.
 */
public class TruckArrived extends Event {

    private final String sortingDirection;

    public TruckArrived(double simTime, long sequenceNumber, String sortingDirection) {
        super(simTime, sequenceNumber);
        this.sortingDirection = sortingDirection;
    }

    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%.0fs] TruckArrived: direction=%s%n",
                sim.getCurrentTime(), sortingDirection);

        List<Shipment> shipped = new ArrayList<>();

        for (Shipment shipment : sim.getAllShipments()) {
            if (shipment.getStatus() == Shipment.Status.PACKED
                    && sortingDirection.equals(shipment.getSortingDirection())) {
                shipment.markShipped(sim.getCurrentTime());
                shipped.add(shipment);
            }
        }

        System.out.printf("[%.0fs] Truck loaded %d shipments for direction %s%n",
                sim.getCurrentTime(), shipped.size(), sortingDirection);

        for (Shipment s : shipped) {
            double dwellTime = s.getShippedAt() - s.getPackedAt();
            System.out.printf("       Shipped: %s (dwell=%.0fs)%n", s.getId(), dwellTime);
        }
    }
}