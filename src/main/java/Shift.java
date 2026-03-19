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

    // Inner class for the port configurations
    public static class PortConfig {
        @SerializedName("id")
        public String portId;
        
        @SerializedName("handlingFlags")
        public List<String> handlingFlags = new ArrayList<>();
    }
}