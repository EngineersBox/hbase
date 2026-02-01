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

import com.engineersbox.kairos.OptionalGenericError;
import com.engineersbox.kairos.SchedulerPluginContainer;
import com.engineersbox.kairos.Task;
import com.engineersbox.kairos.WorkerGroupProviderBox;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.bytedeco.javacpp.PointerScope;

/**
 * Users of the hbase.region.server.rpc.scheduler.factory.class customization config can return an
 * implementation which extends this class in order to minimize impact of breaking interface
 * changes.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.COPROC, HBaseInterfaceAudience.PHOENIX })
@InterfaceStability.Evolving
public class DelegatingRpcScheduler extends RpcScheduler {
  protected RpcScheduler delegate;

  public DelegatingRpcScheduler(RpcScheduler delegate) {
    super(
      null,
      null,
      null,
      null
    );
    this.delegate = delegate;
  }

  @Override
  public void init(Context context) {
    delegate.init(context);
  }

  @Override
  public int getReplicationQueueLength() {
    return delegate.getReplicationQueueLength();
  }

  @Override
  public int getBulkLoadQueueLength() {
    return delegate.getBulkLoadQueueLength();
  }

  @Override
  public int getPriorityQueueLength() {
    return delegate.getPriorityQueueLength();
  }

  @Override
  public int getGeneralQueueLength() {
    return delegate.getGeneralQueueLength();
  }

  @Override
  public int getActiveRpcHandlerCount() {
    return delegate.getActiveRpcHandlerCount();
  }

  @Override
  public int getActiveGeneralRpcHandlerCount() {
    return delegate.getActiveGeneralRpcHandlerCount();
  }

  @Override
  public int getActivePriorityRpcHandlerCount() {
    return delegate.getActivePriorityRpcHandlerCount();
  }

  @Override
  public int getActiveReplicationRpcHandlerCount() {
    return delegate.getActiveReplicationRpcHandlerCount();
  }

  @Override
  public int getActiveBulkLoadRpcHandlerCount() {
    return delegate.getActiveBulkLoadRpcHandlerCount();
  }

  @Override
  public int bindWorkers(final SchedulerPluginContainer schedulerPluginContainer,
    final WorkerGroupProviderBox workerGroupProviderBox) {
    return this.delegate.bindWorkers(schedulerPluginContainer, workerGroupProviderBox);
  }

  @Override
  public OptionalGenericError deinit(final SchedulerPluginContainer schedulerPluginContainer) {
    return this.delegate.deinit(schedulerPluginContainer);
  }

  @Override
  public boolean submit(final SchedulerPluginContainer schedulerPluginContainer, final Task task,
    final long operation_id) {
    return this.delegate.submit(schedulerPluginContainer, task, operation_id);
  }

  @Override
  public void stop(final SchedulerPluginContainer schedulerPluginContainer) {
    this.delegate.stop(schedulerPluginContainer);
  }

  @Override
  public void start(final SchedulerPluginContainer schedulerPluginContainer) {
    this.delegate.start(schedulerPluginContainer);
  }

  @Override
  public int getActiveMetaPriorityRpcHandlerCount() {
    return delegate.getActiveMetaPriorityRpcHandlerCount();
  }

  @Override
  public int getMetaPriorityQueueLength() {
    return delegate.getMetaPriorityQueueLength();
  }

  @Override
  public long getNumGeneralCallsDropped() {
    return delegate.getNumGeneralCallsDropped();
  }

  @Override
  public long getNumLifoModeSwitches() {
    return delegate.getNumLifoModeSwitches();
  }

  @Override
  public int getWriteQueueLength() {
    return 0;
  }

  @Override
  public int getReadQueueLength() {
    return 0;
  }

  @Override
  public int getScanQueueLength() {
    return 0;
  }

  @Override
  public int getActiveWriteRpcHandlerCount() {
    return 0;
  }

  @Override
  public int getActiveReadRpcHandlerCount() {
    return 0;
  }

  @Override
  public int getActiveScanRpcHandlerCount() {
    return 0;
  }

  @Override
  public PointerScope getPointerScope() {
    return this.delegate.getPointerScope();
  }

  @Override
  public CallQueueInfo getCallQueueInfo() {
    return delegate.getCallQueueInfo();
  }
}
