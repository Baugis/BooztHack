import java.util.List;

/**
 * MODEL: TruckSchedule
 *
 * Represents a single truck schedule entry from params.json.
 * Each schedule covers one sorting direction and defines:
 *   - which days of the week trucks run
 *   - the pull times (HH:mm) when trucks depart on those days
 *
 * Example JSON:
 * {
 *   "sortingDirection": "dir-1",
 *   "pullTimes": ["10:00", "15:00", "21:00"],
 *   "weekdays": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"]
 * }
 */
public class TruckSchedule {

    /** Matches Shipment.sortingDirection — used to dispatch the right shipments. */
    public final String sortingDirection;

    /**
     * Pull times in "HH:mm" format.
     * Each pull time represents one truck departure for this direction on applicable days.
     */
    public final List<String> pullTimes;

    /**
     * Days of the week this schedule is active.
     * Values match Java's DayOfWeek.getDisplayName() in English, e.g. "Monday".
     */
    public final List<String> weekdays;

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