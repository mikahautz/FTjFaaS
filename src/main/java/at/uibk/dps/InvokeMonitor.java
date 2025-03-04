package at.uibk.dps;

import java.util.Map;

import at.uibk.dps.function.Function;
import jFaaS.invokers.FaaSInvoker;

/**
 * InvokeMonitor interface
 */
public interface InvokeMonitor {

	String monitoredInvoke(FaaSInvoker invoker, Function function) throws Exception;
}
