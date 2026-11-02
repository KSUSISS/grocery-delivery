package ru.komissarov.grocerydelivery.delivery;

import jakarta.persistence.*;
import lombok.*;
import static ru.komissarov.grocerydelivery.delivery.Types.*;

@Entity @Table(name = "roles")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role {
    @Id @Enumerated(EnumType.STRING) @Column(length = 16)
    private RoleCode code;
    @Column(nullable = false, length = 60)
    private String name;
}
