package com.Warehouse.Simulator.engine.events;

import java.util.List;

/**
 * MODEL: TruckSchedule
 *
 * Represents a single truck schedule entry loaded from params.json.
 * Each instance covers one sorting direction and defines when trucks
 * depart on which days of the week.
 *
 * Example JSON entry:
 * {
 *   "sortingDirection": "dir-1",
 *   "pullTimes": ["10:00", "15:00", "21:00"],
 *   "weekdays": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"]
 * }
 */
public class TruckSchedule {

    /**
     * The sorting direction this schedule applies to.
     * Must match the {@code sortingDirection} field on {@link Shipment}
     * so the simulation knows which packed shipments to load onto which truck.
     */
    public final String sortingDirection;

    /**
     * Departure times for this direction, expressed in "HH:mm" format.
     * Each entry represents one truck pull on every active weekday.
     * Example: ["10:00", "15:00", "21:00"] means three trucks depart per day.
     */
    public final List<String> pullTimes;

    /**
     * Days of the week on which this schedule is active.
     * Values are full English day names as returned by
     * {@code DayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)},
     * e.g. "Monday", "Tuesday".
     * Trucks do not run on days that are absent from this list.
     */
    public final List<String> weekdays;

    /**
     * Constructs a TruckSchedule with all required fields.
     *
     * @param sortingDirection the direction identifier matching shipment routing
     * @param pullTimes        list of "HH:mm" departure times per active day
     * @param weekdays         list of day names on which this schedule is active
     */
    public TruckSchedule(String sortingDirection, List<String> pullTimes, List<String> weekdays) {
        this.sortingDirection = sortingDirection;
        this.pullTimes        = pullTimes;
        this.weekdays         = weekdays;
    }

    @Override
    public String toString() {
        return "TruckSchedule{dir=" + sortingDirection
                + ", pullTimes=" + pullTimes
                + ", weekdays=" + weekdays + "}";
    }
}