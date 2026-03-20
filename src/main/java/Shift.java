import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.ArrayList;


public class Shift {

    @SerializedName("start")
    public String startAt;

    @SerializedName("end")
    public String endAt;

    @SerializedName("portConfig")
    public List<PortConfig> portConfig = new ArrayList<>();

    @SerializedName("breaks")
    public List<BreakWindow> breaks = new ArrayList<>();

    public String getStartAt() { return startAt; }
    public String getEndAt()   { return endAt; }
    public List<BreakWindow> getBreaks() { return breaks != null ? breaks : new ArrayList<>(); }

    /** A scheduled break window within a shift. */
    public static class BreakWindow {
        @SerializedName("start")
        public String startAt;

        @SerializedName("end")
        public String endAt;
    }

    // Inner class for the port configurations
    public static class PortConfig {
        @SerializedName("id")
        public String portId;

        @SerializedName("handlingFlags")
        public List<String> handlingFlags = new ArrayList<>();
    }
}