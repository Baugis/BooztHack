import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import com.google.gson.Gson;


public class Main {
    public static void main(String[] args){
        
        String shipmentPath = "Data\\sample-data\\level1\\shipments.json";       // adresas iki json failo shipments.json
    
        Gson gson = new Gson(); 
        File test = new File(".");
        
        System.out.println("Java is starting its search from: " + test.getAbsolutePath());  // cia tikrinau nuo kur iesko path

        try (FileReader fileReader = new FileReader(shipmentPath)) {                        
            Shipment[] backLog = gson.fromJson(fileReader, Shipment[].class);
            
            if (backLog != null && backLog.length > 0){
                for ( int i = 0; i < backLog.length; i++){
                    int orderNO = i + 1;         
                    System.out.println("\n#######################");
                    System.out.println("Order no: " + orderNO);
                    System.out.println("Order id:" + backLog[i].id);
                    System.out.println("Order items: " + backLog[i].items);
                    System.out.println("Shipment date: " + backLog[i].shipmentDate);
                    System.out.println("#######################\n");
                }
            }      
        } catch (IOException e){
            System.out.println("File not found, path to file:\n" + shipmentPath);
            e.printStackTrace();
        }


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
            System.err.println("Whoops! Something went wrong:");
            e.printStackTrace();
        }
    
    }
}