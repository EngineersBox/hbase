/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.ipc;

import com.engineersbox.kairos.ArcVoid;
import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.LoggerDrainBox;
import com.engineersbox.kairos.OptionalSchedulerBootstrapFn;
import com.engineersbox.kairos.SchedulerArgs;
import com.engineersbox.kairos.SchedulerIDOrKairosResult;
import com.engineersbox.kairos.SchedulerPluginArcBox;
import com.engineersbox.kairos.SchedulerPluginContainer;
import com.engineersbox.kairos.SchedulerPluginCreator;
import com.engineersbox.kairos.SliceU8;
import com.engineersbox.kairos.Operation;
import com.engineersbox.kairos.OperationMetadataOrKairosResult;
import com.engineersbox.kairos.WorkerGroupProviderBox;
import com.engineersbox.kairos.conversion.IntoBox;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.SliceUtils;
import com.google.common.base.Strings;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.conf.ConfigurationObserver;
import org.apache.hadoop.hbase.executor.Scheduling;
import org.apache.hadoop.hbase.master.MasterAnnotationReadingPriorityFunction;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.bytedeco.javacpp.PointerScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default scheduler. Configurable. Maintains isolated handler pools for general ('default'),
 * high-priority ('priority'), and replication ('replication') requests. Default behavior is to
 * balance the requests across handlers. Add configs to enable balancing by read vs writes, etc. See
 * below article for explanation of options.
 * @see <a href=
 *      "http://blog.cloudera.com/blog/2014/12/new-in-cdh-5-2-improvements-for-running-multiple-workloads-on-a-single-hbase-cluster/">Overview
 *      on Request Queuing</a>
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.COPROC, HBaseInterfaceAudience.PHOENIX })
@InterfaceStability.Evolving
public class SimpleRpcScheduler extends RpcScheduler implements ConfigurationObserver {
  private static final Logger LOGGER = LoggerFactory.getLogger(SimpleRpcScheduler.class);

  private int port;
  private final PriorityFunction priority;
//  private final RpcExecutor callExecutor;
  private final long callExecutorID;
//  private final RpcExecutor priorityExecutor;
  private long priorityExecutorID;
//  private final RpcExecutor replicationExecutor;
  private final long replicationExecutorID;

  /**
   * This executor is only for meta transition
   */
//  private final RpcExecutor metaTransitionExecutor;
  private long metaTransitionExecutorID;

//  private final RpcExecutor bulkloadExecutor;
  private final long bulkloadExecutorID;

  /** What level a high priority call is at. */
  private final int highPriorityLevel;

  private Abortable abortable = null;

  private final TransparentPointerScope ptrScope;

  /**
   * @param handlerCount            the number of handler threads that will be used to process calls
   * @param priorityHandlerCount    How many threads for priority handling.
   * @param replicationHandlerCount How many threads for replication handling.
   * @param priority                Function to extract request priority.
   */
  public SimpleRpcScheduler(final SliceU8 name, final Configuration conf, int handlerCount,
    final int priorityHandlerCount, final int replicationHandlerCount, final int metaTransitionHandler,
    final PriorityFunction priority, final Abortable server, final int highPriorityLevel,
    final TransparentPointerScope ptrScope, final SchedulerArgs schedulerArgs, final LoggerDrainBox loggerDrain,
    final ArcVoid pluginCtx) {
    super(name, schedulerArgs, loggerDrain, pluginCtx);
    this.ptrScope = ptrScope;
    int bulkLoadHandlerCount = conf.getInt(HConstants.REGION_SERVER_BULKLOAD_HANDLER_COUNT,
      HConstants.DEFAULT_REGION_SERVER_BULKLOAD_HANDLER_COUNT);
    int maxQueueLength = conf.getInt(RpcScheduler.IPC_SERVER_MAX_CALLQUEUE_LENGTH,
      handlerCount * RpcServer.DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER);
    int maxPriorityQueueLength = conf.getInt(RpcScheduler.IPC_SERVER_PRIORITY_MAX_CALLQUEUE_LENGTH,
      priorityHandlerCount * RpcServer.DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER);
    int maxReplicationQueueLength =
      conf.getInt(RpcScheduler.IPC_SERVER_REPLICATION_MAX_CALLQUEUE_LENGTH,
        replicationHandlerCount * RpcServer.DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER);
    int maxBulkLoadQueueLength = conf.getInt(RpcScheduler.IPC_SERVER_BULKLOAD_MAX_CALLQUEUE_LENGTH,
      bulkLoadHandlerCount * RpcServer.DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER);

    this.priority = priority;
    this.highPriorityLevel = highPriorityLevel;
    this.abortable = server;

    String callQueueType =
      conf.get(RpcExecutor.CALL_QUEUE_TYPE_CONF_KEY, RpcExecutor.CALL_QUEUE_TYPE_CONF_DEFAULT);
    float callqReadShare = conf.getFloat(RWQueueRpcExecutor.CALL_QUEUE_READ_SHARE_CONF_KEY, 0);

    if (callqReadShare > 0) {
      // at least 1 read handler and 1 write handler
      final SchedulerIDOrKairosResult result = FastPathRWQueueRpcExecutor.newFastPathRWQueue("default.FPRWQ",
        port, Math.max(2, handlerCount), maxQueueLength, conf, priority, server,
        this.ptrScope);
      if (result.tag().intern() == Kairos.SchedulerIDOrKairosResultTag.Err_SchedulerID__KairosResult) {
        throw new RuntimeException("Failed to create call executor: " + result.err().intern().name());
      }
      this.callExecutorID = result.ok();
//      callExecutor = new FastPathRWQueueRpcExecutor("default.FPRWQ", Math.max(2, handlerCount),
//        maxQueueLength, priority, conf, server);
    } else {
      if (
        RpcHandlerPool.isFifoQueueType(callQueueType) || RpcHandlerPool.isCodelQueueType(callQueueType)
          || RpcHandlerPool.isPluggableQueueWithFastPath(callQueueType, conf)
      ) {
        final SchedulerIDOrKairosResult result = FastPathBalancedQueueRpcExecutor.newFastPathBalancedQueue(
          "default.FPBQ", port, handlerCount, maxQueueLength, conf, priority, server, ptrScope);
        if (result.tag().intern() == Kairos.SchedulerIDOrKairosResultTag.Err_SchedulerID__KairosResult) {
          throw new RuntimeException("Failed to create call executor: " + result.err().intern().name());
        }
        this.callExecutorID = result.ok();
//        callExecutor = new FastPathBalancedQueueRpcExecutor("default.FPBQ", handlerCount,
//          maxQueueLength, priority, conf, server);
      } else {

        final RpcHandlerPool.Provider wgProvider = ptrScope.attachTransparent(new RpcHandlerPool.Provider(
          "default.BQ", port, maxQueueLength, priority, conf, abortable, ptrScope
        ));
        final SchedulerIDOrKairosResult result = BalancedQueueRpcExecutor.newBalancedQueue(
          "default.BQ", handlerCount, wgProvider, ptrScope);
        if (result.tag().intern() == Kairos.SchedulerIDOrKairosResultTag.Err_SchedulerID__KairosResult) {
          throw new RuntimeException("Failed to create call executor: " + result.err().intern().name());
        }
        this.callExecutorID = result.ok();
//        callExecutor = new BalancedQueueRpcExecutor("default.BQ", handlerCount, maxQueueLength,
//          priority, conf, server);
      }
    }

    float metaCallqReadShare =
      conf.getFloat(MetaRWQueueRpcExecutor.META_CALL_QUEUE_READ_SHARE_CONF_KEY,
        MetaRWQueueRpcExecutor.DEFAULT_META_CALL_QUEUE_READ_SHARE);
    if (metaCallqReadShare > 0) {
      // different read/write handler for meta, at least 1 read handler and 1 write handler
      final RpcHandlerPool.Provider wgProvider = ptrScope.attachTransparent(new RpcHandlerPool.Provider(
        "priority.RWQ", port, maxPriorityQueueLength, priority, conf, abortable, ptrScope
      ));
      final SchedulerIDOrKairosResult result = MetaRWQueueRpcExecutor.newMetaRWQueue("priority.RWQ",
        port, Math.max(2, priorityHandlerCount), conf, wgProvider, ptrScope);
      if (result.tag().intern() == Kairos.SchedulerIDOrKairosResultTag.Err_SchedulerID__KairosResult) {
        throw new RuntimeException("Failed to create meta transition executor: " + result.err().intern().name());
      }
      this.metaTransitionExecutorID = result.ok();
//      this.priorityExecutor = new MetaRWQueueRpcExecutor("priority.RWQ",
//        Math.max(2, priorityHandlerCount), maxPriorityQueueLength, priority, conf, server);
    } else if (priorityHandlerCount > 0) {
      // Create 2 queues to help priorityExecutor be more scalable.
      final SchedulerIDOrKairosResult result = FastPathBalancedQueueRpcExecutor.newFastPathBalancedQueue(
        "priority.FPBQ", port, priorityHandlerCount,maxPriorityQueueLength,
        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, conf, priority, server, ptrScope);
      if (result.tag().intern() == Kairos.SchedulerIDOrKairosResultTag.Err_SchedulerID__KairosResult) {
        throw new RuntimeException("Failed to create priority executor: " + result.err().intern().name());
      }
      this.priorityExecutorID = result.ok();
//      this.priorityExecutor =  new FastPathBalancedQueueRpcExecutor("priority.FPBQ", priorityHandlerCount,
//        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, maxPriorityQueueLength, priority, conf,
//        abortable);
    } else {
//      this.priorityExecutor = null;
      this.priorityExecutorID = 0;
    }
    if (replicationHandlerCount > 0) {
      final SchedulerIDOrKairosResult result = FastPathBalancedQueueRpcExecutor.newFastPathBalancedQueue(
        "replication.FPBQ", port, replicationHandlerCount,maxReplicationQueueLength,
        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, conf, priority, server, ptrScope);
      if (result.tag().intern() == Kairos.SchedulerIDOrKairosResultTag.Err_SchedulerID__KairosResult) {
        throw new RuntimeException("Failed to create replication executor: " + result.err().intern().name());
      }
      this.replicationExecutorID = result.ok();
//      this.replicationExecutor = new FastPathBalancedQueueRpcExecutor("replication.FPBQ", replicationHandlerCount,
//        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, maxReplicationQueueLength, priority, conf,
//        abortable);
    } else {
//      this.replicationExecutor = null;
      this.replicationExecutorID = 0;
    }

    if (metaTransitionHandler > 0) {
      final SchedulerIDOrKairosResult result = FastPathBalancedQueueRpcExecutor.newFastPathBalancedQueue(
        "metaPriority.FPBQ", port, metaTransitionHandler,maxPriorityQueueLength,
        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, conf, priority, server, ptrScope);
      if (result.tag().intern() == Kairos.SchedulerIDOrKairosResultTag.Err_SchedulerID__KairosResult) {
        throw new RuntimeException("Failed to create meta transition executor: " + result.err().intern().name());
      }
      this.metaTransitionExecutorID = result.ok();
//      this.metaTransitionExecutor = new FastPathBalancedQueueRpcExecutor("metaPriority.FPBQ", metaTransitionHandler,
//        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, maxPriorityQueueLength, priority, conf,
//        abortable);
    } else {
//      this.metaTransitionExecutor = null;
      this.metaTransitionExecutorID = 0;
    }
    if (bulkLoadHandlerCount > 0) {
      final SchedulerIDOrKairosResult result = FastPathBalancedQueueRpcExecutor.newFastPathBalancedQueue(
        "bulkLoad.FPBQ", port, bulkLoadHandlerCount,maxBulkLoadQueueLength,
        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, conf, priority, server, ptrScope);
      if (result.tag().intern() == Kairos.SchedulerIDOrKairosResultTag.Err_SchedulerID__KairosResult) {
        throw new RuntimeException("Failed to create bulkload executor: " + result.err().intern().name());
      }
      this.bulkloadExecutorID = result.ok();
//      this.bulkloadExecutor = new FastPathBalancedQueueRpcExecutor("bulkLoad.FPBQ", bulkLoadHandlerCount,
//        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, maxBulkLoadQueueLength, priority, conf,
//        abortable);
    } else {
//      this.bulkloadExecutor = null;
      this.bulkloadExecutorID = 0;
    }
  }

  public SimpleRpcScheduler(final SliceU8 name, final Configuration conf, final int handlerCount,
    final int priorityHandlerCount, final int replicationHandlerCount, final PriorityFunction priority,
    final int highPriorityLevel, final TransparentPointerScope ptrScope, final SchedulerArgs schedulerArgs,
    final LoggerDrainBox loggerDrain, final ArcVoid pluginCtx) {
    this(name, conf, handlerCount, priorityHandlerCount, replicationHandlerCount, 0, priority, null,
      highPriorityLevel, ptrScope, schedulerArgs, loggerDrain, pluginCtx);
  }

  /**
   * Resize call queues;
   * @param conf new configuration
   */
  @Override
  public void onConfigurationChange(Configuration conf) {
    // NOTE:  Not necessary for benchmarking Kairos usage, but needs
    //        to be implemented for general usage. Ideally, the class
    //        that responds to a configuration change should register
    //        itself into some handler pool to receive updates. This
    //        then allows it to do so without needing to call through
    //        the FFI barrier in some weird way.
//    callExecutor.resizeQueues(conf);
//    if (priorityExecutor != null) {
//      priorityExecutor.resizeQueues(conf);
//    }
//    if (replicationExecutor != null) {
//      replicationExecutor.resizeQueues(conf);
//    }
//    if (metaTransitionExecutor != null) {
//      metaTransitionExecutor.resizeQueues(conf);
//    }
//    if (bulkloadExecutor != null) {
//      bulkloadExecutor.resizeQueues(conf);
//    }
//
//    String callQueueType =
//      conf.get(RpcExecutor.CALL_QUEUE_TYPE_CONF_KEY, RpcExecutor.CALL_QUEUE_TYPE_CONF_DEFAULT);
//    if (
//      RpcHandlerPool.isCodelQueueType(callQueueType) || RpcHandlerPool.isPluggableQueueType(callQueueType)
//    ) {
//      callExecutor.onConfigurationChange(conf);
//    }
  }

  @Override
  public void init(final Context context) {
    this.port = context.getListenerAddress().getPort();
  }

  private void startExecutor(final String name, final long schedulerID) {
    Kairos.KairosResult result = Kairos.startScheduler(Scheduling.KAIROS, schedulerID);
    if (result.intern() != Kairos.KairosResult.KAIROS_RESULT_SUCCESS) {
      throw new RuntimeException(String.format("Failed to start %s executor", name));
    }
  }

  @Override
  public void start(final SchedulerPluginContainer schedulerPluginContainer) {
    startExecutor("call", this.callExecutorID);
    if (priorityExecutorID != 0) {
      startExecutor("priority", this.priorityExecutorID);
    }
    if (replicationExecutorID != 0) {
      startExecutor("replication", this.replicationExecutorID);
    }
    if (metaTransitionExecutorID != 0) {
      startExecutor("meta-transition", this.metaTransitionExecutorID);
    }
    if (bulkloadExecutorID != 0) {
      startExecutor("bulkload", this.bulkloadExecutorID);
    }
  }

  private void stopExecutor(final String name, final long schedulerID) {
    Kairos.KairosResult result = Kairos.stopScheduler(Scheduling.KAIROS, schedulerID);
    if (result.intern() != Kairos.KairosResult.KAIROS_RESULT_SUCCESS) {
      throw new RuntimeException(String.format("Failed to stop %s executor", name));
    }
  }

  @Override
  public void stop(final SchedulerPluginContainer schedulerPluginContainer) {
    stopExecutor("call", this.callExecutorID);
    if (priorityExecutorID != 0) {
      stopExecutor("priority", this.priorityExecutorID);
    }
    if (replicationExecutorID != 0) {
      stopExecutor("replication", this.replicationExecutorID);
    }
    if (metaTransitionExecutorID != 0) {
      stopExecutor("meta-transition", this.metaTransitionExecutorID);
    }
    if (bulkloadExecutorID != 0) {
      stopExecutor("bulkload", this.bulkloadExecutorID);
    }
  }

  @Override
  public boolean submit(final SchedulerPluginContainer schedulerPluginContainer, final Operation task,
    final long operation_id) {
    final CallRunner callRunner = task.runnable().container().instance().instance().getPointer(CallRunner.class);
    RpcCall call = callRunner.getRpcCall();
    int level =
      priority.getPriority(call.getHeader(), call.getParam(), call.getRequestUser().orElse(null));
    if (level == HConstants.PRIORITY_UNSET) {
      level = HConstants.NORMAL_QOS;
    }
    OperationMetadataOrKairosResult result;
    if (
      metaTransitionExecutorID != 0
        && level == MasterAnnotationReadingPriorityFunction.META_TRANSITION_QOS
    ) {
      result = Kairos.submit(Scheduling.KAIROS, this.metaTransitionExecutorID, task);
    } else if (priorityExecutorID != 0 && level > highPriorityLevel) {
      result = Kairos.submit(Scheduling.KAIROS, this.priorityExecutorID, task);
    } else if (replicationExecutorID != 0 && level == HConstants.REPLICATION_QOS) {
      result = Kairos.submit(Scheduling.KAIROS, this.replicationExecutorID, task);
    } else if (bulkloadExecutorID != 0 && level == HConstants.BULKLOAD_QOS) {
      result = Kairos.submit(Scheduling.KAIROS, this.bulkloadExecutorID, task);
    } else {
      result = Kairos.submit(Scheduling.KAIROS, this.callExecutorID, task);
    }
    if (result.tag().intern() == Kairos.OperationMetadataOrKairosResultTag.Err_OperationMetadata__KairosResult) {
      LOGGER.error("Failed to dispatch task {}", operation_id);
      return true;
    }
    return false;
  }

  @Override
  public int getMetaPriorityQueueLength() {
    return 0;
//    return metaTransitionExecutor == null ? 0 : metaTransitionExecutor.getQueueLength();
  }

  @Override
  public int getGeneralQueueLength() {
    return 0;
//    return callExecutor.getQueueLength();
  }

  @Override
  public int getPriorityQueueLength() {
    return 0;
//    return priorityExecutor == null ? 0 : priorityExecutor.getQueueLength();
  }

  @Override
  public int getReplicationQueueLength() {
    return 0;
//    return replicationExecutor == null ? 0 : replicationExecutor.getQueueLength();
  }

  @Override
  public int getBulkLoadQueueLength() {
    return 0;
//    return bulkloadExecutor == null ? 0 : bulkloadExecutor.getQueueLength();
  }

  @Override
  public int getActiveRpcHandlerCount() {
    return 0;
//    return callExecutor.getActiveHandlerCount() + getActivePriorityRpcHandlerCount()
//      + getActiveReplicationRpcHandlerCount() + getActiveMetaPriorityRpcHandlerCount()
//      + getActiveBulkLoadRpcHandlerCount();
  }

  @Override
  public int getActiveMetaPriorityRpcHandlerCount() {
    return 0;
//    return (metaTransitionExecutor == null ? 0 : metaTransitionExecutor.getActiveHandlerCount());
  }

  @Override
  public int getActiveGeneralRpcHandlerCount() {
    return 0;
//    return callExecutor.getActiveHandlerCount();
  }

  @Override
  public int getActivePriorityRpcHandlerCount() {
    return 0;
//    return (priorityExecutor == null ? 0 : priorityExecutor.getActiveHandlerCount());
  }

  @Override
  public int getActiveReplicationRpcHandlerCount() {
    return 0;
//    return (replicationExecutor == null ? 0 : replicationExecutor.getActiveHandlerCount());
  }

  @Override
  public int getActiveBulkLoadRpcHandlerCount() {
    return 0;
//    return bulkloadExecutor == null ? 0 : bulkloadExecutor.getActiveHandlerCount();
  }

  @Override
  public long getNumGeneralCallsDropped() {
    return 0;
//    return callExecutor.getNumGeneralCallsDropped();
  }

  @Override
  public long getNumLifoModeSwitches() {
    return 0;
//    return callExecutor.getNumLifoModeSwitches();
  }

  @Override
  public int getWriteQueueLength() {
    return 0;
//    return callExecutor.getWriteQueueLength();
  }

  @Override
  public int getReadQueueLength() {
    return 0;
//    return callExecutor.getReadQueueLength();
  }

  @Override
  public int getScanQueueLength() {
    return 0;
//    return callExecutor.getScanQueueLength();
  }

  @Override
  public int getActiveWriteRpcHandlerCount() {
    return 0;
//    return callExecutor.getActiveWriteHandlerCount();
  }

  @Override
  public int getActiveReadRpcHandlerCount() {
    return 0;
//    return callExecutor.getActiveReadHandlerCount();
  }

  @Override
  public int getActiveScanRpcHandlerCount() {
    return 0;
//    return callExecutor.getActiveScanHandlerCount();
  }

  @Override public PointerScope getPointerScope() {
    return null;
  }

  @Override
  public CallQueueInfo getCallQueueInfo() {
//    String queueName;

    CallQueueInfo callQueueInfo = new CallQueueInfo();

//    if (null != callExecutor) {
//      queueName = "Call Queue";
//      callQueueInfo.setCallMethodCount(queueName, callExecutor.getCallQueueCountsSummary());
//      callQueueInfo.setCallMethodSize(queueName, callExecutor.getCallQueueSizeSummary());
//    }
//
//    if (null != priorityExecutor) {
//      queueName = "Priority Queue";
//      callQueueInfo.setCallMethodCount(queueName, priorityExecutor.getCallQueueCountsSummary());
//      callQueueInfo.setCallMethodSize(queueName, priorityExecutor.getCallQueueSizeSummary());
//    }
//
//    if (null != replicationExecutor) {
//      queueName = "Replication Queue";
//      callQueueInfo.setCallMethodCount(queueName, replicationExecutor.getCallQueueCountsSummary());
//      callQueueInfo.setCallMethodSize(queueName, replicationExecutor.getCallQueueSizeSummary());
//    }
//
//    if (null != metaTransitionExecutor) {
//      queueName = "Meta Transition Queue";
//      callQueueInfo.setCallMethodCount(queueName,
//        metaTransitionExecutor.getCallQueueCountsSummary());
//      callQueueInfo.setCallMethodSize(queueName, metaTransitionExecutor.getCallQueueSizeSummary());
//    }
//
//    if (null != bulkloadExecutor) {
//      queueName = "BulkLoad Queue";
//      callQueueInfo.setCallMethodCount(queueName, bulkloadExecutor.getCallQueueCountsSummary());
//      callQueueInfo.setCallMethodSize(queueName, bulkloadExecutor.getCallQueueSizeSummary());
//    }

    return callQueueInfo;
  }

  public static SchedulerIDOrKairosResult newSimpleRpcScheduler(final String name, final int handlerCount,
    final int priorityHandlerCount, final int replicationHandlerCount, final int metaTransitionHandlerCount,
    final PriorityFunction priority, final Abortable server, final int highPriorityLevel,
    final Configuration conf, final IntoBox<WorkerGroupProviderBox> wgProvider,
    final OptionalSchedulerBootstrapFn bootstrapFn, final TransparentPointerScope ptrScope) {
      try (final TransparentPointerScope tempScope = new TransparentPointerScope()) {
        final Creator creator = Creator.newInstance(
          name,
          handlerCount,
          priorityHandlerCount,
          replicationHandlerCount,
          metaTransitionHandlerCount,
          priority,
          server,
          highPriorityLevel,
          conf,
          ptrScope
        );
        return Kairos.createSchedulerInstance(
          Scheduling.KAIROS,
          SliceUtils.fromString(name, tempScope),
          ptrScope.attachTransparent(creator.intoDescriptor()),
          tempScope.attachTransparent(wgProvider.intoBox()),
          Kairos.newNoopLoggerDrain(),
          bootstrapFn
        );
      }
    }

  public static class Creator extends SchedulerPluginCreator {

    private final int handlerCount;
    private final int priorityHandlerCount;
    private final int replicationHandlerCount;
    private final int metaTransitionHandler;
    private final PriorityFunction priority;
    private final Abortable server;
    private final int highPriorityLevel;
    private final Configuration conf;

    private final TransparentPointerScope runtimeScope;

    public Creator(final SliceU8 name, final int handlerCount, final int priorityHandlerCount,
      final int replicationHandlerCount, final int metaTransitionHandler, final PriorityFunction priority,
      Abortable server, int highPriorityLevel, final Configuration conf, final TransparentPointerScope runtimeScope) {
      super(name);
      this.handlerCount = handlerCount;
      this.priorityHandlerCount = priorityHandlerCount;
      this.replicationHandlerCount = replicationHandlerCount;
      this.metaTransitionHandler = metaTransitionHandler;
      this.priority = priority;
      this.server = server;
      this.highPriorityLevel = highPriorityLevel;
      this.conf = conf;
      this.runtimeScope = runtimeScope;
    }

    public static Creator newInstance(final String name, final int handlerCount,
      final int priorityHandlerCount, final int replicationHandlerCount, final int metaTransitionHandler,
      final PriorityFunction priority, final Abortable server, final int highPriorityLevel,
      final Configuration conf, final TransparentPointerScope runtimeScope) {
      try (final TransparentPointerScope tempScope = new TransparentPointerScope()) {
        return new Creator(
          SliceUtils.fromString(Strings.nullToEmpty(name), tempScope),
          handlerCount,
          priorityHandlerCount,
          replicationHandlerCount,
          metaTransitionHandler,
          priority,
          server,
          highPriorityLevel,
          conf,
          runtimeScope
        );
      }
    }


    @Override
    public int createSchedulerInstance(final SliceU8 name, final SchedulerArgs schedulerArgs,
      final ArcVoid pluginCtx, final LoggerDrainBox loggerDrainBox, final SchedulerPluginArcBox schedulerPlugin) {
      final SimpleRpcScheduler scheduler = this.runtimeScope.attachTransparent(new SimpleRpcScheduler(
        name,
        this.conf,
        this.handlerCount,
        this.priorityHandlerCount,
        this.replicationHandlerCount,
        this.metaTransitionHandler,
        this.priority,
        this.server,
        this.highPriorityLevel,
        this.runtimeScope,
        schedulerArgs,
        loggerDrainBox,
        pluginCtx
      ));
      scheduler.saturateArcBox(schedulerPlugin);
      return 0;
    }
  }

}
