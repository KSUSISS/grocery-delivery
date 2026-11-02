package ru.komissarov.grocerydelivery.delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface EventRepository extends JpaRepository<OrderEvent, Long> {
    List<OrderEvent> findByOrderIdOrderByOccurredAtAscIdAsc(Long orderId);
}
