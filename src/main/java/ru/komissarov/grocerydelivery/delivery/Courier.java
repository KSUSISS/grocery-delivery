package ru.komissarov.grocerydelivery.delivery;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;

@Entity @Table(name = "couriers")
@Getter @Setter(AccessLevel.PACKAGE) @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Courier extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", unique = true)
    private UserAccount user;
    @Column(nullable = false, length = 60) private String lastName;
    @Column(nullable = false, length = 60) private String firstName;
    @Column(nullable = false, length = 60) private String patronymic;
    @Column(nullable = false, length = 16, unique = true) private String phone;
    @Column(nullable = false) private short ageYears;
    @Column(nullable = false) private LocalDate ageRecordedOn;
    @Column(nullable = false) private boolean isActive = true;
    @Column(nullable = false) private Instant createdAt = Instant.now();
}
