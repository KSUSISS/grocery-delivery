package ru.komissarov.grocerydelivery.delivery;
import java.time.*;
import static ru.komissarov.grocerydelivery.delivery.Types.*;

public final class DeliveryViews {
    private DeliveryViews() {}
    public record OrderView(long id, String number, String recipientName, String recipientPhone,
        String addressText, AddressType addressType, Instant deliveryDueAt, String comment,
        OrderStatus status, Long courierId) {}
    public record CourierView(long id, String lastName, String firstName, String patronymic,
        String phone, int ageYears, LocalDate ageRecordedOn, boolean onShift, boolean busy) {}
}
