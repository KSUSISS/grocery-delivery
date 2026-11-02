package ru.komissarov.grocerydelivery.delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface ShiftRepository extends JpaRepository<CourierShift, Long> {
    Optional<CourierShift> findByCourierIdAndEndedAtIsNull(Long courierId);
}
