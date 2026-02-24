package org.apache.hadoop.hbase.ipc;

import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.OptionalGenericError;
import com.engineersbox.kairos.OperationRunnableBox;
import com.engineersbox.kairos.OptionalWorkerGroupError;
import com.engineersbox.kairos.WorkerGroupBox;
import com.engineersbox.kairos.WorkerGroupContainer;
import com.engineersbox.kairos.WorkerGroupProvider;
import com.engineersbox.kairos.WorkerGroupProviderBox;
import com.engineersbox.kairos.WorkerGroupProviderContainer;
import com.engineersbox.kairos.conversion.IntoBox;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.OptionalUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.bytedeco.javacpp.Pointer;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

public class FastPathRpcHandlerPool extends RpcHandlerPool {

  private final Deque<FastPathRpcHandler> handlerStack = new ConcurrentLinkedDeque<>();

  public FastPathRpcHandlerPool(final String name, final int handlerCount,
                                final int maxQueueLength, final int port,
                                final PriorityFunction priority, final Configuration conf, final Abortable abortable) {
    super(name, handlerCount, maxQueueLength, port, priority, conf, abortable);
  }

  public FastPathRpcHandlerPool(final String name, final int handlerCount, final int maxQueueLength,
                                final String callQueueType, final int port, final PriorityFunction priority,
                                final Configuration conf, final Abortable abortable) {
    super(name, handlerCount, maxQueueLength, callQueueType, port, priority, conf, abortable);
  }

  @Override
  protected RpcHandler getHandler(final String name, final double handlerFailureThreshhold,
    final int handlerCount, final BlockingQueue<CallRunner> q,
    final AtomicInteger activeHandlerCount, final AtomicInteger failedHandlerCount,
    final Abortable abortable) {
    return new FastPathRpcHandler(name, handlerFailureThreshhold, handlerCount, q,
      activeHandlerCount, failedHandlerCount, abortable, this.handlerStack);
  }

  @Override
  public OptionalWorkerGroupError assignDirect(final WorkerGroupContainer workerGroupContainer,
    final long worker_id, final OperationRunnableBox taskRunnableBox, final Pointer ctx,
    final long operation_id) {
    final FastPathRpcHandler handler = handlerStack.poll();
    if (handler == null) {
      return OptionalUtils.someWorkerGroupError(Kairos.WorkerGroupError.WORKER_GROUP_ERROR_FAILED);
    }
    final CallRunner callRunner = taskRunnableBox.container().instance().instance().getPointer(
      CallRunner.class);
    if (handler.loadCallRunner(callRunner)) {
      return OptionalUtils.noneWorkerGroupError();
    }
    return OptionalUtils.someWorkerGroupError(Kairos.WorkerGroupError.WORKER_GROUP_ERROR_FAILED);
  }

  public static class Provider extends WorkerGroupProvider implements IntoBox<WorkerGroupProviderBox> {

    private final String name;
    private final int port;
    private final int maxQueueLength;
    private final PriorityFunction priority;
    private final Configuration conf;
    private final Abortable abortable;
    private final TransparentPointerScope ptrScope;
    private final String queueType;

    public Provider(final String name, final int port, final int maxQueueLength, final String queueType,
      final PriorityFunction priority, final Configuration conf, final Abortable abortable, final TransparentPointerScope ptrScope) {
      this.name = name;
      this.port = port;
      this.maxQueueLength = maxQueueLength;
      this.queueType = queueType;
      this.priority = priority;
      this.conf = conf;
      this.abortable = abortable;
      this.ptrScope = ptrScope;
    }


    public Provider(final String name, final int port, final int maxQueueLength, final PriorityFunction priority,
      final Configuration conf, final Abortable abortable, final TransparentPointerScope ptrScope) {
      this(name, port, maxQueueLength,conf.get(CALL_QUEUE_TYPE_CONF_KEY, CALL_QUEUE_TYPE_CONF_DEFAULT),
        priority, conf, abortable, ptrScope);
    }

    @Override
    public Kairos.GenericError provide(final WorkerGroupProviderContainer workerGroupProviderContainer,
      final long size, final WorkerGroupBox workerGroupBox) {
      final FastPathRpcHandlerPool pool = ptrScope.attachTransparent(new FastPathRpcHandlerPool(
        this.name,
        (int) size,
        this.maxQueueLength,
        this.queueType,
        this.port,
        this.priority,
        this.conf,
        this.abortable
      ));
      pool.saturateBox(workerGroupBox);
      return Kairos.GenericError.GENERIC_ERROR_SUCCESS;
    }
  }
}
