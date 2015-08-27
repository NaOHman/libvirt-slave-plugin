/**
 *  Copyright (C) 2010, Byte-Code srl <http://www.byte-code.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Date: Mar 04, 2010
 * @author Marco Mornati<mmornati@byte-code.com>
 */

package hudson.plugins.libvirt;

import hudson.model.*;
import hudson.plugins.libvirt.lib.IDomain;
import hudson.plugins.libvirt.lib.VirtException;
import hudson.slaves.OfflineCause;
import hudson.plugins.libvirt.lib.IConnect;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamTaskListener;
import hudson.util.io.ReopenableRotatingFileOutputStream;
import hudson.model.Slave;

import java.util.Map;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.libvirt.Domain;
import org.libvirt.DomainInfo.DomainState;

public class VirtualMachineSlaveComputer extends SlaveComputer {

	private static final Logger logger = Logger.getLogger(VirtualMachineSlaveComputer.class.getName());
			
	private final TaskListener taskListener;
	
    public VirtualMachineSlaveComputer(Slave slave) {
        super(slave);    
        this.taskListener = new StreamTaskListener(new ReopenableRotatingFileOutputStream(getLogFile(),10));
    }

    @Override
    protected void onRemoved(){
        try {
            getHypervisor().markVMOffline(getDisplayName(), getVirtualMachineName());
        } catch (VirtException e) {}
        super.onRemoved();
    }

    @Override
    protected void kill(){ //message the hypervisor but don't do anything else
        try {
            getHypervisor().markVMOffline(getDisplayName(), getVirtualMachineName());
        } catch (VirtException e) {}
        super.kill();
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long duration, Throwable problem){
        //Use to recover from accidental slave shutdown
        //have hypervisor check to make sure the vm is still on
        logger.log(Level.WARNING, "-----> VM task interrupted by " + problem.toString());
        super.taskCompletedWithProblems(executor, task, duration, problem);
    }

	@Override
	public Future<?> disconnect(OfflineCause cause) {
		VirtualMachineSlave slave = (VirtualMachineSlave) getNode();
		Hypervisor hypervisor = getHypervisor();
		String reason = "";
		if (cause != null) {
			reason =  "reason: "+cause+" ("+cause.getClass().getName()+")";
		}
		logger.log(Level.INFO, "Virtual machine \"" + getVirtualMachineName() + "\" (slave \"" + getDisplayName() + "\") is to be shut down." + reason);
		taskListener.getLogger().println("Virtual machine \"" + getVirtualMachineName() + "\" (slave \"" + getDisplayName() + "\") is to be shut down.");
		try {			
            IDomain domain = hypervisor.getDomainByName(getVirtualMachineName());
            if (domain != null) {
            	if (domain.isRunningOrBlocked()) {
            		String snapshotName = slave.getSnapshotName();
                    if (snapshotName != null && snapshotName.length() > 0) {
                    	taskListener.getLogger().println("Reverting to " + snapshotName + " and shutting down.");
                    	domain.revertToSnapshot(domain.snapshotLookupByName(snapshotName));
                    } else {
                    	taskListener.getLogger().println("Shutting down.");

                        System.err.println("method: " + slave.getShutdownMethod());
                        if (slave.getShutdownMethod().equals("suspend")) {
                            domain.suspend();
                        } else if (slave.getShutdownMethod().equals("destroy")) {
                            domain.destroy();
                        } else {
                    	    domain.shutdown();
                        }
                    }
                } else {
                    taskListener.getLogger().println("Already suspended, no shutdown required.");
                }
                hypervisor.markVMOffline(getDisplayName(), getVirtualMachineName());
            } else {
            	// log to slave 
            	taskListener.getLogger().println("\"" + getVirtualMachineName() + "\" not found on Hypervisor, can not shut down!");
            	
            	// log to jenkins
            	LogRecord rec = new LogRecord(Level.WARNING, "Can not shut down {0} on Hypervisor {1}, domain not found!");
                rec.setParameters(new Object[]{getVirtualMachineName(), hypervisor.getHypervisorURI()});
                logger.log(rec);
            }
        } catch (Throwable t) {
        	taskListener.fatalError(t.getMessage(), t);
        	
            LogRecord rec = new LogRecord(Level.SEVERE, "Error while shutting down {0} on Hypervisor {1}.");
            rec.setParameters(new Object[]{slave.getVirtualMachineName(), hypervisor.getHypervisorURI()});
            rec.setThrown(t);
            logger.log(rec);
        }
		return super.disconnect(cause);
	}

    public Hypervisor getHypervisor(){
        VirtualMachineLauncher vmL = (VirtualMachineLauncher) getLauncher();
        return vmL.getHypervisor();
    }

    public String getVirtualMachineName(){
        VirtualMachineLauncher vmL = (VirtualMachineLauncher) getLauncher();
        return vmL.getVirtualMachineName();
    }
}
