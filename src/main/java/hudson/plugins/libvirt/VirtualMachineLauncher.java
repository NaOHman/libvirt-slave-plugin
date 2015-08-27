/**
 *  Copyright (C) 2010, Byte-Code srl <http://www.byte-code.com>
 *  Copyright (C) 2012  Philipp Bartsch <tastybug@tastybug.com>
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
 * @author Philipp Bartsch <tastybug@tastybug.com>
 */
package hudson.plugins.libvirt;

import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.plugins.libvirt.lib.IDomain;
import hudson.plugins.libvirt.lib.VirtException;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;


public class VirtualMachineLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(VirtualMachineLauncher.class.getName());
    private ComputerLauncher delegate;
    private String hypervisorDescription;
    private String virtualMachineName;
    private String snapshotName;
    private final int WAIT_TIME_MS;
    private final int timesToRetryOnFailure;
    
    @DataBoundConstructor
    public VirtualMachineLauncher(ComputerLauncher delegate, String hypervisorDescription, String virtualMachineName, String snapshotName,
            int waitingTimeSecs, int timesToRetryOnFailure) {
        super();
        this.delegate = delegate;
        this.virtualMachineName = virtualMachineName;
        this.snapshotName = snapshotName;
        this.hypervisorDescription = hypervisorDescription;
        this.WAIT_TIME_MS = waitingTimeSecs*1000;
        this.timesToRetryOnFailure = timesToRetryOnFailure;
    }

    public VirtualMachine getVirtualMachine() throws RuntimeException{
        if (hypervisorDescription != null && virtualMachineName != null) {
            LOGGER.log(Level.FINE, "Grabbing hypervisor...");
            Hypervisor hypervisor = getHypervisor();
            LOGGER.log(Level.FINE, "Hypervisor found, searching for a matching virtual machine for \"" + virtualMachineName + "\"...");
            for (VirtualMachine vm : hypervisor.getVirtualMachines()) {
                if (vm.getName().equals(virtualMachineName)) {
                    return vm;
                }
            }
        }
        LOGGER.log(Level.SEVERE, "Couldn't find vm " + virtualMachineName + " on hypervisor " + hypervisorDescription);
        throw new RuntimeException("Could not find virtual machine on the hypervisor");
    }
    
    public ComputerLauncher getDelegate() {
        return delegate;
    }

    public String getVirtualMachineName() {
        return virtualMachineName;
    }

    @Override
    public boolean isLaunchSupported() {
        return true;
    }

    public Hypervisor getHypervisor() throws RuntimeException {
        if (hypervisorDescription != null && virtualMachineName != null) {
            Hypervisor hypervisor = null;
            for (Cloud cloud : Jenkins.getInstance().clouds) {
                if (cloud instanceof Hypervisor && ((Hypervisor) cloud).getHypervisorDescription().equals(hypervisorDescription)) {
                    hypervisor = (Hypervisor) cloud;
                    return hypervisor;
                }
            }
        }
        LOGGER.log(Level.SEVERE, "Could not find our libvirt cloud instance!");
        throw new RuntimeException("Could not find our libvirt cloud instance!");
    }

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener taskListener) throws IOException, InterruptedException {
    	
    	taskListener.getLogger().println("Virtual machine \"" + virtualMachineName + "\" (slave title \"" + slaveComputer.getDisplayName() + "\") is to be started.");
    	try {
            taskListener.getLogger().println("Connecting to the hypervisor...");
            VirtualMachine virtualMachine = getVirtualMachine(); //throw runtime
            Hypervisor hypervisor = getHypervisor();
            IDomain domain = hypervisor.getDomainByName(virtualMachine.getName()); //virt and runtime exceptions
            if (hypervisor.isFull()){ //Should be checked by the listener but, just to be sure
                taskListener.getLogger().println("Hypervisor " + hypervisorDescription + " is full, can't launch new vms");
                return;
            }
            if (domain != null) {
                if( domain.isNotBlockedAndNotRunning() ) {
                    taskListener.getLogger().println("Starting, waiting for " + WAIT_TIME_MS + "ms to let it fully boot up...");
                    domain.create();
                    Thread.sleep(WAIT_TIME_MS);

                    int attempts = 0;
                    while (true) {
                        attempts++;

                        taskListener.getLogger().println("Connecting slave client.");

                        // This call doesn't seem to actually throw anything, but we'll catch IOException just in case
                        try {
                            delegate.launch(slaveComputer, taskListener);
                        } catch (IOException e) {
                        }

                        if (slaveComputer.isOnline()) {
                            break;
                        } else if (attempts >= timesToRetryOnFailure) {
                            taskListener.getLogger().println("Maximum retries reached. Failed to start slave client.");
                            break;
                        }

                        taskListener.getLogger().println("Not up yet, waiting for " + WAIT_TIME_MS + "ms more (" +
                                                         attempts + "/" + timesToRetryOnFailure + " retries)...");
                        //Make sure a third party didn't destroy or undefine the vm between retry attempts
                        domain = getHypervisor().getDomainByName(virtualMachine.getName());
                        if (domain == null){
                            throw new IOException("Could not find VM \"" + virtualMachine.getName() + "\" aborting");
                        }
                        if (domain.isNotBlockedAndNotRunning()) {
                            taskListener.getLogger().println("Could not create VM \"" + virtualMachine.getName() + "\" trying again");
                            domain.create();
                        }
                        Thread.sleep(WAIT_TIME_MS);
                    }
                } else {
                    taskListener.getLogger().println("Already running, no startup required.");
                    taskListener.getLogger().println("Connecting slave client.");
                    delegate.launch(slaveComputer, taskListener);
                }
            } else {
	            throw new IOException("VM \"" + virtualMachine.getName() + "\" (slave title \"" + slaveComputer.getDisplayName() + "\") not found!");
            }
        } catch (IOException e) {
            taskListener.fatalError(e.getMessage(), e);
            
            LogRecord rec = new LogRecord(Level.SEVERE, "Error while launching {0} on Hypervisor {1}.");
            rec.setParameters(new Object[]{virtualMachineName, hypervisorDescription});
            rec.setThrown(e);
            LOGGER.log(rec);
            throw e;
        } catch (Throwable t) {
        	taskListener.fatalError(t.getMessage(), t);
            
            LogRecord rec = new LogRecord(Level.SEVERE, "Error while launching {0} on Hypervisor {1}.");
            rec.setParameters(new Object[]{virtualMachineName, hypervisorDescription});
            rec.setThrown(t);
            LOGGER.log(rec);
        }
    }

    @Override
    public synchronized void afterDisconnect(SlaveComputer slaveComputer, TaskListener taskListener) {
        delegate.afterDisconnect(slaveComputer, taskListener);
        try {
            getHypervisor().markVMOffline(slaveComputer.getDisplayName(), getVirtualMachineName());
        } catch (VirtException e) {}
    }

    @Override
    public void beforeDisconnect(SlaveComputer slaveComputer, TaskListener taskListener) {
        delegate.beforeDisconnect(slaveComputer, taskListener);
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
    	throw new UnsupportedOperationException();
    }
}
