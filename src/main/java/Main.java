import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


public class Main {
    public static void main(String[] args){

        DataLoader loader = new DataLoader();
        List<Shipment> shipments = loader.loadShipmentsJson();
        List<Bin> bins = loader.loadBinsJson();
        List<Grid> grids = loader.loadGridsJson();
        Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();

        System.out.println(" SHIPMENTS #################################################");
        System.out.println(prettyGson.toJson(shipments));
        System.out.println(" BINS #################################################");
        System.out.println(prettyGson.toJson(bins));
        System.out.println(" GRIDS #################################################");
        System.out.println(prettyGson.toJson(grids));

        // ########################################### router  test ############################
        String routerPath = "Data\\router\\router-windows-amd64.exe"; 

        try {
            System.out.println("Starting Router Process...");
    
            ProcessBuilder processBuilder = new ProcessBuilder(routerPath);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // 1. GENERATE THE LIVE JSON
            // We get the date of the first shipment to use as our "now" time
            String currentSimTime = shipments.get(0).shipmentDate; 

            RouterStateConverter converter = new RouterStateConverter();
            String liveStateJson = converter.generateRouterJson(shipments, bins, grids, currentSimTime);

            // 2. SEND THE JSON TO THE ROUTER
            System.out.println("Sending data to Router...");

            OutputStream stdin = process.getOutputStream(); 

            stdin.write(liveStateJson.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            stdin.close(); 

            // 3. READ THE RESPONSE
            System.out.println("Waiting for response...");
            BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = stdout.readLine()) != null) {
                response.append(line).append("\n");
            }
        
            System.out.println("\n--- ROUTER RESPONSE ---");
            System.out.println(response.toString());
            System.out.println("-----------------------");
        
            int exitCode = process.waitFor();
            System.out.println("Router exited with code: " + exitCode);

        } catch (Exception e) {
                System.err.println("Something went wrong:");
                e.printStackTrace();
            }
    
    }
}