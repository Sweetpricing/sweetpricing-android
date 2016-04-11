package com.sweetpricing.dynamicpricing.test;

import com.sweetpricing.dynamicpricing.AnalyticsContext;
import com.sweetpricing.dynamicpricing.Options;
import com.sweetpricing.dynamicpricing.Traits;
import com.sweetpricing.dynamicpricing.integrations.IdentifyPayload;

import static com.sweetpricing.dynamicpricing.Utils.createContext;
import static com.sweetpricing.dynamicpricing.Utils.createTraits;

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
