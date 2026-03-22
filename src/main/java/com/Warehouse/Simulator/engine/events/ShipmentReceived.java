package com.Warehouse.Simulator.engine.events;

import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.model.*;
/**
 * EVENT: ShipmentReceived
 *
 * Fired once per shipment at the sim time matching its createdAt timestamp.
 * Registers the shipment in the simulation and marks it as RECEIVED.
 *
 * This is the entry point for every customer order into the system.
 * After this, the shipment sits waiting until the next ShipmentRouterTriggered
 * event picks it up and sends it to the router.
 */
public class ShipmentReceived extends Event {

    private final Shipment shipment;

    public ShipmentReceived(double simTime, long sequenceNumber, Shipment shipment) {
        super(simTime, sequenceNumber);
        this.shipment = shipment;
    }

    @Override
    public void execute(Simulation sim) {
        // Record when this shipment entered the system
        shipment.setReceivedAt(sim.getCurrentTime());

        // Register it so other events can look it up by ID
        sim.addShipment(shipment);

        System.out.printf("[%s] ShipmentReceived: %s%n",
                sim.getTimeLabel(), shipment.getId());
    }
}
