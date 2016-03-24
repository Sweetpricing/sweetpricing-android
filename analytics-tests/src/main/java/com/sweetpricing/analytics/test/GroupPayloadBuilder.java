package com.sweetpricing.analytics.test;

import com.sweetpricing.analytics.AnalyticsContext;
import com.sweetpricing.analytics.Options;
import com.sweetpricing.analytics.Traits;
import com.sweetpricing.analytics.integrations.GroupPayload;

import static com.sweetpricing.analytics.Utils.createContext;
import static com.sweetpricing.analytics.Utils.createTraits;

public class GroupPayloadBuilder {

  private AnalyticsContext context;
  private String groupId;
  private Traits traits;
  private Traits groupTraits;
  private Options options;

  public GroupPayloadBuilder context(AnalyticsContext context) {
    this.context = context;
    return this;
  }

  public GroupPayloadBuilder groupId(String groupId) {
    this.groupId = groupId;
    return this;
  }

  public GroupPayloadBuilder traits(Traits traits) {
    this.traits = traits;
    return this;
  }

  public GroupPayloadBuilder groupTraits(Traits groupTraits) {
    this.groupTraits = groupTraits;
    return this;
  }

  public GroupPayloadBuilder options(Options options) {
    this.options = options;
    return this;
  }

  public GroupPayload build() {
    if (traits == null) {
      traits = createTraits();
    }
    if (groupTraits == null) {
      groupTraits = new Traits();
    }
    if (context == null) {
      context = createContext(traits);
    }
    if (options == null) {
      options = new Options();
    }
    if (groupId == null) {
      groupId = "bar";
    }
    return new GroupPayload(context, options, groupId, groupTraits);
  }
}
