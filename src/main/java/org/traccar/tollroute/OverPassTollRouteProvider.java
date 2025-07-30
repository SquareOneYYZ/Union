package org.traccar.tollroute;

import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.InvocationCallback;



public class OverPassTollRouteProvider implements TollRouteProvider {
    private final Client client;
    private final String url;
    private final int accuracy;

    public OverPassTollRouteProvider(Config config, Client client, String url) {
        this.client = client;
        //! got the url from the config , and set it using the base url , hope this works :)
        final String baseurl = config.getString(Keys.TOLL_ROUTE_URL, url);
        this.accuracy = config.getInteger(Keys.TOLL_ROUTE_ACCURACY);
      //  this.url = baseurl + "?data=[out:json];way[toll=yes](around:" + accuracy + ",%f,%f);out%%20tags;";
        this.url = baseurl + "?data=[out:json];way(around:" + accuracy + ",%f,%f)[highway];out%%20tags;";

    }

    @Override
    public void getTollRoute(double latitude, double longitude, TollRouteProviderCallback callback) {
        String formattedUrl = String.format(url, latitude, longitude);
        System.out.println(" Overpass Query URL: " + formattedUrl);
        AsyncInvoker invoker = client.target(formattedUrl).request().async();
        invoker.get(new InvocationCallback<JsonObject>() {
//            @Override
//            public void completed(JsonObject json) {
//                JsonArray elements = json.getJsonArray("elements");
//                if (!elements.isEmpty()) {
//                    JsonObject tags = elements.getJsonObject(0).getJsonObject("tags");
//                    Boolean isToll = determineToll(tags.getString("toll", "no"));
//                    String ref = tags.getString("ref", null);
//                    String name = tags.getString("name", null);
//                    if (ref == null) {
//                        ref = tags.getString("destination:ref", null);
//                    }
//                    if (name == null) {
//                        name = tags.getString("destination:name", null);
//                    }
//                    if (tags.containsKey("toll")) {
//                        callback.onSuccess(new TollData(isToll, ref, name));
//                    } else {
//                        callback.onSuccess(new TollData(isToll, ref, name));
//                    }
//                } else {
//                    callback.onSuccess(new TollData(false, null, null));
//                }
//            }

            @Override
            public void completed(JsonObject json) {
                JsonArray elements = json.getJsonArray("elements");
                if (!elements.isEmpty()) {
                    Boolean isToll = false;
                    String ref = null;
                    String name = null;
                    String surface = null;

                    for (int i = 0; i < elements.size(); i++) {
                        JsonObject element = elements.getJsonObject(i);
                        JsonObject tags = element.getJsonObject("tags");

                        if (tags == null) continue;

                        // Collect surface if available
                        if (surface == null && tags.containsKey("surface")) {
                            surface = tags.getString("surface");
                            System.out.println(" Overpass returned surface: " + surface);
                        }

                        // Toll-specific check
                        if (!isToll && tags.containsKey("toll") && determineToll(tags.getString("toll"))) {
                            isToll = true;
                        }

                        // ref or destination:ref
                        if (ref == null && tags.containsKey("ref")) {
                            ref = tags.getString("ref");
                        }
                        if (ref == null && tags.containsKey("destination:ref")) {
                            ref = tags.getString("destination:ref");
                        }

                        // name or destination:name
                        if (name == null && tags.containsKey("name")) {
                            name = tags.getString("name");
                        }
                        if (name == null && tags.containsKey("destination:name")) {
                            name = tags.getString("destination:name");
                        }

                        if (isToll && ref != null && name != null && surface != null) {
                            break;
                        }
                    }


                    callback.onSuccess(new TollData(isToll, ref, name, surface));

                } else {
                    callback.onSuccess(new TollData(false, null, null, null));
                }
            }


            @Override
            public void failed(Throwable throwable) {
                callback.onFailure(throwable);
            }
        });
    }
    private Boolean determineToll(String tollKey) {
        return tollKey.equals("yes");
    }

}
