package org.apache.hadoop.hbase.ipc;

import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.OptionalGenericError;
import com.engineersbox.kairos.TaskRunnableBox;
import com.engineersbox.kairos.WorkerGroup;
import com.engineersbox.kairos.WorkerGroupContainer;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.OptionalUtils;
import com.engineersbox.kairos.utils.TaskUtils;
import com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.conf.ConfigurationObserver;
import org.apache.hadoop.hbase.util.BoundedPriorityBlockingQueue;
import org.apache.hadoop.hbase.util.ReflectionUtils;
import org.apache.hbase.thirdparty.com.google.common.base.Strings;
import org.apache.yetus.audience.InterfaceAudience;
import org.bytedeco.javacpp.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

@InterfaceAudience.Private
public class RpcHandlerPool extends WorkerGroup {

  private static final Logger LOG = LoggerFactory.getLogger(RpcHandlerPool.class);

  protected static final int DEFAULT_CALL_QUEUE_SIZE_HARD_LIMIT = 250;
  public static final String CALL_QUEUE_HANDLER_FACTOR_CONF_KEY =
    "hbase.ipc.server.callqueue.handler.factor";

  /** max delay in msec used to bound the de-prioritized requests */
  public static final String QUEUE_MAX_CALL_DELAY_CONF_KEY =
    "hbase.ipc.server.queue.max.call.delay";

  /**
   * The default, 'fifo', has the least friction but is dumb. If set to 'deadline', uses a priority
   * queue and de-prioritizes long-running scans. Sorting by priority comes at a cost, reduced
   * throughput.
   */
  public static final String CALL_QUEUE_TYPE_CODEL_CONF_VALUE = "codel";
  public static final String CALL_QUEUE_TYPE_DEADLINE_CONF_VALUE = "deadline";
  public static final String CALL_QUEUE_TYPE_FIFO_CONF_VALUE = "fifo";
  public static final String CALL_QUEUE_TYPE_PLUGGABLE_CONF_VALUE = "pluggable";
  public static final String CALL_QUEUE_TYPE_CONF_KEY = "hbase.ipc.server.callqueue.type";
  public static final String CALL_QUEUE_TYPE_CONF_DEFAULT = CALL_QUEUE_TYPE_FIFO_CONF_VALUE;

  public static final String CALL_QUEUE_QUEUE_BALANCER_CLASS =
    "hbase.ipc.server.callqueue.balancer.class";
  public static final Class<?> CALL_QUEUE_QUEUE_BALANCER_CLASS_DEFAULT = RandomQueueBalancer.class;

  // These 3 are only used by Codel executor
  public static final String CALL_QUEUE_CODEL_TARGET_DELAY =
    "hbase.ipc.server.callqueue.codel.target.delay";
  public static final String CALL_QUEUE_CODEL_INTERVAL =
    "hbase.ipc.server.callqueue.codel.interval";
  public static final String CALL_QUEUE_CODEL_LIFO_THRESHOLD =
    "hbase.ipc.server.callqueue.codel.lifo.threshold";

  public static final int CALL_QUEUE_CODEL_DEFAULT_TARGET_DELAY = 100;
  public static final int CALL_QUEUE_CODEL_DEFAULT_INTERVAL = 100;
  public static final double CALL_QUEUE_CODEL_DEFAULT_LIFO_THRESHOLD = 0.8;

  public static final String PLUGGABLE_CALL_QUEUE_CLASS_NAME =
    "hbase.ipc.server.callqueue.pluggable.queue.class.name";
  public static final String PLUGGABLE_CALL_QUEUE_WITH_FAST_PATH_ENABLED =
    "hbase.ipc.server.callqueue.pluggable.queue.fast.path.enabled";

  private final LongAdder numGeneralCallsDropped = new LongAdder();
  private final LongAdder numLifoModeSwitches = new LongAdder();

  protected final int numCallQueues;
  protected final List<BlockingQueue<CallRunner>> queues;
  private final Class<? extends BlockingQueue> queueClass;
  private final Object[] queueInitArgs;

  protected volatile int currentQueueLimit;

  private final AtomicInteger activeHandlerCount = new AtomicInteger(0);
  private final List<RpcHandler> handlers;
  private final int handlerCount;
  private final AtomicInteger failedHandlerCount = new AtomicInteger(0);

  private final QueueBalancer balancer;

  private String name;

  private final Configuration conf;
  private final Abortable abortable;

  private final TransparentPointerScope scope;

  public RpcHandlerPool(final String name, final int handlerCount, final int maxQueueLength,
    final PriorityFunction priority, final Configuration conf, final Abortable abortable) {
    this(name, handlerCount, conf.get(CALL_QUEUE_TYPE_CONF_KEY, CALL_QUEUE_TYPE_CONF_DEFAULT),
      maxQueueLength, priority, conf, abortable);
  }

  public RpcHandlerPool(final String name, final int handlerCount, final String callQueueType,
    final int maxQueueLength, final PriorityFunction priority, final Configuration conf,
    final Abortable abortable) {
    this.name = name;
    this.conf = conf;
    this.abortable = abortable;
    this.scope = new TransparentPointerScope();
    float callQueuesHandlersFactor = this.conf.getFloat(CALL_QUEUE_HANDLER_FACTOR_CONF_KEY, 0.1f);
    if (
      Float.compare(callQueuesHandlersFactor, 1.0f) > 0
        || Float.compare(0.0f, callQueuesHandlersFactor) > 0
    ) {
      LOG.warn(
        CALL_QUEUE_HANDLER_FACTOR_CONF_KEY + " is *ILLEGAL*, it should be in range [0.0, 1.0]");
      // For callQueuesHandlersFactor > 1.0, we just set it 1.0f.
      if (Float.compare(callQueuesHandlersFactor, 1.0f) > 0) {
        LOG.warn("Set " + CALL_QUEUE_HANDLER_FACTOR_CONF_KEY + " 1.0f");
        callQueuesHandlersFactor = 1.0f;
      } else {
        // But for callQueuesHandlersFactor < 0.0, following method #computeNumCallQueues
        // will compute max(1, -x) => 1 which has same effect of default value.
        LOG.warn("Set " + CALL_QUEUE_HANDLER_FACTOR_CONF_KEY + " default value 0.0f");
      }
    }
    this.numCallQueues = computeNumCallQueues(handlerCount, callQueuesHandlersFactor);
    this.queues = new ArrayList<>(this.numCallQueues);

    this.handlerCount = Math.max(handlerCount, this.numCallQueues);
    this.handlers = new ArrayList<>(this.handlerCount);

    if (isDeadlineQueueType(callQueueType)) {
      this.name += ".Deadline";
      this.queueInitArgs =
        new Object[] { maxQueueLength, new CallPriorityComparator(conf, priority) };
      this.queueClass = BoundedPriorityBlockingQueue.class;
    } else if (isCodelQueueType(callQueueType)) {
      this.name += ".Codel";
      int codelTargetDelay =
        conf.getInt(CALL_QUEUE_CODEL_TARGET_DELAY, CALL_QUEUE_CODEL_DEFAULT_TARGET_DELAY);
      int codelInterval = conf.getInt(CALL_QUEUE_CODEL_INTERVAL, CALL_QUEUE_CODEL_DEFAULT_INTERVAL);
      double codelLifoThreshold =
        conf.getDouble(CALL_QUEUE_CODEL_LIFO_THRESHOLD, CALL_QUEUE_CODEL_DEFAULT_LIFO_THRESHOLD);
      this.queueInitArgs = new Object[] { maxQueueLength, codelTargetDelay, codelInterval,
        codelLifoThreshold, numGeneralCallsDropped, numLifoModeSwitches };
      this.queueClass = AdaptiveLifoCoDelCallQueue.class;
    } else if (isPluggableQueueType(callQueueType)) {
      Optional<Class<? extends BlockingQueue<CallRunner>>> pluggableQueueClass =
        getPluggableQueueClass();

      if (!pluggableQueueClass.isPresent()) {
        throw new PluggableRpcQueueNotFound(
          "Pluggable call queue failed to load and selected call" + " queue type required");
      } else {
        this.queueInitArgs = new Object[] { maxQueueLength, priority, conf };
        this.queueClass = pluggableQueueClass.get();
      }
    } else {
      this.name += ".Fifo";
      this.queueInitArgs = new Object[] { maxQueueLength };
      this.queueClass = LinkedBlockingQueue.class;
    }
    initializeQueues(this.numCallQueues);
    this.balancer = getBalancer(name, conf, this.queues);
  }

  protected void initializeQueues(final int numQueues) {
    if (queueInitArgs.length > 0) {
      currentQueueLimit = (int) queueInitArgs[0];
      queueInitArgs[0] = Math.max((int) queueInitArgs[0], DEFAULT_CALL_QUEUE_SIZE_HARD_LIMIT);
    }
    for (int i = 0; i < numQueues; ++i) {
      queues.add(ReflectionUtils.newInstance(queueClass, queueInitArgs));
    }
  }

  @Override
  public int capabilities(final WorkerGroupContainer workerGroupContainer) {
    return Kairos.WG_CAP_ASSIGN | Kairos.WG_CAP_RESIZE;
  }

  @Override
  public OptionalGenericError assign(final WorkerGroupContainer container,
    final TaskRunnableBox taskRunnable, final Pointer ctx) {
    final CallRunner callRunner = (CallRunner) taskRunnable.container().instance().instance();
    final int queueIndex = this.balancer.getNextQueue(callRunner);
    final Queue<CallRunner> queue = this.queues.get(queueIndex);
    if (queue.size() >= this.currentQueueLimit || !queue.offer(callRunner)) {
      return OptionalUtils.some(Kairos.GenericError.GENERIC_ERROR_FAILED, this.scope);
    }
    return OptionalUtils.noneGenericError();
  }

  @Override
  public OptionalGenericError resize(final WorkerGroupContainer workerGroupContainer,
    final long newSize) {

  }

  @Override
  public long size(final WorkerGroupContainer workerGroupContainer) {
    return this.handlerCount;
  }

  public void start(final int port) {
    startHandlers(port);
  }

  protected List<BlockingQueue<CallRunner>> getQueues() {
    return queues;
  }

  protected void startHandlers(final int port) {
    List<BlockingQueue<CallRunner>> callQueues = getQueues();
    startHandlers(null, handlerCount, callQueues, 0, callQueues.size(), port, activeHandlerCount);
  }

  /**
   * Override if providing alternate Handler implementation.
   */
  protected RpcHandler getHandler(final String name, final double handlerFailureThreshhold,
    final int handlerCount, final BlockingQueue<CallRunner> q,
    final AtomicInteger activeHandlerCount, final AtomicInteger failedHandlerCount,
    final Abortable abortable) {
    return new RpcHandler(name, handlerFailureThreshhold, handlerCount, q, activeHandlerCount,
      failedHandlerCount, abortable);
  }

  /**
   * Start up our handlers.
   */
  protected void startHandlers(final String nameSuffix, final int numHandlers,
    final List<BlockingQueue<CallRunner>> callQueues, final int qindex, final int qsize,
    final int port, final AtomicInteger activeHandlerCount) {
    final String threadPrefix = name + Strings.nullToEmpty(nameSuffix);
    double handlerFailureThreshhold = conf == null
      ? 1.0
      : conf.getDouble(HConstants.REGION_SERVER_HANDLER_ABORT_ON_ERROR_PERCENT,
      HConstants.DEFAULT_REGION_SERVER_HANDLER_ABORT_ON_ERROR_PERCENT);
    for (int i = 0; i < numHandlers; i++) {
      final int index = qindex + (i % qsize);
      String name = "RpcServer." + threadPrefix + ".handler=" + handlers.size() + ",queue=" + index
        + ",port=" + port;
      RpcHandler handler = getHandler(name, handlerFailureThreshhold, handlerCount,
        callQueues.get(index), activeHandlerCount, failedHandlerCount, abortable);
      handler.start();
      handlers.add(handler);
    }
    LOG.debug("Started handlerCount={} with threadPrefix={}, numCallQueues={}, port={}",
      handlers.size(), threadPrefix, qsize, port);
  }

  public void stop() {
    for (RpcHandler handler : handlers) {
      handler.stopRunning();
      handler.interrupt();
    }
  }

  protected int computeNumCallQueues(final int handlerCount, final float callQueuesHandlersFactor) {
    return Math.max(1, Math.round(handlerCount * callQueuesHandlersFactor));
  }

  private static final QueueBalancer ONE_QUEUE = val -> 0;

  public static QueueBalancer getBalancer(final String executorName,
                                          final Configuration conf,
                                          final List<BlockingQueue<CallRunner>> queues) {
    Preconditions.checkArgument(!queues.isEmpty(), "Queue size is <= 0, must be at least 1");
    if (queues.size() == 1) {
      return ONE_QUEUE;
    } else {
      Class<?> balancerClass =
        conf.getClass(CALL_QUEUE_QUEUE_BALANCER_CLASS, CALL_QUEUE_QUEUE_BALANCER_CLASS_DEFAULT);
      return (QueueBalancer) ReflectionUtils.newInstance(balancerClass, conf, executorName, queues);
    }
  }

  public static boolean isDeadlineQueueType(final String callQueueType) {
    return callQueueType.equals(CALL_QUEUE_TYPE_DEADLINE_CONF_VALUE);
  }

  public static boolean isCodelQueueType(final String callQueueType) {
    return callQueueType.equals(CALL_QUEUE_TYPE_CODEL_CONF_VALUE);
  }

  public static boolean isFifoQueueType(final String callQueueType) {
    return callQueueType.equals(CALL_QUEUE_TYPE_FIFO_CONF_VALUE);
  }

  public static boolean isPluggableQueueType(String callQueueType) {
    return callQueueType.equals(CALL_QUEUE_TYPE_PLUGGABLE_CONF_VALUE);
  }

  public static boolean isPluggableQueueWithFastPath(String callQueueType, Configuration conf) {
    return isPluggableQueueType(callQueueType)
      && conf.getBoolean(PLUGGABLE_CALL_QUEUE_WITH_FAST_PATH_ENABLED, false);
  }

  private static class CallPriorityComparator implements Comparator<CallRunner> {
    private final static int DEFAULT_MAX_CALL_DELAY = 5000;

    private final PriorityFunction priority;
    private final int maxDelay;

    public CallPriorityComparator(final Configuration conf, final PriorityFunction priority) {
      this.priority = priority;
      this.maxDelay = conf.getInt(QUEUE_MAX_CALL_DELAY_CONF_KEY, DEFAULT_MAX_CALL_DELAY);
    }

    @Override
    public int compare(CallRunner a, CallRunner b) {
      RpcCall callA = a.getRpcCall();
      RpcCall callB = b.getRpcCall();
      long deadlineA = priority.getDeadline(callA.getHeader(), callA.getParam());
      long deadlineB = priority.getDeadline(callB.getHeader(), callB.getParam());
      deadlineA = callA.getReceiveTime() + Math.min(deadlineA, maxDelay);
      deadlineB = callB.getReceiveTime() + Math.min(deadlineB, maxDelay);
      return Long.compare(deadlineA, deadlineB);
    }
  }

  private Optional<Class<? extends BlockingQueue<CallRunner>>> getPluggableQueueClass() {
    String queueClassName = conf.get(PLUGGABLE_CALL_QUEUE_CLASS_NAME);

    if (queueClassName == null) {
      LOG.error(
        "Pluggable queue class config at " + PLUGGABLE_CALL_QUEUE_CLASS_NAME + " was not found");
      return Optional.empty();
    }

    try {
      Class<?> clazz = Class.forName(queueClassName);

      if (BlockingQueue.class.isAssignableFrom(clazz)) {
        return Optional.of((Class<? extends BlockingQueue<CallRunner>>) clazz);
      } else {
        LOG.error(
          "Pluggable Queue class " + queueClassName + " does not extend BlockingQueue<CallRunner>");
        return Optional.empty();
      }
    } catch (ClassNotFoundException exception) {
      LOG.error("Could not find " + queueClassName + " on the classpath to load.");
      return Optional.empty();
    }
  }

  private void propagateBalancerConfigChange(final QueueBalancer balancer, final Configuration updatedConf) {
    if (balancer instanceof ConfigurationObserver) {
      ((ConfigurationObserver) balancer).onConfigurationChange(updatedConf);
    }
  }

  public void onConfigurationChange(final Configuration updatedConf) {
    propagateBalancerConfigChange(this.balancer, updatedConf);
    // update CoDel Scheduler tunables
    final int codelTargetDelay =
      conf.getInt(CALL_QUEUE_CODEL_TARGET_DELAY, CALL_QUEUE_CODEL_DEFAULT_TARGET_DELAY);
    final int codelInterval = conf.getInt(CALL_QUEUE_CODEL_INTERVAL, CALL_QUEUE_CODEL_DEFAULT_INTERVAL);
    final double codelLifoThreshold =
      conf.getDouble(CALL_QUEUE_CODEL_LIFO_THRESHOLD, CALL_QUEUE_CODEL_DEFAULT_LIFO_THRESHOLD);

    for (final BlockingQueue<CallRunner> queue : queues) {
      if (queue instanceof AdaptiveLifoCoDelCallQueue) {
        ((AdaptiveLifoCoDelCallQueue) queue).updateTunables(codelTargetDelay, codelInterval,
          codelLifoThreshold);
      } else if (queue instanceof ConfigurationObserver) {
        ((ConfigurationObserver) queue).onConfigurationChange(conf);
      }
    }
  }

}
