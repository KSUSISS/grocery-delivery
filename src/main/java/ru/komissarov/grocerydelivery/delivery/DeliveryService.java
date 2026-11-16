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
    private final LocalAddressIndex addresses;
    private final OsrmRouting routing;

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
        if (pendingReturn(shift)) throw conflict("Сначала рассчитай обратный участок.");
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
                addresses.locate(order.getAddressText()).ifPresent(point -> upsertOrderPoint(order.getId(), point));
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
        if (pendingReturn(target)) throw conflict("У курьера еще не рассчитан обратный участок.");
        if (previous != null && previous.getShift().getId().equals(target.getId())) return orderView(order);

        OrderStatus before = order.getStatus();
        if (previous != null) { release(previous, actor, ReleaseReason.REASSIGNED); assignments.flush(); }
        DeliveryAssignment next = new DeliveryAssignment(); next.setOrder(order); next.setShift(target); next.setAssignedBy(actor);
        next.setQueuePosition(nextQueuePosition(queue(target)));

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

    public OrderView correctAddress(long actorId, long orderId, @NotNull @Valid AddressCorrection correction) {
        UserAccount actor = manager(actorId);
        DeliveryOrder order = lockOrder(orderId);
        if (order.getStatus() == OrderStatus.COMPLETED) {
            throw conflict("Адрес завершенного заказа изменить нельзя");
        }

        String address = correction.addressText().strip();
        AddressType type = correction.addressType();
        if (address.equals(order.getAddressText()) && type == order.getAddressType()) {
            return orderView(order);
        }

        jdbc.update("""
            INSERT INTO order_address_changes
                (order_id, changed_by, previous_address, previous_type, new_address, new_type, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, orderId, actor.getId(), order.getAddressText(), order.getAddressType().name(),
            address, type.name(), correction.reason().strip());
        if (!address.equals(order.getAddressText())) {
            var oldHouse = LocalAddressIndex.houseKey(order.getAddressText());
            if (oldHouse.isEmpty() || !oldHouse.equals(LocalAddressIndex.houseKey(address))) {
                jdbc.update("DELETE FROM order_route_points WHERE order_id = ?", orderId);
                addresses.locate(address).ifPresent(point -> upsertOrderPoint(orderId, point));
            }
        }
        order.setAddressText(address);
        order.setAddressType(type);
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

    @Transactional(readOnly = true)
    public List<OrderView> recentOrders(long actorId) {
        manager(actorId);
        var page = org.springframework.data.domain.PageRequest.of(0, 50,
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"));
        return orders.findAll(page).stream().map(this::orderView).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderView> courierQueue(long actorId, long courierId) {
        manager(actorId);
        return shifts.findByCourierIdAndEndedAtIsNull(courierId)
            .map(shift -> queue(shift).stream().map(a -> orderView(a.getOrder())).toList())
            .orElseGet(List::of);
    }

    public void moveInQueue(long actorId, long courierId, long orderId, boolean up) {
        manager(actorId);
        lockCourier(courierId);
        List<DeliveryAssignment> queue = queue(openShift(courierId));
        for (int index = 0; index < queue.size(); index++) {
            DeliveryAssignment current = queue.get(index);
            if (current.getOrder().getId() != orderId) continue;
            int target = index + (up ? -1 : 1);
            if (target < 0 || target >= queue.size()) return;
            DeliveryAssignment neighbor = queue.get(target);
            long position = current.getQueuePosition();
            long destination = neighbor.getQueuePosition();
            current.setQueuePosition(nextQueuePosition(queue));
            assignments.flush();
            neighbor.setQueuePosition(position);
            assignments.flush();
            current.setQueuePosition(destination);
            return;
        }
        throw conflict("Заказ больше не в очереди этого курьера");
    }

    private List<DeliveryAssignment> queue(CourierShift shift) {
        return assignments.findByShiftIdAndReleasedAtIsNullOrderByQueuePositionAsc(shift.getId());
    }

    private long nextQueuePosition(List<DeliveryAssignment> queue) {
        return queue.isEmpty() ? 1 : queue.getLast().getQueuePosition() + 1;
    }

    @Transactional(readOnly = true)
    public Optional<OsrmRouting.Point> routePoint(long actorId, Long orderId) {
        manager(actorId);
        if (orderId == null) {
            return jdbc.query("SELECT latitude, longitude FROM cafe_route_point WHERE id = 1",
                (rs, row) -> new OsrmRouting.Point(rs.getDouble(1), rs.getDouble(2))).stream().findFirst();
        }
        return jdbc.query("SELECT latitude, longitude FROM order_route_points WHERE order_id = ?",
            (rs, row) -> new OsrmRouting.Point(rs.getDouble(1), rs.getDouble(2)), orderId).stream().findFirst();
    }

    public void saveRoutePoint(long actorId, Long orderId, OsrmRouting.Point point) {
        manager(actorId);
        if (orderId == null) {
            jdbc.update("""
                INSERT INTO cafe_route_point (id, latitude, longitude) VALUES (1, ?, ?)
                ON CONFLICT (id) DO UPDATE SET latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude
                """, point.latitude(), point.longitude());
            return;
        }
        if (lockOrder(orderId).getStatus() == OrderStatus.COMPLETED) {
            throw conflict("Заказ уже завершен.");
        }
        upsertOrderPoint(orderId, point);
    }

    private void upsertOrderPoint(long orderId, OsrmRouting.Point point) {
        jdbc.update("""
            INSERT INTO order_route_points (order_id, latitude, longitude) VALUES (?, ?, ?)
            ON CONFLICT (order_id) DO UPDATE SET latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude
            """, orderId, point.latitude(), point.longitude());
    }



    @Transactional(readOnly = true)
    public List<OrderView> candidates(long actorId, List<Long> selected) {
        manager(actorId);
        Map<Long, DeliveryOrder> result = new LinkedHashMap<>();
        orders.findAllById(selected).stream()
            .filter(order -> order.getStatus() == OrderStatus.NEW)
            .forEach(order -> result.put(order.getId(), order));
        orders.findTop50ByStatusOrderByIdDesc(OrderStatus.NEW)
            .forEach(order -> result.putIfAbsent(order.getId(), order));
        return result.values().stream().map(this::orderView).toList();
    }

    public void assignBatch(long actorId, long courierId, List<Long> orderIds) {
        assignBatch(actorId, courierId, orderIds, false);
    }

    public void assignBatch(long actorId, long courierId, List<Long> orderIds, boolean requireEmptyQueue) {
        manager(actorId);
        List<Long> ids = orderIds.stream().distinct().toList();
        if (ids.isEmpty() || ids.size() > 20) {
            throw conflict("Выбери от одного до 20 заказов.");
        }
        // Keep the same lock order as individual assignment: orders, then courier.
        for (long id : ids.stream().sorted().toList()) {
            if (lockOrder(id).getStatus() != OrderStatus.NEW) {
                throw conflict("Один из заказов уже назначен или завершен. Обнови выбор.");
            }
        }
        lockCourier(courierId);
        if (requireEmptyQueue && !queue(openShift(courierId)).isEmpty()) {
            throw conflict("У курьера уже есть заказы. Выбери свободного курьера на смене.");
        }
        for (long id : ids) {
            assignCourier(actorId, id, courierId);
        }
    }

    public void dispatchQueue(long actorId, long courierId) {
        manager(actorId);
        List<Long> expected = courierQueue(actorId, courierId).stream()
            .filter(order -> order.status() == OrderStatus.ASSIGNED).map(OrderView::id).sorted().toList();
        if (expected.isEmpty()) throw conflict("У курьера нет заказов к отправке.");
        expected.forEach(this::lockOrder);
        lockCourier(courierId);
        CourierShift shift = openShift(courierId);
        List<Long> current = queue(shift).stream().filter(a -> a.getDispatchedAt() == null)
            .map(a -> a.getOrder().getId()).sorted().toList();
        if (!current.equals(expected)) throw conflict("Очередь изменилась. Проверь ее и повтори отправку.");
        expected.forEach(id -> dispatch(actorId, id, true));
    }

    public OrderView markDelivered(long actorId, long orderId, Integer meters) {
        manager(actorId);
        if (meters != null && (meters < 0 || meters > 1_000_000)) {
            throw conflict("Укажи целое число метров от 0 до 1 000 000.");
        }
        DeliveryOrder order = lockOrder(orderId);
        if (order.getStatus() != OrderStatus.IN_DELIVERY) throw conflict("Заказ должен быть в доставке.");
        var assignment = assignments.findByOrderIdAndReleasedAtIsNull(orderId).orElseThrow();
        lockCourier(assignment.getShift().getCourier().getId());
        assignment.setDeliveredAt(Instant.now());
        assignment.setConfirmedMeters(meters);
        OrderView completed = completeOrder(actorId, orderId);
        CourierShift shift = assignment.getShift();
        if (queue(shift).isEmpty()) {

            jdbc.update("INSERT INTO courier_returns (shift_id, last_order_id) VALUES (?, ?)", shift.getId(), orderId);
            try {
                applyReturnDistance(actorId, shift, orderId);
            } catch (IllegalStateException unavailable) {
                // Keep the delivery completed and leave the return eligible for a retry.
            }
        }
        return completed;
    }

    private boolean pendingReturn(CourierShift shift) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM courier_returns WHERE shift_id = ? AND returned_at IS NULL)
            """, Boolean.class, shift.getId()));
    }

    @Transactional(readOnly = true)
    public boolean returningToCafe(long actorId, long courierId) {
        manager(actorId);
        return shifts.findByCourierIdAndEndedAtIsNull(courierId).map(this::pendingReturn).orElse(false);
    }

    public void recalculateReturn(long actorId, long courierId) {
        manager(actorId);
        lockCourier(courierId);
        CourierShift shift = openShift(courierId);
        long orderId = jdbc.query("""
            SELECT last_order_id FROM courier_returns WHERE shift_id = ? AND returned_at IS NULL
            """, (rs, row) -> rs.getLong(1), shift.getId()).stream().findFirst()
            .orElseThrow(() -> conflict("Нет ожидающего расчета возврата."));
        applyReturnDistance(actorId, shift, orderId);
    }

    private void applyReturnDistance(long actorId, CourierShift shift, long orderId) {
        var last = routePoint(actorId, orderId).orElseThrow(() -> conflict("У последнего заказа нет точки на карте."));
        var cafe = routePoint(actorId, null).orElseThrow(() -> conflict("Сначала укажи точку кафе."));
        long meters = Math.round(routing.plan(List.of(last, cafe)).distance());
        if (meters < 0 || meters > 1_000_000) throw conflict("Не удалось рассчитать корректный пробег до кафе.");
        jdbc.update("""
            UPDATE courier_returns SET returned_at = clock_timestamp(), confirmed_meters = ?, confirmed_by = ?
            WHERE shift_id = ? AND last_order_id = ? AND returned_at IS NULL
            """, meters, actorId, shift.getId(), orderId);
    }

    @Transactional(readOnly = true)
    public List<CourierReport> deliveryReport(long actorId, Instant from, Instant to) {
        manager(actorId);
        return jdbc.query("""
            WITH deliveries AS (
                SELECT s.courier_id, count(*) AS delivered, count(a.confirmed_meters) AS measured,
                    coalesce(sum(a.confirmed_meters), 0) AS meters
                FROM delivery_assignments a JOIN courier_shifts s ON s.id = a.shift_id
                WHERE a.delivered_at >= ? AND a.delivered_at < ?
                GROUP BY s.courier_id
            ), returns AS (
                SELECT s.courier_id, count(*) AS trips, sum(r.confirmed_meters) AS meters
                FROM courier_returns r JOIN courier_shifts s ON s.id = r.shift_id
                WHERE r.returned_at >= ? AND r.returned_at < ?
                GROUP BY s.courier_id
            )
            SELECT concat_ws(' ', c.last_name, c.first_name, c.patronymic) AS name,
                coalesce(d.delivered, 0), coalesce(d.measured, 0), coalesce(d.meters, 0),
                coalesce(r.trips, 0), coalesce(r.meters, 0)
            FROM couriers c LEFT JOIN deliveries d ON d.courier_id = c.id
            LEFT JOIN returns r ON r.courier_id = c.id ORDER BY name, c.id
            """, (rs, row) -> new CourierReport(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6)),
            Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to));
    }

    public record CourierReport(String name, long delivered, long measured, long deliveryMeters,
                                long returns, long returnMeters) {
        public long meters() { return deliveryMeters + returnMeters; }
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
            c.getAgeRecordedOn(), shift.isPresent(), shift.map(s -> pendingReturn(s) || assignments.existsByShiftIdAndReleasedAtIsNull(s.getId())).orElse(false));
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
