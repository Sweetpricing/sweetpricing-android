package com.sweetpricing.analytics.test;

import com.sweetpricing.analytics.AnalyticsContext;
import com.sweetpricing.analytics.Options;
import com.sweetpricing.analytics.Traits;
import com.sweetpricing.analytics.integrations.IdentifyPayload;

import static com.sweetpricing.analytics.Utils.createContext;
import static com.sweetpricing.analytics.Utils.createTraits;

public class IdentifyPayloadBuilder {

  private AnalyticsContext context;
  private Traits traits;
  private Options options;

  public IdentifyPayloadBuilder traits(Traits traits) {
    this.traits = traits;
    return this;
  }

  public IdentifyPayloadBuilder options(Options options) {
    this.options = options;
    return this;
  }

  public IdentifyPayloadBuilder context(AnalyticsContext context) {
    this.context = context;
    return this;
  }

  public IdentifyPayload build() {
    if (traits == null) {
      traits = createTraits();
    }
    if (context == null) {
      context = createContext(traits);
    }
    if (options == null) {
      options = new Options();
    }
    return new IdentifyPayload(context, options, traits);
  }
}
