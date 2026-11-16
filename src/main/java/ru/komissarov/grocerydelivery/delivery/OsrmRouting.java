package ru.komissarov.grocerydelivery.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
class OsrmRouting {
    private final RestClient client;
    private final String base;

    OsrmRouting(@Value("${KSTOLU_OSRM_URL:http://127.0.0.1:5000}") String base) {
        this.base = base.replaceAll("/+$", "");
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(4));
        client = RestClient.builder().requestFactory(factory).build();
    }

    Route plan(List<Point> points) {
        String coordinates = points.stream().map(p -> p.longitude() + "," + p.latitude())
            .collect(Collectors.joining(";"));
        String radiuses = String.join(";", Collections.nCopies(points.size(), "250"));
        try {
            var response = client.get().uri(URI.create(base + "/route/v1/driving/" + coordinates
                + "?overview=full&geometries=geojson&radiuses=" + radiuses)).retrieve().body(Response.class);
            if (response == null || !"Ok".equals(response.code()) || response.routes().isEmpty()) {
                throw unavailable();
            }
            Route route = response.routes().getFirst();
            if (route.legs().size() != points.size() - 1) throw unavailable();
            return route;
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("Маршрут недоступен. Проверь запуск OSRM и убедись, что точки находятся у дорог в зоне карты Мурома.");
    }

    public record Point(double latitude, double longitude) {
        public Point {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                throw new IllegalArgumentException("Проверь широту и долготу.");
            }
        }
    }

    public record Response(String code, List<Route> routes) {}
    public record Route(double distance, double duration, List<Leg> legs, Geometry geometry) {}
    public record Leg(double distance, double duration) {}
    public record Geometry(List<List<Double>> coordinates) {}
}
