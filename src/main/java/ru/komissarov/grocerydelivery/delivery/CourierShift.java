package ru.komissarov.grocerydelivery.delivery;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "courier_shifts")
@Getter @Setter(AccessLevel.PACKAGE) @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourierShift extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) private Courier courier;
    @Column(nullable = false) private Instant startedAt = Instant.now();
    private Instant endedAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "opened_by") private UserAccount openedBy;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "closed_by") private UserAccount closedBy;
}
