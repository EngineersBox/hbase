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

import java.util.Map;
import com.engineersbox.kairos.ArcVoid;
import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.LoggerDrainBox;
import com.engineersbox.kairos.OptionalGenericError;
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
 * An {@link RpcExecutor} that will balance requests evenly across all its queues, but still remains
 * efficient with a single queue via an inlinable queue balancing mechanism. Defaults to FIFO but
 * you can pass an alternate queue class to use.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.COPROC, HBaseInterfaceAudience.PHOENIX })
@InterfaceStability.Evolving
public class BalancedQueueRpcExecutor extends RpcExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(BalancedQueueRpcExecutor.class);

  private final int handlerCount;
  protected WorkerGroupBox workerGroupBox;
  protected RpcHandlerPool pool;

  public BalancedQueueRpcExecutor(final String name, final int handlerCount,
    final TransparentPointerScope ptrScope, final SchedulerArgs schedulerArgs,
    final LoggerDrainBox loggerDrain, final ArcVoid pluginCtx) {
    super(name, ptrScope, schedulerArgs, loggerDrain, pluginCtx);
    this.handlerCount = handlerCount;
    bindWorkers(null, schedulerArgs.worker_group_provider());
  }

  @Override
  public int bindWorkers(final SchedulerPluginContainer schedulerPluginContainer,
    final WorkerGroupProviderBox workerGroupProviderBox) {
    final WorkerGroupProviderVTable.Provide provide = workerGroupProviderBox.vtbl().provide();
    final WorkerGroupBox newWorkerGroupBox = this.ptrScope.attachTransparent(new WorkerGroupBox());
    int result = provide.call(
      workerGroupProviderBox.container(),
      this.pool != null ? this.pool.activeHandlerCount.get() : this.handlerCount,
      newWorkerGroupBox
    );
    if (result != Kairos.GenericError.GENERIC_ERROR_SUCCESS.value) {
      LOGGER.error("Unable to retrieve write worker group for RpcExecutor {}", this.name);
      this.ptrScope.detach(newWorkerGroupBox);
      return result;
    }
    final RpcHandlerPool newPool = newWorkerGroupBox.container().instance().instance().getPointer(RpcHandlerPool.class);
    this.ptrScope.detach(this.workerGroupBox);
    this.workerGroupBox = newWorkerGroupBox;
    this.pool = newPool;
    return Kairos.GenericError.GENERIC_ERROR_SUCCESS.value;
  }

  @Override
  public boolean submit(final SchedulerPluginContainer schedulerPluginContainer, final Operation task,
    final long operation_id) {
    final OptionalGenericError result = this.workerGroupBox.vtbl().assign().call(
      this.workerGroupBox.container(),
      task.runnable(),
      task.context(),
      operation_id
    );
    if (result.tag().intern() == Kairos.OptionalGenericErrorTag.Some_GenericError) {
      LOGGER.error("Failed to submit write task with operation ID {}", operation_id);
      return false;
    }
    return true;
  }

  @Override
  public long getNumGeneralCallsDropped() {
    return this.pool.numGeneralCallsDropped.longValue();
  }

  @Override
  public long getNumLifoModeSwitches() {
    return this.pool.numLifoModeSwitches.longValue();
  }

  @Override
  public int getActiveHandlerCount() {
    return this.pool.activeHandlerCount.get();
  }

  @Override
  public int getQueueLength() {
    return this.pool.getQueueLength();
  }

  @Override
  public Map<String, Long> getCallQueueCountsSummary() {
    return this.pool.getCallQueueCountsSummary();
  }

  @Override
  public Map<String, Long> getCallQueueSizeSummary() {
    return this.pool.getCallQueueCountsSummary();
  }

  @Override
  public void resizeQueues(final Configuration conf) {
    this.pool.resizeQueues(conf);
  }

  @Override
  public void onConfigurationChange(final Configuration conf) {
    this.pool.onConfigurationChange(conf);
  }

  public static SchedulerIDOrKairosResult newBalancedQueue(final String name, final int handlerCount,
    final IntoBox<WorkerGroupProviderBox> wgProvider, final TransparentPointerScope ptrScope) {
    try (final TransparentPointerScope tempScope = new TransparentPointerScope()) {
      final Creator creator = Creator.newInstance(name, handlerCount, ptrScope);
      return Kairos.runSchedulerInstance(Scheduling.KAIROS,
        SliceUtils.fromString(name, tempScope),
        ptrScope.attachTransparent(creator.intoDescriptor()),
        tempScope.attachTransparent(wgProvider.intoBox()),
        Kairos.newDummyLoggerDrain()
      );
    }
  }

  public static class Creator extends SchedulerPluginCreator {

    private final String name;
    private final int handlerCount;

    private final TransparentPointerScope runtimeScope;

    private Creator(final SliceU8 name, final int handlerCount, final SliceU8 description,
      final TransparentPointerScope runtimeScope) {
      super(name, description);
      this.name = SliceUtils.intoString(name);
      this.handlerCount = handlerCount;
      this.runtimeScope = runtimeScope;
    }

    public static Creator newInstance(final String name, final int handlerCount,
      final TransparentPointerScope runtimeScope) {
      try (final TransparentPointerScope tempScope = new TransparentPointerScope()) {
        return new Creator(
          SliceUtils.fromString(Strings.nullToEmpty(name), tempScope),
          handlerCount,
          SliceUtils.fromString("", tempScope),
          runtimeScope
        );
      }
    }

    @Override
    public int createSchedulerInstance(final SliceU8 name, final SchedulerArgs schedulerArgs,
      final ArcVoid pluginCtx, final LoggerDrainBox loggerDrainBox, final SchedulerPluginArcBox schedulerPlugin) {
      final BalancedQueueRpcExecutor executor = this.runtimeScope.attachTransparent(new BalancedQueueRpcExecutor(
        this.name,
        this.handlerCount,
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
