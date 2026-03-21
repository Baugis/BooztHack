import java.util.ArrayList;
import java.util.List;

/**
 * EVENT: TruckArrived
 *
 * Fires at a specific pull time for a sorting direction.
 * All PACKED shipments assigned to that direction are marked SHIPPED.
 *
 * Also records whether each shipment was "packed on time" — meaning it was
 * packed before or at this truck's pull time. This feeds into the
 * end-of-simulation KPI report.
 *
 * A shipment is considered late if it was packed after the pull time
 * (which shouldn't happen in a healthy simulation, but is tracked for
 * performance analysis).
 */
public class TruckArrived extends Event {

    private final String sortingDirection;

    /**
     * The pull time of this truck in simulation seconds (offset from epoch).
     * Used for "packed on time" calculation.
     */
    private final double pullTimeSecs;

    public TruckArrived(double simTime, long sequenceNumber,
                        String sortingDirection, double pullTimeSecs) {
        super(simTime, sequenceNumber);
        this.sortingDirection = sortingDirection;
        this.pullTimeSecs     = pullTimeSecs;
    }

    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%s] TruckArrived: direction=%s%n",
                sim.getTimeLabel(), sortingDirection);

        List<Shipment> shipped   = new ArrayList<>();
        int onTime = 0;
        int late   = 0;

        for (Shipment shipment : sim.getAllShipments()) {
            if (shipment.getStatus() == Shipment.ShipmentStatus.PACKED
                    && sortingDirection.equals(shipment.getSortingDirection())) {

                shipment.markAsShipped(sim.getCurrentTime());
                shipped.add(shipment);

                // Packed on time = packed before this truck's pull time
                if (shipment.getPackedAt() <= pullTimeSecs) {
                    onTime++;
                } else {
                    late++;
                    System.out.printf("       LATE: %s (packed=%.0fs, pullTime=%.0fs, delta=+%.0fs)%n",
                            shipment.getId(),
                            shipment.getPackedAt(),
                            pullTimeSecs,
                            shipment.getPackedAt() - pullTimeSecs);
                }
            }
        }

        System.out.printf("[%s] Truck loaded %d shipments for direction=%s (onTime=%d, late=%d)%n",
                sim.getTimeLabel(), shipped.size(), sortingDirection, onTime, late);

        for (Shipment s : shipped) {
            double dwellTime = s.getShippedAt() - s.getPackedAt();
            System.out.printf("       Shipped: %s (dwell=%.0fs)%n", s.getId(), dwellTime);
        }
    }
}