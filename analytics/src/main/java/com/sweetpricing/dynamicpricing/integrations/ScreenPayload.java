/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Segment, Inc.
 * Modified work Copyright (c) 2016 Sweet Pricing Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.sweetpricing.dynamicpricing.integrations;

import com.sweetpricing.dynamicpricing.AnalyticsContext;
import com.sweetpricing.dynamicpricing.Options;
import com.sweetpricing.dynamicpricing.Properties;

import static com.sweetpricing.dynamicpricing.internal.Utils.isNullOrEmpty;

public class ScreenPayload extends BasePayload {
  private static final String CATEGORY_KEY = "category";
  private static final String NAME_KEY = "name";
  private static final String PROPERTIES_KEY = "properties";

  /** Either the name or category of the event. */
  private String event;

  public ScreenPayload(AnalyticsContext context, Options options, String category, String name,
      Properties properties) {
    super(Type.screen, context, options);
    put(CATEGORY_KEY, category);
    put(NAME_KEY, name);
    put(PROPERTIES_KEY, properties);
  }

  /** The category of the page or screen. We recommend using title case, like "Docs". */
  public String category() {
    return getString(CATEGORY_KEY);
  }

  /** The name of the page or screen. We recommend using title case, like "About". */
  public String name() {
    return getString(NAME_KEY);
  }

  /** Either the name or category of the event. */
  public String event() {
    if (isNullOrEmpty(event)) {
      event = isNullOrEmpty(name()) ? category() : name();
    }
    return event;
  }

  /** The page and screen methods also take a properties dictionary, just like track. */
  public Properties properties() {
    return (Properties) get(PROPERTIES_KEY);
  }

  @Override public String toString() {
    return "ScreenPayload{name=\"" + name() + ",category=\"" + category() + "\"}";
  }
}
