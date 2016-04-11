package com.sweetpricing.dynamicpricing.test;

import com.sweetpricing.dynamicpricing.AnalyticsContext;
import com.sweetpricing.dynamicpricing.Options;
import com.sweetpricing.dynamicpricing.Properties;
import com.sweetpricing.dynamicpricing.Traits;
import com.sweetpricing.dynamicpricing.integrations.TrackPayload;

import static com.sweetpricing.dynamicpricing.Utils.createContext;
import static com.sweetpricing.dynamicpricing.Utils.createTraits;

public class TrackPayloadBuilder {

  private AnalyticsContext context;
  private Traits traits;
  private String event;
  private Properties properties;
  private Options options;

  public TrackPayloadBuilder context(AnalyticsContext context) {
    this.context = context;
    return this;
  }

  public TrackPayloadBuilder traits(Traits traits) {
    this.traits = traits;
    return this;
  }

  public TrackPayloadBuilder event(String event) {
    this.event = event;
    return this;
  }

  public TrackPayloadBuilder properties(Properties properties) {
    this.properties = properties;
    return this;
  }

  public TrackPayloadBuilder options(Options options) {
    this.options = options;
    return this;
  }

  public TrackPayload build() {
    if (traits == null) {
      traits = createTraits();
    }
    if (event == null) {
      event = "bar";
    }
    if (context == null) {
      context = createContext(traits);
    }
    if (properties == null) {
      properties = new Properties();
    }
    if (options == null) {
      options = new Options();
    }
    return new TrackPayload(context, options, event, properties);
  }
}
