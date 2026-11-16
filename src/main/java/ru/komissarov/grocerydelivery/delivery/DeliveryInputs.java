package ru.komissarov.grocerydelivery.delivery;

import jakarta.validation.constraints.*;
import java.time.Instant;
import static ru.komissarov.grocerydelivery.delivery.Types.*;

public final class DeliveryInputs {
    private DeliveryInputs() {}
    public record CourierInput(
        @NotBlank @Size(max=60) String lastName,
        @NotBlank @Size(max=60) String firstName,
        @NotBlank @Size(max=60) String patronymic,
        @NotBlank @Size(max=25) String phone,
        @Min(1) @Max(120) int ageYears) {}
    public record AddressCorrection(
        @NotBlank @Size(max = 500) String addressText,
        @NotNull AddressType addressType,
        @NotBlank @Size(max = 500) String reason) {}

    public record OrderInput(
        @NotBlank @Size(max=120) String recipientName,
        @NotBlank @Size(max=25) String recipientPhone,
        @NotBlank @Size(max=500) String addressText,
        @NotNull AddressType addressType,
        @NotNull Instant deliveryDueAt,
        @Size(max=500) String deliveryComment) {}
}
