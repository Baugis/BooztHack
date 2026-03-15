import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import com.google.gson.Gson;


public class Main {
    public static void main(String[] args){
        String path = "BooztHack\\Data\\sample-data\\level1\\shipments.json";
    
        Gson gson = new Gson(); 
        File test = new File(".");
        System.out.println("Java is starting its search from: " + test.getAbsolutePath());  // cia tikrinau nuo kur iesko path

        try (FileReader fileReader = new FileReader(path)) {                                // adresas iki json failo shipments.json
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
            System.out.println("File not found, path to file:\n" + path);
            e.printStackTrace();
        }
    }
}