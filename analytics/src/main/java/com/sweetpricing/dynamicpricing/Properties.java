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

package com.sweetpricing.dynamicpricing;

import java.util.Map;

/**
 * Properties are a dictionary of free-form information to attach to specific events.
 * <p/>
 * Just like traits, we also accept some properties with semantic meaning, and you should only ever
 * use these property names for that purpose.
 */
public class Properties extends ValueMap {
  private static final String PRODUCT_ID_KEY = "productId";

  public Properties() {
  }

  public Properties(int initialCapacity) {
    super(initialCapacity);
  }

  // For deserialization
  Properties(Map<String, Object> delegate) {
    super(delegate);
  }

  @Override public Properties putValue(String key, Object value) {
    super.putValue(key, value);
    return this;
  }

  /**
   * Set the Product ID that has been purchased in-app
   */
  public Properties putProductId(String name) {
    return putValue(PRODUCT_ID_KEY, name);
  }

  public String productId() {
    return getString(PRODUCT_ID_KEY);
  }
}
