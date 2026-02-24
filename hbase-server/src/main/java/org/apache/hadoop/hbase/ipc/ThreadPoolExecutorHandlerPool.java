package org.apache.hadoop.hbase.ipc;

import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.OperationRunnableBox;
import com.engineersbox.kairos.OptionalSliceOperationID;
import com.engineersbox.kairos.OptionalSliceWorkerID;
import com.engineersbox.kairos.OptionalWorkerGroupError;
import com.engineersbox.kairos.UsizeOrWorkerGroupError;
import com.engineersbox.kairos.WorkerGroup;
import com.engineersbox.kairos.WorkerGroupContainer;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.OperationUtils;
import com.engineersbox.kairos.utils.OptionalUtils;
import com.engineersbox.kairos.utils.ResultUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hbase.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.bytedeco.javacpp.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolExecutorHandlerPool extends WorkerGroup {

  private static final Logger LOGGER = LoggerFactory.getLogger(ThreadPoolExecutorHandlerPool.class);

  protected final int handlerCount;
  protected final int maxQueueLength;
  protected final TransparentPointerScope ptrScope;
  protected final AtomicInteger queueSize = new AtomicInteger(0);
  protected ThreadPoolExecutor executor;

  public ThreadPoolExecutorHandlerPool(final Configuration conf, final int handlerCount) {
    this.handlerCount = handlerCount;
    this.maxQueueLength = conf.getInt(RpcScheduler.IPC_SERVER_MAX_CALLQUEUE_LENGTH,
      handlerCount * RpcServer.DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER);
    this.ptrScope = new TransparentPointerScope();
  }

  @Override
  public OptionalWorkerGroupError start(final WorkerGroupContainer workerGroupContainer) {
    LOGGER.info("Using {} as user call queue; handlerCount={}; maxQueueLength={}",
      this.getClass().getSimpleName(), handlerCount, maxQueueLength);
    this.executor = new ThreadPoolExecutor(handlerCount, handlerCount, 60, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(maxQueueLength),
      new ThreadFactoryBuilder().setNameFormat("FifoRpcScheduler.handler-pool-%d").setDaemon(true)
        .setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER).build(),
      new ThreadPoolExecutor.CallerRunsPolicy());
    return OptionalUtils.noneWorkerGroupError();
  }

  @Override
  public OptionalWorkerGroupError stop(final WorkerGroupContainer workerGroupContainer) {
    this.executor.shutdownNow();
    return OptionalUtils.noneWorkerGroupError();
  }

  @Override
  public OptionalSliceWorkerID workerIDs(WorkerGroupContainer workerGroupContainer) {
    return OptionalUtils.noneSliceWorkerID();
  }

  @Override
  public OptionalWorkerGroupError assign(final WorkerGroupContainer workerGroupContainer,
    final OperationRunnableBox operationRunnableBox, final Pointer ctx, final long operationID) {
    final CallRunner callRunner = operationRunnableBox.container().instance().instance().getPointer(
      CallRunner.class);
    // Executors provide no offer, so make our own.
    int queued = queueSize.getAndIncrement();
    if (maxQueueLength > 0 && queued >= maxQueueLength) {
      queueSize.decrementAndGet();
      return OptionalUtils.someWorkerGroupError(Kairos.WorkerGroupError.WORKER_GROUP_ERROR_FAILED);
    }
    executor.execute(() -> {
      callRunner.setStatus(RpcServer.getStatus());
      OperationUtils.invoke(
        operationRunnableBox,
        ctx,
        operationID
      );
      queueSize.decrementAndGet();
    });
    return OptionalUtils.noneWorkerGroupError();
  }

  @Override
  public OptionalWorkerGroupError assignDirect(final WorkerGroupContainer workerGroupContainer, final long workerID,
    final OperationRunnableBox operationRunnableBox, final Pointer pointer, final long operation) {
    return OptionalUtils.someWorkerGroupError(Kairos.WorkerGroupError.WORKER_GROUP_ERROR_DIRECT_UNSUPPORTED);
  }

  @Override
  public OptionalWorkerGroupError resize(final WorkerGroupContainer workerGroupContainer, final long newSize) {
    this.executor.setCorePoolSize((int) newSize);
    return OptionalUtils.noneWorkerGroupError();
  }

  @Override
  public long size(WorkerGroupContainer workerGroupContainer) {
    return this.executor.getCorePoolSize();
  }

  @Override
  public void flush(WorkerGroupContainer workerGroupContainer) {
    this.executor.shutdownNow();
    while (this.executor.getTaskCount() > 0);
  }

  @Override
  public long operationCount(WorkerGroupContainer workerGroupContainer) {
    return this.executor.getTaskCount();
  }

  @Override
  public UsizeOrWorkerGroupError workerOperationCount(final WorkerGroupContainer workerGroupContainer,
    final long workerID) {
    return ResultUtils.errWorkerGroupErr(
      Kairos.WorkerGroupError.WORKER_GROUP_ERROR_FAILED,
      this.ptrScope
    );
  }

  @Override
  public OptionalSliceOperationID workerOperationIds(
    WorkerGroupContainer workerGroupContainer, long l) {
    return OptionalUtils.noneSliceOperationID();
  }
}
