/*
 * Copyright 2024 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.vindecoder;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.InvocationCallback;

public class NHTSAVinDecoderProvider implements VinDecoderProvider {

    private static final String VPIC_URL = "https://vpic.nhtsa.dot.gov/api/vehicles/decodevinvalues/";

    private final Client client;

    public NHTSAVinDecoderProvider(Client client) {
        this.client = client;
    }

    @Override
    public void decodeVin(String vin, VinDecoderCallback callback) {
        if (vin == null || vin.trim().isEmpty() || vin.trim().length() > 17) {
            callback.onFailure(
                    new IllegalArgumentException("Invalid VIN. VIN must not exceed 17 characters."));
            return;
        }
        vin = vin.trim();

        String normalizedVin = vin.trim().toUpperCase();
        String url = VPIC_URL + normalizedVin + "?format=json";

        AsyncInvoker invoker = client.target(url).request().async();
        invoker.get(new InvocationCallback<JsonObject>() {
            @Override
            public void completed(JsonObject json) {
                try {
                    JsonArray results = json.getJsonArray("Results");
                    if (results == null || results.isEmpty()) {
                        callback.onFailure(new VinDecoderException("No vehicle data found for this VIN"));
                        return;
                    }

                    JsonObject result = results.getJsonObject(0);
                    VinDecoder vinDecoder = new VinDecoder();
                    vinDecoder.setVin(result.getString("VIN", ""));
                    vinDecoder.setMake(result.getString("Make", ""));
                    vinDecoder.setManufacturer(result.getString("Manufacturer", ""));
                    vinDecoder.setModel(result.getString("Model", ""));
                    vinDecoder.setModelYear(result.getString("ModelYear", ""));
                    vinDecoder.setVehicleType(result.getString("VehicleType", ""));

                    callback.onSuccess(vinDecoder);
                } catch (Exception e) {
                    callback.onFailure(new VinDecoderException("Error parsing VIN decoder response", e));
                }
            }

            @Override
            public void failed(Throwable throwable) {
                callback.onFailure(throwable);
            }
        });
    }

}
