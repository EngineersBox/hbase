package org.apache.hadoop.hbase.ipc;

import com.engineersbox.kairos.OptionalSchedulerBootstrapFn;
import com.engineersbox.kairos.SchedulerIDOrKairosResult;
import com.engineersbox.kairos.utils.OptionalUtils;

public abstract class RpcSchedulerProvider {

  public final boolean isInstanceScheduler;
  public final boolean shouldRegisterConfigurationObserver;
  private OptionalSchedulerBootstrapFn bootstrapFn;

  public RpcSchedulerProvider(final boolean isInstanceScheduler, final boolean shouldRegisterConfigurationObserver) {
    this.isInstanceScheduler = isInstanceScheduler;
    this.shouldRegisterConfigurationObserver = shouldRegisterConfigurationObserver;
    this.bootstrapFn = null;
  }

  public RpcSchedulerProvider withBootstrapFn(final OptionalSchedulerBootstrapFn bootstrapFn) {
    this.bootstrapFn = bootstrapFn;
    return this;
  }

  public abstract SchedulerIDOrKairosResult createScheduler(final OptionalSchedulerBootstrapFn optionalSchedulerBootstrapFn);

  public SchedulerIDOrKairosResult provide() {
    return createScheduler(
      this.bootstrapFn == null
        ? OptionalUtils.noneSchedulerBootstrapFn()
        : this.bootstrapFn
    );
  }
}
