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
package org.apache.hadoop.hbase.executor;

import java.io.IOException;
import java.io.Writer;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.engineersbox.kairos.CMap;
import com.engineersbox.kairos.CMapBox;
import com.engineersbox.kairos.CMapContainer;
import com.engineersbox.kairos.DataBrokerBootstrapFn;
import com.engineersbox.kairos.DataBrokerPlugin;
import com.engineersbox.kairos.DataPublisherBox;
import com.engineersbox.kairos.DylibSpecifier;
import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.OptionalGenericError;
import com.engineersbox.kairos.SliceU8;
import com.engineersbox.kairos.TaskRunnable;
import com.engineersbox.kairos.TaskRunnableBox;
import com.engineersbox.kairos.TaskRunnableContainer;
import com.engineersbox.kairos.WorkerGroup;
import com.engineersbox.kairos.WorkerGroupBox;
import com.engineersbox.kairos.WorkerGroupContainer;
import com.engineersbox.kairos.WorkerGroupProvider;
import com.engineersbox.kairos.WorkerGroupProviderBox;
import com.engineersbox.kairos.WorkerGroupProviderContainer;
import com.engineersbox.kairos.broker.HashCMap;
import com.engineersbox.kairos.conversion.IntoBox;
import com.engineersbox.kairos.logging.SLF4JLoggerDrain;
import com.engineersbox.kairos.scope.TransparentPointerScope;
import com.engineersbox.kairos.utils.OptionalUtils;
import com.engineersbox.kairos.utils.SliceUtils;
import com.engineersbox.kairos.utils.TaskUtils;
import org.apache.hadoop.hbase.monitoring.ThreadMonitoring;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hbase.thirdparty.com.google.common.collect.ImmutableMap;
import org.apache.yetus.audience.InterfaceAudience;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.LongPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.base.Preconditions;
import org.apache.hbase.thirdparty.com.google.common.collect.Lists;
import org.apache.hbase.thirdparty.com.google.common.collect.Maps;
import org.apache.hbase.thirdparty.com.google.common.util.concurrent.ListenableFuture;
import org.apache.hbase.thirdparty.com.google.common.util.concurrent.ListeningScheduledExecutorService;
import org.apache.hbase.thirdparty.com.google.common.util.concurrent.MoreExecutors;
import org.apache.hbase.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * This is a generic executor service. This component abstracts a threadpool, a queue to which
 * {@link EventType}s can be submitted, and a <code>Runnable</code> that handles the object that is
 * added to the queue.
 * <p>
 * In order to create a new service, create an instance of this class and then do:
 * <code>instance.startExecutorService(executorConfig);</code>. {@link ExecutorConfig} wraps the
 * configuration needed by this service. When done call {@link #shutdown()}.
 * <p>
 * In order to use the service created above, call {@link #submit(EventHandler)}.
 */
@InterfaceAudience.Private
public class ExecutorService {
  private static final Logger LOG = LoggerFactory.getLogger(ExecutorService.class);
  private static final BytePointer KAIROS;

  static {
    KAIROS = Kairos.kairos_new();
    if (KAIROS == null || KAIROS.isNull()) {
      throw new IllegalStateException("Failed to initialise Kairos");
    }
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        try {
          final Kairos.KairosResult result = Kairos.kairos_free(KAIROS).intern();
          if (result != Kairos.KairosResult.KAIROS_RESULT_SUCCESS) {
            throw new IllegalStateException("Failed to destroy Kairos: " + result);
          }
        } finally {
          KAIROS.deallocate();
        }
      }
    });
  }

  // hold the all the executors created in a map addressable by their names
  private final ConcurrentMap<String, Executor> executorMap = new ConcurrentHashMap<>();

  // Name of the server hosting this executor service.
  private final String servername;

  private final ListeningScheduledExecutorService delayedSubmitTimer =
    MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1, new ThreadFactoryBuilder()
      .setDaemon(true).setNameFormat("Event-Executor-Delay-Submit-Timer").build()));

  /**
   * Default constructor.
   * @param servername Name of the hosting server.
   */
  public ExecutorService(final String servername) {
    this.servername = servername;
  }

  /**
   * Start an executor service with a given name. If there was a service already started with the
   * same name, this throws a RuntimeException.
   * @param config Configuration to use for the executor.
   */
  public void startExecutorService(final ExecutorConfig config) {
    final String name = config.getName();
    Executor hbes = this.executorMap.compute(name, (key, value) -> {
      if (value != null) {
        throw new RuntimeException(
          "An executor service with the name " + key + " is already running!");
      }
      return new Executor(config);
    });

    LOG.debug("Starting executor service name={}, corePoolSize={}, maxPoolSize={}", name,
      hbes.threadPoolExecutor.getCorePoolSize(), hbes.threadPoolExecutor.getMaximumPoolSize());
  }

  boolean isExecutorServiceRunning(String name) {
    return this.executorMap.containsKey(name);
  }

  public void shutdown() {
    this.delayedSubmitTimer.shutdownNow();
    for (Entry<String, Executor> entry : this.executorMap.entrySet()) {
      try {
        List<Runnable> wasRunning = entry.getValue().shutdownNow();
        if (!wasRunning.isEmpty()) {
          LOG.info(entry.getValue() + " had " + wasRunning + " on shutdown");
        }
      } catch (final Exception e) {
        LOG.error("Failed to shutdown executor service " + entry.getKey(), e);
      }
    }
    this.executorMap.clear();
  }

  Executor getExecutor(final ExecutorType type) {
    return getExecutor(type.getExecutorName(this.servername));
  }

  Executor getExecutor(String name) {
    return this.executorMap.get(name);
  }

  public ThreadPoolExecutor getExecutorThreadPool(final ExecutorType type) {
    return getExecutor(type).getThreadPoolExecutor();
  }

  /**
   * Initialize the executor lazily, Note if an executor need to be initialized lazily, then all
   * paths should use this method to get the executor, should not start executor by using
   * {@link ExecutorService#startExecutorService(ExecutorConfig)}
   */
  public ThreadPoolExecutor getExecutorLazily(ExecutorConfig config) {
    return executorMap.computeIfAbsent(config.getName(), (executorName) -> new Executor(config))
      .getThreadPoolExecutor();
  }

  public void submit(final EventHandler eh) {
    Executor executor = getExecutor(eh.getEventType().getExecutorServiceType());
    if (executor == null) {
      // This happens only when events are submitted after shutdown() was
      // called, so dropping them should be "ok" since it means we're
      // shutting down.
      LOG.error("Cannot submit [" + eh + "] because the executor is missing."
        + " Is this process shutting down?");
    } else {
      executor.submit(eh);
    }
  }

  // Submit the handler after the given delay. Used for retrying.
  public void delayedSubmit(EventHandler eh, long delay, TimeUnit unit) {
    ListenableFuture<?> future = delayedSubmitTimer.schedule(() -> submit(eh), delay, unit);
    future.addListener(() -> {
      try {
        future.get();
      } catch (Exception e) {
        LOG.error("Failed to submit the event handler {} to executor", eh, e);
      }
    }, MoreExecutors.directExecutor());
  }

  public Map<String, ExecutorStatus> getAllExecutorStatuses() {
    Map<String, ExecutorStatus> ret = Maps.newHashMap();
    for (Map.Entry<String, Executor> e : executorMap.entrySet()) {
      ret.put(e.getKey(), e.getValue().getStatus());
    }
    return ret;
  }

  /**
   * Configuration wrapper for {@link Executor}.
   */
  public class ExecutorConfig {
    // Refer to ThreadPoolExecutor javadoc for details of these configuration.
    // Argument validation and bound checks delegated to the underlying ThreadPoolExecutor
    // implementation.
    public static final long KEEP_ALIVE_TIME_MILLIS_DEFAULT = 1000;
    private int corePoolSize = -1;
    private boolean allowCoreThreadTimeout = false;
    private long keepAliveTimeMillis = KEEP_ALIVE_TIME_MILLIS_DEFAULT;
    private ExecutorType executorType;
    private String schedulerLibName = "example_scheduler";

    public ExecutorConfig setExecutorType(ExecutorType type) {
      this.executorType = type;
      return this;
    }

    private ExecutorType getExecutorType() {
      return Preconditions.checkNotNull(executorType, "ExecutorType not set.");
    }

    public ExecutorConfig setSchedulerLibName(final String schedulerLibName) {
      this.schedulerLibName = schedulerLibName;
      return this;
    }

    public String getSchedulerLibName() {
      return this.schedulerLibName;
    }

    public int getCorePoolSize() {
      return corePoolSize;
    }

    public ExecutorConfig setCorePoolSize(int corePoolSize) {
      this.corePoolSize = corePoolSize;
      return this;
    }

    public boolean allowCoreThreadTimeout() {
      return allowCoreThreadTimeout;
    }

    /**
     * Allows timing out of core threads. Good to set this for non-critical thread pools for release
     * of unused resources. Refer to {@link ThreadPoolExecutor#allowCoreThreadTimeOut} for
     * additional details.
     */
    public ExecutorConfig setAllowCoreThreadTimeout(boolean allowCoreThreadTimeout) {
      this.allowCoreThreadTimeout = allowCoreThreadTimeout;
      return this;
    }

    /**
     * Returns the executor name inferred from the type and the servername on which this is running.
     */
    public String getName() {
      return getExecutorType().getExecutorName(servername);
    }

    public long getKeepAliveTimeMillis() {
      return keepAliveTimeMillis;
    }

    public ExecutorConfig setKeepAliveTimeMillis(long keepAliveTimeMillis) {
      this.keepAliveTimeMillis = keepAliveTimeMillis;
      return this;
    }
  }

  static class DataBrokerProperties extends HashCMap {

    private final TransparentPointerScope scope;

    public DataBrokerProperties(final TransparentPointerScope scope) {
      this.scope = scope;
    }

    public DataBrokerProperties queueSize(final long queueSize) {
      super.put("queue_size", this.scope.attachTransparent(new LongPointer(new long[]{queueSize})));
      return this;
    }

  }

  /**
   * Executor instance.
   */
  static class Executor {
    public static final String DATA_BROKER_NAME = "example_broker";
    // the thread pool executor that services the requests
    final TrackingThreadPoolExecutor threadPoolExecutor;
    // work queue to use - unbounded queue
    final BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
    private final ConcurrentMap<String, DataPublisherBox> publishers;
    private final String name;
    private static final AtomicLong seqids = new AtomicLong(0);
    private final long id;
    private final TransparentPointerScope scope;

    protected Executor(final ExecutorConfig config) {
      this.id = seqids.incrementAndGet();
      this.name = config.getName();
      this.scope = new TransparentPointerScope();
      this.publishers = new ConcurrentHashMap<>();
      // create the thread pool executor
      this.threadPoolExecutor = new TrackingThreadPoolExecutor(
        // setting maxPoolSize > corePoolSize has no effect since we use an unbounded task queue.
        config.getCorePoolSize(), config.getCorePoolSize(), config.getKeepAliveTimeMillis(),
        TimeUnit.MILLISECONDS, q);
      this.threadPoolExecutor.allowCoreThreadTimeOut(config.allowCoreThreadTimeout());
      // name the threads for this threadpool
      ThreadFactoryBuilder tfb = new ThreadFactoryBuilder();
      tfb.setNameFormat(this.name + "-%d");
      tfb.setDaemon(true);
      tfb.setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER);
      this.threadPoolExecutor.setThreadFactory(tfb.build());
      registerBrokers();
      initScheduler(config.getSchedulerLibName());
    }
    private void registerBrokers() {
      final DylibSpecifier brokerDylib = this.scope.attachTransparent(new DylibSpecifier());
      brokerDylib.libType(Kairos.LibNameType.LIB_NAME_TYPE_NAME);
      brokerDylib.name(SliceUtils.fromString(DATA_BROKER_NAME, this.scope));
      final DataBrokerBootstrapFn bootstrapFn = this.scope.attachTransparent(new DataBrokerBootstrapFn() {
        @Override
        public OptionalGenericError call(final DataBrokerPlugin plugin) {
          final DataPublisherBox publisher = scope.attachTransparent(new DataPublisherBox());
          final SliceU8 topic = SliceUtils.fromString(name, scope);
          final int result = plugin.vtbl_databroker().publish().call(
            plugin.container(),
            topic,
            publisher
          );
          if (result == Kairos.GenericError.GENERIC_ERROR_FAILED.value) {
            return OptionalUtils.some(Kairos.GenericError.GENERIC_ERROR_FAILED, scope);
          }
          publishers.put(
            DATA_BROKER_NAME,
            publisher
          );
          return OptionalUtils.none(scope);
        }
      });
      final DataBrokerProperties properties = scope.attachTransparent(new DataBrokerProperties(scope))
        .queueSize(10);
      final Kairos.KairosResult result = Kairos.kairos_register_data_broker_dylib(
        ExecutorService.KAIROS,
        brokerDylib,
        scope.attachTransparent(properties.intoBox()),
        Kairos.new_dummy_logger_drain(),
        bootstrapFn
      ).intern();
      if (result != Kairos.KairosResult.KAIROS_RESULT_SUCCESS) {
        throw new IllegalStateException("Failed to register data broker: " + result.name());
      }
    }

    private void initScheduler(final String schedulerLibName) {
      final DylibSpecifier schedulerDylib = this.scope.attachTransparent(new DylibSpecifier());
      schedulerDylib.instanceName(SliceUtils.fromString(name, this.scope));
      schedulerDylib.libType(Kairos.LibNameType.LIB_NAME_TYPE_NAME);
      schedulerDylib.name(SliceUtils.fromString(schedulerLibName, this.scope));
      final Kairos.KairosResult result = Kairos.kairos_run_scheduler_dylib(
        ExecutorService.KAIROS,
        schedulerDylib,
        scope.attachTransparent(new TrackingThreadPoolProvider(
          scope,
          threadPoolExecutor
        ).intoBox()),
        scope.attachTransparent(new SLF4JLoggerDrain(
          this.toString(),
          scope
        ).intoBox())
      ).intern();
      if (result != Kairos.KairosResult.KAIROS_RESULT_SUCCESS) {
        throw new IllegalStateException("Failed to run scheduler: " + result.name());
      }
    }

    /**
     * Submit the event to the queue for handling.
     */
    void submit(final EventHandler event) {
      // If there is a listener for this type, make sure we call the before
      // and after process methods.
      final TaskRunnable task = scope.attachTransparent(new TaskRunnable() {
        @Override
        public void run(final TaskRunnableContainer cont, final Pointer context) {
          event.run();
        }
      });
      final Kairos.KairosResult result = Kairos.kairos_submit(
        ExecutorService.KAIROS,
        SliceUtils.fromString(name, this.scope),
        TaskUtils.create(
          0,
          null,
          task,
          this.scope
        )
      ).intern();
      if (result != Kairos.KairosResult.KAIROS_RESULT_SUCCESS) {
        throw new IllegalStateException("Failed to submit task: " + result.name());
      }
    }

    TrackingThreadPoolExecutor getThreadPoolExecutor() {
      return threadPoolExecutor;
    }

    DataPublisherBox getDataPublisher(final String name) {
      return this.publishers.get(name);
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "-" + id + "-" + name;
    }

    public ExecutorStatus getStatus() {
      List<EventHandler> queuedEvents = Lists.newArrayList();
      for (Runnable r : q) {
        if (!(r instanceof EventHandler)) {
          LOG.warn("Non-EventHandler " + r + " queued in " + name);
          continue;
        }
        queuedEvents.add((EventHandler) r);
      }

      List<RunningEventStatus> running = Lists.newArrayList();
      for (Map.Entry<Thread, Runnable> e : threadPoolExecutor.getRunningTasks().entrySet()) {
        Runnable r = e.getValue();
        if (!(r instanceof EventHandler)) {
          LOG.warn("Non-EventHandler " + r + " running in " + name);
          continue;
        }
        running.add(new RunningEventStatus(e.getKey(), (EventHandler) r));
      }

      return new ExecutorStatus(this, queuedEvents, running);
    }

    public List<Runnable> shutdownNow() throws Exception {
      final List<Runnable> tasks = this.threadPoolExecutor.shutdownNow();
      this.scope.close();
      return tasks;
    }
  }

  /**
   * A subclass of ThreadPoolExecutor that keeps track of the Runnables that are executing at any
   * given point in time.
   */
  static class TrackingThreadPoolExecutor extends ThreadPoolExecutor {
    private ConcurrentMap<Thread, Runnable> running = Maps.newConcurrentMap();

    public TrackingThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime,
      TimeUnit unit, BlockingQueue<Runnable> workQueue) {
      super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
      super.afterExecute(r, t);
      running.remove(Thread.currentThread());
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
      Runnable oldPut = running.put(t, r);
      assert oldPut == null : "inconsistency for thread " + t;
      super.beforeExecute(t, r);
    }

    /**
     * @return a map of the threads currently running tasks inside this executor. Each key is an
     *         active thread, and the value is the task that is currently running. Note that this is
     *         not a stable snapshot of the map.
     */
    public ConcurrentMap<Thread, Runnable> getRunningTasks() {
      return running;
    }
  }

  private static final class TrackingThreadPoolProvider extends WorkerGroupProvider implements
    IntoBox<WorkerGroupProviderBox> {

    private final TransparentPointerScope scope;
    private final TrackingThreadPoolExecutor executor;

    public TrackingThreadPoolProvider(final TransparentPointerScope scope, final TrackingThreadPoolExecutor executor) {
      this.scope = scope;
      this.scope.attach(this);
      this.executor = executor;
    }

    @Override
    public Kairos.GenericError provide(final WorkerGroupProviderContainer workerGroupProviderContainer,
      final long id,
      final WorkerGroupBox workerGroupBox) {
      final WorkerGroup group = scope.attachTransparent(new WorkerGroup() {
        @Override
        public OptionalGenericError assignWorker(final WorkerGroupContainer workerGroupContainer,
          final TaskRunnableBox taskRunnableBox,
          final Pointer pointer) {
          executor.submit(TaskUtils.intoRunnable(taskRunnableBox, pointer));
          return OptionalUtils.none(scope);
        }
      });
      group.saturateBox(workerGroupBox);
      return Kairos.GenericError.GENERIC_ERROR_SUCCESS;
    }
  }

  /**
   * A snapshot of the status of a particular executor. This includes the contents of the executor's
   * pending queue, as well as the threads and events currently being processed. This is a
   * consistent snapshot that is immutable once constructed.
   */
  public static class ExecutorStatus {
    final Executor executor;
    final List<EventHandler> queuedEvents;
    final List<RunningEventStatus> running;

    ExecutorStatus(Executor executor, List<EventHandler> queuedEvents,
      List<RunningEventStatus> running) {
      this.executor = executor;
      this.queuedEvents = queuedEvents;
      this.running = running;
    }

    public List<EventHandler> getQueuedEvents() {
      return queuedEvents;
    }

    public List<RunningEventStatus> getRunning() {
      return running;
    }

    /**
     * Dump a textual representation of the executor's status to the given writer.
     * @param out    the stream to write to
     * @param indent a string prefix for each line, used for indentation
     */
    public void dumpTo(Writer out, String indent) throws IOException {
      out.write(indent + "Status for executor: " + executor + "\n");
      out.write(indent + "=======================================\n");
      out.write(indent + queuedEvents.size() + " events queued, " + running.size() + " running\n");
      if (!queuedEvents.isEmpty()) {
        out.write(indent + "Queued:\n");
        for (EventHandler e : queuedEvents) {
          out.write(indent + "  " + e + "\n");
        }
        out.write("\n");
      }
      if (!running.isEmpty()) {
        out.write(indent + "Running:\n");
        for (RunningEventStatus stat : running) {
          out.write(indent + "  Running on thread '" + stat.threadInfo.getThreadName() + "': "
            + stat.event + "\n");
          out.write(ThreadMonitoring.formatThreadInfo(stat.threadInfo, indent + "  "));
          out.write("\n");
        }
      }
      out.flush();
    }
  }

  /**
   * The status of a particular event that is in the middle of being handled by an executor.
   */
  public static class RunningEventStatus {
    final ThreadInfo threadInfo;
    final EventHandler event;

    public RunningEventStatus(Thread t, EventHandler event) {
      this.threadInfo = ThreadMonitoring.getThreadInfo(t);
      this.event = event;
    }
  }
}
