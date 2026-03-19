import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;


/**
 * Atspindi pakavimo stotį, kurioje daiktai yra imami iš Binų ir pakuojami į Siuntas.
 *
 * Portas priklauso konkrečiam Gridui ir veikia pagal nustatytus Pamainus.
 * Jis turi savo siuntos eilę (max MAX_QUEUE_CAPACITY) ir apdoroja jas po vieną.
 *
 * Suderinamumas tikrinamas per HandlingFlags — Portas priims Siuntą tik jei
 * jo vėliavėlių rinkinys apima visas Siuntos reikalaujamas vėliavėles.
 */
public class Port {

    // -------------------------------------------------------------------------
    // Konstantos
    // -------------------------------------------------------------------------

    /** Maksimalus siunčiamų siuntų kiekis porto vietinėje eilėje. */
    public static final int MAX_QUEUE_CAPACITY = 20;

    // -------------------------------------------------------------------------
    // Būsenos enum
    // -------------------------------------------------------------------------

    /**
     * Gyvavimo ciklo būsenos, kuriose Portas gali būti simuliacijos metu.
     */
    public enum Status {
        /** Portas yra už savo pamainos lango — nepriima ir neapdoroja darbo. */
        CLOSED,

        /** Portas yra pamainos metu, neturi aktyvios siuntos ir yra pasiruošęs
         *  priimti kitą iš savo eilės arba iš grido eilės. */
        IDLE,

        /** Portas aktyviai renka daiktus siuntai. */
        BUSY,

        /**
         * Porto pamaina (arba pertrauka) baigėsi kol jis buvo BUSY.
         * Jis baigs dabartinę siuntą ir tada pereis į CLOSED.
         * Šioje būsenoje naujos siuntos nepriimamos.
         */
        PENDING_CLOSE
    }

    // -------------------------------------------------------------------------
    // Tapatybė ir konfigūracija
    // -------------------------------------------------------------------------

    /** Unikalus porto identifikatorius (pvz. "P1"). */
    private final String portId;

    /** Gridas, kuriam šis portas priklauso. */
    private final String gridId;

    /**
     * Šio porto palaikomų vėliavėlių rinkinys (pvz. "fragile", "priority").
     * Siunta gali būti priskirta čia tik jei visos jos reikalaujamos vėliavėlės
     * yra šiame rinkinyje.
     */
    private final Set<String> handlingFlags;

    // -------------------------------------------------------------------------
    // Vykdymo būsena
    // -------------------------------------------------------------------------

    /** Dabartinė gyvavimo ciklo būsena. Portai pradeda CLOSED kol prasideda pamaina. */
    private Status status;

    /**
     * Tvarkinga siuntų eilė, priskirta šiam portui, laukianti būti surinkta.
     * Talpa apribota iki MAX_QUEUE_CAPACITY.
     */
    private final Queue<Shipment> shipmentQueue;

    /**
     * Siunta, kurią šiuo metu renka/pakuoja šis portas.
     * Null kai portas yra IDLE arba CLOSED.
     */
    private Shipment activeShipment;

    // -------------------------------------------------------------------------
    // Konstruktorius
    // -------------------------------------------------------------------------

    /**
     * Sukuria naują Portą CLOSED būsenoje su tuščia eile.
     *
     * @param portId        unikalus porto identifikatorius
     * @param gridId        grido, kuriam priklauso šis portas, ID
     * @param handlingFlags vėliavėlių rinkinys, kurį šis portas gali apdoroti
     */
    public Port(String portId, String gridId, Set<String> handlingFlags) {
        this.portId = portId;
        this.gridId = gridId;
        this.handlingFlags = Set.copyOf(handlingFlags); // nekintama gynybinė kopija
        this.status = Status.CLOSED;
        this.shipmentQueue = new ArrayDeque<>();
        this.activeShipment = null;
    }

    // -------------------------------------------------------------------------
    // Getter'iai
    // -------------------------------------------------------------------------

    /** @return unikalus porto identifikatorius */
    public String getPortId() { return portId; }

    /** @return grido, kuriam priklauso šis portas, ID */
    public String getGridId() { return gridId; }

    /** @return neredaguojamas šio porto vėliavėlių vaizdas */
    public Set<String> getHandlingFlags() { return handlingFlags; }

    /** @return dabartinė šio porto būsena */
    public Status getStatus() { return status; }

    /** @return šiuo metu apdorojama siunta, arba null jei nėra */
    public Shipment getActiveShipment() { return activeShipment; }

    /** @return siunčiamų siuntų kiekis šiuo metu laukiančių šio porto eilėje */
    public int getQueueSize() { return shipmentQueue.size(); }

    /** @return true jei eilė dar nepasiekė MAX_QUEUE_CAPACITY */
    public boolean hasQueueCapacity() {
        return shipmentQueue.size() < MAX_QUEUE_CAPACITY;
    }

    /**
     * Grąžina tik skaitomą dabartinės eilės tvarkos momentinę nuotrauką.
     * Skirta patikrinimui ir registravimui — neredaguokite grąžinto sąrašo.
     *
     * @return neredaguojamas eilėje esančių siuntų sąrašas, eilės pradžia pirma
     */
    public List<Shipment> getQueueSnapshot() {
        return Collections.unmodifiableList(new LinkedList<>(shipmentQueue));
    }

    // -------------------------------------------------------------------------
    // Suderinamumo patikrinimas
    // -------------------------------------------------------------------------

    /**
     * Patikrina ar šis portas gali apdoroti nurodytą siuntą.
     * Portas yra suderinamas jei jo vėliavėlės yra siuntos reikalaujamų
     * vėliavėlių superset'as.
     *
     * @param shipment siunta kurią testuojame
     * @return true jei šis portas gali apdoroti visas siuntos vėliavėles
     */
    public boolean isCompatibleWith(Shipment shipment) {
        return handlingFlags.containsAll(shipment.items.keySet());
        // PASTABA: pakeiskite shipment.items.keySet() į shipment.getHandlingFlags()
        // kai Shipment turės tinkamą getHandlingFlags() metodą.
    }

    // -------------------------------------------------------------------------
    // Eilės valdymas
    // -------------------------------------------------------------------------

    /**
     * Prideda siuntą į šio porto eilės galą.
     *
     * @param shipment siunta kurią reikia įdėti į eilę
     * @throws IllegalStateException jei eilė jau pilna arba
     *                               portas nepriima darbo (CLOSED / PENDING_CLOSE)
     */
    public void enqueue(Shipment shipment) {
        if (status == Status.CLOSED || status == Status.PENDING_CLOSE) {
            throw new IllegalStateException(
                "Portas " + portId + " nepriima naujų siuntų — būsena: " + status
            );
        }
        if (!hasQueueCapacity()) {
            throw new IllegalStateException(
                "Porto " + portId + " eilė pilna (" + MAX_QUEUE_CAPACITY + " siuntų)"
            );
        }
        shipmentQueue.add(shipment);
    }

    /**
     * Pašalina ir grąžina siuntą eilės priekyje.
     * Grąžina null jei eilė tuščia.
     *
     * @return kita eilėje esanti siunta, arba null jei nėra
     */
    public Shipment dequeue() {
        return shipmentQueue.poll();
    }

    /**
     * Ištuština visas siuntas iš šio porto eilės į naują sąrašą ir išvalo eilę.
     * Naudojama kai portas užsidaro ir turi grąžinti laukiančius darbus į Grido eilę.
     *
     * @return ištuštintų siuntų sąrašas originalia eilės tvarka
     */
    public List<Shipment> drainQueue() {
        List<Shipment> drained = new LinkedList<>(shipmentQueue);
        shipmentQueue.clear();
        return drained;
    }

    // -------------------------------------------------------------------------
    // Aktyvi siunta
    // -------------------------------------------------------------------------

    /**
     * Nustato siuntą, kurią šis portas šiuo metu apdoroja, ir perkelia
     * portą į BUSY būseną.
     *
     * @param shipment siunta kurią pradėti apdoroti (negali būti null)
     * @throws IllegalStateException jei portas nėra IDLE
     */
    public void startProcessing(Shipment shipment) {
        if (status != Status.IDLE) {
            throw new IllegalStateException(
                "Portas " + portId + " negali pradėti apdorojimo — dabartinė būsena: " + status
            );
        }
        this.activeShipment = shipment;
        this.status = Status.BUSY;
    }

    /**
     * Išvalo aktyvią siuntą kai rinkimas/pakavimas baigtas.
     * Jei portas yra BUSY — pereina atgal į IDLE kad galėtų priimti kitą siuntą.
     * Jei yra PENDING_CLOSE — pereina į CLOSED.
     *
     * @throws IllegalStateException jei nėra aktyvios siuntos kurią baigti
     */
    public void finishProcessing() {
        if (activeShipment == null) {
            throw new IllegalStateException(
                "Portas " + portId + " neturi aktyvios siuntos kurią baigti"
            );
        }
        this.activeShipment = null;
        if (status == Status.PENDING_CLOSE) {
            this.status = Status.CLOSED;
        } else {
            this.status = Status.IDLE;
        }
    }

    // -------------------------------------------------------------------------
    // Būsenos perėjimai
    // -------------------------------------------------------------------------

    /**
     * Atidaro portą pamainos pradžioje — pereina iš CLOSED į IDLE.
     *
     * @throws IllegalStateException jei portas šiuo metu nėra CLOSED
     */
    public void open() {
        if (status != Status.CLOSED) {
            throw new IllegalStateException(
                "Portas " + portId + " negali būti atidarytas — dabartinė būsena: " + status
            );
        }
        this.status = Status.IDLE;
    }

    /**
     * Signalizuoja kad pamaina (arba pertrauka) baigėsi.
     * Jei IDLE — iš karto užsidaro.
     * Jei BUSY — pereina į PENDING_CLOSE kad dabartinė siunta galėtų
     * būti baigta prieš uždarant portą.
     *
     * @throws IllegalStateException jei portas jau yra CLOSED arba PENDING_CLOSE
     */
    public void requestClose() {
        if (status == Status.CLOSED || status == Status.PENDING_CLOSE) {
            throw new IllegalStateException(
                "Portas " + portId + " jau užsidaro arba uždarytas — būsena: " + status
            );
        }
        if (status == Status.BUSY) {
            this.status = Status.PENDING_CLOSE;
        } else { // IDLE
            this.status = Status.CLOSED;
        }
    }

    /**
     * Priverstinai uždaro portą nepriklausomai nuo dabartinės būsenos.
     * Skirta avariniam išjungimui arba simuliacijos pabaigos valymui.
     * NEIŠTUŠTINA eilės — kviečiantysis atsakingas už tai jei reikia.
     */
    public void forceClose() {
        this.status = Status.CLOSED;
        this.activeShipment = null;
    }
    
    // -------------------------------------------------------------------------
    // Derinimas
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "Port{id=" + portId +
               ", grid=" + gridId +
               ", status=" + status +
               ", queueSize=" + shipmentQueue.size() +
               ", active=" + (activeShipment != null ? activeShipment.id : "nėra") +
               ", flags=" + handlingFlags + "}";
    }
}