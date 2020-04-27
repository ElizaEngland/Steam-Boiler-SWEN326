package steam.boiler.core;

//import org.eclipse.jdt.annotation.NonNull;
//import org.eclipse.jdt.annotation.Nullable;

import steam.boiler.model.PhysicalUnits;
import steam.boiler.model.PumpControllerModels;
import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.MemoryAnnotations;
import steam.boiler.util.SteamBoilerCharacteristics;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings({"checkstyle:SummaryJavadoc", "checkstyle:Indentation"})
public class MySteamBoilerController implements SteamBoilerController {

    /**
     * Captures the various modes in which the controller can operate
     *
     * @author David J. Pearce
     *
     */
    @SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation"})
    private enum State {
        WAITING, READY, NORMAL, DEGRADED, RESCUE, EMERGENCY_STOP
    }

    /**
     * Records the configuration characteristics for the given boiler problem.
     */
    @SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation"})
    private final SteamBoilerCharacteristics configuration;

    /**
     * Identifies the current mode in which the controller is operating.
     */
    @SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation"})
    private State mode = State.WAITING;
    private State prevRescueMode = State.WAITING;
    private State prevDegradedMode = State.WAITING;
    private Mailbox outgoing;
    private Mailbox incoming;
    private Message levelMessage;
    private Message steamMessage;
    private Message[] pumpStateMessages;
    private Message[] pumpControlStateMessages;
    private boolean openValve =false;
    private double waterLevel;
    private double rescueWaterEstimate =0;
    private double steamLevel;
    private int brokenPumpNo =-1;



    private List<Message> messages = new ArrayList<>();
    private List<Boolean> onOffPumps = new ArrayList<>();
    private ArrayList<Double> middlePoints = new ArrayList<>();
    private int getMessages=0;

    /**
     * Construct a steam boiler controller for a given set of characteristics.
     *
     * @param configuration The boiler characteristics to be used.
     */
    @SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation"})
    public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
        this.configuration = configuration;
        messageInitialisation();
        pumpListInitialisation();
    }

    @MemoryAnnotations.Initialisation
    public void messageInitialisation(){
        for(int i =0; i<100;i++){
            Message m = new Message(MessageKind.VALVE);
            messages.add(m);
        }
    }
    @MemoryAnnotations.Initialisation
    public void pumpListInitialisation(){
        for(int i =0; i<this.configuration.getNumberOfPumps();i++){
            onOffPumps.add(false);
        }
        for(int i=0; i< configuration.getNumberOfPumps();i++){
            middlePoints.add(null);
        }
    }

    /**
     * This message is displayed in the simulation window, and enables a limited
     * form of debug output. The content of the message has no material effect on
     * the system, and can be whatever is desired. In principle, however, it should
     * display a useful message indicating the current state of the controller.
     *
     * @return
     */
    @SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation"})
    @Override
    public String getStatusMessage() {
        return mode.toString();
    }

    /**
     * Process a clock signal which occurs every 5 seconds. This requires reading
     * the set of incoming messages from the physical units and producing a set of
     * output messages which are sent back to them.
     *
     * @param incoming The set of incoming messages from the physical units.
     * @param outgoing Messages generated during the execution of this method should
     *                 be written here.
     */
    @Override
    public void clock(Mailbox incoming, Mailbox outgoing) {
        this.getMessages=0;
        this.incoming=incoming;
        this.outgoing =outgoing;
        // Extract expected messages
        this.levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
        this.steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
        this.pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
        this.pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b, incoming);
        //
        if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages)) {
            // Level and steam messages required, so emergency stop.
            this.mode = State.EMERGENCY_STOP;
        }

        // FIXME: this is where the main implementation stems from

        if(this.mode == State.WAITING) {
            outgoing.send(new Message(MessageKind.MODE_m,Mailbox.Mode.INITIALISATION));
            if(incoming.contains(new Message(MessageKind.STEAM_BOILER_WAITING))){
                if(this.steamMessage.getDoubleParameter()!=0) { // steam measuring device is defective
                    this.mode = State.EMERGENCY_STOP;
                    this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
                    return;
                }
                if(waterLevelFailure()) {
                    this.outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
                    this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
                    this.mode = State.EMERGENCY_STOP;
                    return;
                }
                System.out.println("READY");
                this.mode=State.READY;
                this.waterLevel = this.levelMessage.getDoubleParameter();
                double level = levelMessage.getDoubleParameter();
                if(level > configuration.getMinimalNormalLevel() && level < configuration.getMaximalNormalLevel()) {
                    this.outgoing.send(new Message(MessageKind.PROGRAM_READY));
                }
            }
        }
        else if(this.mode == State.READY) {
            System.out.println("Initializing...");
            if(this.incoming.contains(new Message(MessageKind.PHYSICAL_UNITS_READY))) {
                System.out.println("Physical units are ready. Entering normal mode....");
                this.mode = State.NORMAL;
                this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
            }else{
                initializationMode();
            }
        }
        else if(this.mode == State.NORMAL) {
            normalMode();
        }
        else if(this.mode == State.DEGRADED) {
            degradedMode();
        }
        else if(this.mode == State.RESCUE) {
            rescueMode();
        }
        else if(this.mode == State.EMERGENCY_STOP) {
            emergencyStopMode();
        }
        else {
            System.out.println("Error with state");
        }

        // NOTE: this is an example message send to illustrate the syntax

    }

    /*
     *The start mode for the pump
     */
    public void initializationMode() {
        System.out.println("===INITIALISATION===");
        int noOfPumpsOn;
        if(this.steamMessage.getDoubleParameter()!=0) { // steam measuring device is defective
            System.out.println("Steam Defective");
            this.mode = State.EMERGENCY_STOP;
            this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            return;
        }

        // check for water level detection failure
        if(waterLevelFailure()) {
            System.out.println("Water level detection failure");
            this.outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
            this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            this.mode = State.EMERGENCY_STOP;
            return;
        }

        this.waterLevel = this.levelMessage.getDoubleParameter();
        this.steamLevel = this.steamMessage.getDoubleParameter();

        //checks if water level is ready to go to normal
        if(this.levelMessage.getDoubleParameter() > configuration.getMinimalNormalLevel() && this.levelMessage.getDoubleParameter() < 	configuration.getMaximalNormalLevel()) {
            System.out.println("Move to Normal");
            turnOnPumps(-1);
            this.outgoing.send(new Message(MessageKind.PROGRAM_READY));
            return;
        }
        if(this.levelMessage.getDoubleParameter() > configuration.getMaximalNormalLevel()) { // empty boiler of water
            System.out.println("Empty");
            this.outgoing.send(new Message(MessageKind.VALVE));
            this.openValve = true;
        }
        else if(this.levelMessage.getDoubleParameter() < configuration.getMinimalNormalLevel()) { // fill
            System.out.println("Fill");
            if(this.openValve){ // if valve is open, shuts valve
                this.outgoing.send(new Message(MessageKind.VALVE));
                this.openValve = false;
            }
            noOfPumpsOn = estimatePumps(this.steamMessage.getDoubleParameter(), this.levelMessage.getDoubleParameter());
            turnOnPumps(noOfPumpsOn);
        }

    }


    public void normalMode() {
        this.brokenPumpNo=-1;
        System.out.println("===NORMAL===");
        System.out.println("water level: " + this.waterLevel+ " water message: "+ this.levelMessage.getDoubleParameter());

        if(steamFailure()){  // if steam failure go to degraded mode
            System.out.println("Steam failure. Going to degraded mode...");
            this.mode = State.DEGRADED;
            outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
            outgoing.send(messages.get(getMessages++).set(MessageKind.STEAM_FAILURE_DETECTION));
            this.waterLevel = this.levelMessage.getDoubleParameter();
            degradedMode();
            return;
        }

        if(waterLevelFailure() || this.levelMessage.getDoubleParameter()==0) { // check for water-level detection failure
            System.out.println("Water Level Failure. Going to rescue mode...");
            outgoing.send(messages.get(getMessages++).set(MessageKind.LEVEL_FAILURE_DETECTION));
            outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
            this.mode = State.RESCUE;
            this.prevRescueMode = State.NORMAL;
            this.steamLevel = this.steamMessage.getDoubleParameter();
            rescueMode();
            return;
        }
        if(nearMaxMin() || overMax()){ // checks if water is near or over the max
            System.out.println("Going to emergency mode ...");
            outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            this.mode = State.EMERGENCY_STOP;
            emergencyStopMode();
            return;
        }
        int no = pumpFailure();
        if(no!= -1){ // check for any pump failure
            System.out.println("Pump failure. Going to degraded mode...");
            this.brokenPumpNo=no;
            this.mode = State.DEGRADED;
            this.prevDegradedMode=State.NORMAL;
            outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
            outgoing.send(messages.get(getMessages++).set(MessageKind.PUMP_FAILURE_DETECTION_n,no));
            degradedMode();
            return;
        }
        no=pumpControllerFailure();
        if(no !=-1){ // check for any controller failure
            System.out.println("Pump controller failure. Going to degraded mode...");
            this.mode = State.DEGRADED;
            this.prevDegradedMode=State.NORMAL;
            outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
            outgoing.send(messages.get(getMessages++).set(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, no));
            degradedMode();
            return;
        }

        //all error messages checked. Can run normal mode as per usual.

        outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
        this.waterLevel = this.levelMessage.getDoubleParameter();
        this.steamLevel = this.steamMessage.getDoubleParameter();
        int noOfPumps = estimatePumps(this.steamMessage.getDoubleParameter(), this.levelMessage.getDoubleParameter());
        turnOnPumps(noOfPumps); // pump water in

        if(this.levelMessage.getDoubleParameter() < configuration.getMinimalNormalLevel()) { // if it drops below min level
            noOfPumps = estimatePumps(this.steamMessage.getDoubleParameter(), this.levelMessage.getDoubleParameter());
            turnOnPumps(noOfPumps);
        }
        if(this.levelMessage.getDoubleParameter() > configuration.getMaximalNormalLevel()) { // if it goes above max normal level
            noOfPumps = estimatePumps(this.steamMessage.getDoubleParameter(), this.levelMessage.getDoubleParameter());
            turnOnPumps(noOfPumps);
        }


    }


    public void degradedMode() {
        System.out.println("===DEGRADED===");

        //if failure of water-level measuring unit got to rescueMode()
        if(waterLevelFailure()) {
            outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
            this.mode = State.RESCUE;
            this.prevRescueMode = State.DEGRADED;
            rescueMode();
            return;
        }
        // if water level risks reaching M1 or M2 go to emergencyStopMode()
        if(nearMaxMin()){
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            this.mode = State.EMERGENCY_STOP;
            emergencyStopMode();
            return;
        }

        for(int i =0; i < incoming.size(); i++){ //check for fixed messages
            Message msg = incoming.read(i);
            if(msg.getKind().equals(MessageKind.PUMP_REPAIRED_n)){
                int pumpNo = msg.getIntegerParameter();
                outgoing.send(messages.get(getMessages++).set(MessageKind.PUMP_REPAIRED_ACKNOWLEDGEMENT_n,pumpNo));
                this.mode=this.prevDegradedMode;
            }
            if(msg.getKind().equals(MessageKind.PUMP_CONTROL_REPAIRED_n)){
                int pumpNo = msg.getIntegerParameter();
                outgoing.send(messages.get(getMessages++).set(MessageKind.PUMP_CONTROL_REPAIRED_ACKNOWLEDGEMENT_n,pumpNo));
                this.mode=this.prevDegradedMode;

            }
            if(msg.getKind().equals(MessageKind.STEAM_REPAIRED)){
                outgoing.send(messages.get(getMessages++).set(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT));
                this.mode=this.prevDegradedMode;
            }
        }

        if (this.mode.equals(State.NORMAL)) {
            brokenPumpNo=-1;
            outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
            return;
        } else if(this.mode.equals(State.READY)) {
            brokenPumpNo=-1;
            outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
            return;
        }else {
            this.waterLevel = this.levelMessage.getDoubleParameter();
            outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
            this.mode = State.DEGRADED;
            int noOfPumps = estimatePumps(this.steamLevel, this.waterLevel);
            turnOnPumps(noOfPumps);
        }

        //if transmissionFailure go to emergencyStopMode()
    }


    public void rescueMode() {
        System.out.println("===RESCUE===");
        // if water level risks reaching M1 or M2 go to emergencyStopMode()
        if(nearMaxRescue() || waterLevel <=0){
            outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            this.mode = State.EMERGENCY_STOP;
            emergencyStopMode();
        }
/*
        for(int i = 0; i < incoming.size(); i++) {
            Message message = incoming.read(i);
            if(message.getKind().equals(MessageKind.LEVEL_REPAIRED)){
                this.mode = this.prevRescueMode;
                if (this.mode.equals(State.NORMAL)) {
                    outgoing.send(messages.get(getMessages++).set(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
                    outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
                    return;
                } else {
                    outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
                    return;
                }
            }
        }*/

        if(extractOnlyMatch(MessageKind.LEVEL_REPAIRED,incoming)!=null){
            outgoing.send(messages.get(getMessages++).set(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
            System.out.println(this.prevRescueMode);
            this.mode = this.prevRescueMode;
            if (this.mode.equals(State.NORMAL)) {
                outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
                this.waterLevel=this.levelMessage.getDoubleParameter();
                return;
            } else {
                outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
                this.waterLevel=this.levelMessage.getDoubleParameter();
                System.out.println(this.waterLevel);
                return;
            }
        }
        else{
            outgoing.send(messages.get(getMessages++).set(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
            int noOfPumps = estimatePumps(this.steamMessage.getDoubleParameter(), this.waterLevel);
            this.waterLevel=this.rescueWaterEstimate;
            turnOnPumps(noOfPumps);

        }
        //if transmissionFailure go to emergencyStopMode()

    }


    public void emergencyStopMode() {
        System.out.println("EMERGENCY-MODE");
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
        this.mode = State.EMERGENCY_STOP;
    }


     /**
     * Determine how many pumps to turn on
     */
    public int estimatePumps(double steam, double water){
        if(this.levelMessage.getDoubleParameter()>configuration.getMaximalNormalLevel()){return -1; }
        double midPoint = ((configuration.getMaximalNormalLevel()-configuration.getMinimalNormalLevel())/2)+configuration.getMinimalNormalLevel();
        double l =  water;
        double s = steam;
        double w = configuration.getMaximualSteamRate();
        double c=0;
        double n=0;
        for(int pumpNo = 0; pumpNo < configuration.getNumberOfPumps(); pumpNo++ ) {
            n=pumpNo +1;
            c = configuration.getPumpCapacity(pumpNo);
            double lmax = l + (5*c*n) - (5*s);
            double lmin = l + (5*c*n) - (5*w);
            double middlePoint = ((lmax-lmin)/2)+lmin;
            middlePoints.set(pumpNo, middlePoint);
        }
        double closestDistance = 10000;
        int pumpNo=5;
        for(int i = 0; i <middlePoints.size();i++){
            double m = middlePoints.get(i);
            double distance = Math.abs(midPoint-m);
            if(distance<closestDistance){
                closestDistance=distance;
                pumpNo=i;
                this.rescueWaterEstimate = middlePoints.get(i);
            }
        }
        System.out.println("NO OF PUMPS TURNING ON:" + (pumpNo+1));
        return pumpNo;
    }

    /**
     * Turns on number of pumps, turns off the rest of the pipes
     * @param no no of pumps to turn on
     */
    public void turnOnPumps(int no){
        if(this.brokenPumpNo>-1){
            if(no==configuration.getNumberOfPumps()){no--;}
            System.out.println("BROKEN:CLOSED");
            outgoing.send(messages.get(getMessages++).set(MessageKind.CLOSE_PUMP_n, brokenPumpNo));
            System.out.println(brokenPumpNo);
            onOffPumps.set(brokenPumpNo,false);
        }

        int count = no;
        for(int i = 0; i<configuration.getNumberOfPumps();i++){
            if(count>=0 && i !=brokenPumpNo){
                System.out.println("OPEN");
                outgoing.send(messages.get(getMessages++).set(MessageKind.OPEN_PUMP_n, i));
                onOffPumps.set(i,true);
                count--;
            }
            else if ( i !=brokenPumpNo){
                System.out.println("CLOSED");
                outgoing.send(messages.get(getMessages++).set(MessageKind.CLOSE_PUMP_n, i));
                onOffPumps.set(i,false);
            }

            if(i==configuration.getNumberOfPumps()){
                System.out.println("Done");
                return;}
        }

    }

    /**
     * Check to see if the water level measuring unit has failed or not
     * @return true if failed, false if not
     */
    public boolean waterLevelFailure(){
        if(this.levelMessage.getDoubleParameter()<0){return true;}
        else if(this.levelMessage.getDoubleParameter() >= this.configuration.getCapacity()  ){ return true;}
        else if((this.mode!=State.READY && this.mode!=State.WAITING) && (this.levelMessage.getDoubleParameter() > (this.waterLevel*2))){return true;}
        else if((this.mode!=State.READY && this.mode!=State.WAITING) && (this.levelMessage.getDoubleParameter() < (this.waterLevel-(this.waterLevel/2)))){return true;}
        else{
            return false;
        }
    }

    /**
     * Sees if any of the pumps are failing
     * @return true if a pump has failed, false if no pumps have failed
     */
    public int pumpFailure(){
        if(onOffPumps.size()>0) {
            for (int i = 0; i < this.pumpStateMessages.length; i++) {
               // System.out.println(i);
                System.out.println(this.pumpStateMessages[i].getBooleanParameter() + " : " + this.onOffPumps.get(i));
                if (this.pumpStateMessages[i].getBooleanParameter() != this.onOffPumps.get(i)) { brokenPumpNo =i; return i; }
                if (i != this.pumpStateMessages.length && (this.onOffPumps.get(i) == false && this.onOffPumps.get(i++) == true)) {
                    brokenPumpNo = i;
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Checks if any pump controllers are failing
     * @return no of controller that is broken
     */
    public int pumpControllerFailure(){
        if(onOffPumps.size()>0) {
            for (int i = 0; i < this.pumpControlStateMessages.length; i++) {
                //System.out.println(this.pumpControlStateMessages[i].getBooleanParameter() + " " + this.onOffPumps.get(i));
                if(this.pumpControlStateMessages[i].getBooleanParameter() != this.onOffPumps.get(i)){return i;}
            }
        }
        return -1;
    }

    /**
     * Check to see if water level is near either of the limits.
     * @return true if near a limit, false if not
     */
    public boolean nearMaxMin(){
        double waterLevel = this.levelMessage.getDoubleParameter();
        double no = (configuration.getMaximalLimitLevel() - configuration.getMaximalNormalLevel())/4;
        if(waterLevel> configuration.getMaximalLimitLevel() || waterLevel > configuration.getMaximalLimitLevel()-no){  System.out.println("NEar max"); return true;}
        else if(waterLevel < configuration.getMinimalLimitLevel() || waterLevel < configuration.getMinimalLimitLevel() + no){ System.out.println("WNear min");return true;}
        return false;
    }

    /**
     * Check to see if water is over maximum water level
     * @return true if over, false if not
     */
    public boolean overMax(){
        if(this.levelMessage.getDoubleParameter()>this.configuration.getMaximalLimitLevel()){return true;}
        return false;
    }

    /**
     * Check to see if the steam level measuring unit is failing or not
     * @return true if failing,false if not
     */
    public boolean steamFailure(){
        double steam = this.steamMessage.getDoubleParameter();
        if(steam<0){ return true; }
        if(steam>configuration.getMaximualSteamRate()){return true;}
        return false;
    }

    /**
     * Check to see if water level is near either of the limits.
     * @return true if near a limit, false if not
     */
    public boolean nearMaxRescue(){
        double waterLevel = this.waterLevel;
        double no = (configuration.getMaximalLimitLevel() - configuration.getMaximalNormalLevel())/2;
        if(waterLevel > configuration.getMaximalLimitLevel() || waterLevel > configuration.getMaximalLimitLevel()-no){
            System.out.println("max");return true;}
        else if(waterLevel < configuration.getMinimalLimitLevel() || waterLevel < configuration.getMinimalLimitLevel() + no){
            System.out.println("Min");return true;}
        return false;
    }

    /**
     * Check whether there was a transmission failure. This is indicated in several
     * ways. Firstly, when one of the required messages is missing. Secondly, when
     * the values returned in the messages are nonsensical.
     *
     * @param levelMessage      Extracted LEVEL_v message.
     * @param steamMessage      Extracted STEAM_v message.
     * @param pumpStates        Extracted PUMP_STATE_n_b messages.
     * @param pumpControlStates Extracted PUMP_CONTROL_STATE_n_b messages.
     * @return
     */
    @SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation", "checkstyle:LineLength", "checkstyle:WhitespaceAround"})
    private boolean transmissionFailure(Message levelMessage, Message steamMessage, Message[] pumpStates,
                                        Message[] pumpControlStates) {
        // Check level readings
        if (levelMessage == null) {
            // Nonsense or missing level reading
            return true;
        } else if (steamMessage == null) {
            // Nonsense or missing steam reading
            return true;
        } else if (pumpStates.length != configuration.getNumberOfPumps()) {
            // Nonsense pump state readings
            return true;
        } else if (pumpControlStates.length != configuration.getNumberOfPumps()) {
            // Nonsense pump control state readings
            return true;
        }
        // Done
        return false;
    }

    /**
     * Find and extract a message of a given kind in a mailbox. This must the only
     * match in the mailbox, else <code>null</code> is returned.
     *
     * @param kind     The kind of message to look for.
     * @param incoming The mailbox to search through.
     * @return The matching message, or <code>null</code> if there was not exactly
     *         one match.
     */
    @SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation", "checkstyle:LineLength"})
    private static Message extractOnlyMatch(MessageKind kind, Mailbox incoming) {
        Message match = null;
        for (int i = 0; i != incoming.size(); ++i) {
            Message ith = incoming.read(i);
            if (ith.getKind() == kind) {
                if (match == null) {
                    match = ith;
                } else {
                    // This indicates that we matched more than one message of the given kind.
                    return null;
                }
            }
        }
        return match;
    }

    /**
     * Find and extract all messages of a given kind.
     *
     * @param kind     The kind of message to look for.
     * @param incoming The mailbox to search through.
     * @return The array of matches, which can empty if there were none.
     */
    @SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation"})
    private static Message[] extractAllMatches(MessageKind kind, Mailbox incoming) {
        int count = 0;
        // Count the number of matches
        for (int i = 0; i != incoming.size(); ++i) {
            Message ith = incoming.read(i);
            if (ith.getKind() == kind) {
                count = count + 1;
            }
        }
        // Now, construct resulting array
        Message[] matches = new Message[count];
        int index = 0;
        for (int i = 0; i != incoming.size(); ++i) {
            Message ith = incoming.read(i);
            if (ith.getKind() == kind) {
                matches[index++] = ith;
            }
        }
        return matches;
    }
}
