package ru.komissarov.grocerydelivery.delivery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

@Component
class LocalAddressIndex {
    private static final Pattern ADDRESS = Pattern.compile(
        "^(?:(?:г\\.?\\s+)?муром\\s*,\\s*)?(.+?),\\s*(?:д\\.|дом)\\s*([^,]+)(?:,.*)?$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private final Map<Key, List<OsrmRouting.Point>> homes;

    LocalAddressIndex(@Value("${KSTOLU_OSM_ADDRESS_FILE:}") String file) {
        homes = read(file);
    }

    Optional<OsrmRouting.Point> locate(String address) {
        return houseKey(address).flatMap(key -> {
            var matches = homes.getOrDefault(key, List.of());
            if (matches.isEmpty()) return Optional.empty();
            var first = matches.getFirst();
            // A building outline and address node can describe the same house.
            return matches.stream().allMatch(point ->
                Math.abs(point.latitude() - first.latitude()) < 0.0004
                && Math.abs(point.longitude() - first.longitude()) < 0.0006)
                ? Optional.of(first) : Optional.empty();
        });
    }

    static Optional<Key> houseKey(String address) {
        var match = ADDRESS.matcher(address.strip());
        return match.matches() ? Optional.of(key(match.group(1), match.group(2))) : Optional.empty();
    }

    private static Key key(String street, String house) {
        String name = normalize(street).replaceAll("^(?:улица|ул\\.)\\s+|\\s+(?:улица|ул\\.)$", "")
            .replace("1100-летия города мурома", "1100-летия мурома");
        return new Key(name, normalize(house).replace(" ", ""));
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ");
    }

    private static Map<Key, List<OsrmRouting.Point>> read(String file) {
        if (file.isBlank()) return Map.of();
        try (var stream = Files.newInputStream(Path.of(file))) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var root = factory.newDocumentBuilder().parse(stream).getDocumentElement();
            if (!"osm".equals(root.getTagName())) throw new IllegalArgumentException("Expected OSM XML");

            Map<String, OsrmRouting.Point> nodes = new HashMap<>();
            Map<Key, List<OsrmRouting.Point>> result = new HashMap<>();
            var elements = root.getChildNodes();
            for (int i = 0; i < elements.getLength(); i++) {
                if (elements.item(i) instanceof Element item && "node".equals(item.getTagName())) {
                    var point = new OsrmRouting.Point(Double.parseDouble(item.getAttribute("lat")),
                        Double.parseDouble(item.getAttribute("lon")));
                    nodes.put(item.getAttribute("id"), point);
                    Key key = taggedKey(item);
                    if (key != null) result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(point);
                }
            }
            for (int i = 0; i < elements.getLength(); i++) {
                if (elements.item(i) instanceof Element item && "way".equals(item.getTagName())) {
                    Key key = taggedKey(item);
                    if (key != null) center(item, nodes).ifPresent(point ->
                        result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(point));
                }
            }
            LoggerFactory.getLogger(LocalAddressIndex.class).info("Loaded {} local house addresses", result.size());
            return Map.copyOf(result);
        } catch (Exception error) {
            LoggerFactory.getLogger(LocalAddressIndex.class)
                .warn("Local house index unavailable; manual route points remain available", error);
            return Map.of();
        }
    }

    private static Key taggedKey(Element item) {
        String street = "";
        String house = "";
        var tags = item.getElementsByTagName("tag");
        for (int i = 0; i < tags.getLength(); i++) {
            var tag = (Element) tags.item(i);
            switch (tag.getAttribute("k")) {
                case "addr:street" -> street = tag.getAttribute("v");
                case "addr:housenumber" -> house = tag.getAttribute("v");
                default -> { }
            }
        }
        return street.isBlank() || house.isBlank() ? null : key(street, house);
    }

    private static Optional<OsrmRouting.Point> center(Element way, Map<String, OsrmRouting.Point> nodes) {
        var refs = way.getElementsByTagName("nd");
        Set<String> seen = new HashSet<>();
        double latitude = 0;
        double longitude = 0;
        for (int i = 0; i < refs.getLength(); i++) {
            String id = ((Element) refs.item(i)).getAttribute("ref");
            if (!seen.add(id)) continue;
            var point = nodes.get(id);
            if (point == null) return Optional.empty();
            latitude += point.latitude();
            longitude += point.longitude();
        }
        return seen.isEmpty() ? Optional.empty()
            : Optional.of(new OsrmRouting.Point(latitude / seen.size(), longitude / seen.size()));
    }

    record Key(String street, String number) {}
}
