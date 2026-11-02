package ru.komissarov.grocerydelivery.delivery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static ru.komissarov.grocerydelivery.delivery.Types.*;
import static ru.komissarov.grocerydelivery.delivery.DeliveryInputs.*;
import static ru.komissarov.grocerydelivery.delivery.DeliveryViews.*;

/** Internal application service. The future web layer must supply the authenticated actor ID. */
@Service @Validated @Transactional @RequiredArgsConstructor
public class DeliveryService {
    private final UserAccountRepository users;
    private final CourierRepository couriers;
    private final ShiftRepository shifts;
    private final OrderRepository orders;
    private final AssignmentRepository assignments;
    private final EventRepository events;
    private final JdbcTemplate jdbc;
    private final OrderNumberGenerator numbers;

    public CourierView registerCourier(long actorId, @NotNull @Valid CourierInput input) {
        manager(actorId);
        String phone = phone(input.phone());
        if (couriers.existsByPhone(phone)) throw conflict("Курьер с таким телефоном уже существует");
        Courier c = new Courier();
        c.setLastName(input.lastName().strip()); c.setFirstName(input.firstName().strip());
        c.setPatronymic(input.patronymic().strip()); c.setPhone(phone);
        c.setAgeYears((short)input.ageYears()); c.setAgeRecordedOn(LocalDate.now(ZoneId.of("Europe/Moscow")));
        couriers.saveAndFlush(c);
        return courierView(c);
    }

    public long startShift(long actorId, long courierId) {
        UserAccount actor = manager(actorId);
        Courier c = lockCourier(courierId);
        if (!c.isActive()) throw conflict("Курьер неактивен");
        if (shifts.findByCourierIdAndEndedAtIsNull(courierId).isPresent()) throw conflict("Смена уже открыта");
        CourierShift shift = new CourierShift(); shift.setCourier(c); shift.setOpenedBy(actor);
        return shifts.saveAndFlush(shift).getId();
    }

    public void endShift(long actorId, long courierId) {
        UserAccount actor = manager(actorId); lockCourier(courierId);
        CourierShift shift = openShift(courierId);
        if (assignments.existsByShiftIdAndReleasedAtIsNull(shift.getId())) throw conflict("Сначала завершите активный заказ");
        shift.setEndedAt(Instant.now()); shift.setClosedBy(actor);
    }

    public OrderView createOrder(long actorId, @NotNull @Valid OrderInput input) {
        UserAccount actor = manager(actorId);
        String phone = phone(input.recipientPhone());
        Instant now = Instant.now();
        for (int attempt = 0; attempt < 100; attempt++) {
            String number = numbers.next();
            List<Long> ids = jdbc.query("""
                INSERT INTO orders(order_number, created_by, recipient_name, recipient_phone,
                    address_text, address_type, delivery_due_at, delivery_comment, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_number) DO NOTHING RETURNING id
                """, (rs, row) -> rs.getLong(1), number, actor.getId(), input.recipientName().strip(), phone,
                input.addressText().strip(), input.addressType().name(), Timestamp.from(input.deliveryDueAt()),
                input.deliveryComment() == null ? "" : input.deliveryComment().strip(), Timestamp.from(now));
            if (!ids.isEmpty()) {
                DeliveryOrder order = orders.findById(ids.getFirst()).orElseThrow();
                event(order, actor, EventType.CREATED, null);
                return orderView(order);
            }
        }
        throw conflict("Не удалось подобрать свободный номер заказа. Повторите создание");
    }

    public OrderView assignCourier(long actorId, long orderId, long courierId) {
        UserAccount actor = manager(actorId);
        DeliveryOrder order = lockOrder(orderId);
        if (order.getStatus() != OrderStatus.NEW && order.getStatus() != OrderStatus.ASSIGNED)
            throw conflict("Переназначение возможно только до отправки");
        DeliveryAssignment previous = assignments.findByOrderIdAndReleasedAtIsNull(orderId).orElse(null);
        Set<Long> ids = new TreeSet<>(); ids.add(courierId);
        if (previous != null) ids.add(previous.getShift().getCourier().getId());
        List<Courier> locked = couriers.lockAll(ids);
        Courier courier = locked.stream().filter(c -> c.getId() == courierId).findFirst()
            .orElseThrow(() -> conflict("Курьер не найден"));
        if (!courier.isActive()) throw conflict("Курьер неактивен");
        CourierShift target = openShift(courierId);
        if (previous != null && previous.getShift().getId().equals(target.getId())) return orderView(order);
        if (assignments.existsByShiftIdAndReleasedAtIsNull(target.getId())) throw conflict("Курьер занят другим заказом");
        OrderStatus before = order.getStatus();
        if (previous != null) { release(previous, actor, ReleaseReason.REASSIGNED); assignments.flush(); }
        DeliveryAssignment next = new DeliveryAssignment(); next.setOrder(order); next.setShift(target); next.setAssignedBy(actor);
        assignments.saveAndFlush(next);
        order.setStatus(OrderStatus.ASSIGNED);
        event(order, actor, previous == null ? EventType.ASSIGNED : EventType.REASSIGNED, before);
        return orderView(order);
    }

    /** confirmed must be set only after the dispatcher confirms the send action in the UI. */
    public OrderView dispatch(long actorId, long orderId, boolean confirmed) {
        UserAccount actor = manager(actorId);
        if (!confirmed) throw conflict("Подтвердите отправку курьера");
        DeliveryOrder order = lockOrder(orderId);
        if (order.getStatus() != OrderStatus.ASSIGNED) throw conflict("Заказ должен быть назначен и еще не отправлен");
        DeliveryAssignment assignment = assignments.findByOrderIdAndReleasedAtIsNull(orderId).orElseThrow();
        Courier c = lockCourier(assignment.getShift().getCourier().getId());
        if (!c.isActive() || assignment.getShift().getEndedAt() != null) throw conflict("Курьер должен быть активен и на смене");
        assignment.setDispatchedAt(Instant.now()); assignment.setDispatchedBy(actor);
        order.setStatus(OrderStatus.IN_DELIVERY);
        event(order, actor, EventType.DISPATCHED, OrderStatus.ASSIGNED);
        return orderView(order);
    }

    public OrderView completeOrder(long actorId, long orderId) {
        UserAccount actor = manager(actorId);
        DeliveryOrder order = lockOrder(orderId);
        if (order.getStatus() == OrderStatus.COMPLETED) return orderView(order);
        OrderStatus before = order.getStatus();
        assignments.findByOrderIdAndReleasedAtIsNull(orderId).ifPresent(a -> {
            lockCourier(a.getShift().getCourier().getId()); release(a, actor, ReleaseReason.COMPLETED);
        });
        order.setStatus(OrderStatus.COMPLETED); order.setCompletedAt(Instant.now()); order.setCompletedBy(actor);
        event(order, actor, EventType.COMPLETED, before);
        return orderView(order);
    }

    @Transactional(readOnly = true)
    public Optional<OrderView> findOrder(long actorId, @NotBlank @Pattern(regexp="[1-9][0-9]{7}") String number) {
        manager(actorId); return orders.findByOrderNumber(number).map(this::orderView);
    }

    @Transactional(readOnly = true)
    public List<CourierView> listCouriers(long actorId) {
        manager(actorId); return couriers.findAll(org.springframework.data.domain.Sort.by("lastName", "firstName", "id"))
            .stream().map(this::courierView).toList();
    }

    private UserAccount manager(long id) {
        UserAccount actor = users.findById(id).orElseThrow(() -> conflict("Пользователь не найден"));
        if (!actor.isActive() || (actor.getRole().getCode() != RoleCode.ADMIN && actor.getRole().getCode() != RoleCode.MANAGER))
            throw conflict("Операция доступна только администратору или менеджеру");
        return actor;
    }
    private Courier lockCourier(long id) { return couriers.lockAll(List.of(id)).stream().findFirst().orElseThrow(() -> conflict("Курьер не найден")); }
    private DeliveryOrder lockOrder(long id) { return orders.lockById(id).orElseThrow(() -> conflict("Заказ не найден")); }
    private CourierShift openShift(long id) { return shifts.findByCourierIdAndEndedAtIsNull(id).orElseThrow(() -> conflict("Курьер вне смены")); }
    private void release(DeliveryAssignment a, UserAccount actor, ReleaseReason reason) {
        a.setReleasedAt(Instant.now()); a.setReleasedBy(actor); a.setReleaseReason(reason);
    }
    private void event(DeliveryOrder order, UserAccount actor, EventType type, OrderStatus before) {
        OrderEvent e = new OrderEvent(); e.setOrder(order); e.setActor(actor); e.setEventType(type);
        e.setPreviousStatus(before); e.setNewStatus(order.getStatus()); events.save(e);
    }
    private CourierView courierView(Courier c) {
        Optional<CourierShift> shift = shifts.findByCourierIdAndEndedAtIsNull(c.getId());
        return new CourierView(c.getId(), c.getLastName(), c.getFirstName(), c.getPatronymic(), c.getPhone(), c.getAgeYears(),
            c.getAgeRecordedOn(), shift.isPresent(), shift.map(s -> assignments.existsByShiftIdAndReleasedAtIsNull(s.getId())).orElse(false));
    }
    private OrderView orderView(DeliveryOrder o) {
        Long courier = assignments.findByOrderIdAndReleasedAtIsNull(o.getId()).map(a -> a.getShift().getCourier().getId()).orElse(null);
        return new OrderView(o.getId(), o.getOrderNumber(), o.getRecipientName(), o.getRecipientPhone(), o.getAddressText(),
            o.getAddressType(), o.getDeliveryDueAt(), o.getDeliveryComment(), o.getStatus(), courier);
    }
    private String phone(String raw) {
        if (!raw.matches("[+0-9() \\-]+")) throw conflict("Некорректный телефон");
        String normalized = "+" + raw.replaceAll("\\D", "");
        if (!normalized.matches("\\+[1-9][0-9]{6,14}")) throw conflict("Некорректный телефон");
        return normalized;
    }
    private IllegalStateException conflict(String message) { return new IllegalStateException(message); }
}
