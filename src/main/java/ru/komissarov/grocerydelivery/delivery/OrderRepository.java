package ru.komissarov.grocerydelivery.delivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface OrderRepository extends JpaRepository<DeliveryOrder, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DeliveryOrder o where o.id = :id")
    Optional<DeliveryOrder> lockById(Long id);
    List<DeliveryOrder> findTop50ByStatusOrderByIdDesc(Types.OrderStatus status);
    Optional<DeliveryOrder> findByOrderNumber(String number);
}
