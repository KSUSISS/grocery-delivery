package ru.komissarov.grocerydelivery.delivery;

import java.security.Principal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import static ru.komissarov.grocerydelivery.delivery.DeliveryInputs.*;
import static ru.komissarov.grocerydelivery.delivery.Types.*;

@Controller
@RequestMapping("/orders")
@RequiredArgsConstructor
class OrdersController {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(MOSCOW);
    private final DeliveryService service;
    private final UserAccountRepository users;

    @GetMapping
    String list(@RequestParam(defaultValue = "") String number, Principal principal, Model model) {
        long actor = actor(principal);
        String query = number.strip();
        model.addAttribute("number", query);
        if (query.isEmpty()) {
            model.addAttribute("orders", service.recentOrders(actor));
        } else if (query.matches("[1-9][0-9]{7}")) {
            model.addAttribute("orders", service.findOrder(actor, query).stream().toList());
        } else {
            model.addAttribute("error", "Введи восьмизначный номер заказа.");
            model.addAttribute("orders", java.util.List.of());
        }
        model.addAttribute("shiftQueue", service.listCouriers(actor).stream().filter(DeliveryViews.CourierView::onShift)
            .map(c -> new CourierQueue(c, service.courierQueue(actor, c.id()), service.returningToCafe(actor, c.id()))).toList());
        return "orders";
    }

    @GetMapping("/{number:[1-9][0-9]{7}}")
    String detail(@PathVariable String number, Principal principal, Model model) {
        var order = service.findOrder(actor(principal), number)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("order", order);
        model.addAttribute("due", DATE.format(order.deliveryDueAt()));
        model.addAttribute("couriers", service.listCouriers(actor(principal)).stream().filter(DeliveryViews.CourierView::onShift).toList());
        return "order-detail";
    }

    @GetMapping("/new")
    String newOrder() {
        return "order-new";
    }

    @GetMapping("/reports")
    String reports(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                   Principal principal, Model model) {
        LocalDate today = LocalDate.now(MOSCOW);
        LocalDate start = from == null ? today.withDayOfMonth(1) : from;
        LocalDate end = to == null ? today : to;
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        if (start.isAfter(end)) {
            model.addAttribute("error", "Дата начала должна быть не позже даты окончания.");
            model.addAttribute("report", java.util.List.of());
        } else {
            model.addAttribute("report", service.deliveryReport(actor(principal),
                start.atStartOfDay(MOSCOW).toInstant(), end.plusDays(1).atStartOfDay(MOSCOW).toInstant()));
        }
        return "delivery-report";
    }

    @PostMapping("/couriers/{id}/return")
    String recalculateReturn(@PathVariable long id, Principal principal, RedirectAttributes flash) {
        String target = perform("redirect:/orders", flash, () -> service.recalculateReturn(actor(principal), id));
        if (!flash.getFlashAttributes().containsKey("error")) {
            flash.addFlashAttribute("notice", "Обратный пробег рассчитан и добавлен в общий итог.");
        }
        return target;
    }

    @PostMapping("/couriers/{id}/dispatch")
    String dispatchQueue(@PathVariable long id, Principal principal, RedirectAttributes flash) {
        return perform("redirect:/orders", flash, () -> service.dispatchQueue(actor(principal), id));
    }

    @PostMapping("/{number:[1-9][0-9]{7}}/delivered")
    String delivered(@PathVariable String number, @RequestParam(required = false) Integer meters,
                     Principal principal, RedirectAttributes flash) {
        long actor = actor(principal);
        var order = service.findOrder(actor, number)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return perform("redirect:/orders/" + number, flash, () -> service.markDelivered(actor, order.id(), meters));
    }


    @PostMapping
    String create(@RequestParam String recipientName, @RequestParam String recipientPhone,
                  @RequestParam String street, @RequestParam String house,
                  @RequestParam(defaultValue = "") String apartment, @RequestParam(defaultValue = "") String entrance, @RequestParam AddressType addressType,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime due,
                  @RequestParam(defaultValue = "") String comment, Principal principal, RedirectAttributes flash) {
        try {
            var order = service.createOrder(actor(principal), new OrderInput(recipientName, recipientPhone,
                formatAddress(street, house, apartment, entrance, addressType), addressType, due.atZone(MOSCOW).toInstant(), comment));
            boolean located = service.routePoint(actor(principal), order.id()).isPresent();
            flash.addFlashAttribute("notice", located
                ? "Заказ создан. Точка дома добавлена на карту."
                : "Заказ создан. Дом не найден в справочнике, укажи точку при построении маршрута.");
            return "redirect:/orders/" + order.number();
        } catch (IllegalStateException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        } catch (ConstraintViolationException ex) {
            flash.addFlashAttribute("error", "Проверь обязательные поля и длину введенных данных.");
        }
        return "redirect:/orders/new";
    }

    @PostMapping("/{number:[1-9][0-9]{7}}/address")
    String correct(@PathVariable String number, @RequestParam String addressText,
                   @RequestParam AddressType addressType, @RequestParam String reason,
                   Principal principal, RedirectAttributes flash) {
        long actor = actor(principal);
        var order = service.findOrder(actor, number)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            var changed = service.correctAddress(actor, order.id(), new AddressCorrection(addressText, addressType, reason));
            boolean updated = !Objects.equals(order.addressText(), changed.addressText()) || order.addressType() != changed.addressType();
            flash.addFlashAttribute("notice", updated ? "Адрес обновлен." : "Адрес остался прежним.");
        } catch (IllegalStateException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        } catch (ConstraintViolationException ex) {
            flash.addFlashAttribute("error", "Укажи адрес и причину исправления.");
        }
        return "redirect:/orders/" + number;
    }

    @GetMapping("/couriers")
    String couriers(Principal principal, Model model) {
        long actor = actor(principal);
        model.addAttribute("couriers", service.listCouriers(actor).stream()
            .map(c -> new CourierQueue(c, service.courierQueue(actor, c.id()), service.returningToCafe(actor, c.id()))).toList());
        return "couriers";
    }

    @GetMapping("/couriers/new")
    String newCourier(Model model) {
        if (!model.containsAttribute("courierInput")) {
            model.addAttribute("courierInput", new CourierInput("", "", "", "", 0));
        }
        return "courier-new";
    }

    @PostMapping("/couriers")
    String register(@ModelAttribute CourierInput input, Principal principal, RedirectAttributes flash) {
        String list = perform("redirect:/orders/couriers", flash, () -> service.registerCourier(actor(principal), input));
        if (flash.getFlashAttributes().containsKey("error")) {
            flash.addFlashAttribute("courierInput", input);
            return "redirect:/orders/couriers/new";
        }
        flash.addFlashAttribute("notice", "Курьер добавлен.");
        return list;
    }

    @PostMapping("/couriers/{id}/shift")
    String shift(@PathVariable long id, @RequestParam boolean open, Principal principal, RedirectAttributes flash) {
        long actor = actor(principal);
        return perform("redirect:/orders/couriers", flash, () -> {
            if (open) service.startShift(actor, id);
            else service.endShift(actor, id);
        });
    }

    @PostMapping("/couriers/{id}/queue")
    String reorder(@PathVariable long id, @RequestParam long orderId, @RequestParam boolean up, @RequestParam(defaultValue = "false") boolean backToRoute,
                   Principal principal, RedirectAttributes flash) {
        String target = backToRoute ? "redirect:/orders/couriers/" + id + "/route" : "redirect:/orders/couriers";
        return perform(target, flash, () -> service.moveInQueue(actor(principal), id, orderId, up));
    }

    @PostMapping("/{number:[1-9][0-9]{7}}/delivery")
    String delivery(@PathVariable String number, @RequestParam(required = false) Long courierId,
                    @RequestParam(defaultValue = "") String action, Principal principal, RedirectAttributes flash) {
        long actor = actor(principal);
        var order = service.findOrder(actor, number)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return perform("redirect:/orders/" + number, flash, () -> {
            switch (action) {
                case "assign" -> {
                    if (courierId == null) throw new IllegalStateException("Выбери курьера.");
                    service.assignCourier(actor, order.id(), courierId);
                }
                case "dispatch" -> service.dispatch(actor, order.id(), true);
                case "complete" -> service.completeOrder(actor, order.id());
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        });
    }

    private String perform(String redirect, RedirectAttributes flash, Runnable operation) {
        try {
            operation.run();
        } catch (IllegalStateException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        } catch (ConstraintViolationException ex) {
            flash.addFlashAttribute("error", "Проверь данные формы.");
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            flash.addFlashAttribute("error", "Не удалось сохранить изменения. Проверь данные и попробуй снова.");
        }
        return redirect;
    }

    public record CourierQueue(DeliveryViews.CourierView courier, java.util.List<DeliveryViews.OrderView> queue, boolean returning) {}


    @GetMapping("/{number:[1-9][0-9]{7}}/plan")
    String plan(@PathVariable String number, @RequestParam long courierId, Principal principal, RedirectAttributes redirect) {
        var order = service.findOrder(actor(principal), number)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        redirect.addAttribute("selected", order.id());
        return "redirect:/orders/route";
    }

    private String formatAddress(String street, String house, String apartment, String entrance, AddressType type) {
        if ((type == AddressType.APARTMENT && entrance.length() > 40) || street.isBlank() || street.length() > 250 || house.isBlank() || house.length() > 80
                || (type == AddressType.APARTMENT && (apartment.isBlank() || apartment.length() > 80))) {
            throw new IllegalStateException("Укажи улицу, дом и, для квартиры, ее номер.");
        }
        return street.strip() + ", д. " + house.strip()
            + (type == AddressType.APARTMENT ? ", кв. " + apartment.strip()
                + (entrance.isBlank() ? "" : ", подъезд " + entrance.strip()) : "");
    }

    private long actor(Principal principal) {
        return users.findByEmailIgnoreCase(principal.getName()).orElseThrow().getId();
    }
}
