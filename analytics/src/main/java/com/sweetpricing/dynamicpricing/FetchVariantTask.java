package com.sweetpricing.dynamicpricing;

import android.os.AsyncTask;
import android.util.Log;

import com.sweetpricing.dynamicpricing.Client;
import com.sweetpricing.dynamicpricing.integrations.VariantRequestPayload;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Map;

import static com.sweetpricing.dynamicpricing.internal.Utils.buffer;
import static com.sweetpricing.dynamicpricing.internal.Utils.closeQuietly;

public class FetchVariantTask extends AsyncTask<Integer, Void, Variant> {
    final Cartographer cartographer = Cartographer.INSTANCE;
    private final DynamicPricing dynamicPricing;
    protected Exception e = null;
    private Variant defaultVariant = Variant.create(new ValueMap());

    public FetchVariantTask(DynamicPricing dynamicPricing) {
        this.dynamicPricing = dynamicPricing;
    }

    @Override
    protected Variant doInBackground(Integer... params) {
        int productGroupId = params[0];
        Client client = dynamicPricing.getClient();

        VariantRequestPayload payload = new VariantRequestPayload(
                dynamicPricing.getAnalyticsContext(), dynamicPricing.getDefaultOptions(), productGroupId);
        String payloadJson = null;

        try {
            payloadJson = cartographer.toJson(payload);
        } catch (IOException e) {
            this.e = e;
            return defaultVariant;
        }

        Client.Connection connection = null;

        try {
            // Open a connection.
            connection = client.variant();

            // Write the payloads into the OutputStream.
            BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(connection.os));
            bufferedWriter.write(payloadJson);
            bufferedWriter.close();

            InputStream is = connection.connection.getInputStream();
            Map<String, Object> respMap = cartographer.fromJson(buffer(is));
            is.close();

            try {
                // Upload the payloads.
                connection.close();
            } catch (Client.UploadException e) {
                this.e = e;
                return defaultVariant;
            }

            return Variant.create(respMap);
        } catch (IOException e) {
            this.e = e;
            return defaultVariant;
        } finally {
            closeQuietly(connection);
        }
    }
}