package dps.FTinvoker.exception;


/**
 * Custom exception which is thrown if a function is canceled on purpose
 * 
 * @author Matteo Battaglin
 *
 */
public class CancelInvokeException extends Exception {
	private static final long serialVersionUID = 1L;

	public CancelInvokeException() {
		super();
	}
}
