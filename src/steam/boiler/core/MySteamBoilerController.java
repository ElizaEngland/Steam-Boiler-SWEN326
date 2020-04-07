package steam.boiler.core;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.SteamBoilerCharacteristics;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;

public class MySteamBoilerController implements SteamBoilerController {

	/**
	 * Captures the various modes in which the controller can operate
	 *
	 * @author David J. Pearce
	 *
	 */
	private enum State {
		WAITING, READY, NORMAL, DEGRADED, RESCUE, EMERGENCY_STOP
	}

	/**
	 * Records the configuration characteristics for the given boiler problem.
	 */
	private final SteamBoilerCharacteristics configuration;

	/**
	 * Identifies the current mode in which the controller is operating.
	 */
	private State mode = State.WAITING;

	/**
	 * Construct a steam boiler controller for a given set of characteristics.
	 *
	 * @param configuration The boiler characteristics to be used.
	 */
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
		// Extract expected messages
		Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
		Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
		Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
		Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b, incoming);
		//
		if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages)) {
			// Level and steam messages required, so emergency stop.
			this.mode = State.EMERGENCY_STOP;
		}
		turnOnPumps(levelMessage, steamMessage);
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
			initializationMode(incoming,outgoing, steamMessage, levelMessage);
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
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
	}

	/*
	 *The start mode for the pump
	 */
	public void initializationMode(Mailbox incoming, Mailbox outgoing, Message steam, Message water) {
		double steamQuantity = steam.getDoubleParameter();
		if(steamQuantity!=0) { // steam measuring device is defective
			this.mode = State.EMERGENCY_STOP;
			return;
		}

		double maxNormal = 	configuration.getMaximalNormalLevel();
		double minNormal = 	configuration.getMinimalNormalLevel();
		double waterLevel = water.getDoubleParameter();
		System.out.println("WaterLevel: "+ waterLevel);
		System.out.println("MinLevel: "+ minNormal);
		System.out.println("MaxLevel: "+ maxNormal);
		if(waterLevel < minNormal) {
			// fill
			outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 0));
			outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 1));
		}
		else if(waterLevel > maxNormal) {
			// empty
			outgoing.send(new Message(MessageKind.VALVE));
		}


		if(waterLevel > (minNormal+30) && waterLevel < (maxNormal-30)) {
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 1));
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 0));
			outgoing.send(new Message(MessageKind.PROGRAM_READY));
		}

		// check for water level detection failure
		Message[] waterLevelFailure = extractAllMatches(MessageKind.LEVEL_FAILURE_ACKNOWLEDGEMENT, incoming);
		if(waterLevelFailure.length != 0) {
			System.out.println("Water level detection failure acknowledged");
			this.mode = State.EMERGENCY_STOP;
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
		}

		// check to see if ready
		Message[] ready = extractAllMatches(MessageKind.PHYSICAL_UNITS_READY, incoming);
		if(ready.length > 0) {
			System.out.println("Physical units are ready. Entering normal mode....");
			this.mode = State.NORMAL;
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
		}

		//if any physical units defective go to degradedMode()
	}

	public void normalMode() {

		//if failure of water-level measuring unit got to rescueMode()
		//if failure of any other physical units go to degradedMode()
		// if water level risks reaching M1 or M2 go to emergencyStopMode()
		//if transmissionFailure go to emergencyStopMode()

	}

	public void degradedMode() {
		//if failure of water-level measuring unit got to rescueMode()
		// if water level risks reaching M1 or M2 go to emergencyStopMode()
		//if transmissionFailure go to emergencyStopMode()
	}

	public void rescueMode() {
		// if water level risks reaching M1 or M2 go to emergencyStopMode()
		//if transmissionFailure go to emergencyStopMode()

	}

	public void emergencyStopMode() {}

	/**
	 * Determine how many pumps to turn on
	 */
	public void turnOnPumps(Message water,Message steam){
		double l = water.getDoubleParameter();
		double s = steam.getDoubleParameter();
		double w = configuration.getMaximualSteamRate();
		double c;
		double n;
		for(int pumpNo = 0; pumpNo < 4; pumpNo++ ) {
			n=pumpNo +1;
			c = configuration.getPumpCapacity(pumpNo);
			double lmax = l + (5*c*n) - (5*s);
			double lmin = l + (5*c*n) - (5*w);
			System.out.println("MAX: "+ lmax + " MIN: " +lmin);
		}
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
