/**
 * {@link RetentionStrategy} that spins up a vm when needed and shuts it down when finished while respecting the hypervisor settings
 *
 * @author Jeffrey Lyman
 */

package hudson.plugins.libvirt;

import hudson.Extension;

import java.util.logging.Level;
import java.util.logging.Logger;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.slaves.OfflineCause;
import hudson.model.*;
import hudson.model.Messages;
import hudson.model.Queue.*;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import javax.annotation.concurrent.GuardedBy;
import java.util.HashMap;
import java.util.Collections;

public class LibvirtRetentionStrategy extends RetentionStrategy<VirtualMachineSlaveComputer> {
    private static final Logger LOGGER = Logger.getLogger(Demand.class.getName());
    private final long maxIdleTime;

    @DataBoundConstructor
    public LibvirtRetentionStrategy(long maxIdleTime){
        LOGGER.info("Libvirt RetentionStrategy constructed");
        this.maxIdleTime = maxIdleTime;
    }

    @Override
    public boolean isManualLaunchAllowed(VirtualMachineSlaveComputer vm){
        VirtualMachineLauncher vML = (VirtualMachineLauncher) vm.getLauncher();
        return !vML.getHypervisor().isFull();
    }

    @Override
    @GuardedBy("hudson.model.Queue.lock")
    public long check(final VirtualMachineSlaveComputer vm) {
        VirtualMachineLauncher vmL = (VirtualMachineLauncher) vm.getLauncher();
        Hypervisor hypervisor = vmL.getHypervisor();
        if (vm.isOffline() && hasUniqueJob(vm)){
            if (hypervisor.isFull()){
                VirtualMachineSlaveComputer slacker = getIdleVM();
                if (slacker != null) {
                    slacker.disconnect(OfflineCause.create(Messages._CLI_wait_node_offline_shortDescription()));
                    vm.connect(false);
                }
                LOGGER.log(Level.INFO, "CHECKING " + vm.getDisplayName() + " Hyper is Full, everyone is busy");
            } else {
                vm.connect(false);
            }
        }
        return 1;
    }

    private VirtualMachineSlaveComputer getIdleVM(){
        for (Computer c : Jenkins.getInstance().getComputers()){
            if (c instanceof VirtualMachineSlaveComputer && c.isOnline() && c.isIdle()){
                if (System.currentTimeMillis() - c.getIdleStartMilliseconds() > 60 * 1000){
                    VirtualMachineSlaveComputer vmSC = (VirtualMachineSlaveComputer) c;
                    if (!hasUniqueJob(vmSC))
                        return vmSC;
                }
            }
        }
        return null;
    }

    private boolean hasUniqueJob(final SlaveComputer c) {
        Node node = c.getNode();
        if (node == null){
            return false;
        }
        final HashMap<Computer, Integer> availableComputers = new HashMap<Computer, Integer>();
        for (Computer o : Jenkins.getInstance().getComputers()) {
            if ((o.isOnline() || o.isConnecting()) && o.isPartiallyIdle() && o.isAcceptingTasks()) {
                final int idleExecutors = o.countIdle();
                if (idleExecutors>0)
                    availableComputers.put(o, idleExecutors);
            }
        }
        for (Queue.BuildableItem item : Queue.getInstance().getBuildableItems()) {
            // can any of the currently idle executors take this task?
            // assume the answer is no until we can find such an executor
            boolean needExecutor = true;
            for (Computer o : Collections.unmodifiableSet(availableComputers.keySet())) {
                Node otherNode = o.getNode();
                if (otherNode != null && otherNode.canTake(item) == null) {
                    needExecutor = false;
                    final int availableExecutors = availableComputers.remove(o);
                    if (availableExecutors > 1) {
                        availableComputers.put(o, availableExecutors - 1);
                    } else {
                        availableComputers.remove(o);
                    }
                    break;
                }
            }
            if (needExecutor && node.canTake(item) == null) {
                return true;
            }
        }
        return false;
    }

    public long getMaxIdleTime() {
        return maxIdleTime;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName()  {
            return "Kill idle slaves when hypervisor is full and this slave is in demand";
        }
    }
}
