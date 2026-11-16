package ru.komissarov.grocerydelivery.delivery;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalAddressIndexTest {
    @Test void matchesHouseRegardlessOfApartment(@TempDir Path directory) throws Exception {
        Path data = directory.resolve("addresses.osm");
        Files.writeString(data, """
            <osm><node id="1" lat="55.57" lon="42.04">
              <tag k="addr:street" v="Московская улица"/>
              <tag k="addr:housenumber" v="55А"/>
            </node></osm>
            """);
        var index = new LocalAddressIndex(data.toString());

        assertEquals(new OsrmRouting.Point(55.57, 42.04),
            index.locate("ул. Московская, д. 55а, кв. 12, подъезд 2").orElseThrow());
        assertTrue(index.locate("Московская, д. 55").isEmpty());
    }
}
