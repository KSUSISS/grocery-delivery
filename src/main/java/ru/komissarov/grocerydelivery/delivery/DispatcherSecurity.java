package ru.komissarov.grocerydelivery.delivery;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class DispatcherSecurity {
    @Bean PasswordEncoder dispatcherPasswords() {
        return new BCryptPasswordEncoder();
    }

    @Bean UserDetailsService dispatcherUsers(UserAccountRepository users, Environment env) {
        return login -> users.findByEmailIgnoreCase(dispatcherEmail(login, env))
            .map(account -> User.withUsername(account.getEmail())
                .password(account.getPasswordHash())
                .roles(account.getRole().getCode().name())
                .disabled(!account.isActive()).build())
            .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
    }

    @Bean SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/orders", "/orders/**").hasAnyRole("MANAGER", "ADMIN")
                .anyRequest().permitAll())
            .formLogin(login -> login.defaultSuccessUrl("/orders", false))
            .build();
    }

    private static String dispatcherEmail(String login, Environment env) {
        String alias = env.getProperty("KSTOLU_DISPATCHER_LOGIN");
        return alias != null && alias.equalsIgnoreCase(login) ? "dispatcher@kstolu.local" : login;
    }

    @Bean ApplicationRunner localDispatcher(Environment env, UserAccountRepository users,
                                               RoleRepository roles, PasswordEncoder passwords) {
        return args -> {
            String password = env.getProperty("KSTOLU_DISPATCHER_PASSWORD");
            String email = "dispatcher@kstolu.local";
            if (password == null || password.isBlank()) {
                return;
            }
            var existing = users.findByEmailIgnoreCase(email);
            if (existing.isPresent()) {
                var account = existing.get();
                if (env.getProperty("KSTOLU_DISPATCHER_LOGIN") != null
                        && !passwords.matches(password, account.getPasswordHash())) {
                    account.setPasswordHash(passwords.encode(password));
                    users.save(account);
                }
                return;
            }
            UserAccount account = new UserAccount();
            account.setEmail(email);
            account.setDisplayName("Диспетчер");
            account.setPasswordHash(passwords.encode(password));
            account.setRole(roles.findById(Types.RoleCode.MANAGER).orElseThrow());
            users.save(account);
        };
    }
}
