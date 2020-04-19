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
	private State prevMode = State.WAITING;
	private Mailbox outgoing;
	private Mailbox incoming;
    private Message levelMessage;
    private Message steamMessage;
    private Message[] pumpStateMessages;
    private Message[] pumpControlStateMessages;
    private boolean openValve =false;

    /**
	 * Construct a steam boiler controller for a given set of characteristics.
	 *
	 * @param configuration The boiler characteristics to be used.
	 */
	@SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation"})
	public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
		this.configuration = configuration;
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
	@SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation", "checkstyle:RightCurly", "checkstyle:WhitespaceAround", "checkstyle:LineLength"})
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
			Message[] waiting = extractAllMatches(MessageKind.STEAM_BOILER_WAITING, incoming);
			if(waiting.length==0) {
				System.out.println("Waiting...");
			}
			else {
				System.out.println("Ready...");
				this.mode=State.READY;
			}
		}
		else if(this.mode == State.READY) {
			System.out.println("Initializing...");
			initializationMode(steamMessage, levelMessage);
		}
		else if(this.mode == State.NORMAL) {
			normalMode(steamMessage, levelMessage);
		}
		else if(this.mode == State.DEGRADED) {
			degradedMode(steamMessage, levelMessage);
		}
		else if(this.mode == State.RESCUE) {
			rescueMode(steamMessage, levelMessage);
		}
		else if(this.mode == State.EMERGENCY_STOP) {
			emergencyStopMode(steamMessage, levelMessage);
		}
		else {
			System.out.println("Error with state");
		}

		// NOTE: this is an example message send to illustrate the syntax
		//outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
	}

	/*
	 *The start mode for the pump
	 */
	@SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation", "checkstyle:WhitespaceAround", "checkstyle:LineLength", "checkstyle:RightCurly", "checkstyle:MissingJavadocMethod"})
	public void initializationMode( Message steam, Message water) {
        int noOfPumpsOn;
		double steamQuantity = steam.getDoubleParameter();
		if(steamQuantity!=0) { // steam measuring device is defective
			this.mode = State.EMERGENCY_STOP;
            this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
			return;
		}

        double maxNormal = 	configuration.getMaximalNormalLevel();
        double minNormal = 	configuration.getMinimalNormalLevel();
        double waterLevel = water.getDoubleParameter();

        // check to see if ready
        Message[] ready = extractAllMatches(MessageKind.PHYSICAL_UNITS_READY, incoming);
        if(this.incoming.contains(new Message(MessageKind.PHYSICAL_UNITS_READY))) {
            System.out.println("Physical units are ready. Entering normal mode....");
            this.mode = State.NORMAL;
            this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
            return;
        }

        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));

        if(waterLevel > maxNormal) {
            turnOffAllPumps();
            this.outgoing.send(new Message(MessageKind.VALVE));
            this.openValve = true;
        }

		if(waterLevel < minNormal) {
			// fill
            if(this.openValve){ // if valve is open, shuts valve
                this.outgoing.send(new Message(MessageKind.VALVE));
                this.openValve = false;
            }
            turnOffAllPumps();
			noOfPumpsOn = estimatePumps(water,steam);
			turnOnPumps(noOfPumpsOn);
		}
        //checks if water level is ready to go to normal
        if(waterLevel > (minNormal+30) && waterLevel < (maxNormal-30)) {
            turnOffAllPumps();
            this.outgoing.send(new Message(MessageKind.PROGRAM_READY));
            return;
        }

        // check for water level detection failure
        if(waterLevelFailure(water)) {
            this.outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
            this.outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            this.mode = State.EMERGENCY_STOP;
            rescueMode(steam,water);
            return;
        }


		//if any physical units defective go to degradedMode()
	}

	@SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation"})
	public void normalMode(Message steam, Message water) {
        turnOffAllPumps();

        // if water level risks reaching M1 or M2 go to emergencyStopMode()
        if(nearMax(water)){
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            this.mode = State.EMERGENCY_STOP;
            emergencyStopMode(steam,water);
        }
        //if failure of water-level measuring unit got to rescueMode()
        if(waterLevelFailure(water)) {
            outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
            this.mode = State.RESCUE;
            this.prevMode = State.NORMAL;
            rescueMode(steam,water);
        }


        int noOfPumps = estimatePumps(water, steam);
        turnOnPumps(noOfPumps);

	    if(water.getDoubleParameter() < configuration.getMinimalNormalLevel()) { // if it drops below min level
	        noOfPumps = estimatePumps(water, steam);
            turnOnPumps(noOfPumps);
        }
        if(water.getDoubleParameter() > configuration.getMaximalNormalLevel()) { // if it goes above max normal level
            noOfPumps = estimatePumps(water, steam);
            turnOnPumps(noOfPumps);
        }



		//if failure of any other physical units go to degradedMode()
		// if water level risks reaching M1 or M2 go to emergencyStopMode()
		//if transmissionFailure go to emergencyStopMode()

	}

	@SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation"})
	public void degradedMode( Message steam, Message water) {

		//if failure of water-level measuring unit got to rescueMode()
        if(waterLevelFailure(water)) {
            outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
            this.mode = State.RESCUE;
            this.prevMode = State.DEGRADED;
            rescueMode(steam,water);
        }
        // if water level risks reaching M1 or M2 go to emergencyStopMode()
        if(nearMax(water)){
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            this.mode = State.EMERGENCY_STOP;
            emergencyStopMode(steam,water);
        }

		//if transmissionFailure go to emergencyStopMode()
	}

	@SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation"})
	public void rescueMode(Message steam, Message water) {
		// if water level risks reaching M1 or M2 go to emergencyStopMode()
        if(nearMax(water)){
            outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
            this.mode = State.EMERGENCY_STOP;
            emergencyStopMode(steam,water);
        }


        Message[] fixedWaterLevel = extractAllMatches(MessageKind.LEVEL_REPAIRED, incoming);
        if(fixedWaterLevel!=null) {
            if (!waterLevelFailure(water)) {
                this.mode = this.prevMode;
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

	@SuppressWarnings("checkstyle:FileTabCharacter")
	public void emergencyStopMode(Message steam, Message water) {}

	/**
	 * Determine how many pumps to turn on
	 */
	@SuppressWarnings({"checkstyle:FileTabCharacter", "checkstyle:Indentation", "checkstyle:WhitespaceAround", "checkstyle:ParenPad", "checkstyle:RightCurly"})
	public int estimatePumps(Message water, Message steam){
	    if(water.getDoubleParameter()>configuration.getMaximalNormalLevel()){return 0;}
		double midPoint = ((configuration.getMaximalNormalLevel()-configuration.getMinimalNormalLevel())/2)+configuration.getMinimalNormalLevel();
		double l = water.getDoubleParameter();
		double s = steam.getDoubleParameter();
		double w = configuration.getMaximualSteamRate();
		double c;
		double n;
		ArrayList<Double> middlePoints = new ArrayList<>();
		for(int pumpNo = 0; pumpNo < 4; pumpNo++ ) {
			n=pumpNo +1;
			c = configuration.getPumpCapacity(pumpNo);
			double lmax = l + (5*c*n) - (5*s);
			double lmin = l + (5*c*n) - (5*w);
			double middlePoint = ((lmax-lmin)/2)+lmin;
			System.out.println("MAX: "+ lmax + " MIN: " +lmin + "MIDDLE: "+middlePoint);
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
		System.out.println(pumpNo);
        System.out.println("NO OF PUMPS TURNING ON: "+ pumpNo);
		return pumpNo;
	}

    /**
     * Turn off all of the pumps
     */
	public void turnOffAllPumps(){
	    int count = 0;
	    while(count<4){
	       // physicalUnits.getPump(count).close();
            outgoing.send(new Message(MessageKind.CLOSE_PUMP_n,count));
            count++;
        }
    }

    public void turnOnPumps(int no){
        System.out.println(no);
        while(no!=-1){
            System.out.println("here");
            //physicalUnits.getPump(no).open();
            outgoing.send(new Message(MessageKind.OPEN_PUMP_n, no));
            no--;
        }

        if((configuration.getNumberOfPumps()-no)==3){
            for(int i = 0; i<4;i++) {
                outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
            }
        }
        else if((configuration.getNumberOfPumps()-no)==2){
            for(int i = 0; i<3;i++) {
                outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
            }
        }
        else if((configuration.getNumberOfPumps()-no)==1){
            for(int i = 0; i<2;i++) {
                outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
            }
        }

    }

    public boolean waterLevelFailure(Message water){
	    if( water.getDoubleParameter()<0){return true;}
	    else if(this.configuration.getCapacity() < water.getDoubleParameter()){ return true;}
	    else{return false;}
    }

    public boolean pumpFailure(){

	    for(int i =0; i <this.pumpControlStateMessages.length;i++){
            if (!this.pumpControlStateMessages[i].getBooleanParameter()){return true;}
        }

        return false;
    }

    public boolean nearMax(Message water){
	    double waterLevel = water.getDoubleParameter();
	    double no = (configuration.getMaximalLimitLevel() - configuration.getMaximalNormalLevel())/2;
	    if(waterLevel> configuration.getMaximalLimitLevel() || waterLevel > configuration.getMaximalLimitLevel()-no){ return true;}
	    else if(waterLevel < configuration.getMinimalLimitLevel() || waterLevel < configuration.getMinimalLimitLevel() + no){return true;}
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
