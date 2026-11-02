package ru.komissarov.grocerydelivery.delivery;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;

@Component
public class OrderNumberGenerator {
    private final SecureRandom random = new SecureRandom();
    public String next() { return Integer.toString(10_000_000 + random.nextInt(90_000_000)); }
}
