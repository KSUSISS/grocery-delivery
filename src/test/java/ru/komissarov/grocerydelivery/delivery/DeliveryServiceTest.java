package ru.komissarov.grocerydelivery.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.komissarov.grocerydelivery.delivery.Types.*;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {
    @Mock UserAccountRepository users;
    @Mock CourierRepository couriers;
    @Mock ShiftRepository shifts;
    @Mock OrderRepository orders;
    @Mock AssignmentRepository assignments;
    @Mock EventRepository events;
    @Mock org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Mock OrderNumberGenerator numbers;
    @InjectMocks DeliveryService service;

    @Test void unconfirmedDispatchDoesNotChangeOrLockOrder() {
        UserAccount actor = actor(RoleCode.MANAGER);
        when(users.findById(1L)).thenReturn(java.util.Optional.of(actor));
        assertThrows(IllegalStateException.class, () -> service.dispatch(1, 10, false));
        verifyNoInteractions(orders, assignments, events, couriers);
    }
    @Test void clientCannotManageShifts() {
        UserAccount actor = actor(RoleCode.CLIENT);
        when(users.findById(1L)).thenReturn(java.util.Optional.of(actor));
        assertThrows(IllegalStateException.class, () -> service.startShift(1, 10));
        verifyNoInteractions(shifts, couriers);
    }
    @Test void inactiveManagerCannotCreateCourier() {
        UserAccount actor = new UserAccount(); actor.setActive(false);
        when(users.findById(1L)).thenReturn(java.util.Optional.of(actor));
        assertThrows(IllegalStateException.class, () -> service.registerCourier(1,
            new DeliveryInputs.CourierInput("Иванов", "Иван", "Иванович", "+79000000001", 25)));
        verifyNoInteractions(couriers);
    }
    private UserAccount actor(RoleCode code) {
        Role role = mock(Role.class); when(role.getCode()).thenReturn(code);
        UserAccount actor = new UserAccount(); actor.setRole(role); return actor;
    }
}
