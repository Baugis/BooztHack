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

        System.out.println(prettyGson.toJson(shipments));
        System.out.println(prettyGson.toJson(bins));
        System.out.println(prettyGson.toJson(grids));

        // ########################################### router  test ############################
        String routerPath = "Data\\router\\router-windows-amd64.exe"; 
        try {
            System.out.println("Starting Router Process...");
            
            ProcessBuilder processBuilder = new ProcessBuilder(routerPath);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            // 3. Prepare a tiny piece of dummy JSON based on the documentation
            // This is what we will send to the router's stdin
            String dummyStateJson = "{"
                    + "\"state\": {"
                    + "\"now\": \"2026-03-13T10:00:00Z\","
                    + "\"shipments_backlog\": [],"
                    + "\"stock_bins\": [],"
                    + "\"truck_arrival_schedules\": { \"schedules\": [] },"
                    + "\"grids\": []"
                    + "}"
                    + "}";

            // 4. Send the JSON to the Router
            System.out.println("Sending data to Router...");
            OutputStream stdin = process.getOutputStream();
            stdin.write(dummyStateJson.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            stdin.close(); // IMPORTANT: Closing tells the router we are done sending data

            // 5. Read the response from the Router's stdout
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

            // 7. Make sure the process closed cleanly
            int exitCode = process.waitFor();
            System.out.println("Router exited with code: " + exitCode);

        } catch (Exception e) {
            System.err.println("Something went wrong:");
            e.printStackTrace();
        }
    
    }
}