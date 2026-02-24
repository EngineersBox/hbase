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
import com.engineersbox.kairos.SchedulerArgs;
import com.engineersbox.kairos.SchedulerIDOrKairosResult;
import com.engineersbox.kairos.SchedulerPluginArcBox;
import com.engineersbox.kairos.SchedulerPluginCreator;
import com.engineersbox.kairos.SliceU8;
import com.engineersbox.kairos.WorkerGroupProviderBox;
import com.engineersbox.kairos.conversion.IntoBox;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.OptionalUtils;
import com.engineersbox.kairos.utils.SliceUtils;
import com.google.common.base.Strings;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.executor.Scheduling;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * RPC Executor that uses different queues for reads and writes for meta.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class MetaRWQueueRpcExecutor extends RWQueueRpcExecutor {
  public static final String META_CALL_QUEUE_READ_SHARE_CONF_KEY =
    "hbase.ipc.server.metacallqueue.read.ratio";
  public static final String META_CALL_QUEUE_SCAN_SHARE_CONF_KEY =
    "hbase.ipc.server.metacallqueue.scan.ratio";
  public static final float DEFAULT_META_CALL_QUEUE_READ_SHARE = 0.9f;

  public MetaRWQueueRpcExecutor(final String name, final int port, final int handlerCount,
    final Configuration conf, final TransparentPointerScope scope, final SchedulerArgs schedulerArgs,
    final LoggerDrainBox loggerDrain, final ArcVoid pluginCtx) {
    super(name, port, handlerCount, conf, scope, schedulerArgs, loggerDrain, pluginCtx);
  }

  @Override
  protected float getReadShare(final Configuration conf) {
    return conf.getFloat(META_CALL_QUEUE_READ_SHARE_CONF_KEY, DEFAULT_META_CALL_QUEUE_READ_SHARE);
  }

  @Override
  protected float getScanShare(final Configuration conf) {
    return conf.getFloat(META_CALL_QUEUE_SCAN_SHARE_CONF_KEY, 0);
  }

  public static SchedulerIDOrKairosResult newMetaRWQueue(final String name, final int port,
    final int handlerCount, final Configuration conf, final IntoBox<WorkerGroupProviderBox> wgProvider,
    final TransparentPointerScope ptrScope) {
    try (final TransparentPointerScope tempScope = new TransparentPointerScope()) {
      final Creator creator = Creator.newInstance(name, port, handlerCount, conf, ptrScope);
      return Kairos.createSchedulerInstance(Scheduling.KAIROS,
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
      final MetaRWQueueRpcExecutor executor = this.runtimeScope.attachTransparent(new MetaRWQueueRpcExecutor(
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
