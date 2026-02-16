package org.traccar.vindecoder;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OverpassApiProvider implements OverpassProvider {
    private static final String OVERPASS_URL = "http://147.182.153.145/api/interpreter";
    private final Client client;

    public OverpassApiProvider(Client client) {
        this.client = client;
    }

    @Override
    public void fetchTollWays(String query, Callback callback) {

        AsyncInvoker invoker = client
                .target(OVERPASS_URL)
                .request(MediaType.APPLICATION_JSON)
                .async();

        invoker.post(Entity.text(query), new InvocationCallback<JsonObject>() {

            @Override
            public void completed(JsonObject json) {
                try {
                    JsonArray elements = json.getJsonArray("elements");
                    List<TollWay> tollWays = new ArrayList<>();
                    for (JsonValue value : elements) {
                        JsonObject element = value.asJsonObject();
                        if (!"way".equals(element.getString("type", ""))) {
                            continue;
                        }
                        JsonObject tags = element.getJsonObject("tags");
                        if (tags == null || !"yes".equals(tags.getString("toll", ""))) {
                            continue;
                        }

                        TollWay way = new TollWay();
                        way.setType(element.getString("type", ""));
                        way.setId(element.getJsonNumber("id").longValue());
                        JsonObject boundsJson = element.getJsonObject("bounds");
                        if (boundsJson != null) {
                            Bounds bounds = new Bounds();
                            bounds.setMinlat(boundsJson.getJsonNumber("minlat").doubleValue());
                            bounds.setMinlon(boundsJson.getJsonNumber("minlon").doubleValue());
                            bounds.setMaxlat(boundsJson.getJsonNumber("maxlat").doubleValue());
                            bounds.setMaxlon(boundsJson.getJsonNumber("maxlon").doubleValue());
                            way.setBounds(bounds);
                        }
                        List<Long> nodeList = new ArrayList<>();
                        JsonArray nodes = element.getJsonArray("nodes");
                        if (nodes != null) {
                            for (JsonValue node : nodes) {
                                nodeList.add(((JsonNumber) node).longValue());
                            }
                        }
                        way.setNodes(nodeList);
                        List<Coordinate> geometryList = new ArrayList<>();
                        JsonArray geometry = element.getJsonArray("geometry");
                        if (geometry != null) {
                            for (JsonValue g : geometry) {
                                JsonObject geo = g.asJsonObject();
                                Coordinate c = new Coordinate();
                                c.setLat(geo.getJsonNumber("lat").doubleValue());
                                c.setLon(geo.getJsonNumber("lon").doubleValue());
                                geometryList.add(c);
                            }
                        }
                        way.setGeometry(geometryList);
                        Map<String, String> tagMap = new HashMap<>();
                        if (tags != null) {
                            for (String key : tags.keySet()) {
                                tagMap.put(key, tags.getString(key, ""));
                            }
                        }
                        way.setTags(tagMap);
                        tollWays.add(way);
                    }

                    callback.onSuccess(tollWays);

                } catch (Exception e) {
                    callback.onFailure(e);
                }
            }
            @Override
            public void failed(Throwable throwable) {
                callback.onFailure(throwable);
            }
        });
    }
}
