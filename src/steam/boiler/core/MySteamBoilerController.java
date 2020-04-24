package steam.boiler.core;

//import org.eclipse.jdt.annotation.NonNull;
//import org.eclipse.jdt.annotation.Nullable;

import steam.boiler.model.PhysicalUnits;
import steam.boiler.model.PumpControllerModels;
import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
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
    private List<Boolean> onOffPumps;

    /**
	 * Construct a steam boiler controller for a given set of characteristics.
	 *
	 * @param configuration The boiler characteristics to be used.
	 */
	@SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation"})
	public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
		this.configuration = configuration;
		this.onOffPumps = new ArrayList<>();
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
                //outgoing.send(new Message(MessageKind.MODE_m,Mailbox.Mode.INITIALISATION));
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

        double maxNormal = 	configuration.getMaximalNormalLevel();
        double minNormal = 	configuration.getMinimalNormalLevel();
        double waterLevel = this.levelMessage.getDoubleParameter();

        //checks if water level is ready to go to normal
        if(waterLevel > minNormal && waterLevel < maxNormal) {
            System.out.println("Move to Normal");
            turnOnPumps(-1);
            this.outgoing.send(new Message(MessageKind.PROGRAM_READY));
            return;
        }
        if(waterLevel > maxNormal) { // empty boiler of water
            System.out.println("Empty");
            this.outgoing.send(new Message(MessageKind.VALVE));
            this.openValve = true;
        }
		else if(waterLevel < minNormal) { // fill
            System.out.println("Fill");
            if(this.openValve){ // if valve is open, shuts valve
                this.outgoing.send(new Message(MessageKind.VALVE));
                this.openValve = false;
            }
			noOfPumpsOn = estimatePumps();
            turnOnPumps(noOfPumpsOn);
		}

	}


	public void normalMode() {


        if(waterLevelFailure()) { // check for water-level detection failure
            outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
            this.mode = State.RESCUE;
            this.prevRescueMode = State.NORMAL;
            rescueMode();
            return;
        }
        if(nearMax() || overMax()){ // checks if water is near or over the max
            System.out.println("HI high water");
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            this.mode = State.EMERGENCY_STOP;
            emergencyStopMode();
            return;
        }
        if(pumpFailure()!= -1){ // check for any pump failure
            this.mode = State.DEGRADED;
            this.prevDegradedMode=State.NORMAL;
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
            outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, pumpFailure()));
            degradedMode();
            return;
        }
        if(pumpController() !=-1){ // check for any controller failure
            this.mode = State.DEGRADED;
            this.prevDegradedMode=State.NORMAL;
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
            outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, pumpController()));
            degradedMode();
            return;
        }


        int noOfPumps = estimatePumps();
        turnOnPumps(noOfPumps); // pump water in

	    if(this.levelMessage.getDoubleParameter() < configuration.getMinimalNormalLevel()) { // if it drops below min level
	        noOfPumps = estimatePumps();
            turnOnPumps(noOfPumps);
        }
        if(this.levelMessage.getDoubleParameter() > configuration.getMaximalNormalLevel()) { // if it goes above max normal level
            noOfPumps = estimatePumps();
            turnOnPumps(noOfPumps);
        }

        if(steamFailure()){  // if steam failure go to degraded mode
            this.mode = State.DEGRADED;
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
            outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
            degradedMode();
            return;
        }

		//if failure of any other physical units go to degradedMode()


	}


	public void degradedMode() {
	    System.out.println("DEGRADED-MODE");

        //if failure of water-level measuring unit got to rescueMode()
        if(waterLevelFailure()) {
            outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
            this.mode = State.RESCUE;
            this.prevRescueMode = State.DEGRADED;
            rescueMode();
        }
        // if water level risks reaching M1 or M2 go to emergencyStopMode()
        if(nearMax()){
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            this.mode = State.EMERGENCY_STOP;
            emergencyStopMode();
            return;
        }

	    if(pumpFailure()!=-1){ // if pumps are broken still
	        int brokenPump = pumpFailure();
            int noOfPumps = estimatePumps();
            turnOnWithBrokenPump(noOfPumps,brokenPump);
        }
        else if(pumpController()!=-1){
            int brokenPump = pumpFailure();
            int noOfPumps = estimatePumps();
            turnOnWithBrokenPump(noOfPumps,brokenPump);
        }
        else if(steamFailure()){
            int noOfPumps = estimatePumps();
            turnOnPumps(noOfPumps);
	    }
	    else{ // else go back
	        this.mode=this.prevDegradedMode;
            if (this.mode.equals(State.NORMAL)) {
                outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
            } else {
                outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
            }

        }

        //if transmissionFailure go to emergencyStopMode()
	}


	public void rescueMode() {

        System.out.println("RESCUE-MODE");
		// if water level risks reaching M1 or M2 go to emergencyStopMode()
        if(nearMax()){
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            this.mode = State.EMERGENCY_STOP;
            emergencyStopMode();
        }


        Message[] fixedWaterLevel = extractAllMatches(MessageKind.LEVEL_REPAIRED, incoming);
        if(fixedWaterLevel!=null) {
            if (!waterLevelFailure()) {
                this.mode = this.prevRescueMode;
                if (this.mode.equals(State.NORMAL)) {
                    outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
                } else {
                    outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
                }

                outgoing.send(new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
            }
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
	public int estimatePumps(){
	    if(this.levelMessage.getDoubleParameter()>configuration.getMaximalNormalLevel()){return -1; }
		double midPoint = ((configuration.getMaximalNormalLevel()-configuration.getMinimalNormalLevel())/2)+configuration.getMinimalNormalLevel();
		double l = this.levelMessage.getDoubleParameter();
		double s = this.steamMessage.getDoubleParameter();
		double w = configuration.getMaximualSteamRate();
		double c=0;
		double n=0;
		ArrayList<Double> middlePoints = new ArrayList<>();
		for(int pumpNo = 0; pumpNo < configuration.getNumberOfPumps(); pumpNo++ ) {
			n=pumpNo +1;
			c = configuration.getPumpCapacity(pumpNo);
			double lmax = l + (5*c*n) - (5*s);
			double lmin = l + (5*c*n) - (5*w);
			double middlePoint = ((lmax-lmin)/2)+lmin;
			middlePoints.add(middlePoint);
		}
		double closestDistance = 10000;
		int pumpNo=5;
		for(int i = 0; i <middlePoints.size();i++){
			double m = middlePoints.get(i);
			double distance = Math.abs(midPoint-m);
			if(distance<closestDistance){
				closestDistance=distance;
				pumpNo=i;
			}
		}
        System.out.println("NO OF PUMPS TURNING ON:" + (pumpNo+1));
		return pumpNo;
	}

    /**
     * Turn off all of the pumps
     */
	public void turnOffAllPumps(){
	    int count = 0;
	    while(count<4){
            outgoing.send(new Message(MessageKind.CLOSE_PUMP_n,count));
            count++;
        }
    }

    /**
     * Turns on number of pumps, turns off the rest of the pipes
     * @param no no of pumps to turn on
     */
    public void turnOnPumps(int no){

        int count = no;
        this.onOffPumps = new ArrayList<>();
        for(int i = 0; i<configuration.getNumberOfPumps();i++){
            if(i<=count){
               // System.out.println("Open");
                outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
                onOffPumps.add(true);
            }
            else{
                //System.out.println("Closed");
                outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
                onOffPumps.add(false);
            }
        }

    }

    /**
     * Turn on pumps knowing one of the pumps is broken
     * @param no no of pumps we want to turn on
     * @param brokenPump pump number that is broken
     */
    public void turnOnWithBrokenPump(int no, int brokenPump){
        if(no==configuration.getNumberOfPumps()){no--;} // with 1 pump broken, you can never tun on the max no of pumps
        int count = no;
        this.onOffPumps = new ArrayList<>();
        for(int i = 0; i<configuration.getNumberOfPumps();i++){
            if(i==brokenPump){ // if pump is the broken pump, close it and increment i.
                outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
                onOffPumps.add(false);
                i++;
            }
            if(i==configuration.getNumberOfPumps()){return;} // all pumps turned on
            if(i<=count){
                //System.out.println("Open");
                outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
                onOffPumps.add(true);
            }
            else{
                //System.out.println("Closed");
                outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
                onOffPumps.add(false);
            }
        }
    }

    /**
     * Check to see if the water level measuring unit has failed or not
     * @return true if failed, false if not
     */
    public boolean waterLevelFailure(){
	    if(this.levelMessage.getDoubleParameter()<0){return true;}
	    else if(this.levelMessage.getDoubleParameter() >= this.configuration.getCapacity()  ){ return true;}
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
               // System.out.println(this.pumpStateMessages[i].getBooleanParameter() + " " + this.onOffPumps.get(i));
                if(this.pumpStateMessages[i].getBooleanParameter() != this.onOffPumps.get(i)){return i;}
            }
        }
        return -1;
    }

    /**
     * Checks if any pump controllers are failing
     * @return no of controller that is broken
     */
    public int pumpController(){
        if(onOffPumps.size()>0) {
            for (int i = 0; i < this.pumpControlStateMessages.length; i++) {
                System.out.println(this.pumpControlStateMessages[i].getBooleanParameter() + " " + this.onOffPumps.get(i));
                if(this.pumpControlStateMessages[i].getBooleanParameter() != this.onOffPumps.get(i)){return i;}
            }
        }
        return -1;
    }

    /**
     * Check to see if water level is near either of the limits.
     * @return true if near a limit, false if not
     */
    public boolean nearMax(){
	    double waterLevel = this.levelMessage.getDoubleParameter();
	    double no = (configuration.getMaximalLimitLevel() - configuration.getMaximalNormalLevel())/2;
	    if(waterLevel> configuration.getMaximalLimitLevel() || waterLevel > configuration.getMaximalLimitLevel()-no){ return true;}
	    else if(waterLevel < configuration.getMinimalLimitLevel() || waterLevel < configuration.getMinimalLimitLevel() + no){return true;}
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
