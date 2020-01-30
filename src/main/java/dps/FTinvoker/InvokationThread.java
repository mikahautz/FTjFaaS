package dps.FTinvoker;
import com.amazonaws.regions.Regions;

import dps.FTinvoker.database.SQLLiteDatabase;
import dps.FTinvoker.exception.CancelInvokeException;
import dps.FTinvoker.exception.InvalidResourceException;
import dps.FTinvoker.function.Function;
import dps.invoker.LambdaInvoker;
import dps.invoker.OpenWhiskInvoker;

/**
 * Will invoke a single FaaS function on the correct provider
 * Can be canceled using by calling the stop() function
 * Will store Exception in "exception" if invocation fails
 * If successful it will store result in "result"
 */
public class InvokationThread implements Runnable {
	private Exception exception;
	private Thread thread;
	private AWSAccount awsAccount = null;
	private IBMAccount ibmAccount = null;
	private OpenWhiskInvoker ibmInvoker = null; // needed to cancel invocation
	private Function function;
	private boolean cancel = false;
	private boolean finished = false;
	private String result = null;

	InvokationThread(AWSAccount awsAccount, IBMAccount ibmAccount, Function function) {
		this.awsAccount = awsAccount;
		this.ibmAccount = ibmAccount;
		this.function = function;
	}

	/**
	 * detects the region of a Function on AWS
	 * throws InvalidResourceException if region can not be detected
	 */
	private Regions detectAWSRegion(String functionURL) throws InvalidResourceException {
		String regionName;
		int searchIndex = functionURL.indexOf("lambda:");
		if (searchIndex != -1) {
			regionName = functionURL.substring(searchIndex + "lambda:".length());
			regionName = regionName.split(":")[0];
			try {
				Regions tmp = Regions.fromName(regionName);
				this.function.setRegion(regionName);
				return tmp;
			} catch (Exception e) {
				throw new InvalidResourceException("Region detection failed");
			}
		} else {
			// Error Parsing arn
			throw new InvalidResourceException("Region detection failed");
		}
	}
	
	/**
	 * detects the region of a Function on IBM and sets Region
	 */
	private void detectAndSetIBMRegion(String functionURL){
		//https://eu-gb.functions.cloud.ibm.com
		String regionName;
		int searchIndex = functionURL.indexOf("://");
		if (searchIndex != -1) {
			regionName = functionURL.substring(searchIndex + "://".length());
			regionName = regionName.split(".functions")[0];
			this.function.setRegion(regionName);
		} else {
			this.function.setRegion(null);
		}
	}

	public synchronized void reset() {
		this.exception = null;
		this.ibmInvoker = null;
		this.cancel = false;
		this.finished = false;
		this.result = null;
	}

	/**
	 * stops this thread
	 */
	public synchronized void stop() {
		// Stop invokation and terminate thread
		this.cancel = true;
		if (this.ibmInvoker != null) {
			ibmInvoker.cancelInvoke(); // to stop IBM Invoke
		}
		this.thread.interrupt(); // to stop AWS Invoke
	}

	public synchronized Thread getThread() {
		return thread;
	}

	public synchronized String getResult() {
		return result;
	}

	public synchronized void setResult(String result) {
		this.result = result;
	}

	@Override
	public void run() {
		this.thread = Thread.currentThread();
		try {
			// Try to invoke function
			this.result = invokeFunctionOnCorrectProvider(this.function);
		} catch (CancelInvokeException e) {
			this.result = null;
			System.out.println("Invocation in " +thread.toString() + "has been canceled.");
			System.out.flush();
			this.exception = e;
			this.finished = true;
			return;
		} catch (Exception e) {
			this.result = null;
			System.out.println("Invocation in "+thread.toString() + "failed!");
			System.out.flush();
			this.exception = e;
			this.finished = true;
			return;
		}
		this.finished = true;
		this.exception = null;
		System.out.println("Invocation in "+thread.toString() + " OK - " + "RESULT: " + result);
		System.out.flush();
	}

	/**
	 * Detects the FaaS provider
	 */
	private static String detectProvider(String functionURL) {
		if (functionURL.contains(".functions.cloud.ibm.com/")) {
			return "ibm";
		}
		if (functionURL.contains("arn:aws:lambda:")) {
			return "aws";
		}
		// Inform Scheduler Provider Detection Failed
		return "fail";
	}

	/**
	 * invokes function on correct provider
	 * throws Exception if invocation failed
	 */
	private String invokeFunctionOnCorrectProvider(Function function) throws Exception {
		switch (detectProvider(function.getUrl())) {
		case "ibm":
			detectAndSetIBMRegion(function.getUrl());
			OpenWhiskInvoker OWinvoker = new OpenWhiskInvoker(this.ibmAccount.getIBMKey());
			this.ibmInvoker = OWinvoker; // Set so invocation can be canceled;
			if (!this.cancel) {
				return OpenWhiskFT.monitoredInvoke(OWinvoker, function);
			} else {
				throw new CancelInvokeException();
			}
		case "aws":
			try{
			 Regions detectedRegion = detectAWSRegion(function.getUrl());
			 function.setRegion(detectedRegion.getName());
			 LambdaInvoker lambdaInvoker = new LambdaInvoker(this.awsAccount.getAwsAccessKey(),
						this.awsAccount.getAwsSecretKey(), detectedRegion);
				if (!this.cancel) {
						return LambdaFT.monitoredInvoke(lambdaInvoker, function);
				} else {
					throw new CancelInvokeException();
				}
			}catch(InvalidResourceException e){
				// Add to DB (Normally after invokation - but will never be reached so we do it here)
				SQLLiteDatabase DB = new SQLLiteDatabase("jdbc:sqlite:Database/FTDatabase.db");
				DB.add(function.getUrl(), function.getType(), "AWS",null, null, null, e.getClass().getName(), "Region detection Failed");
				throw e;
			}
		default:
			// Tell Scheduler we cannot deal with this request;
			SQLLiteDatabase DB = new SQLLiteDatabase("jdbc:sqlite:Database/FTDatabase.db");
			InvalidResourceException exception = new InvalidResourceException("Detection of provider failed");
			DB.add(function.getUrl(), function.getType(), null,null, null, null,exception.getClass().getName(), "Provider detection Failed");
			throw exception;
		}
	}

	public AWSAccount getAwsAccount() {
		return awsAccount;
	}

	public void setAwsAccount(AWSAccount awsAccount) {
		this.awsAccount = awsAccount;
	}

	public IBMAccount getIbmAccount() {
		return ibmAccount;
	}

	public void setIbmAccount(IBMAccount ibmAccount) {
		this.ibmAccount = ibmAccount;
	}

	public synchronized boolean isFinished() {
		return finished;
	}

	public synchronized void setFinished(boolean finished) {
		this.finished = finished;
	}

	public Exception getException() {
		return exception;
	}

	public void setException(Exception exception) {
		this.exception = exception;
	}

}