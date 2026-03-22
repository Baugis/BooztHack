package com.Warehouse.Simulator.model;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.ArrayList;

/**
 * MODEL: Shift
 *
 * Represents a single operational window for a grid, loaded from grids.json.
 * A shift defines when a set of ports is active, which ports are included and
 * what handling flags each port supports, and any scheduled break windows
 * within the shift period.
 *
 * Shift times are stored as "HH:mm" strings. Simulation.shiftTimeToIso()
 * converts them to full ISO-8601 timestamps when building router payloads.
 *
 * ShiftOpenEvent is scheduled at the shift's start time and in turn schedules
 * BreakStartEvent/BreakEndEvent pairs and a ShiftCloseEvent using the offsets
 * defined here.
 */
public class Shift {

    /**
     * Shift start time in "HH:mm" format, e.g. "06:00".
     * Mapped from the JSON field "start".
     */
    @SerializedName("start")
    public String startAt;

    /**
     * Shift end time in "HH:mm" format, e.g. "18:00".
     * Mapped from the JSON field "end".
     * If this value is not after startAt the shift is treated as overnight
     * (ending the following day).
     */
    @SerializedName("end")
    public String endAt;

    /**
     * Port configurations active during this shift.
     * Each entry defines one port ID and the handling flags that port supports.
     * Mapped from the JSON field "portConfig".
     */
    @SerializedName("portConfig")
    public List<PortConfig> portConfig = new ArrayList<>();

    /**
     * Scheduled break windows within this shift.
     * Ports pause during breaks: IDLE ports close immediately and BUSY ports
     * finish their current shipment before pausing (PENDING_CLOSE).
     * Mapped from the JSON field "breaks". May be empty if no breaks are defined.
     */
    @SerializedName("breaks")
    public List<BreakWindow> breaks = new ArrayList<>();

    /** Returns the shift start time string ("HH:mm"). */
    public String getStartAt() { return startAt; }

    /** Returns the shift end time string ("HH:mm"). */
    public String getEndAt()   { return endAt; }

    /**
     * Returns the list of break windows for this shift.
     * Guards against null (e.g. when Gson does not populate the field)
     * by returning an empty list instead.
     */
    public List<BreakWindow> getBreaks() {
        return breaks != null ? breaks : new ArrayList<>();
    }

    /**
     * A single scheduled break window within a shift.
     * During a break, ports behave the same as at shift end: IDLE ports close
     * immediately and BUSY ports enter PENDING_CLOSE until their current
     * shipment is finished. Ports reopen when the break ends (BreakEndEvent).
     */
    public static class BreakWindow {

        /**
         * Break start time in "HH:mm" format.
         * Mapped from the JSON field "start".
         */
        @SerializedName("start")
        public String startAt;

        /**
         * Break end time in "HH:mm" format.
         * Mapped from the JSON field "end".
         */
        @SerializedName("end")
        public String endAt;
    }

    /**
     * Configuration for a single port within a shift.
     * Defines the port's identifier and the set of handling flags it supports
     * during this shift window.
     */
    public static class PortConfig {

        /**
         * Unique port identifier, e.g. "port-AS1-0".
         * Mapped from the JSON field "id".
         */
        @SerializedName("id")
        public String portId;

        /**
         * Handling flags this port can process during the shift,
         * e.g. ["fragile", "heavy"]. A shipment is only assigned to this
         * port if all of its required flags are present in this list.
         * Mapped from the JSON field "handlingFlags".
         */
        @SerializedName("handlingFlags")
        public List<String> handlingFlags = new ArrayList<>();
    }
}