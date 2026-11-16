package ru.komissarov.grocerydelivery.delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AssignmentRepository extends JpaRepository<DeliveryAssignment, Long> {
    Optional<DeliveryAssignment> findByOrderIdAndReleasedAtIsNull(Long orderId);
    List<DeliveryAssignment> findByShiftIdAndReleasedAtIsNullOrderByQueuePositionAsc(Long shiftId);

    boolean existsByShiftIdAndReleasedAtIsNull(Long shiftId);
    List<DeliveryAssignment> findByOrderIdOrderByAssignedAtAsc(Long orderId);
}
