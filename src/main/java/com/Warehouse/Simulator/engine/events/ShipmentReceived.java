package com.Warehouse.Simulator.engine.events;

import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.model.*;
/**
 * EVENT: ShipmentReceived
 *
 * Fired once per shipment at the simulation time matching its createdAt timestamp.
 * Registers the shipment in the simulation registry and stamps its received time,
 * marking it as the entry point of the shipment lifecycle.
 *
 * After this event fires, the shipment sits in RECEIVED status until the next
 * ShipmentRouterTriggered cycle picks it up and sends it to the external router.
 */
public class ShipmentReceived extends Event {

    /** The shipment entering the system via this event. */
    private final Shipment shipment;

    /**
     * Creates a ShipmentReceived event.
     *
     * @param simTime        simulation time at which the shipment arrives (seconds from epoch)
     * @param sequenceNumber tie-breaking sequence number for same-timestamp events
     * @param shipment       the shipment to register when this event fires
     */
    public ShipmentReceived(double simTime, long sequenceNumber, Shipment shipment) {
        super(simTime, sequenceNumber);
        this.shipment = shipment;
    }

    /**
     * Stamps the shipment's received time and adds it to the simulation registry
     * so subsequent events (router, port assignment, picking) can look it up by ID.
     *
     * @param sim the running simulation context
     */
    @Override
    public void execute(Simulation sim) {
        shipment.setReceivedAt(sim.getCurrentTime());
        sim.addShipment(shipment);
        System.out.printf("[%s] ShipmentReceived: %s%n",
                sim.getTimeLabel(), shipment.getId());
    }
}