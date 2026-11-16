package ru.komissarov.grocerydelivery.delivery;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import static ru.komissarov.grocerydelivery.delivery.Types.*;

@Entity @Table(name = "delivery_assignments")
@Getter @Setter(AccessLevel.PACKAGE) @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryAssignment extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id") private DeliveryOrder order;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "shift_id") private CourierShift shift;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "assigned_by") private UserAccount assignedBy;
    @Column(nullable = false) private Instant assignedAt = Instant.now();
    @Column(nullable = false) private long queuePosition;

    private Integer confirmedMeters;
    private Instant deliveredAt;
    private Instant dispatchedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "dispatched_by") private UserAccount dispatchedBy;
    private Instant releasedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "released_by") private UserAccount releasedBy;
    @Enumerated(EnumType.STRING) @Column(length = 20) private ReleaseReason releaseReason;
}
