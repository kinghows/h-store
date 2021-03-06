package edu.brown.hstore;

import java.util.Iterator;

import org.apache.log4j.Logger;
import org.voltdb.catalog.Procedure;
import org.voltdb.types.SpecExecSchedulerPolicyType;
import org.voltdb.types.SpeculationType;

import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.estimators.EstimatorState;
import edu.brown.hstore.internal.InternalMessage;
import edu.brown.hstore.specexec.AbstractConflictChecker;
import edu.brown.hstore.txns.AbstractTransaction;
import edu.brown.hstore.txns.LocalTransaction;
import edu.brown.interfaces.DebugContext;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.profilers.SpecExecProfiler;
import edu.brown.statistics.FastIntHistogram;
import edu.brown.utils.StringUtil;

/**
 * Special scheduler that can figure out what the next best single-partition
 * to speculatively execute at a partition based on the current distributed transaction 
 * @author pavlo
 */
public class SpecExecScheduler {
    private static final Logger LOG = Logger.getLogger(SpecExecScheduler.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    private final int partitionId;
    private final PartitionLockQueue queue;
    private AbstractConflictChecker checker;
    private SpecExecSchedulerPolicyType policyType;
    private int windowSize = 1;
    
    /** Ignore all LocalTransaction handles **/
    private boolean ignore_all_local = false;
    
    /** Don't reset the iterator if the queue size changes **/
    private boolean ignore_queue_size_change = false;
    
    /** Don't reset the iterator if the current SpeculationType changes */
    private boolean ignore_speculation_type_change = false;
    
    private AbstractTransaction lastDtxn;
    private SpeculationType lastSpecType;
    private Iterator<AbstractTransaction> lastIterator;
    private int lastSize = 0;
    private boolean interrupted = false;
    private Class<? extends InternalMessage> latchMsg;

    private final SpecExecProfiler profilerMap[];
    private boolean profiling = false;
    private AbstractTransaction profilerCurrentTxn;
    private final FastIntHistogram profilerExecuteCounter = new FastIntHistogram(SpeculationType.values().length);
    
    /**
     * Constructor
     * @param catalogContext
     * @param checker TODO
     * @param partitionId
     * @param queue
     */
    public SpecExecScheduler(AbstractConflictChecker checker,
                             int partitionId, PartitionLockQueue queue,
                             SpecExecSchedulerPolicyType schedule_policy, int window_size) {
        assert(schedule_policy != null) : "Unsupported schedule policy parameter passed in";
        
        this.partitionId = partitionId;
        this.queue = queue;
        this.checker = checker;
        this.policyType = schedule_policy;
        this.windowSize = window_size;
        
        this.profiling = HStoreConf.singleton().site.specexec_profiling;
        this.profilerExecuteCounter.setKeepZeroEntries(true);
        this.profilerMap = new SpecExecProfiler[SpeculationType.values().length];
        if (this.profiling) {
            for (int i = 0; i < this.profilerMap.length; i++) {
                this.profilerMap[i] = new SpecExecProfiler();
            } // FOR
        }
    }
    
    /**
     * Replace the ConflictChecker. This should only be used for testing
     * @param checker
     */
    protected void setConflictChecker(AbstractConflictChecker checker) {
        LOG.warn(String.format("Replacing original checker %s with %s",
                 this.checker.getClass().getSimpleName(),
                 checker.getClass().getCanonicalName()));
        this.checker = checker;
    }
    protected void setIgnoreAllLocal(boolean ignore_all_local) {
        this.ignore_all_local = ignore_all_local;
    }
    protected void setIgnoreQueueSizeChange(boolean ignore_queue_changes) {
        this.ignore_queue_size_change = ignore_queue_changes;
    }
    protected void setIgnoreSpeculationTypeChange(boolean ignore_speculation_type_change) {
        this.ignore_speculation_type_change = ignore_speculation_type_change;
    }
    protected void setWindowSize(int window) {
        this.windowSize = window;
    }
    protected void setPolicyType(SpecExecSchedulerPolicyType policy) {
        this.policyType = policy;
    }
    protected void reset() {
        this.lastIterator = null;
    }

    public boolean shouldIgnoreProcedure(Procedure catalog_proc) {
        return (this.checker.shouldIgnoreProcedure(catalog_proc));
    }
    
    public void interruptSearch(InternalMessage msg) {
        if (this.interrupted == false) {
            this.interrupted = true;
            this.latchMsg = msg.getClass();
        }
    }
    
    /**
     * Find the next non-conflicting txn that we can speculatively execute.
     * Note that if we find one, it will be immediately removed from the queue
     * and returned. If you do this and then find out for some reason that you
     * can't execute the StartTxnMessage that is returned, you must be sure
     * to requeue it back.
     * @param dtxn The current distributed txn at this partition.
     * @return
     */
    public LocalTransaction next(AbstractTransaction dtxn, SpeculationType specType) {
        this.interrupted = false;
        
        if (debug.val) {
            LOG.debug(String.format("%s - Checking queue for transaction to speculatively execute " +
                      "[specType=%s, windowSize=%d, queueSize=%d, policy=%s]",
                      dtxn, specType, this.windowSize, this.queue.size(), this.policyType));
            if (trace.val)
                LOG.trace(String.format("%s - Last Invocation [lastDtxn=%s, lastSpecType=%s, lastIterator=%s]",
                          dtxn, this.lastDtxn, this.lastSpecType, this.lastIterator));
        }
        
        SpecExecProfiler profiler = null;
        if (this.profiling) {
            if (this.profilerCurrentTxn != dtxn && this.profilerCurrentTxn != null) {
                for (int i = 0; i < this.profilerMap.length; i++) {
                    int cnt = (int)this.profilerExecuteCounter.get(i, 0);
                    this.profilerMap[i].num_executed.put(i, cnt);
                } // FOR
                this.profilerExecuteCounter.clearValues();
            }
            this.profilerCurrentTxn = dtxn;
            profiler = this.profilerMap[specType.ordinal()];
            profiler.total_time.start();
        }
        
        // If we have a distributed txn, then check make sure it's legit
        if (dtxn != null) {
            assert(this.checker.shouldIgnoreProcedure(dtxn.getProcedure()) == false) :
                String.format("Trying to check for speculative txns for %s but the txn " +
                		      "should have been ignored", dtxn);
            
            // If this is a LocalTransaction and all of the remote partitions that it needs are
            // on the same site, then we won't bother with trying to pick something out
            // because there is going to be very small wait times.
            if (this.ignore_all_local && dtxn instanceof LocalTransaction && ((LocalTransaction)dtxn).isPredictAllLocal()) {
                if (debug.val)
                    LOG.debug(String.format("%s - Ignoring current distributed txn because all of the partitions that " +
                              "it is using are on the same HStoreSite [%s]", dtxn, dtxn.getProcedure()));
                if (this.profiling) profiler.total_time.stop();
                return (null);
            }
        }
        
        // Now peek in the queue looking for single-partition txns that do not
        // conflict with the current dtxn
        LocalTransaction next = null;
        int txn_ctr = 0;
        int examined_ctr = 0;
        long bestTime = (this.policyType == SpecExecSchedulerPolicyType.LONGEST ? Long.MIN_VALUE : Long.MAX_VALUE);

        // Check whether we can use our same iterator from the last call
        if (this.policyType != SpecExecSchedulerPolicyType.FIRST ||
                this.lastDtxn != dtxn ||
                this.lastIterator == null ||
                (this.ignore_speculation_type_change == false && this.lastSpecType != specType) ||
                (this.ignore_queue_size_change == false && this.lastSize != this.queue.size())) {
            this.lastIterator = this.queue.iterator();    
        }
        boolean resetIterator = true;
        if (this.profiling) profiler.queue_size.put(this.queue.size());
        boolean lastHasNext;
        if (trace.val) LOG.trace(StringUtil.header("BEGIN QUEUE CHECK :: " + dtxn));
        while ((lastHasNext = this.lastIterator.hasNext()) == true) {
            if (this.interrupted) {
                if (debug.val)
                    LOG.warn(String.format("Search interrupted after %d examinations [%s]",
                             examined_ctr, this.latchMsg.getSimpleName()));
                if (this.profiling) profiler.interrupts++;
                break;
            }
            
            AbstractTransaction txn = this.lastIterator.next();
            assert(txn != null) : "Null transaction handle " + txn;
            boolean singlePartition = txn.isPredictSinglePartition();
            txn_ctr++;

            // Skip any distributed or non-local transactions
            if ((txn instanceof LocalTransaction) == false || singlePartition == false) {
                if (trace.val)
                    LOG.trace(String.format("Skipping non-speculative candidate %s", txn));
                continue;
            }
            LocalTransaction localTxn = (LocalTransaction)txn;
            
            // Skip anything already executed
            if (localTxn.isMarkExecuted()) {
                if (trace.val)
                    LOG.trace(String.format("Skipping %s because it was already executed", txn));
                continue;
            }

            // Let's check it out!
            if (this.profiling) profiler.compute_time.start();
            if (singlePartition == false) {
                if (trace.val)
                    LOG.trace(String.format("Skipping %s because it is not single-partitioned", localTxn));
                continue;
            }
            if (debug.val)
                LOG.debug(String.format("Examining whether %s conflicts with current dtxn", localTxn));
            examined_ctr++;
            try {
                switch (specType) {
                    // We can execute anything when we are in 2PC or idle
                    case IDLE:
                    case SP2_REMOTE_BEFORE:
                    case SP3_LOCAL:
                    case SP3_REMOTE: {
                        break;
                    }
                    // For SP1 + SP2 we can execute anything if the txn has not
                    // executed a query at this partition.
                    case SP1_LOCAL:
                    case SP2_REMOTE_AFTER: {
                        if (this.checker.canExecute(dtxn, localTxn, this.partitionId) == false) {
                            continue;
                        }
                        break;
                    }
                    // BUSTED!
                    default:
                        String msg = String.format("Unexpected %s.%s", specType.getClass().getSimpleName(), specType);
                        throw new RuntimeException(msg);
                } // SWITCH
                
                // Scheduling Policy: FIRST MATCH
                if (this.policyType == SpecExecSchedulerPolicyType.FIRST) {
                    next = localTxn;
                    resetIterator = false;
                    break;
                }
                
                // Estimate the time that remains.
                EstimatorState es = localTxn.getEstimatorState();
                if (es != null) {
                    long remainingTime = es.getLastEstimate().getRemainingExecutionTime();
                    if ((this.policyType == SpecExecSchedulerPolicyType.SHORTEST && remainingTime < bestTime) ||
                        (this.policyType == SpecExecSchedulerPolicyType.LONGEST && remainingTime > bestTime)) {
                        bestTime = remainingTime;
                        next = localTxn;
                        if (debug.val)
                            LOG.debug(String.format("[%s %d/%d] New Match -> %s / remainingTime=%d",
                                      this.policyType, examined_ctr, this.windowSize, next, remainingTime));
                     }
                }
                    
                // Stop if we've reached our window size
                if (examined_ctr == this.windowSize) break;
                
            } finally {
                if (this.profiling) profiler.compute_time.stop();
            }
        } // WHILE
        if (trace.val) LOG.trace(StringUtil.header("END QUEUE CHECK"));
        if (this.profiling) profiler.num_comparisons.put(txn_ctr);
        
        // We found somebody to execute right now!
        // Make sure that we set the speculative flag to true!
        if (next != null) {
            next.markReleased(this.partitionId);
            if (this.profiling) {
                this.profilerExecuteCounter.put(specType.ordinal());
                profiler.success++;
            }
            if (this.policyType == SpecExecSchedulerPolicyType.FIRST) {
                this.lastIterator.remove();
            } else {
                this.queue.remove(next);
            }
            if (debug.val)
                LOG.debug(dtxn + " - Found next non-conflicting speculative txn " + next);
        }
        else if (debug.val && this.queue.isEmpty() == false) {
            LOG.debug(String.format("Failed to find non-conflicting speculative txn " +
            		  "[dtxn=%s, txnCtr=%d, examinedCtr=%d]",
                      dtxn, txn_ctr, examined_ctr));
        }
        
        this.lastDtxn = dtxn;
        this.lastSpecType = specType;
        if (resetIterator || lastHasNext == false) this.lastIterator = null;
        else if (this.ignore_queue_size_change == false) this.lastSize = this.queue.size();
        if (this.profiling) profiler.total_time.stop();
        return (next);
    }
    
    // ----------------------------------------------------------------------------
    // DEBUG METHODS
    // ----------------------------------------------------------------------------
    
    public class Debug implements DebugContext {
        public AbstractTransaction getLastDtxn() {
            return (lastDtxn);
        }
        public int getLastSize() {
            return (lastSize);
        }
        public Iterator<AbstractTransaction> getLastIterator() {
            return (lastIterator);
        }
        public SpeculationType getLastSpecType() {
            return (lastSpecType);
        }
        public SpecExecProfiler[] getProfilers() {
            return (profilerMap);
        }
        public SpecExecProfiler getProfiler(SpeculationType stype) {
            return (profilerMap[stype.ordinal()]);
        }
        
    } // CLASS
    
    private SpecExecScheduler.Debug cachedDebugContext;
    public SpecExecScheduler.Debug getDebugContext() {
        if (this.cachedDebugContext == null) {
            // We don't care if we're thread-safe here...
            this.cachedDebugContext = new SpecExecScheduler.Debug();
        }
        return this.cachedDebugContext;
    }
}
