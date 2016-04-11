package com.sweetpricing.dynamicpricing;

import android.os.AsyncTask;

import com.sweetpricing.dynamicpricing.Client;
import com.sweetpricing.dynamicpricing.integrations.VariantRequestPayload;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;

import static com.sweetpricing.dynamicpricing.internal.Utils.closeQuietly;

public class FetchVariantTask extends AsyncTask<Integer, Void, String> {
    final Cartographer cartographer = Cartographer.INSTANCE;
    private final DynamicPricing dynamicPricing;

    public FetchVariantTask(DynamicPricing dynamicPricing) {
        this.dynamicPricing = dynamicPricing;
    }

    @Override
    protected String doInBackground(Integer... params) {
        int productGroupId = params[0];
        Client client = dynamicPricing.getClient();

        VariantRequestPayload payload = new VariantRequestPayload(
                dynamicPricing.getAnalyticsContext(), dynamicPricing.getDefaultOptions(), productGroupId);
        String payloadJson = null;

        try {
            payloadJson = cartographer.toJson(payload);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Client.Connection connection = null;

        try {
            // Open a connection.
            connection = client.variant();

            // Write the payloads into the OutputStream.
            BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(connection.os));
            bufferedWriter.write(payloadJson);
            bufferedWriter.close();

            try {
                // Upload the payloads.
                connection.close();
            } catch (Client.UploadException e) {
                // Simply log and proceed to remove the rejected payloads from the queue
                return null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeQuietly(connection);
        }

        return null;
    }
}