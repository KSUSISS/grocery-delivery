package ru.komissarov.grocerydelivery.delivery;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "app_users")
@Getter @Setter(AccessLevel.PACKAGE) @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "role_code")
    private Role role;
    @Column(nullable = false, length = 254) private String email;
    @Column(nullable = false, length = 255) private String passwordHash;
    @Column(nullable = false, length = 150) private String displayName;
    @Column(nullable = false) private boolean isActive = true;
    @Column(nullable = false) private Instant createdAt = Instant.now();
}
