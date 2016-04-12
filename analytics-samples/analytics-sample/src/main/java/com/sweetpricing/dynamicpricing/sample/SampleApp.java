package com.sweetpricing.dynamicpricing.sample;

import android.app.Application;
import android.widget.Toast;
import com.sweetpricing.dynamicpricing.DynamicPricing;

public class SampleApp extends Application {

  private static final String ANALYTICS_WRITE_KEY = "l8v1ga655b";

  @Override public void onCreate() {
    super.onCreate();

    // Initialize a new instance of the DynamicPricing client.
    DynamicPricing.Builder builder = new DynamicPricing.Builder(this, ANALYTICS_WRITE_KEY);
    if (BuildConfig.DEBUG) {
      builder.logLevel(DynamicPricing.LogLevel.VERBOSE);
    }

    // Set the initialized instance as a globally accessible instance.
    DynamicPricing.setSingletonInstance(builder.build());
  }
}
