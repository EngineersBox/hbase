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
import com.engineersbox.kairos.SchedulerPlugin;
import com.engineersbox.kairos.SchedulerPluginContainer;
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
//  protected WorkerGroupBox workerGroupBox;
//  protected RpcHandlerPool workerGroup;

  protected String name;
  protected final TransparentPointerScope ptrScope;

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

  /** Add the request to the executor queue */
//  public abstract boolean dispatch(final CallRunner callOperation, final long operation_id);

  @Override
  public OptionalGenericError deinit(final SchedulerPluginContainer schedulerPluginContainer) {
    stop(schedulerPluginContainer);
    this.ptrScope.deallocate();
    return OptionalUtils.noneGenericError();
  }

  public abstract long getNumGeneralCallsDropped();

  public abstract long getNumLifoModeSwitches();

  public abstract int getActiveHandlerCount();

  public int getActiveWriteHandlerCount() {
    return 0;
  }

  public int getActiveReadHandlerCount() {
    return 0;
  }

  public int getActiveScanHandlerCount() {
    return 0;
  }

  public abstract int getQueueLength();

  public int getReadQueueLength() {
    return 0;
  }

  public int getScanQueueLength() {
    return 0;
  }

  public int getWriteQueueLength() {
    return 0;
  }

  public abstract Map<String, Long> getCallQueueCountsSummary();
  public abstract Map<String, Long> getCallQueueSizeSummary();

  public String getName() {
    return this.name;
  }

  public abstract void resizeQueues(final Configuration conf);

  public abstract void onConfigurationChange(final Configuration conf);

  public static float getCallQueuesHandlersFactor(final Configuration conf) {
    float callQueuesHandlersFactor = conf.getFloat(CALL_QUEUE_HANDLER_FACTOR_CONF_KEY, 0.1f);
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
    return callQueuesHandlersFactor;
  }

  protected int computeNumCallQueues(final int handlerCount, final float callQueuesHandlersFactor) {
    return Math.max(1, Math.round(handlerCount * callQueuesHandlersFactor));
  }
}
