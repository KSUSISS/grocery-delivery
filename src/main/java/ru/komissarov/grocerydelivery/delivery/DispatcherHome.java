package ru.komissarov.grocerydelivery.delivery;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class DispatcherHome {
    @GetMapping("/")
    String home() {
        return "redirect:/orders";
    }
}
