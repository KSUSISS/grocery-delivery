package ru.komissarov.grocerydelivery.delivery;

import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping({"/orders/route", "/orders/couriers/{courierId}/route"})
@RequiredArgsConstructor
class RouteController {
    private final DeliveryService service;
    private final UserAccountRepository users;
    private final OsrmRouting routing;

    @GetMapping
    String route(@PathVariable(required = false) Long courierId, @RequestParam(required = false) List<Long> selected,
                 Principal principal, Model model) {
        long actor = actor(principal);

        boolean draft = courierId == null;
        var active = service.listCouriers(actor).stream().filter(DeliveryViews.CourierView::onShift).toList();
        var courier = draft ? null : service.listCouriers(actor).stream().filter(c -> c.id() == courierId).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<Long> requested = selection(selected);
        var candidates = draft || courier.onShift() ? service.candidates(actor, requested) : List.<DeliveryViews.OrderView>of();
        var proposed = requested.stream()
            .flatMap(id -> candidates.stream().filter(order -> order.id() == id)).toList();
        var queue = draft ? List.<DeliveryViews.OrderView>of() : service.courierQueue(actor, courierId);
        model.addAttribute("draft", draft);
        model.addAttribute("availableCouriers", active.stream().filter(c -> !c.busy()).toList());
        model.addAttribute("routePath", routePath(courierId));
        var cafe = service.routePoint(actor, null).orElse(null);
        List<Stop> stops = new ArrayList<>();
        queue.forEach(order -> stops.add(stop(actor, order, false)));
        proposed.forEach(order -> stops.add(stop(actor, order, true)));

        model.addAttribute("courier", courier);
        model.addAttribute("cafe", cafe);
        model.addAttribute("stops", stops);
        model.addAttribute("selected", proposed.stream().map(DeliveryViews.OrderView::id).toList());
        List<DeliveryViews.OrderView> orderedOptions = new ArrayList<>(proposed);
        candidates.stream().filter(order -> !requested.contains(order.id())).forEach(orderedOptions::add);
        model.addAttribute("options", orderedOptions);
        if (requested.size() != proposed.size()) {
            model.addAttribute("error", "Часть выбранных заказов больше недоступна для назначения.");
        }
        if (cafe != null && !stops.isEmpty() && stops.stream().allMatch(stop -> stop.point() != null)) {
            var points = new ArrayList<OsrmRouting.Point>();
            points.add(cafe);
            stops.forEach(stop -> points.add(stop.point()));
            points.add(cafe);
            try {
                var route = routing.plan(points);
                model.addAttribute("route", route);
                model.addAttribute("line", route.geometry().coordinates());
            } catch (IllegalStateException ex) {
                model.addAttribute("routeError", ex.getMessage());
            }
        }
        return "courier-route";
    }

    @PostMapping("/assign")
    String assign(@PathVariable(required = false) Long courierId,
                  @RequestParam(required = false) Long chosenCourier, @RequestParam(required = false) List<Long> selected,
                  Principal principal, RedirectAttributes flash) {
        List<Long> ids = selection(selected);
        try {
            long actor = actor(principal);
            if (courierId == null) {
                if (chosenCourier == null) throw new IllegalStateException("Выбери курьера.");
                var cafe = service.routePoint(actor, null).orElseThrow(() -> new IllegalStateException("Укажи точку кафе."));
                List<OsrmRouting.Point> points = new ArrayList<>();
                points.add(cafe);
                for (long id : ids) {
                    points.add(service.routePoint(actor, id).orElseThrow(() -> new IllegalStateException("Укажи точку каждого заказа.")));
                }
                if (!ids.isEmpty()) {
                    points.add(cafe);
                    routing.plan(points);
                }
                service.assignBatch(actor, chosenCourier, ids, true);
            } else {
                service.assignBatch(actor, courierId, ids);
            }
            flash.addFlashAttribute("notice", "Маршрут назначен. Для отправки нажми на курьера над списком заказов.");
            return "redirect:/orders";
        } catch (IllegalStateException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
            if (!ids.isEmpty()) flash.addAttribute("selected", ids);
        }
        return redirect(courierId);
    }

    @PostMapping("/points")
    String point(@PathVariable(required = false) Long courierId, @RequestParam(required = false) Long orderId,
                 @RequestParam(required = false) List<Long> selected,
                 @RequestParam double latitude, @RequestParam double longitude,
                 Principal principal, RedirectAttributes flash) {
        long actor = actor(principal);
        List<Long> ids = selection(selected);
        if (orderId != null && (courierId == null || service.courierQueue(actor, courierId).stream().noneMatch(o -> o.id() == orderId))
                && (!ids.contains(orderId) || service.candidates(actor, ids).stream().noneMatch(o -> o.id() == orderId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            service.saveRoutePoint(actor, orderId, new OsrmRouting.Point(latitude, longitude));
            flash.addFlashAttribute("notice", "Точка сохранена.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        if (!ids.isEmpty()) flash.addAttribute("selected", ids);
        return redirect(courierId);
    }

    private List<Long> selection(List<Long> ids) {
        if (ids == null) return List.of();
        if (ids.size() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выбери не больше 20 заказов");
        return ids.stream().distinct().toList();
    }

    private Stop stop(long actor, DeliveryViews.OrderView order, boolean proposed) {
        return new Stop(order, service.routePoint(actor, order.id()).orElse(null), proposed);
    }

    @PostMapping("/position")
    String position(@PathVariable(required = false) Long courierId, @RequestParam List<Long> selected,
                    @RequestParam long orderId, @RequestParam boolean up, RedirectAttributes flash) {
        List<Long> ids = new ArrayList<>(selection(selected));
        int index = ids.indexOf(orderId);
        int next = index + (up ? -1 : 1);
        if (index >= 0 && next >= 0 && next < ids.size()) Collections.swap(ids, index, next);
        flash.addAttribute("selected", ids);
        return redirect(courierId);
    }

    private String routePath(Long courierId) {
        return courierId == null ? "/orders/route" : "/orders/couriers/" + courierId + "/route";
    }

    private String redirect(Long courierId) {
        return "redirect:" + routePath(courierId);
    }

    private long actor(Principal principal) {
        return users.findByEmailIgnoreCase(principal.getName()).orElseThrow().getId();
    }

    public record Stop(DeliveryViews.OrderView order, OsrmRouting.Point point, boolean proposed) {}
}
