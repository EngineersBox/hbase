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

import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.SchedulerPluginContainer;
import com.engineersbox.kairos.SliceU8;
import com.engineersbox.kairos.Task;
import com.engineersbox.kairos.TaskMetadataOrKairosResult;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.SliceUtils;
import com.engineersbox.kairos.utils.TaskUtils;
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
  private final RpcExecutor callExecutor;
  private final SliceU8 callExecutorName;
  private final RpcExecutor priorityExecutor;
  private final SliceU8 priorityExecutorName;
  private final RpcExecutor replicationExecutor;
  private final SliceU8 replicationExecutorName;

  /**
   * This executor is only for meta transition
   */
  private final RpcExecutor metaTransitionExecutor;
  private final SliceU8 metaTransitionExecutorName;

  private final RpcExecutor bulkloadExecutor;
  private final SliceU8 bulkloadExecutorName;

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
  public SimpleRpcScheduler(Configuration conf, int handlerCount, int priorityHandlerCount,
    int replicationHandlerCount, int metaTransitionHandler, PriorityFunction priority,
    Abortable server, int highPriorityLevel, final TransparentPointerScope ptrScope) {
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
      callExecutor = new FastPathRWQueueRpcExecutor("default.FPRWQ", Math.max(2, handlerCount),
        maxQueueLength, priority, conf, server);
    } else {
      if (
        RpcHandlerPool.isFifoQueueType(callQueueType) || RpcHandlerPool.isCodelQueueType(callQueueType)
          || RpcHandlerPool.isPluggableQueueWithFastPath(callQueueType, conf)
      ) {
        callExecutor = new FastPathBalancedQueueRpcExecutor("default.FPBQ", handlerCount,
          maxQueueLength, priority, conf, server);
      } else {
        callExecutor = new BalancedQueueRpcExecutor("default.BQ", handlerCount, maxQueueLength,
          priority, conf, server);
      }
    }
    this.callExecutorName = SliceUtils.fromString(this.callExecutor.getName(), this.ptrScope);

    float metaCallqReadShare =
      conf.getFloat(MetaRWQueueRpcExecutor.META_CALL_QUEUE_READ_SHARE_CONF_KEY,
        MetaRWQueueRpcExecutor.DEFAULT_META_CALL_QUEUE_READ_SHARE);
    if (metaCallqReadShare > 0) {
      // different read/write handler for meta, at least 1 read handler and 1 write handler
      this.priorityExecutor = new MetaRWQueueRpcExecutor("priority.RWQ",
        Math.max(2, priorityHandlerCount), maxPriorityQueueLength, priority, conf, server);
      this.priorityExecutorName = SliceUtils.fromString(this.priorityExecutor.getName(), this.ptrScope);
    } else if (priorityHandlerCount > 0) {
      // Create 2 queues to help priorityExecutor be more scalable.
      this.priorityExecutor =  new FastPathBalancedQueueRpcExecutor("priority.FPBQ", priorityHandlerCount,
        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, maxPriorityQueueLength, priority, conf,
        abortable);
      this.priorityExecutorName = SliceUtils.fromString(this.priorityExecutor.getName(), this.ptrScope);
    } else {
      this.priorityExecutor = null;
      this.priorityExecutorName = null;
    }
    if (replicationHandlerCount > 0) {
      this.replicationExecutor = new FastPathBalancedQueueRpcExecutor("replication.FPBQ", replicationHandlerCount,
        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, maxReplicationQueueLength, priority, conf,
        abortable);
      this.replicationExecutorName = SliceUtils.fromString(this.replicationExecutor.getName(), this.ptrScope);
    } else {
      this.replicationExecutor = null;
      this.replicationExecutorName = null;
    }

    if (metaTransitionHandler > 0) {
      this.metaTransitionExecutor = new FastPathBalancedQueueRpcExecutor("metaPriority.FPBQ", metaTransitionHandler,
        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, maxPriorityQueueLength, priority, conf,
        abortable);
      this.metaTransitionExecutorName = SliceUtils.fromString(this.metaTransitionExecutor.getName(), this.ptrScope);
    } else {
      this.metaTransitionExecutor = null;
      this.metaTransitionExecutorName = null;
    }
    if (bulkLoadHandlerCount > 0) {
      this.bulkloadExecutor = new FastPathBalancedQueueRpcExecutor("bulkLoad.FPBQ", bulkLoadHandlerCount,
        RpcExecutor.CALL_QUEUE_TYPE_FIFO_CONF_VALUE, maxBulkLoadQueueLength, priority, conf,
        abortable);
      this.bulkloadExecutorName = SliceUtils.fromString(this.bulkloadExecutor.getName(), this.ptrScope);
    } else {
      this.bulkloadExecutor = null;
      this.bulkloadExecutorName = null;
    }
  }

  public SimpleRpcScheduler(Configuration conf, int handlerCount, int priorityHandlerCount,
    int replicationHandlerCount, PriorityFunction priority, int highPriorityLevel,
    final TransparentPointerScope ptrScope) {
    this(conf, handlerCount, priorityHandlerCount, replicationHandlerCount, 0, priority, null,
      highPriorityLevel, ptrScope);
  }

  /**
   * Resize call queues;
   * @param conf new configuration
   */
  @Override
  public void onConfigurationChange(Configuration conf) {
    callExecutor.resizeQueues(conf);
    if (priorityExecutor != null) {
      priorityExecutor.resizeQueues(conf);
    }
    if (replicationExecutor != null) {
      replicationExecutor.resizeQueues(conf);
    }
    if (metaTransitionExecutor != null) {
      metaTransitionExecutor.resizeQueues(conf);
    }
    if (bulkloadExecutor != null) {
      bulkloadExecutor.resizeQueues(conf);
    }

    String callQueueType =
      conf.get(RpcExecutor.CALL_QUEUE_TYPE_CONF_KEY, RpcExecutor.CALL_QUEUE_TYPE_CONF_DEFAULT);
    if (
      RpcHandlerPool.isCodelQueueType(callQueueType) || RpcHandlerPool.isPluggableQueueType(callQueueType)
    ) {
      callExecutor.onConfigurationChange(conf);
    }
  }

  @Override
  public void init(Context context) {
    this.port = context.getListenerAddress().getPort();
  }

  @Override
  public void start(final SchedulerPluginContainer schedulerPluginContainer) {
    callExecutor.start();
    if (priorityExecutor != null) {
      priorityExecutor.start(schedulerPluginContainer);
    }
    if (replicationExecutor != null) {
      replicationExecutor.start(schedulerPluginContainer);
    }
    if (metaTransitionExecutor != null) {
      metaTransitionExecutor.start(schedulerPluginContainer);
    }
    if (bulkloadExecutor != null) {
      bulkloadExecutor.start(schedulerPluginContainer);
    }

  }

  @Override
  public void stop(final SchedulerPluginContainer schedulerPluginContainer) {
    callExecutor.stop(schedulerPluginContainer);
    if (priorityExecutor != null) {
      priorityExecutor.stop(schedulerPluginContainer);
    }
    if (replicationExecutor != null) {
      replicationExecutor.stop(schedulerPluginContainer);
    }
    if (metaTransitionExecutor != null) {
      metaTransitionExecutor.stop(schedulerPluginContainer);
    }
    if (bulkloadExecutor != null) {
      bulkloadExecutor.stop(schedulerPluginContainer);
    }
  }

  @Override
  public boolean submit(final SchedulerPluginContainer schedulerPluginContainer, final Task task,
    final long operation_id) {
    final CallRunner callRunner = task.runnable().container().instance().instance().getPointer(CallRunner.class);
    RpcCall call = callRunner.getRpcCall();
    int level =
      priority.getPriority(call.getHeader(), call.getParam(), call.getRequestUser().orElse(null));
    if (level == HConstants.PRIORITY_UNSET) {
      level = HConstants.NORMAL_QOS;
    }
    TaskMetadataOrKairosResult result;
    if (
      metaTransitionExecutor != null
        && level == MasterAnnotationReadingPriorityFunction.META_TRANSITION_QOS
    ) {
      result = Kairos.submit(Scheduling.KAIROS, this.metaTransitionExecutorName, task);
    } else if (priorityExecutor != null && level > highPriorityLevel) {
      result = Kairos.submit(Scheduling.KAIROS, this.priorityExecutorName, task);
    } else if (replicationExecutor != null && level == HConstants.REPLICATION_QOS) {
      result = Kairos.submit(Scheduling.KAIROS, this.replicationExecutorName, task);
    } else if (bulkloadExecutor != null && level == HConstants.BULKLOAD_QOS) {
      result = Kairos.submit(Scheduling.KAIROS, this.bulkloadExecutorName, task);
    } else {
      result = Kairos.submit(Scheduling.KAIROS, this.callExecutorName, task);
    }
    if (result.tag().intern() == Kairos.TaskMetadataOrKairosResultTag.Err_TaskMetadata__KairosResult) {
      LOGGER.error("Failed to dispatch task {}", operation_id);
      return true;
    }
    return false;
  }

  @Override
  public int getMetaPriorityQueueLength() {
    return metaTransitionExecutor == null ? 0 : metaTransitionExecutor.getQueueLength();
  }

  @Override
  public int getGeneralQueueLength() {
    return callExecutor.getQueueLength();
  }

  @Override
  public int getPriorityQueueLength() {
    return priorityExecutor == null ? 0 : priorityExecutor.getQueueLength();
  }

  @Override
  public int getReplicationQueueLength() {
    return replicationExecutor == null ? 0 : replicationExecutor.getQueueLength();
  }

  @Override
  public int getBulkLoadQueueLength() {
    return bulkloadExecutor == null ? 0 : bulkloadExecutor.getQueueLength();
  }

  @Override
  public int getActiveRpcHandlerCount() {
    return callExecutor.getActiveHandlerCount() + getActivePriorityRpcHandlerCount()
      + getActiveReplicationRpcHandlerCount() + getActiveMetaPriorityRpcHandlerCount()
      + getActiveBulkLoadRpcHandlerCount();
  }

  @Override
  public int getActiveMetaPriorityRpcHandlerCount() {
    return (metaTransitionExecutor == null ? 0 : metaTransitionExecutor.getActiveHandlerCount());
  }

  @Override
  public int getActiveGeneralRpcHandlerCount() {
    return callExecutor.getActiveHandlerCount();
  }

  @Override
  public int getActivePriorityRpcHandlerCount() {
    return (priorityExecutor == null ? 0 : priorityExecutor.getActiveHandlerCount());
  }

  @Override
  public int getActiveReplicationRpcHandlerCount() {
    return (replicationExecutor == null ? 0 : replicationExecutor.getActiveHandlerCount());
  }

  @Override
  public int getActiveBulkLoadRpcHandlerCount() {
    return bulkloadExecutor == null ? 0 : bulkloadExecutor.getActiveHandlerCount();
  }

  @Override
  public long getNumGeneralCallsDropped() {
    return callExecutor.getNumGeneralCallsDropped();
  }

  @Override
  public long getNumLifoModeSwitches() {
    return callExecutor.getNumLifoModeSwitches();
  }

  @Override
  public int getWriteQueueLength() {
    return callExecutor.getWriteQueueLength();
  }

  @Override
  public int getReadQueueLength() {
    return callExecutor.getReadQueueLength();
  }

  @Override
  public int getScanQueueLength() {
    return callExecutor.getScanQueueLength();
  }

  @Override
  public int getActiveWriteRpcHandlerCount() {
    return callExecutor.getActiveWriteHandlerCount();
  }

  @Override
  public int getActiveReadRpcHandlerCount() {
    return callExecutor.getActiveReadHandlerCount();
  }

  @Override
  public int getActiveScanRpcHandlerCount() {
    return callExecutor.getActiveScanHandlerCount();
  }

  @Override public PointerScope getPointerScope() {
    return null;
  }

  @Override
  public CallQueueInfo getCallQueueInfo() {
    String queueName;

    CallQueueInfo callQueueInfo = new CallQueueInfo();

    if (null != callExecutor) {
      queueName = "Call Queue";
      callQueueInfo.setCallMethodCount(queueName, callExecutor.getCallQueueCountsSummary());
      callQueueInfo.setCallMethodSize(queueName, callExecutor.getCallQueueSizeSummary());
    }

    if (null != priorityExecutor) {
      queueName = "Priority Queue";
      callQueueInfo.setCallMethodCount(queueName, priorityExecutor.getCallQueueCountsSummary());
      callQueueInfo.setCallMethodSize(queueName, priorityExecutor.getCallQueueSizeSummary());
    }

    if (null != replicationExecutor) {
      queueName = "Replication Queue";
      callQueueInfo.setCallMethodCount(queueName, replicationExecutor.getCallQueueCountsSummary());
      callQueueInfo.setCallMethodSize(queueName, replicationExecutor.getCallQueueSizeSummary());
    }

    if (null != metaTransitionExecutor) {
      queueName = "Meta Transition Queue";
      callQueueInfo.setCallMethodCount(queueName,
        metaTransitionExecutor.getCallQueueCountsSummary());
      callQueueInfo.setCallMethodSize(queueName, metaTransitionExecutor.getCallQueueSizeSummary());
    }

    if (null != bulkloadExecutor) {
      queueName = "BulkLoad Queue";
      callQueueInfo.setCallMethodCount(queueName, bulkloadExecutor.getCallQueueCountsSummary());
      callQueueInfo.setCallMethodSize(queueName, bulkloadExecutor.getCallQueueSizeSummary());
    }

    return callQueueInfo;
  }

}
