package ru.komissarov.grocerydelivery.delivery;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import static ru.komissarov.grocerydelivery.delivery.Types.*;

@Entity @Table(name = "orders")
@Getter @Setter(AccessLevel.PACKAGE) @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryOrder extends BaseEntity {
    @Column(nullable = false, length = 8, unique = true) private String orderNumber;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id") private UserAccount customer;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by") private UserAccount createdBy;
    @Column(nullable = false, length = 120) private String recipientName;
    @Column(nullable = false, length = 16) private String recipientPhone;
    @Column(nullable = false, length = 500) private String addressText;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AddressType addressType;
    @Column(nullable = false) private Instant deliveryDueAt;
    @Column(nullable = false, length = 500) private String deliveryComment = "";
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private OrderStatus status = OrderStatus.NEW;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    private Instant completedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "completed_by") private UserAccount completedBy;
}
