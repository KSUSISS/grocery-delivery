package ru.komissarov.grocerydelivery.delivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface CourierRepository extends JpaRepository<Courier, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Courier c where c.id in :ids order by c.id")
    List<Courier> lockAll(Collection<Long> ids);
    boolean existsByPhone(String phone);
}
