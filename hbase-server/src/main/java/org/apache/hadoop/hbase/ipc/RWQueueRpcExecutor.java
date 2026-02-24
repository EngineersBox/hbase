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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.engineersbox.kairos.ArcVoid;
import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.LoggerDrainBox;
import com.engineersbox.kairos.OptionalGenericError;
import com.engineersbox.kairos.OptionalWorkerGroupError;
import com.engineersbox.kairos.SchedulerArgs;
import com.engineersbox.kairos.SchedulerIDOrKairosResult;
import com.engineersbox.kairos.SchedulerPluginArcBox;
import com.engineersbox.kairos.SchedulerPluginContainer;
import com.engineersbox.kairos.SchedulerPluginCreator;
import com.engineersbox.kairos.SliceU8;
import com.engineersbox.kairos.Operation;
import com.engineersbox.kairos.WorkerGroupBox;
import com.engineersbox.kairos.WorkerGroupProviderBox;
import com.engineersbox.kairos.WorkerGroupProviderVTable;
import com.engineersbox.kairos.conversion.IntoBox;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.OptionalUtils;
import com.engineersbox.kairos.utils.SliceUtils;
import com.google.common.base.Strings;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.executor.Scheduling;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RPC Executor that uses different queues for reads and writes. With the options to use different
 * queues/executors for gets and scans. Each handler has its own queue and there is no stealing.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.COPROC, HBaseInterfaceAudience.PHOENIX })
@InterfaceStability.Evolving
public class RWQueueRpcExecutor extends RpcExecutor {
  private static final Logger LOGGER = LoggerFactory.getLogger(RWQueueRpcExecutor.class);

  public static final String CALL_QUEUE_READ_SHARE_CONF_KEY =
    "hbase.ipc.server.callqueue.read.ratio";
  public static final String CALL_QUEUE_SCAN_SHARE_CONF_KEY =
    "hbase.ipc.server.callqueue.scan.ratio";

  protected WorkerGroupBox writeWorkerGroupBox;
  protected RpcHandlerPool writePool;
  protected WorkerGroupBox readWorkerGroupBox;
  protected RpcHandlerPool readPool;
  protected WorkerGroupBox scanWorkerGroupBox;
  protected RpcHandlerPool scanPool;

  private int port;

  private final int writeHandlersCount;
  private final int readHandlersCount;
  private final int scanHandlersCount;
  private final int numScanQueues;

  public RWQueueRpcExecutor(final String name, final int port, final int handlerCount,
    final Configuration conf, final TransparentPointerScope scope, final SchedulerArgs schedulerArgs,
    final LoggerDrainBox loggerDrain, final ArcVoid pluginCtx) {
    super(name, scope, schedulerArgs, loggerDrain, pluginCtx);
    this.port = port;
    final float callqReadShare = getReadShare(conf);
    final float callqScanShare = getScanShare(conf);
    final int numCallQueues = computeNumCallQueues(
      handlerCount,
      RpcExecutor.getCallQueuesHandlersFactor(conf)
    );
    int numWriteQueues = calcNumWriters(numCallQueues, callqReadShare);
    this.writeHandlersCount = Math.max(numWriteQueues, calcNumWriters(handlerCount, callqReadShare));
    int readQueues = calcNumReaders(numCallQueues, callqReadShare);
    int readHandlers = Math.max(readQueues, calcNumReaders(handlerCount, callqReadShare));
    int scanHandlers = Math.max(0, (int) Math.floor(readHandlers * callqScanShare));
    int scanQueues =
      scanHandlers > 0 ? Math.max(1, (int) Math.floor(readQueues * callqScanShare)) : 0;
    if (scanQueues > 0) {
      // if scanQueues > 0, the handler count of read should > 0, then we make readQueues >= 1
      readQueues = Math.max(1, readQueues - scanQueues);
      readHandlers -= scanHandlers;
    }
    final int numReadQueues = readQueues;
    this.readHandlersCount = readHandlers;
    this.numScanQueues = scanQueues;
    this.scanHandlersCount = scanHandlers;
    bindWorkers(null, schedulerArgs.worker_group_provider());
    LOGGER.info(getName() + " writeQueues=" + numWriteQueues + " writeHandlers=" + writeHandlersCount
      + " readQueues=" + numReadQueues + " readHandlers=" + readHandlersCount + " scanQueues="
      + numScanQueues + " scanHandlers=" + scanHandlersCount);
  }

  @Override
  public int bindWorkers(final SchedulerPluginContainer schedulerPluginContainer,
    final WorkerGroupProviderBox workerGroupProviderBox) {
    final WorkerGroupProviderVTable.Provide provide = workerGroupProviderBox.vtbl().provide();
    final List<WorkerGroupBox> rollback = new ArrayList<>();
    // Write
    final WorkerGroupBox newWriteWorkerGroupBox = this.ptrScope.attachTransparent(new WorkerGroupBox());
    rollback.add(newWriteWorkerGroupBox);
    int result = provide.call(
      workerGroupProviderBox.container(),
      this.writeHandlersCount,
      newWriteWorkerGroupBox
    );
    if (result != Kairos.GenericError.GENERIC_ERROR_SUCCESS.value) {
      LOGGER.error("Unable to retrieve write worker group for RpcExecutor {}", this.name);
      rollback.forEach(this.ptrScope::detach);
      return result;
    }
    final RpcHandlerPool newWritePool = newWriteWorkerGroupBox.container().instance().instance().getPointer(RpcHandlerPool.class);
    // Read
    final WorkerGroupBox newReadWorkerGroupBox = this.ptrScope.attachTransparent(new WorkerGroupBox());
    rollback.add(newReadWorkerGroupBox);
    result = provide.call(
      workerGroupProviderBox.container(),
      this.readHandlersCount,
      newReadWorkerGroupBox
    );
    if (result != Kairos.GenericError.GENERIC_ERROR_SUCCESS.value) {
      LOGGER.error("Unable to retrieve read worker group for RpcExecutor {}", this.name);
      rollback.forEach(this.ptrScope::detach);
      return result;
    }
    final RpcHandlerPool newReadPool = newReadWorkerGroupBox.container().instance().instance().getPointer(RpcHandlerPool.class);
    // Scan
    final WorkerGroupBox newScanWorkerGroupBox = this.ptrScope.attachTransparent(new WorkerGroupBox());
    rollback.add(newScanWorkerGroupBox);
    result = provide.call(
      workerGroupProviderBox.container(),
      this.scanHandlersCount,
      newScanWorkerGroupBox
    );
    if (result != Kairos.GenericError.GENERIC_ERROR_SUCCESS.value) {
      LOGGER.error("Unable to retrieve scan worker group for RpcExecutor {}", this.name);
      rollback.forEach(this.ptrScope::detach);
      return result;
    }
    final RpcHandlerPool newScanPool = newScanWorkerGroupBox.container().instance().instance().getPointer(RpcHandlerPool.class);
    // Write
    this.ptrScope.detach(this.writeWorkerGroupBox);
    this.writeWorkerGroupBox = newWriteWorkerGroupBox;
    this.writePool = newWritePool;
    // Read
    this.ptrScope.detach(this.readWorkerGroupBox);
    this.readWorkerGroupBox = newReadWorkerGroupBox;
    this.readPool = newReadPool;
    // Scan
    this.ptrScope.detach(this.scanWorkerGroupBox);
    this.scanWorkerGroupBox = newScanWorkerGroupBox;
    this.scanPool = newScanPool;
    return Kairos.GenericError.GENERIC_ERROR_SUCCESS.value;
  }

  @Override
  protected int computeNumCallQueues(final int handlerCount, final float callQueuesHandlersFactor) {
    // at least 1 read queue and 1 write queue
    return Math.max(2, (int) Math.round(handlerCount * callQueuesHandlersFactor));
  }

  @Override
  public void start(SchedulerPluginContainer schedulerPluginContainer) {
    startHandlers();
  }

  @Override
  public void stop(SchedulerPluginContainer schedulerPluginContainer) {
    stopHandlers();
  }

  public void startHandlers() {
    this.writePool.startHandlers(".write", this.port);
    this.readPool.startHandlers(".read", this.port);
    if (this.numScanQueues > 0) {
      this.scanPool.startHandlers(".scan", this.port);
    }
  }

  public void stopHandlers() {
    this.writePool.stop();
    this.readPool.stop();
    this.scanPool.stop();
  }

  @Override
  public boolean submit(final SchedulerPluginContainer schedulerPluginContainer, final Operation task,
    final long operation_id) {
    final CallRunner callRunner = task.runnable().container().instance().instance().getPointer(CallRunner.class);
    if (callRunner.isWriteRequest()) {
      final OptionalWorkerGroupError result = this.writeWorkerGroupBox.vtbl().assign().call(
        this.writeWorkerGroupBox.container(),
        task.runnable(),
        task.context(),
        operation_id
      );
      if (result.tag().intern() == Kairos.OptionalWorkerGroupErrorTag.Some_WorkerGroupError) {
        LOGGER.error("Failed to submit write task with operation ID {}", operation_id);
        return false;
      }
    } else if (shouldDispatchToScanQueue(callRunner)) {
      final OptionalWorkerGroupError result = this.scanWorkerGroupBox.vtbl().assign().call(
        this.scanWorkerGroupBox.container(),
        task.runnable(),
        task.context(),
        operation_id
      );
      if (result.tag().intern() == Kairos.OptionalWorkerGroupErrorTag.Some_WorkerGroupError) {
        LOGGER.error("Failed to submit scan task with operation ID {}", operation_id);
        return false;
      }
    } else {
      final OptionalWorkerGroupError result = this.readWorkerGroupBox.vtbl().assign().call(
        this.readWorkerGroupBox.container(),
        task.runnable(),
        task.context(),
        operation_id
      );
      if (result.tag().intern() == Kairos.OptionalWorkerGroupErrorTag.Some_WorkerGroupError) {
        LOGGER.error("Failed to submit read task with operation ID {}", operation_id);
        return false;
      }
    }
    return true;
  }

  @Override
  public int getQueueLength() {
    return getWriteQueueLength()
      + getReadQueueLength()
      + getScanQueueLength();
  }

  @Override
  public int getWriteQueueLength() {
    return this.writePool.getQueueLength();
  }

  @Override
  public int getReadQueueLength() {
    return this.readPool.getQueueLength();
  }

  @Override
  public int getScanQueueLength() {
    return this.scanPool.getQueueLength();
  }

  @Override
  public long getNumGeneralCallsDropped() {
    return this.writePool.numGeneralCallsDropped.longValue()
      + this.readPool.numGeneralCallsDropped.longValue()
      + this.scanPool.numGeneralCallsDropped.longValue();
  }

  @Override public long getNumLifoModeSwitches() {
    return this.writePool.numLifoModeSwitches.longValue()
      + this.readPool.numLifoModeSwitches.longValue()
      + this.scanPool.numLifoModeSwitches.longValue();
  }

  @Override
  public int getActiveHandlerCount() {
    return getActiveWriteHandlerCount()
      + getActiveReadHandlerCount()
      + getActiveScanHandlerCount();
  }

  @Override
  public int getActiveWriteHandlerCount() {
    return this.writePool.activeHandlerCount.get();
  }

  @Override
  public int getActiveReadHandlerCount() {
    return this.readPool.activeHandlerCount.get();
  }

  @Override
  public int getActiveScanHandlerCount() {
    return this.scanPool.activeHandlerCount.get();
  }

  public Map<String, Long> getCallQueueCountsSummary() {
    final Map<String, Long> summary = new HashMap<>();
    summary.putAll(this.writePool.getCallQueueCountsSummary());
    summary.putAll(this.readPool.getCallQueueCountsSummary());
    summary.putAll(this.scanPool.getCallQueueCountsSummary());
    return summary;
  }

  public Map<String, Long> getCallQueueSizeSummary() {
    final Map<String, Long> summary = new HashMap<>();
    summary.putAll(this.writePool.getCallQueueSizeSummary());
    summary.putAll(this.readPool.getCallQueueSizeSummary());
    summary.putAll(this.scanPool.getCallQueueSizeSummary());
    return summary;
  }

  QueueBalancer getWriteBalancer() {
    return this.writePool.balancer;
  }

  QueueBalancer getReadBalancer() {
    return this.readPool.balancer;
  }

  QueueBalancer getScanBalancer() {
    return this.scanPool.balancer;
  }

  protected boolean shouldDispatchToScanQueue(final CallRunner task) {
    return numScanQueues > 0 && task.isScanRequest();
  }

  protected float getReadShare(final Configuration conf) {
    return conf.getFloat(CALL_QUEUE_READ_SHARE_CONF_KEY, 0);
  }

  protected float getScanShare(final Configuration conf) {
    return conf.getFloat(CALL_QUEUE_SCAN_SHARE_CONF_KEY, 0);
  }

  /*
   * Calculate the number of writers based on the "total count" and the read share. You'll get at
   * least one writer.
   */
  private static int calcNumWriters(final int count, final float readShare) {
    return Math.max(1, count - Math.max(1, (int) Math.round(count * readShare)));
  }

  /*
   * Calculate the number of readers based on the "total count" and the read share. You'll get at
   * least one reader.
   */
  private static int calcNumReaders(final int count, final float readShare) {
    return count - calcNumWriters(count, readShare);
  }

  @Override
  public void resizeQueues(final Configuration conf) {
    this.writePool.resizeQueues(conf);
    this.readPool.resizeQueues(conf);
    this.scanPool.resizeQueues(conf);
  }

  @Override
  public void onConfigurationChange(final Configuration conf) {
    this.writePool.onConfigurationChange(conf);
    this.readPool.onConfigurationChange(conf);
    this.scanPool.onConfigurationChange(conf);
  }

  public static SchedulerIDOrKairosResult newRWQueue(final String name, final int port,
    final int handlerCount, final Configuration conf, final IntoBox<WorkerGroupProviderBox> wgProvider,
    final TransparentPointerScope ptrScope) {
    try (final TransparentPointerScope tempScope = new TransparentPointerScope()) {
      final Creator creator = Creator.newInstance(name, port, handlerCount, conf, ptrScope);
      return Kairos.createSchedulerInstance(
        Scheduling.KAIROS,
        SliceUtils.fromString(name, tempScope),
        ptrScope.attachTransparent(creator.intoDescriptor()),
        tempScope.attachTransparent(wgProvider.intoBox()),
        Kairos.newNoopLoggerDrain(),
        OptionalUtils.noneSchedulerBootstrapFn()
      );
    }
  }

  public static class Creator extends SchedulerPluginCreator {

    private final String name;
    private final int port;
    private final int handlerCount;
    private final Configuration conf;

    private final TransparentPointerScope runtimeScope;

    private Creator(final SliceU8 name, final int port, final int handlerCount,
      final Configuration conf, final TransparentPointerScope runtimeScope) {
      super(name);
      this.name = SliceUtils.intoString(name);
      this.port = port;
      this.handlerCount = handlerCount;
      this.conf = conf;
      this.runtimeScope = runtimeScope;
    }

    public static Creator newInstance(final String name, final int port, final int handlerCount,
      final Configuration conf, final TransparentPointerScope runtimeScope) {
      try (final TransparentPointerScope tempScope = new TransparentPointerScope()) {
        return new Creator(
          SliceUtils.fromString(Strings.nullToEmpty(name), tempScope),
          port,
          handlerCount,
          conf,
          runtimeScope
        );
      }
    }

    @Override
    public int createSchedulerInstance(final SliceU8 name, final SchedulerArgs schedulerArgs,
      final ArcVoid pluginCtx, final LoggerDrainBox loggerDrainBox, final SchedulerPluginArcBox schedulerPlugin) {
      final RWQueueRpcExecutor executor = this.runtimeScope.attachTransparent(new RWQueueRpcExecutor(
        this.name,
        this.port,
        this.handlerCount,
        this.conf,
        new TransparentPointerScope(),
        schedulerArgs,
        loggerDrainBox,
        pluginCtx
      ));
      executor.saturateArcBox(schedulerPlugin);
      return 0;
    }
  }
}
