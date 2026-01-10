package org.apache.hadoop.hbase.executor;

import com.engineersbox.kairos.Kairos;
import org.bytedeco.javacpp.BytePointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Scheduling {
  private static final Logger LOG = LoggerFactory.getLogger(Scheduling.class);

  public static final BytePointer KAIROS;

  static {
    KAIROS = Kairos.create();
    if (KAIROS == null || KAIROS.isNull()) {
      LOG.error("Failed to initialise Kairos");
      throw new IllegalStateException("Failed to initialise Kairos");
    }
    LOG.info("Initialised Kairos");
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        try {
          final Kairos.KairosResult result = Kairos.free(KAIROS).intern();
          if (result != Kairos.KairosResult.KAIROS_RESULT_SUCCESS) {
            LOG.error("Failed to destroy Kairos: {}", result);
            throw new IllegalStateException("Failed to destroy Kairos: " + result);
          }
        } finally {
          LOG.info("Destroyed Kairos");
          KAIROS.deallocate();
        }
      }
    });
  }
}
