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

import java.util.Locale;
import com.engineersbox.kairos.ArcVoid;
import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.LoggerDrainBox;
import com.engineersbox.kairos.OptionalGenericError;
import com.engineersbox.kairos.SchedulerArgs;
import com.engineersbox.kairos.SchedulerPlugin;
import com.engineersbox.kairos.SchedulerPluginContainer;
import com.engineersbox.kairos.Task;
import com.engineersbox.kairos.WorkerGroupBox;
import com.engineersbox.kairos.WorkerGroupProviderBox;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.OptionalUtils;
import com.engineersbox.kairos.utils.SliceUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.base.Strings;

/**
 * Runs the CallRunners passed here via {@link #dispatch(CallRunner)}. Subclass and add particular
 * scheduling behavior.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.COPROC, HBaseInterfaceAudience.PHOENIX })
@InterfaceStability.Evolving
public abstract class RpcExecutor extends SchedulerPlugin {
  private static final Logger LOGGER = LoggerFactory.getLogger(RpcExecutor.class);

  public static final String CALL_QUEUE_HANDLER_FACTOR_CONF_KEY =
    "hbase.ipc.server.callqueue.handler.factor";

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

  public static final String PLUGGABLE_CALL_QUEUE_CLASS_NAME =
    "hbase.ipc.server.callqueue.pluggable.queue.class.name";
  public static final String PLUGGABLE_CALL_QUEUE_WITH_FAST_PATH_ENABLED =
    "hbase.ipc.server.callqueue.pluggable.queue.fast.path.enabled";

  protected volatile int currentQueueLimit;
  protected WorkerGroupBox workerGroupBox;
  protected RpcHandlerPool workerGroup;

  private String name;

  private final TransparentPointerScope ptrScope;

  public RpcExecutor(final String name, final TransparentPointerScope ptrScope, final SchedulerArgs schedulerArgs,
    final LoggerDrainBox loggerDrain, final ArcVoid pluginCtx) {
    super(SliceUtils.fromString(Strings.nullToEmpty(name), ptrScope), schedulerArgs, loggerDrain, pluginCtx);
    this.name = Strings.nullToEmpty(name);
    this.ptrScope = ptrScope;
    final int result = bindWorkers(null, schedulerArgs.worker_group_provider());
    if (result != Kairos.GenericError.GENERIC_ERROR_SUCCESS.value) {
      throw new IllegalStateException("Failed to created RpcExecutor");
    }
  }

  public void start(final int port) {
    this.workerGroup.startHandlers(port);
  }

  public void stop() {
    this.workerGroup.stop();
  }

  @Override
  public boolean submit(final SchedulerPluginContainer schedulerPluginContainer, final Task task,
    final long operation_id) {
    return dispatch(task.runnable().container().instance().instance().getPointer(CallRunner.class),
      operation_id);
  }

  /** Add the request to the executor queue */
  public abstract boolean dispatch(final CallRunner callTask, final long operation_id);

  @Override
  public int bindWorkers(final SchedulerPluginContainer schedulerPluginContainer,
    final WorkerGroupProviderBox workerGroupProviderBox) {
    this.workerGroupBox = this.ptrScope.attachTransparent(new WorkerGroupBox());
    final int result = workerGroupProviderBox.vtbl().provide().call(
      workerGroupProviderBox.container(),
      0,
      this.workerGroupBox
    );
    if (result != Kairos.GenericError.GENERIC_ERROR_SUCCESS.value) {
      LOGGER.error("Unable to retrieve worker group for RpcExecutor {}", name);
      return result;
    }
    this.workerGroup = this.workerGroupBox.container().instance().instance().getPointer(RpcHandlerPool.class);
    return Kairos.GenericError.GENERIC_ERROR_SUCCESS.value;
  }

  @Override
  public OptionalGenericError deinit(final SchedulerPluginContainer schedulerPluginContainer) {
    stop();
    this.ptrScope.deallocate();
    return OptionalUtils.noneGenericError();
  }

  public long getNumGeneralCallsDropped() {
  return this.workerGroup.numGeneralCallsDropped.longValue();
  }

  public long getNumLifoModeSwitches() {
    return this.workerGroup.numLifoModeSwitches.longValue();
  }

  public int getActiveHandlerCount() {
    return this.workerGroup.activeHandlerCount.get();
  }

  public int getActiveWriteHandlerCount() {
    return 0;
  }

  public int getActiveReadHandlerCount() {
    return 0;
  }

  public int getActiveScanHandlerCount() {
    return 0;
  }


  public int getReadQueueLength() {
    return 0;
  }

  public int getScanQueueLength() {
    return 0;
  }

  public int getWriteQueueLength() {
    return 0;
  }

  public String getName() {
    return this.name;
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
    final int queueLimit = this.workerGroup.currentQueueLimit;
    final OptionalGenericError result = this.workerGroup.resize(
      this.workerGroupBox.container(),
      conf.getInt(configKey, queueLimit)
    );
    if (result.tag().intern() == Kairos.OptionalGenericErrorTag.Some_GenericError) {
      throw new IllegalStateException("Unable to resize worker queues: " + result.some().intern().name());
    }
  }

  public void onConfigurationChange(final Configuration conf) {
    this.workerGroup.onConfigurationChange(conf);
  }
}
