package ru.komissarov.grocerydelivery.delivery;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ru.komissarov.grocerydelivery.PostgresTestSupport;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.komissarov.grocerydelivery.delivery.Types.*;
import static ru.komissarov.grocerydelivery.delivery.DeliveryInputs.*;

@SpringBootTest
class DeliveryDatabaseTest extends PostgresTestSupport {
    @Autowired DeliveryService service;
    @Autowired RoleRepository roles;
    @Autowired UserAccountRepository users;
    @Autowired AssignmentRepository assignments;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean OrderNumberGenerator numbers;
    private long manager;

    @BeforeEach void setup() {
        reset(numbers);
        jdbc.execute("TRUNCATE order_events, delivery_assignments, orders, courier_shifts, couriers, app_users RESTART IDENTITY CASCADE");
        manager = user(RoleCode.MANAGER);
    }
    private long user(RoleCode code) {
        UserAccount u = new UserAccount(); u.setRole(roles.findById(code).orElseThrow());
        u.setEmail(UUID.randomUUID()+"@example.test");u.setDisplayName("Test user");u.setPasswordHash("test-only-hash-no-login");
        return users.saveAndFlush(u).getId();
    }
    private long courier(String suffix) {
        return service.registerCourier(manager,new CourierInput("Иванов", "Иван", "Иванович", "+790000000"+suffix,25)).id();
    }
    private DeliveryViews.OrderView order(AddressType type) {
        return service.createOrder(manager,new OrderInput("Анна", "+7 (900) 000-00-55",
            "Владимир, ул. Мира, д. 36"+(type==AddressType.APARTMENT?", кв. 42":""),type,
            Instant.now().plusSeconds(3600),"Позвонить перед приездом"));
    }

    @Test void dispatcherCanCorrectAddressInDeliveryAndHistorySurvivesCompletion() {
        long courier = courier("01");
        service.startShift(manager, courier);
        var original = order(AddressType.APARTMENT);
        service.assignCourier(manager, original.id(), courier);
        service.dispatch(manager, original.id(), true);
        var correction = new AddressCorrection("Муром, ул. Лесная, д. 12А",
            AddressType.PRIVATE_HOUSE, "Клиент уточнил адрес");

        service.correctAddress(manager, original.id(), correction);
        service.correctAddress(manager, original.id(), correction);
        var updated = service.findOrder(manager, original.number()).orElseThrow();
        assertEquals(correction.addressText(), updated.addressText());
        assertEquals(AddressType.PRIVATE_HOUSE, updated.addressType());
        assertEquals(OrderStatus.IN_DELIVERY, updated.status());
        assertEquals(original.addressText(), jdbc.queryForObject(
            "SELECT previous_address FROM order_address_changes WHERE order_id = ?", String.class, original.id()));
        assertEquals(manager, jdbc.queryForObject(
            "SELECT changed_by FROM order_address_changes WHERE order_id = ?", Long.class, original.id()));

        service.completeOrder(manager, original.id());
        assertThrows(IllegalStateException.class, () -> service.correctAddress(manager, original.id(), correction));
    }

    @Test void completeLifecycleKeepsRegistrationShiftAndDeliverySeparate() {
        long c = courier("01");
        assertFalse(service.listCouriers(manager).getFirst().onShift());
        var o = order(AddressType.APARTMENT);
        assertThrows(IllegalStateException.class,()->service.assignCourier(manager,o.id(),c));
        service.startShift(manager,c);
        service.assignCourier(manager,o.id(),c);
        assertThrows(IllegalStateException.class,()->service.endShift(manager,c));
        assertThrows(IllegalStateException.class,()->service.dispatch(manager,o.id(),false));
        assertEquals(OrderStatus.IN_DELIVERY,service.dispatch(manager,o.id(),true).status());
        service.completeOrder(manager,o.id());
        var free = service.listCouriers(manager).getFirst();
        assertTrue(free.onShift());assertFalse(free.busy());
        service.endShift(manager,c);
        assertFalse(service.listCouriers(manager).getFirst().onShift());
        assertEquals(4,jdbc.queryForObject("select count(*) from order_events where order_id=?",Integer.class,o.id()));
        assertEquals("COMPLETED",jdbc.queryForObject("select release_reason from delivery_assignments where order_id=?",String.class,o.id()));
    }
    @Test void reassignmentPreservesHistoryAndReleasesPreviousCourier() {
        long a=courier("01"),b=courier("02");service.startShift(manager,a);service.startShift(manager,b);
        var o=order(AddressType.PRIVATE_HOUSE);service.assignCourier(manager,o.id(),a);service.assignCourier(manager,o.id(),b);
        var history=assignments.findByOrderIdOrderByAssignedAtAsc(o.id());assertEquals(2,history.size());
        assertEquals(ReleaseReason.REASSIGNED,history.getFirst().getReleaseReason());
        service.endShift(manager,a);
        assertThrows(IllegalStateException.class,()->service.endShift(manager,b));
        service.dispatch(manager,o.id(),true);
        assertThrows(IllegalStateException.class,()->service.assignCourier(manager,o.id(),a));
    }
    @Test void numberCollisionRetriesAndCompletedNumbersStayReserved() {
        doReturn("12345678","12345678","87654321").when(numbers).next();
        var a=order(AddressType.APARTMENT);service.completeOrder(manager,a.id());
        var b=order(AddressType.PRIVATE_HOUSE);
        assertEquals("12345678",a.number());assertEquals("87654321",b.number());
        assertEquals(OrderStatus.COMPLETED,service.findOrder(manager,a.number()).orElseThrow().status());
        assertEquals(AddressType.PRIVATE_HOUSE,service.findOrder(manager,b.number()).orElseThrow().addressType());
    }
    @Test void missingAddressRejectedAndCompletedActionIsIdempotent() {
        assertThrows(ConstraintViolationException.class,()->service.createOrder(manager,
            new OrderInput("Анна","+79000000001","   ",AddressType.APARTMENT,Instant.now(),"")));
        assertEquals(0,jdbc.queryForObject("select count(*) from orders",Integer.class));
        var o=order(AddressType.PRIVATE_HOUSE);service.completeOrder(manager,o.id());service.completeOrder(manager,o.id());
        assertEquals(1,jdbc.queryForObject("select count(*) from order_events where order_id=? and event_type='COMPLETED'",Integer.class,o.id()));
    }
    @Test void courierCanReorderOrdersAndCloseShiftAfterFinishingBoth() {
        long courier = courier("01");
        service.startShift(manager, courier);
        assertThrows(IllegalStateException.class, () -> service.startShift(manager, courier));
        var first = order(AddressType.APARTMENT);
        var second = order(AddressType.PRIVATE_HOUSE);
        var unavailable = order(AddressType.APARTMENT);
        service.completeOrder(manager, unavailable.id());
        assertThrows(IllegalStateException.class,
            () -> service.assignBatch(manager, courier, List.of(first.id(), unavailable.id())));
        assertTrue(queueIds(courier).isEmpty());
        service.assignBatch(manager, courier, List.of(first.id(), second.id()));
        assertEquals(List.of(first.id(), second.id()), queueIds(courier));

        service.moveInQueue(manager, courier, first.id(), false);
        assertEquals(List.of(second.id(), first.id()), queueIds(courier));
        service.moveInQueue(manager, courier, first.id(), true);
        assertEquals(List.of(first.id(), second.id()), queueIds(courier));

        service.completeOrder(manager, first.id());
        assertEquals(List.of(second.id()), queueIds(courier));
        assertThrows(IllegalStateException.class, () -> service.endShift(manager, courier));
        service.completeOrder(manager, second.id());
        service.endShift(manager, courier);
        assertTrue(queueIds(courier).isEmpty());
    }

    @Test void dispatchesWholeQueueAndReportsOnlyDeliveredMileage() {
        long courier = courier("01");
        service.startShift(manager, courier);
        var first = order(AddressType.APARTMENT);
        var second = order(AddressType.PRIVATE_HOUSE);
        service.assignBatch(manager, courier, List.of(first.id(), second.id()), true);
        service.dispatchQueue(manager, courier);
        assertTrue(service.courierQueue(manager, courier).stream()
            .allMatch(order -> order.status() == OrderStatus.IN_DELIVERY));

        service.markDelivered(manager, first.id(), 3400);
        service.completeOrder(manager, second.id());
        var summary = service.deliveryReport(manager, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60)).getFirst();
        assertEquals(1L, summary.delivered());
        assertEquals(1L, summary.measured());
        assertEquals(3400L, summary.meters());
    }

    private List<Long> queueIds(long courier) {
        return service.courierQueue(manager, courier).stream().map(DeliveryViews.OrderView::id).toList();
    }
    @Test void databaseItselfRejectsDuplicateNumbersAndOpenShifts() {
        long c=courier("01");service.startShift(manager,c);var o=order(AddressType.APARTMENT);
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("insert into courier_shifts(courier_id,opened_by) values (?,?)",c,manager));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("""
            insert into orders(order_number,created_by,recipient_name,recipient_phone,address_text,address_type,delivery_due_at)
            values (?,?,'Test','+79000000001','Address','PRIVATE_HOUSE',current_timestamp)
            """,o.number(),manager));
    }
    @Test void simultaneousAssignmentAndShiftClosureCannotLeaveAssignmentOnClosedShift() throws Exception {
        long c=courier("01");service.startShift(manager,c);var o=order(AddressType.APARTMENT);
        CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)){
            Future<Boolean> assign=pool.submit(()->race(start,()->service.assignCourier(manager,o.id(),c)));
            Future<Boolean> close=pool.submit(()->race(start,()->service.endShift(manager,c)));
            start.countDown();boolean assigned=assign.get(20,TimeUnit.SECONDS),closed=close.get(20,TimeUnit.SECONDS);
            assertNotEquals(assigned,closed);
        }
        assertEquals(0,jdbc.queryForObject("""
            select count(*) from delivery_assignments a join courier_shifts s on s.id=a.shift_id
            where a.released_at is null and s.ended_at is not null
            """,Integer.class));
    }
    private boolean race(CountDownLatch start,Runnable action) throws InterruptedException {
        start.await();try{action.run();return true;}catch(IllegalStateException expected){return false;}
    }
}
