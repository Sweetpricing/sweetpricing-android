package com.sweetpricing.dynamicpricing.integrations;

import com.sweetpricing.dynamicpricing.AnalyticsContext;
import com.sweetpricing.dynamicpricing.Options;

/**
 * Created by brendonboshell on 11/04/2016.
 */
public class VariantRequestPayload extends BasePayload {
    private static final String PRODUCT_GROUP_ID_KEY = "productGroupId";

    public VariantRequestPayload(AnalyticsContext context, Options options, int productGroupId) {
        super(Type.variantRequest, context, options);
        put(PRODUCT_GROUP_ID_KEY, productGroupId);
    }

    public int productGroupId() {
        return getInt(PRODUCT_GROUP_ID_KEY, 0);
    }

    @Override
    public String toString() {
        return "VariantRequestPayload{event=\"" + productGroupId() + "\"}";
    }
}
