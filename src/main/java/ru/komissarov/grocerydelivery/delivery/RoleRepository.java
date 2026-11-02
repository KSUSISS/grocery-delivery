package ru.komissarov.grocerydelivery.delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.komissarov.grocerydelivery.delivery.Types.RoleCode;
public interface RoleRepository extends JpaRepository<Role, RoleCode> {}
