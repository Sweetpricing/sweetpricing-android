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

import static java.util.Collections.unmodifiableMap;

public class Variant extends ValueMap {

    private static final String ID_KEY = "id";
    private static final String SKUS_KEY = "skus";

    static Variant create(Map<String, Object> map) {
        return new Variant(map);
    }

    private Variant(Map<String, Object> map) {
        super(unmodifiableMap(map));
    }

    public int getId() {
        return getInt(ID_KEY, 0);
    }

    public String getProductSku(int productId, String defaultValue) {
        String sku = null;
        String productIdStr = Integer.toString(productId);

        ValueMap skusValueMap = getValueMap(SKUS_KEY);

        if (skusValueMap != null) {
            sku = skusValueMap.getString(productIdStr);
        }

        if (sku == null) {
            sku = defaultValue;
        }

        return sku;
    }
}
