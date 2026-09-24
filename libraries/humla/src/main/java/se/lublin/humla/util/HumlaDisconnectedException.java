package se.lublin.humla.util;

/**
 * Thrown when the session is requested while the service is not connected.
 */
@SuppressWarnings("serial")
public class HumlaDisconnectedException extends RuntimeException {
    public HumlaDisconnectedException() {
        super("Caller attempted to use the protocol while disconnected.");
    }

    public HumlaDisconnectedException(String reason) {
        super(reason);
    }
}
