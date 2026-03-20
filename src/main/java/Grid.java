import java.util.*;

public class Grid {
    
    private final String gridId;
    //Map leidzia paimti bet koki bina pagal jo ID O(1) laiku
    //paima String ID ir grazina Bin objekta kuriame laikomi daiktai
    private final Map<String, Bin> bins;

    //Saugo pamainu tvarkarasti objekta nuo jo priklauso kada operuos tam tikri Portai ant grido
    private final List<Shift> shifts;

    //gridQueue saugo Shipmentus kurie buvo assigninti i sita grida bet dar nebuvo
    //assigninti i tam tikra porta. Kaip laukimas eileje
    private final Queue<Shipment> gridQueue;

    public Grid(String gridID, List<Shift> shifts){
        this.gridId = gridID; 
        this.shifts = new ArrayList<>(shifts);
        this.bins = new HashMap<>();
        this.gridQueue = new LinkedList<>();
    }

    //BIN MANAGEMENT
    public void addBin(Bin bin) {
        bins.put(bin.getBinId(), bin);
    }

    //sugrazina Bina pagal jo ID
    public Bin getBin(String binId) {
        return bins.get(binId);
    }

    // Returns all bins in this grid.
    // Used by the router when it needs to know what stock is available here.
    // 'unmodifiableCollection' means the caller can read the collection
    // but cannot add/remove bins from it directly - they must go through
    // addBin() so we stay in control of our internal state.
    public Collection<Bin> getAllBins() {
        return Collections.unmodifiableCollection(bins.values());
    }

    // Prideda Shipmenta i eiles gala
    // kviecia jei:
    // 1. jei shipmentas paruostas procesinimui bet joks portas nera laisvas
    // 2. portas uzsidare viduryje shifto pvz pertrauka ir vel enqueuina to porto shipmentus
    public void enqueueShipment(Shipment shipment) {
        gridQueue.add(shipment);
    }

    //Isima shipmenta is gridQueue eiles priekio
    //Kvieciamas kai portas atsilaisvina
    public Shipment dequeueShipment() {
        return gridQueue.poll();
    }

    public boolean hasQueuedShipments() {
        return !gridQueue.isEmpty();
    }

    //Grazina read only queue perziura
    public Queue<Shipment> getGridQueue() {
        // return Collections.unmodifiableQueue(gridQueue);  SITAS NETIKO PASIULE PAKEISTI SITU
        return new LinkedList<>(gridQueue); // Returns a safe copy
    }

    public String getId() {return gridId;}
    public List<Shift> getShifts() {return Collections.unmodifiableList(shifts);}
}
