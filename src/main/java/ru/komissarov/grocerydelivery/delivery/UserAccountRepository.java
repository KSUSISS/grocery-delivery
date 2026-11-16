package ru.komissarov.grocerydelivery.delivery;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "role")
    java.util.Optional<UserAccount> findByEmailIgnoreCase(String email);
}
