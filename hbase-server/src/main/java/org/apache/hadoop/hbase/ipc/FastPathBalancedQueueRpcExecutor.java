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
import com.engineersbox.kairos.OptionalGenericError;
import com.engineersbox.kairos.OptionalWorkerGroupError;
import com.engineersbox.kairos.SchedulerArgs;
import com.engineersbox.kairos.SchedulerIDOrKairosResult;
import com.engineersbox.kairos.SchedulerPluginArcBox;
import com.engineersbox.kairos.SchedulerPluginContainer;
import com.engineersbox.kairos.SchedulerPluginCreator;
import com.engineersbox.kairos.SliceU8;
import com.engineersbox.kairos.Operation;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.OptionalUtils;
import com.engineersbox.kairos.utils.SliceUtils;
import com.google.common.base.Strings;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.executor.Scheduling;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Balanced queue executor with a fastpath. Because this is FIFO, it has no respect for ordering so
 * a fast path skipping the queuing of Calls if an Handler is available, is possible. Just pass the
 * Call direct to waiting Handler thread. Try to keep the hot Handlers bubbling rather than let them
 * go cold and lose context. Idea taken from Apace Kudu (incubating). See
 * https://gerrit.cloudera.org/#/c/2938/7/src/kudu/rpc/service_queue.h
 */
@InterfaceAudience.Private
public class FastPathBalancedQueueRpcExecutor extends BalancedQueueRpcExecutor {
  // Depends on default behavior of BalancedQueueRpcExecutor being FIFO!

  public FastPathBalancedQueueRpcExecutor(final String name, final int handlerCount,
    final TransparentPointerScope scope, final SchedulerArgs schedulerArgs,
    final LoggerDrainBox loggerDrain, final ArcVoid pluginCtx) {
    super(name, handlerCount, scope, schedulerArgs, loggerDrain, pluginCtx);
  }

  @Override
  public boolean submit(final SchedulerPluginContainer schedulerPluginContainer, final Operation task,
    final long operation_id) {
    final CallRunner callOperation = task.runnable().container().instance().instance().getPointer(
      CallRunner.class);
    final OptionalWorkerGroupError result = super.pool.assignDirect(
      super.workerGroupBox.container(),
      0,
      task.runnable(),
      task.context(),
      operation_id
    );
    if (result.tag().intern() == Kairos.OptionalWorkerGroupErrorTag.None_WorkerGroupError) {
      return true;
    } else if (result.some().intern() == Kairos.WorkerGroupError.WORKER_GROUP_ERROR_DIRECT_UNSUPPORTED) {
      super.submit(schedulerPluginContainer, task, operation_id);
    }
    return false;
  }

  public static SchedulerIDOrKairosResult newFastPathBalancedQueue(final String name, final int port,
    final int handlerCount, final int maxQueueLength, final Configuration conf, final PriorityFunction priority,
    final Abortable abortable, final TransparentPointerScope ptrScope) {
    try (final TransparentPointerScope tempScope = new TransparentPointerScope()) {
      final FastPathRpcHandlerPool.Provider wgProvider = tempScope.attachTransparent(
        new FastPathRpcHandlerPool.Provider(name, port, maxQueueLength, priority, conf,
          abortable, ptrScope));
      final Creator creator = Creator.newInstance(name, handlerCount, ptrScope);
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
  public static SchedulerIDOrKairosResult newFastPathBalancedQueue(final String name, final int port,
    final int handlerCount, final int maxQueueLength, final String queueType, final Configuration conf,
    final PriorityFunction priority,
    final Abortable abortable, final TransparentPointerScope ptrScope) {
    try (final TransparentPointerScope tempScope = new TransparentPointerScope()) {
      final FastPathRpcHandlerPool.Provider wgProvider = tempScope.attachTransparent(
        new FastPathRpcHandlerPool.Provider(name, port, maxQueueLength, queueType, priority, conf,
          abortable, ptrScope));
      final Creator creator = Creator.newInstance(name, handlerCount, ptrScope);
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
    private final int handlerCount;

    private final TransparentPointerScope runtimeScope;

    private Creator(final SliceU8 name, final int handlerCount, final TransparentPointerScope runtimeScope) {
      super(name);
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
          runtimeScope
        );
      }
    }

    @Override
    public int createSchedulerInstance(final SliceU8 name, final SchedulerArgs schedulerArgs,
      final ArcVoid pluginCtx, final LoggerDrainBox loggerDrainBox, final SchedulerPluginArcBox schedulerPlugin) {
      final FastPathBalancedQueueRpcExecutor executor = this.runtimeScope.attachTransparent(new FastPathBalancedQueueRpcExecutor(
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
