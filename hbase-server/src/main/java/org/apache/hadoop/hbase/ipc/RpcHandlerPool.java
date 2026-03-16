package org.apache.hadoop.hbase.ipc;

import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.OptionalGenericError;
import com.engineersbox.kairos.OperationRunnableBox;
import com.engineersbox.kairos.OptionalSliceOperationID;
import com.engineersbox.kairos.OptionalSliceWorkerID;
import com.engineersbox.kairos.OptionalWorkerGroupError;
import com.engineersbox.kairos.UsizeOrWorkerGroupError;
import com.engineersbox.kairos.WorkerGroup;
import com.engineersbox.kairos.WorkerGroupBox;
import com.engineersbox.kairos.WorkerGroupContainer;
import com.engineersbox.kairos.WorkerGroupProvider;
import com.engineersbox.kairos.WorkerGroupProviderBox;
import com.engineersbox.kairos.WorkerGroupProviderContainer;
import com.engineersbox.kairos.conversion.IntoBox;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.OptionalUtils;
import com.engineersbox.kairos.utils.SliceUtils;
import org.apache.hbase.thirdparty.com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.conf.ConfigurationObserver;
import org.apache.hadoop.hbase.util.BoundedPriorityBlockingQueue;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.ReflectionUtils;
import org.apache.hbase.thirdparty.com.google.common.base.Strings;
import org.apache.hbase.thirdparty.com.google.protobuf.Descriptors;
import org.apache.yetus.audience.InterfaceAudience;
import org.bytedeco.javacpp.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Collectors;

@InterfaceAudience.Private
public class RpcHandlerPool extends WorkerGroup {

  private static final Logger LOGGER = LoggerFactory.getLogger(RpcHandlerPool.class);

  protected static final int DEFAULT_CALL_QUEUE_SIZE_HARD_LIMIT = 250;
  protected static final float DEFAULT_CALL_QUEUE_HANDLER_FACTOR = 0.1f;
  protected static final int UNDEFINED_MAX_CALLQUEUE_LENGTH = -1;
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

  protected final LongAdder numGeneralCallsDropped = new LongAdder();
  protected final LongAdder numLifoModeSwitches = new LongAdder();

  protected final int numCallQueues;
  protected final List<BlockingQueue<CallRunner>> queues;
  private final Class<? extends BlockingQueue> queueClass;
  private final Object[] queueInitArgs;

  protected volatile int currentQueueLimit;

  protected final AtomicInteger activeHandlerCount = new AtomicInteger(0);
  private final List<RpcHandler> handlers;
  private final int handlerCount;
  private final AtomicInteger failedHandlerCount = new AtomicInteger(0);

  protected QueueBalancer balancer;
  protected final AtomicBoolean flushing = new AtomicBoolean(false);

  protected String name;
  protected final int rawHandlerCount;
  protected final String callQueueType;
  protected final int maxQueueLength;
  protected final int port;
  protected final PriorityFunction priority;
  protected final Configuration conf;
  protected final Abortable abortable;

  protected final TransparentPointerScope ptrScope;

  public RpcHandlerPool(final String name, final int handlerCount, final int maxQueueLength,
    final int port, final PriorityFunction priority, final Configuration conf, final Abortable abortable) {
    this(name, handlerCount, maxQueueLength, conf.get(CALL_QUEUE_TYPE_CONF_KEY, CALL_QUEUE_TYPE_CONF_DEFAULT),
      port, priority, conf, abortable);
  }

  public RpcHandlerPool(final String name, final int handlerCount, final int maxQueueLength,
    final String callQueueType, final int port, final PriorityFunction priority, final Configuration conf,
    final Abortable abortable) {
    this.name = name;
    this.conf = conf;
    this.abortable = abortable;
    this.port = port;
    this.rawHandlerCount = handlerCount;
    this.callQueueType = callQueueType;
    this.priority = priority;
    this.ptrScope = new TransparentPointerScope();
    float callQueuesHandlersFactor = getCallQueueHandlerFactor(conf);
    if (
      Float.compare(callQueuesHandlersFactor, 1.0f) > 0
        || Float.compare(0.0f, callQueuesHandlersFactor) > 0
    ) {
      LOGGER.warn(
        CALL_QUEUE_HANDLER_FACTOR_CONF_KEY + " is *ILLEGAL*, it should be in range [0.0, 1.0]");
      // For callQueuesHandlersFactor > 1.0, we just set it 1.0f.
      if (Float.compare(callQueuesHandlersFactor, 1.0f) > 0) {
        LOGGER.warn("Set " + CALL_QUEUE_HANDLER_FACTOR_CONF_KEY + " 1.0f");
        callQueuesHandlersFactor = 1.0f;
      } else {
        // But for callQueuesHandlersFactor < 0.0, following method #computeNumCallQueues
        // will compute max(1, -x) => 1 which has same effect of default value.
        LOGGER.warn("Set " + CALL_QUEUE_HANDLER_FACTOR_CONF_KEY + " default value 0.0f");
      }
    }
    this.numCallQueues = computeNumCallQueues(handlerCount, callQueuesHandlersFactor);
    this.queues = new ArrayList<>(this.numCallQueues);

    this.handlerCount = Math.max(handlerCount, this.numCallQueues);
    this.handlers = new ArrayList<>(this.handlerCount);

    // If soft limit of queue is not provided, then calculate using
    // DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER
    if (maxQueueLength == UNDEFINED_MAX_CALLQUEUE_LENGTH) {
      int handlerCountPerQueue = this.handlerCount / this.numCallQueues;
      this.maxQueueLength = handlerCountPerQueue * RpcServer.DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER;
    } else {
      this.maxQueueLength = maxQueueLength;
    }
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
    LOGGER.info(
      "Instantiated {} with queueClass={}; "
        + "numCallQueues={}, maxQueueLength={}, handlerCount={}",
      this.name, this.queueClass, this.numCallQueues, maxQueueLength, this.handlerCount);
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
  public OptionalWorkerGroupError start(final WorkerGroupContainer workerGroupContainer) {
    startHandlers(port);
    return OptionalUtils.noneWorkerGroupError();
  }

  @Override
  public OptionalWorkerGroupError stop(final WorkerGroupContainer workerGroupContainer) {
    return OptionalUtils.noneWorkerGroupError();
  }

  @Override
  public void flush(final WorkerGroupContainer workerGroupContainer) {
    this.flushing.set(true);
    for (final BlockingQueue<CallRunner> queue : this.queues) {
        while (!queue.isEmpty()) {
          try {
            queue.wait(10);
          } catch (final InterruptedException ignored) {
            // Ignored
          }
        }
    }
  }

  @Override
  public OptionalWorkerGroupError assign(final WorkerGroupContainer container,
    final OperationRunnableBox taskRunnable, final Pointer ctx, final long operationID) {
    if (this.flushing.get()) {
      LOGGER.warn("Queue is flushing, dropping task for operation {}", operationID);
      return OptionalUtils.someWorkerGroupError(Kairos.WorkerGroupError.WORKER_GROUP_ERROR_FAILED);
    }
    final CallRunner callRunner = (CallRunner) taskRunnable.container().instance().instance();
    final int queueIndex = this.balancer.getNextQueue(callRunner);
    final Queue<CallRunner> queue = this.queues.get(queueIndex);
    if (queue.size() >= this.currentQueueLimit || !queue.offer(callRunner)) {
      return OptionalUtils.someWorkerGroupError(Kairos.WorkerGroupError.WORKER_GROUP_ERROR_FAILED);
    }
    return OptionalUtils.noneWorkerGroupError();
  }

  private void resize(final int newSize) {
    this.currentQueueLimit = newSize;
  }

  @Override
  public OptionalWorkerGroupError resize(final WorkerGroupContainer workerGroupContainer,
    final long newSize) {
    resize((int) newSize);
    return OptionalUtils.noneWorkerGroupError();
  }

  @Override
  public long size(final WorkerGroupContainer workerGroupContainer) {
    return this.handlerCount;
  }

  @Override
  public OptionalSliceWorkerID workerIDs(final WorkerGroupContainer workerGroupContainer) {
    final long[] workerIDs = new long[this.handlers.size()];
    for (int i = 0; i < this.handlers.size(); i++) {
      workerIDs[i] = this.handlers.get(0).getId();
    }
    return OptionalUtils.someSliceWorkerID(
      SliceUtils.fromWorkerIDArray(workerIDs, this.ptrScope),
      this.ptrScope
    );
  }

  @Override
  public OptionalWorkerGroupError assignDirect(final WorkerGroupContainer workerGroupContainer,
    final long workerID, final OperationRunnableBox operationRunnableBox, final Pointer ctx,
    final long operationID) {
    return OptionalUtils.someWorkerGroupError(Kairos.WorkerGroupError.WORKER_GROUP_ERROR_DIRECT_UNSUPPORTED);
  }

  @Override public long operationCount(WorkerGroupContainer workerGroupContainer) {
    return super.operationCount(workerGroupContainer);
  }

  @Override
  public UsizeOrWorkerGroupError workerOperationCount(WorkerGroupContainer workerGroupContainer,
    long l) {
    return super.workerOperationCount(workerGroupContainer, l);
  }

  @Override
  public OptionalSliceOperationID workerOperationIds(WorkerGroupContainer workerGroupContainer,
    long l) {
    return super.workerOperationIds(workerGroupContainer, l);
  }

  public void start(final int port) {
    startHandlers(port);
  }

  protected List<BlockingQueue<CallRunner>> getQueues() {
    return queues;
  }

  protected void startHandlers(final int port) {
    startHandlers(null, port);
  }

  protected void startHandlers(final String nameSuffix, final int port) {
    List<BlockingQueue<CallRunner>> callQueues = getQueues();
    startHandlers(nameSuffix, handlerCount, callQueues, 0, callQueues.size(), port, activeHandlerCount);
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
    final double handlerFailureThreshhold = conf == null
      ? 1.0
      : conf.getDouble(HConstants.REGION_SERVER_HANDLER_ABORT_ON_ERROR_PERCENT,
      HConstants.DEFAULT_REGION_SERVER_HANDLER_ABORT_ON_ERROR_PERCENT);
    for (int i = 0; i < numHandlers; i++) {
      final int index = qindex + (i % qsize);
      final String name = "RpcServer." + threadPrefix + ".handler=" + handlers.size() + ",queue=" + index
        + ",port=" + port;
      final RpcHandler handler = getHandler(name, handlerFailureThreshhold, handlerCount,
        callQueues.get(index), activeHandlerCount, failedHandlerCount, abortable);
      handler.start();
      handlers.add(handler);
    }
    LOGGER.debug("Started handlerCount={} with threadPrefix={}, numCallQueues={}, port={}",
      handlers.size(), threadPrefix, qsize, port);
  }

  public void stop() {
    for (final RpcHandler handler : handlers) {
      handler.stopRunning();
      handler.interrupt();
    }
  }

  public Map<String, Long> getCallQueueCountsSummary() {
    return queues.stream().flatMap(Collection::stream).map(RpcHandlerPool::getMethodName)
      .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
  }

  public Map<String, Long> getCallQueueSizeSummary() {
    return queues.stream().flatMap(Collection::stream)
      .map(callRunner -> new Pair<>(getMethodName(callRunner), getRpcCallSize(callRunner)))
      .collect(Collectors.groupingBy(Pair::getFirst, Collectors.summingLong(Pair::getSecond)));
  }

  /**
   * Return the {@link Descriptors.MethodDescriptor#getName()} from {@code callRunner} or "Unknown".
   */
  private static String getMethodName(final CallRunner callRunner) {
    return Optional.ofNullable(callRunner).map(CallRunner::getRpcCall).map(RpcCall::getMethod)
      .map(Descriptors.MethodDescriptor::getName).orElse("Unknown");
  }

  /**
   * Return the {@link RpcCall#getSize()} from {@code callRunner} or 0L.
   */
  private static long getRpcCallSize(final CallRunner callRunner) {
    return Optional.ofNullable(callRunner).map(CallRunner::getRpcCall).map(RpcCall::getSize)
      .orElse(0L);
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

  protected float getCallQueueHandlerFactor(Configuration conf) {
    return conf.getFloat(CALL_QUEUE_HANDLER_FACTOR_CONF_KEY, DEFAULT_CALL_QUEUE_HANDLER_FACTOR);
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
      LOGGER.error(
        "Pluggable queue class config at " + PLUGGABLE_CALL_QUEUE_CLASS_NAME + " was not found");
      return Optional.empty();
    }

    try {
      Class<?> clazz = Class.forName(queueClassName);

      if (BlockingQueue.class.isAssignableFrom(clazz)) {
        return Optional.of((Class<? extends BlockingQueue<CallRunner>>) clazz);
      } else {
        LOGGER.error(
          "Pluggable Queue class " + queueClassName + " does not extend BlockingQueue<CallRunner>");
        return Optional.empty();
      }
    } catch (ClassNotFoundException exception) {
      LOGGER.error("Could not find " + queueClassName + " on the classpath to load.");
      return Optional.empty();
    }
  }

  /** Returns the length of the pending queue */
  public int getQueueLength() {
    int length = 0;
    for (final BlockingQueue<CallRunner> queue : this.queues) {
      length += queue.size();
    }
    return length;
  }

  private void propagateBalancerConfigChange(final QueueBalancer balancer, final Configuration updatedConf) {
    if (balancer instanceof ConfigurationObserver) {
      ((ConfigurationObserver) balancer).onConfigurationChange(updatedConf);
    }
  }

  /**
   * Update current soft limit for executor's call queues
   * @param conf updated configuration
   */
  public void resizeQueues(final Configuration conf) {
    String configKey = RpcScheduler.IPC_SERVER_MAX_CALLQUEUE_LENGTH;
    if (name != null) {
      if (name.toLowerCase(Locale.ROOT).contains("priority")) {
        configKey = RpcScheduler.IPC_SERVER_PRIORITY_MAX_CALLQUEUE_LENGTH;
      } else if (name.toLowerCase(Locale.ROOT).contains("replication")) {
        configKey = RpcScheduler.IPC_SERVER_REPLICATION_MAX_CALLQUEUE_LENGTH;
      } else if (name.toLowerCase(Locale.ROOT).contains("bulkload")) {
        configKey = RpcScheduler.IPC_SERVER_BULKLOAD_MAX_CALLQUEUE_LENGTH;
      }
    }
    final int queueLimit = this.currentQueueLimit;
    resize(conf.getInt(configKey, queueLimit));
  }

  public void onConfigurationChange(final Configuration updatedConf) {
    resizeQueues(updatedConf);
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

  public static class Provider extends WorkerGroupProvider implements
    IntoBox<WorkerGroupProviderBox> {

    private final String name;
    private final int port;
    private final int maxQueueLength;
    private final PriorityFunction priority;
    private final Configuration conf;
    private final Abortable abortable;
    private final TransparentPointerScope ptrScope;

    public Provider(final String name, final int port, final int maxQueueLength, final PriorityFunction priority,
      final Configuration conf, final Abortable abortable, final TransparentPointerScope ptrScope) {
      this.name = name;
      this.port = port;
      this.maxQueueLength = maxQueueLength;
      this.priority = priority;
      this.conf = conf;
      this.abortable = abortable;
      this.ptrScope = ptrScope;
    }

    @Override
    public Kairos.GenericError provide(final WorkerGroupProviderContainer workerGroupProviderContainer,
      final long size, final WorkerGroupBox workerGroupBox) {
      final RpcHandlerPool pool = ptrScope.attachTransparent(new RpcHandlerPool(
        this.name,
        (int) size,
        this.maxQueueLength,
        this.port,
        this.priority,
        this.conf,
        this.abortable
      ));
      pool.saturateBox(workerGroupBox);
      return Kairos.GenericError.GENERIC_ERROR_SUCCESS;
    }
  }

}
