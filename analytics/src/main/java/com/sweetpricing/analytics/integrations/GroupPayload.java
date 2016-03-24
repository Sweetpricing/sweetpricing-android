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

package com.sweetpricing.analytics.integrations;

import com.sweetpricing.analytics.AnalyticsContext;
import com.sweetpricing.analytics.Options;
import com.sweetpricing.analytics.Traits;

public class GroupPayload extends BasePayload {
  private static final String GROUP_ID_KEY = "groupId";
  private static final String TRAITS_KEY = "traits";

  public GroupPayload(AnalyticsContext context, Options options, String groupId,
      Traits groupTraits) {
    super(Type.group, context, options);
    put(GROUP_ID_KEY, groupId);
    put(TRAITS_KEY, groupTraits.unmodifiableCopy());
  }

  /**
   * A unique identifier that refers to the group in your database. For example, if your product
   * groups people by "organization" you would use the organization's ID in your database as the
   * group ID.
   */
  public String groupId() {
    return getString(GROUP_ID_KEY);
  }

  /**
   * The group method also takes a traits dictionary, just like identify.
   */
  public Traits traits() {
    return getValueMap(TRAITS_KEY, Traits.class);
  }

  @Override public String toString() {
    return "GroupPayload{groupId=\"" + groupId() + "\"}";
  }
}
