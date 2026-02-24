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

import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.engineersbox.kairos.ArcVoid;
import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.LoggerDrainBox;
import com.engineersbox.kairos.Operation;
import com.engineersbox.kairos.OptionalGenericError;
import com.engineersbox.kairos.SchedulerArgs;
import com.engineersbox.kairos.SchedulerIDOrKairosResult;
import com.engineersbox.kairos.SchedulerPluginArcBox;
import com.engineersbox.kairos.SchedulerPluginContainer;
import com.engineersbox.kairos.SchedulerPluginCreator;
import com.engineersbox.kairos.SliceU8;
import com.engineersbox.kairos.WorkerGroupProviderBox;
import com.engineersbox.kairos.conversion.IntoBox;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.OptionalUtils;
import com.engineersbox.kairos.utils.SliceUtils;
import com.google.common.base.Strings;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.executor.Scheduling;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.yetus.audience.InterfaceAudience;
import org.bytedeco.javacpp.PointerScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.hbase.thirdparty.io.netty.util.internal.StringUtil;

/**
 * A very simple {@code }RpcScheduler} that serves incoming requests in order. This can be used for
 * HMaster, where no prioritization is needed.
 */
@InterfaceAudience.Private
public class FifoRpcScheduler extends RpcScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(FifoRpcScheduler.class);
  protected final int port;
  protected final int handlerCount;
  protected final int maxQueueLength;
  protected final AtomicInteger queueSize = new AtomicInteger(0);
  protected ThreadPoolExecutor executor;
  protected final TransparentPointerScope ptrScope;

  public FifoRpcScheduler(final SliceU8 name, final SchedulerArgs args, final LoggerDrainBox loggerDrain,
    final ArcVoid pluginCtx, final TransparentPointerScope ptrScope, final Configuration conf,
    final int handlerCount, final int port) {
    super(name, args, loggerDrain, pluginCtx);
    this.port = port;
    this.handlerCount = handlerCount;
    this.maxQueueLength = conf.getInt(RpcScheduler.IPC_SERVER_MAX_CALLQUEUE_LENGTH,
      handlerCount * RpcServer.DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER);
    this.ptrScope = ptrScope;
  }

  @Override
  public void init(final Context context) {
    // no-op
  }

  private static class FifoCallRunner implements Runnable {
    private final CallRunner callRunner;

    FifoCallRunner(CallRunner cr) {
      this.callRunner = cr;
    }

    CallRunner getCallRunner() {
      return callRunner;
    }

    @Override
    public void run() {
      callRunner.run();
    }

  }

  @Override
  public int bindWorkers(SchedulerPluginContainer schedulerPluginContainer,
    WorkerGroupProviderBox workerGroupProviderBox) {
    return super.bindWorkers(schedulerPluginContainer, workerGroupProviderBox);
  }

  @Override
  public OptionalGenericError deinit(SchedulerPluginContainer schedulerPluginContainer) {
    return super.deinit(schedulerPluginContainer);
  }

  @Override
  public boolean submit(final SchedulerPluginContainer schedulerPluginContainer, final Operation operation,
    final long operationID) {
    final CallRunner task = operation.runnable().container().instance().instance().getPointer(CallRunner.class);
    return executeRpcCall(executor, queueSize, task);
  }

  @Override
  public void stop(final SchedulerPluginContainer schedulerPluginContainer) {
    if (this.executor != null) {
      this.executor.shutdown();
    }
  }

  @Override
  public void start(final SchedulerPluginContainer schedulerPluginContainer) {
    LOG.info("Using {} as user call queue; handlerCount={}; maxQueueLength={}",
      this.getClass().getSimpleName(), handlerCount, maxQueueLength);
    this.executor = new ThreadPoolExecutor(handlerCount, handlerCount, 60, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(maxQueueLength),
      new ThreadFactoryBuilder().setNameFormat("FifoRpcScheduler.handler-pool-%d").setDaemon(true)
        .setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER).build(),
      new ThreadPoolExecutor.CallerRunsPolicy());
  }

  protected boolean executeRpcCall(final ThreadPoolExecutor executor, final AtomicInteger queueSize,
    final CallRunner task) {
    // Executors provide no offer, so make our own.
    int queued = queueSize.getAndIncrement();
    if (maxQueueLength > 0 && queued >= maxQueueLength) {
      queueSize.decrementAndGet();
      return false;
    }

    executor.execute(new FifoCallRunner(task) {
      @Override
      public void run() {
        task.setStatus(RpcServer.getStatus());
        task.run();
        queueSize.decrementAndGet();
      }
    });

    return true;
  }

  @Override
  public int getGeneralQueueLength() {
    return executor.getQueue().size();
  }

  @Override
  public int getPriorityQueueLength() {
    return 0;
  }

  @Override
  public int getReplicationQueueLength() {
    return 0;
  }

  @Override
  public int getBulkLoadQueueLength() {
    return 0;
  }

  @Override
  public int getActiveRpcHandlerCount() {
    return executor.getActiveCount();
  }

  @Override
  public int getActiveGeneralRpcHandlerCount() {
    return getActiveRpcHandlerCount();
  }

  @Override
  public int getActivePriorityRpcHandlerCount() {
    return 0;
  }

  @Override
  public int getActiveReplicationRpcHandlerCount() {
    return 0;
  }

  @Override
  public int getActiveBulkLoadRpcHandlerCount() {
    return 0;
  }

  @Override
  public int getActiveMetaPriorityRpcHandlerCount() {
    return 0;
  }

  @Override
  public long getNumGeneralCallsDropped() {
    return 0;
  }

  @Override
  public long getNumLifoModeSwitches() {
    return 0;
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
    return this.ptrScope;
  }

  @Override
  public int getMetaPriorityQueueLength() {
    return 0;
  }

  @Override
  public CallQueueInfo getCallQueueInfo() {
    String queueName = "Fifo Queue";

    HashMap<String, Long> methodCount = new HashMap<>();
    HashMap<String, Long> methodSize = new HashMap<>();

    CallQueueInfo callQueueInfo = new CallQueueInfo();
    callQueueInfo.setCallMethodCount(queueName, methodCount);
    callQueueInfo.setCallMethodSize(queueName, methodSize);

    updateMethodCountAndSizeByQueue(executor.getQueue(), methodCount, methodSize);

    return callQueueInfo;
  }

  protected void updateMethodCountAndSizeByQueue(BlockingQueue<Runnable> queue,
    HashMap<String, Long> methodCount, HashMap<String, Long> methodSize) {
    for (Runnable r : queue) {
      FifoCallRunner mcr = (FifoCallRunner) r;
      RpcCall rpcCall = mcr.getCallRunner().getRpcCall();

      String method = getCallMethod(mcr.getCallRunner());
      if (StringUtil.isNullOrEmpty(method)) {
        method = "Unknown";
      }

      long size = rpcCall.getSize();

      methodCount.put(method, 1 + methodCount.getOrDefault(method, 0L));
      methodSize.put(method, size + methodSize.getOrDefault(method, 0L));
    }
  }

  protected String getCallMethod(final CallRunner task) {
    RpcCall call = task.getRpcCall();
    if (call != null && call.getMethod() != null) {
      return call.getMethod().getName();
    }
    return null;
  }

  public static SchedulerIDOrKairosResult newFifoRpcScheduler(final String name, final int port,
    final int handlerCount, final Configuration conf, final IntoBox<WorkerGroupProviderBox> wgProvider,
    final TransparentPointerScope ptrScope) {
    try (final TransparentPointerScope tempScope = new TransparentPointerScope()) {
      final Creator creator = Creator.newInstance(name, port, handlerCount, conf, ptrScope);
      return Kairos.createSchedulerInstance(
        Scheduling.KAIROS,
        SliceUtils.fromString(name, tempScope),
        ptrScope.attachTransparent(creator.intoDescriptor()),
        tempScope.attachTransparent(wgProvider.intoBox()),
        Kairos.newNoopLoggerDrain(), OptionalUtils.noneSchedulerBootstrapFn()
      );
    }
  }

  public static class Creator extends SchedulerPluginCreator {

    private final SliceU8 name;
    private final int port;
    private final int handlerCount;
    private final Configuration conf;
    private final TransparentPointerScope runtimeScope;

    public Creator(final SliceU8 name, final int port, final int handlerCount,
      final Configuration conf, final TransparentPointerScope runtimeScope) {
      super(name);
      this.name = name;
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
    public int createSchedulerInstance(final SliceU8 sliceU8, final SchedulerArgs schedulerArgs,
      final ArcVoid pluginCtx, final LoggerDrainBox loggerDrainBox, final SchedulerPluginArcBox schedulerPluginArcBox) {
      final FifoRpcScheduler scheduler = this.runtimeScope.attachTransparent(new FifoRpcScheduler(
        this.name,
        schedulerArgs,
        loggerDrainBox,
        pluginCtx,
        this.runtimeScope,
        this.conf,
        this.handlerCount,
        this.port
      ));
      scheduler.saturateArcBox(schedulerPluginArcBox);
      return Kairos.GenericError.GENERIC_ERROR_SUCCESS.value;
    }
  }
}
