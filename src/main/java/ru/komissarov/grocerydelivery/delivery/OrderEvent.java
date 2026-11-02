package ru.komissarov.grocerydelivery.delivery;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import static ru.komissarov.grocerydelivery.delivery.Types.*;

@Entity @Table(name = "order_events")
@Getter @Setter(AccessLevel.PACKAGE) @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderEvent extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id") private DeliveryOrder order;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "actor_user_id") private UserAccount actor;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private EventType eventType;
    @Enumerated(EnumType.STRING) @Column(length = 20) private OrderStatus previousStatus;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private OrderStatus newStatus;
    @Column(nullable = false, length = 500) private String note = "";
    @Column(nullable = false) private Instant occurredAt = Instant.now();
}
