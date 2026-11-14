package dev.myriadclient.testing;

/** One flag Grim raised against the player: the check's name ("Simulation") and Grim's details (".0023 /gl 12"). */
public record GrimFlag(String check, String details) {
	@Override
	public String toString() {
		return check + (details.isEmpty() ? "" : " " + details);
	}
}
