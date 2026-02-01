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

import com.engineersbox.kairos.Kairos;
import com.engineersbox.kairos.OperationRunnable;
import com.engineersbox.kairos.OperationRunnableContainer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import org.apache.hadoop.hbase.CallDroppedException;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.exceptions.TimeoutIOException;
import org.apache.hadoop.hbase.monitoring.MonitoredRPCHandler;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.server.trace.IpcServerSpanBuilder;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RegionServerStatusProtos;
import org.apache.hadoop.hbase.trace.TraceUtil;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.util.StringUtils;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.apache.hbase.thirdparty.com.google.protobuf.Message;
import org.bytedeco.javacpp.Pointer;

/**
 * The request processing logic, which is usually executed in thread pools provided by an
 * {@link RpcScheduler}. Call {@link #run()} to actually execute the contained RpcServer.Call
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.COPROC, HBaseInterfaceAudience.PHOENIX })
@InterfaceStability.Evolving
public class CallRunner extends OperationRunnable {

  private static final CallDroppedException CALL_DROPPED_EXCEPTION = new CallDroppedException();

  private RpcCall call;
  private RpcServerInterface rpcServer;
  private MonitoredRPCHandler status;
  private final Span span;
  private volatile boolean successful;
  private volatile long operationID;

  /**
   * On construction, adds the size of this call to the running count of outstanding call sizes.
   * Presumption is that we are put on a queue while we wait on an executor to run us. During this
   * time we occupy heap.
   *
   * The parentScope is the PointerScope instance used by the scheduler that owns this task,
   * it is used to self-deallocate the task once it has completed
   */
  // The constructor is shutdown so only RpcServer in this class can make one of these.
  CallRunner(final RpcServerInterface rpcServer, final RpcCall call) {
    this.call = call;
    this.rpcServer = rpcServer;
    this.span = Span.current();
    // Add size of the call to queue size.
    if (rpcServer != null) {
      if (call != null) {
        this.rpcServer.addCallSize(call.getSize());
      }
      this.rpcServer.getScheduler().getPointerScope().attach(this);
    }
    this.operationID = 0;
  }

  public RpcCall getRpcCall() {
    return call;
  }

  public void setStatus(MonitoredRPCHandler status) {
    this.status = status;
  }

  /**
   * Cleanup after ourselves... let go of references.
   */
  private void cleanup() {
    this.call.cleanup();
    this.call = null;
    this.rpcServer.getScheduler().getPointerScope().detach(this);
    this.rpcServer = null;
  }

  @Override
  public void run(final OperationRunnableContainer container, final Pointer ctx,
    final long operationID) {
    this.operationID = operationID;
    run();
  }

  public void run() {
    try (Scope ignored = span.makeCurrent()) {
      this.span.setAttribute("kairos_operation_id", this.operationID);
      if (call.disconnectSince() >= 0) {
        RpcServer.LOG.debug("{}: skipped {}", Thread.currentThread().getName(), call);
        span.addEvent("Client disconnect detected");
        span.setStatus(StatusCode.OK);
        return;
      }
      call.setStartTime(EnvironmentEdgeManager.currentTime());
      if (call.getStartTime() > call.getDeadline()) {
        RpcServer.LOG.warn("Dropping timed out call: {}", call);
        this.rpcServer.getMetrics().callTimedOut();
        span.addEvent("Call deadline exceeded");
        span.setStatus(StatusCode.OK);
        return;
      }
      this.status.setStatus("Setting up call");
      this.status.setConnection(call.getRemoteAddress().getHostAddress(), call.getRemotePort());
      if (RpcServer.LOG.isTraceEnabled()) {
        RpcServer.LOG.trace("{} executing as {}", call.toShortString(),
          call.getRequestUser().map(User::getName).orElse("NULL principal"));
      }
      Throwable errorThrowable = null;
      String error = null;
      Pair<Message, CellScanner> resultPair = null;
      RpcServer.CurCall.set(call);
      final Span ipcServerSpan = new IpcServerSpanBuilder(call).build();
      try (Scope ignored1 = ipcServerSpan.makeCurrent()) {
        if (!this.rpcServer.isStarted()) {
          InetSocketAddress address = rpcServer.getListenerAddress();
          throw new ServerNotRunningYetException(
            "Server " + (address != null ? address : "(channel closed)") + " is not running yet");
        }
        // make the call
        resultPair = this.rpcServer.call(call, this.status);
      } catch (TimeoutIOException e) {
        RpcServer.LOG.warn("Can not complete this request in time, drop it: {}", call);
        TraceUtil.setError(ipcServerSpan, e);
        return;
      } catch (Throwable e) {
        TraceUtil.setError(ipcServerSpan, e);
        if (e instanceof ServerNotRunningYetException) {
          // If ServerNotRunningYetException, don't spew stack trace.
          if (RpcServer.LOG.isTraceEnabled()) {
            RpcServer.LOG.trace(call.toShortString(), e);
          }
        } else {
          // Don't dump full exception.. just String version
          RpcServer.LOG.debug("{}, exception={}", call.toShortString(), e);
        }
        errorThrowable = e;
        error = StringUtils.stringifyException(e);
        if (e instanceof Error) {
          throw (Error) e;
        }
      } finally {
        RpcServer.CurCall.set(null);
        if (resultPair != null) {
          this.rpcServer.addCallSize(call.getSize() * -1);
          ipcServerSpan.setStatus(StatusCode.OK);
          successful = true;
        }
        ipcServerSpan.end();
      }
      this.status.markComplete("To send response");
      // return the RPC request read BB we can do here. It is done by now.
      call.cleanup();
      // Set the response
      Message param = resultPair != null ? resultPair.getFirst() : null;
      CellScanner cells = resultPair != null ? resultPair.getSecond() : null;
      call.setResponse(param, cells, errorThrowable, error);
      call.sendResponseIfReady();
      // don't touch `span` here because its status and `end()` are managed in `call#setResponse()`
    } catch (OutOfMemoryError e) {
      TraceUtil.setError(span, e);
      if (
        this.rpcServer.getErrorHandler() != null && this.rpcServer.getErrorHandler().checkOOME(e)
      ) {
        RpcServer.LOG.info("{}: exiting on OutOfMemoryError", Thread.currentThread().getName());
        // exception intentionally swallowed
      } else {
        // rethrow if no handler
        throw e;
      }
    } catch (ClosedChannelException cce) {
      InetSocketAddress address = rpcServer.getListenerAddress();
      RpcServer.LOG.warn(
        "{}: caught a ClosedChannelException, " + "this means that the server "
          + (address != null ? address : "(channel closed)")
          + " was processing a request but the client went away. The error message was: {}",
        Thread.currentThread().getName(), cce.getMessage());
      TraceUtil.setError(span, cce);
    } catch (Exception e) {
      RpcServer.LOG.warn("{}: caught: {}", Thread.currentThread().getName(),
        StringUtils.stringifyException(e));
      TraceUtil.setError(span, e);
    } finally {
      if (!successful) {
        this.rpcServer.addCallSize(call.getSize() * -1);
      }

      if (this.status.isRPCRunning()) {
        this.status.markComplete("Call error");
      }
      this.status.pause("Waiting for a call");
      cleanup();
      span.end();
    }
  }

  /**
   * When we want to drop this call because of server is overloaded.
   */
  public void drop() {
    try (Scope ignored = span.makeCurrent()) {
      if (call.disconnectSince() >= 0) {
        RpcServer.LOG.debug("{}: skipped {}", Thread.currentThread().getName(), call);
        span.addEvent("Client disconnect detected");
        span.setStatus(StatusCode.OK);
        return;
      }

      // Set the response
      InetSocketAddress address = rpcServer.getListenerAddress();
      call.setResponse(null, null, CALL_DROPPED_EXCEPTION, "Call dropped, server "
        + (address != null ? address : "(channel closed)") + " is overloaded, please retry.");
      TraceUtil.setError(span, CALL_DROPPED_EXCEPTION);
      call.sendResponseIfReady();
      this.rpcServer.getMetrics().exception(CALL_DROPPED_EXCEPTION);
    } catch (ClosedChannelException cce) {
      InetSocketAddress address = rpcServer.getListenerAddress();
      RpcServer.LOG.warn(
        "{}: caught a ClosedChannelException, " + "this means that the server "
          + (address != null ? address : "(channel closed)")
          + " was processing a request but the client went away. The error message was: {}",
        Thread.currentThread().getName(), cce.getMessage());
      TraceUtil.setError(span, cce);
    } catch (Exception e) {
      RpcServer.LOG.warn("{}: caught: {}", Thread.currentThread().getName(),
        StringUtils.stringifyException(e));
      TraceUtil.setError(span, e);
    } finally {
      if (!successful) {
        this.rpcServer.addCallSize(call.getSize() * -1);
      }
      cleanup();
      span.end();
    }
  }

  public boolean isWriteRequest() {
    final Message param = this.call.getParam();
    // TODO: Is there a better way to do this?
    if (param instanceof ClientProtos.MultiRequest) {
      ClientProtos.MultiRequest multi = (ClientProtos.MultiRequest) param;
      for (final ClientProtos.RegionAction regionAction : multi.getRegionActionList()) {
        for (final ClientProtos.Action action : regionAction.getActionList()) {
          if (action.hasMutation()) {
            return true;
          }
        }
      }
    }
    if (param instanceof ClientProtos.MutateRequest) {
      return true;
    }
    // Below here are methods for master. It's a pretty brittle version of this.
    // Not sure that master actually needs a read/write queue since 90% of requests to
    // master are writing to status or changing the meta table.
    // All other read requests are admin generated and can be processed whenever.
    // However changing that would require a pretty drastic change and should be done for
    // the next major release and not as a fix for HBASE-14239
    if (param instanceof RegionServerStatusProtos.ReportRegionStateTransitionRequest) {
      return true;
    }
    if (param instanceof RegionServerStatusProtos.RegionServerStartupRequest) {
      return true;
    }
    if (param instanceof RegionServerStatusProtos.RegionServerReportRequest) {
      return true;
    }
    return false;
  }

  public boolean isScanRequest() {
    return this.call.getParam() instanceof ClientProtos.ScanRequest;
  }

  public Kairos.OperationKind getOperationKind() {
    if (isWriteRequest()) {
      return Kairos.OperationKind.Write;
    } else if (isScanRequest()) {
      return Kairos.OperationKind.Scan;
    }
    return Kairos.OperationKind.Read;
  }

}
