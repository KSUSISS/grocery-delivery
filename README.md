# Сервис доставки продуктов

Курсовая работа по дисциплине «Разработка корпоративных приложений».

## Стек

- Java 21 или совместимая более новая JDK.
- Spring Boot, Spring Web MVC, Thymeleaf, Bean Validation.
- Maven Wrapper: отдельная установка Maven не требуется.

## Открытие в IntelliJ IDEA

1. Выберите **File → Open** и папку `D:\grocery-delivery`.
2. Дождитесь импорта Maven и загрузки зависимостей.
3. В **File → Project Structure → Project SDK** выберите установленную JDK (например, corretto-24.0.2).
4. Запустите `GroceryDeliveryApplication` кнопкой рядом с методом `main`.
5. Откройте <http://localhost:8080>.

## Запуск из PowerShell

Укажите путь к установленной JDK, если JAVA_HOME еще не настроен:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\corretto-24.0.2"
.\mvnw.cmd spring-boot:run
```

Проверка проекта:

```powershell
.\mvnw.cmd verify
```

## Текущее состояние

Это начальный каркас с главной страницей и проверкой загрузки контекста Spring. Каталог, база данных, авторизация, заказы, отчеты и уведомления будут реализованы на следующих этапах.

Требования к работе: [docs/requirements.md](docs/requirements.md).