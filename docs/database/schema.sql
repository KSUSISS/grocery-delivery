-- КСтолу: справочная копия схемы PostgreSQL для первого этапа.
-- Источник схемы в приложении: src/main/resources/db/migration/V1__delivery_core.sql.
-- Этот файл отдельно не выполняется автоматически; не запускать перед Flyway.
-- Выполнять в новой выделенной базе. Скрипт не содержит удаления таблиц.
BEGIN;

CREATE TABLE roles (
    code varchar(16) PRIMARY KEY,
    name varchar(60) NOT NULL,
    CONSTRAINT ck_role_code CHECK (code IN ('ADMIN', 'MANAGER', 'CLIENT', 'COURIER'))
);
INSERT INTO roles(code, name) VALUES
    ('ADMIN', 'Администратор'), ('MANAGER', 'Менеджер / диспетчер'),
    ('CLIENT', 'Клиент'), ('COURIER', 'Курьер');

CREATE TABLE app_users (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_code varchar(16) NOT NULL REFERENCES roles(code),
    email varchar(254) NOT NULL,
    password_hash varchar(255) NOT NULL,
    display_name varchar(150) NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_email CHECK (length(btrim(email)) > 3 AND email = btrim(email)),
    CONSTRAINT ck_user_password CHECK (length(btrim(password_hash)) > 0),
    CONSTRAINT ck_user_name CHECK (length(btrim(display_name)) > 0)
);
CREATE UNIQUE INDEX uq_user_email ON app_users(lower(email));
CREATE INDEX ix_users_role ON app_users(role_code);

CREATE TABLE couriers (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint UNIQUE REFERENCES app_users(id),
    last_name varchar(60) NOT NULL,
    first_name varchar(60) NOT NULL,
    patronymic varchar(60) NOT NULL,
    phone varchar(16) NOT NULL UNIQUE,
    age_years smallint NOT NULL,
    age_recorded_on date NOT NULL DEFAULT CURRENT_DATE,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_courier_names CHECK (
        length(btrim(last_name)) > 0 AND length(btrim(first_name)) > 0
        AND length(btrim(patronymic)) > 0
    ),
    CONSTRAINT ck_courier_phone CHECK (phone ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT ck_courier_age CHECK (age_years BETWEEN 1 AND 120)
);

CREATE TABLE courier_shifts (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    courier_id bigint NOT NULL REFERENCES couriers(id),
    started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at timestamptz,
    opened_by bigint NOT NULL REFERENCES app_users(id),
    closed_by bigint REFERENCES app_users(id),
    CONSTRAINT ck_shift_time CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT ck_shift_closer CHECK ((ended_at IS NULL) = (closed_by IS NULL))
);
CREATE UNIQUE INDEX uq_courier_open_shift ON courier_shifts(courier_id)
    WHERE ended_at IS NULL;
CREATE INDEX ix_shifts_courier_time ON courier_shifts(courier_id, started_at);

CREATE TABLE orders (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_number varchar(8) NOT NULL UNIQUE,
    customer_id bigint REFERENCES app_users(id),
    created_by bigint NOT NULL REFERENCES app_users(id),
    recipient_name varchar(120) NOT NULL,
    recipient_phone varchar(16) NOT NULL,
    address_text varchar(500) NOT NULL,
    address_type varchar(16) NOT NULL,
    delivery_due_at timestamptz NOT NULL,
    delivery_comment varchar(500) NOT NULL DEFAULT '',
    status varchar(20) NOT NULL DEFAULT 'NEW',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamptz,
    completed_by bigint REFERENCES app_users(id),
    CONSTRAINT ck_order_number CHECK (order_number ~ '^[1-9][0-9]{7}$'),
    CONSTRAINT ck_order_recipient CHECK (length(btrim(recipient_name)) > 0),
    CONSTRAINT ck_order_phone CHECK (recipient_phone ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT ck_order_address CHECK (length(btrim(address_text)) > 0),
    CONSTRAINT ck_order_address_type CHECK (address_type IN ('APARTMENT', 'PRIVATE_HOUSE')),
    CONSTRAINT ck_order_status CHECK (status IN ('NEW', 'ASSIGNED', 'IN_DELIVERY', 'COMPLETED')),
    CONSTRAINT ck_order_completion CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL AND completed_by IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL AND completed_by IS NULL)
    ),
    CONSTRAINT ck_order_completion_time CHECK (completed_at IS NULL OR completed_at >= created_at)
);
CREATE INDEX ix_orders_status_created ON orders(status, created_at DESC);
CREATE INDEX ix_orders_due_active ON orders(delivery_due_at)
    WHERE status <> 'COMPLETED';
CREATE INDEX ix_orders_customer ON orders(customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX ix_orders_created_by ON orders(created_by);

-- История назначений. Текущее назначение определяется released_at IS NULL.
CREATE TABLE delivery_assignments (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id bigint NOT NULL REFERENCES orders(id),
    shift_id bigint NOT NULL REFERENCES courier_shifts(id),
    assigned_by bigint NOT NULL REFERENCES app_users(id),
    assigned_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at timestamptz,
    dispatched_by bigint REFERENCES app_users(id),
    released_at timestamptz,
    released_by bigint REFERENCES app_users(id),
    release_reason varchar(20),
    CONSTRAINT ck_assignment_dispatch CHECK ((dispatched_at IS NULL) = (dispatched_by IS NULL)),
    CONSTRAINT ck_assignment_release CHECK (
        (released_at IS NULL AND released_by IS NULL AND release_reason IS NULL)
        OR (released_at IS NOT NULL AND released_by IS NOT NULL AND release_reason IS NOT NULL)
    ),
    CONSTRAINT ck_assignment_reason CHECK (release_reason IN ('REASSIGNED', 'COMPLETED')),
    CONSTRAINT ck_assignment_times CHECK (
        (dispatched_at IS NULL OR dispatched_at >= assigned_at)
        AND (released_at IS NULL OR released_at >= assigned_at)
        AND (released_at IS NULL OR dispatched_at IS NULL OR released_at >= dispatched_at)
    )
);
CREATE UNIQUE INDEX uq_order_active_assignment ON delivery_assignments(order_id)
    WHERE released_at IS NULL;
CREATE UNIQUE INDEX uq_shift_active_assignment ON delivery_assignments(shift_id)
    WHERE released_at IS NULL;
CREATE INDEX ix_assignments_order ON delivery_assignments(order_id, assigned_at);
CREATE INDEX ix_assignments_shift ON delivery_assignments(shift_id, assigned_at);

CREATE TABLE order_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id bigint NOT NULL REFERENCES orders(id),
    actor_user_id bigint NOT NULL REFERENCES app_users(id),
    event_type varchar(24) NOT NULL,
    previous_status varchar(20),
    new_status varchar(20) NOT NULL,
    note varchar(500) NOT NULL DEFAULT '',
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_order_event_type CHECK (event_type IN ('CREATED', 'ASSIGNED', 'REASSIGNED', 'DISPATCHED', 'COMPLETED')),
    CONSTRAINT ck_event_previous CHECK (previous_status IN ('NEW', 'ASSIGNED', 'IN_DELIVERY', 'COMPLETED')),
    CONSTRAINT ck_event_new CHECK (new_status IN ('NEW', 'ASSIGNED', 'IN_DELIVERY', 'COMPLETED')),
    CONSTRAINT ck_event_creation CHECK ((event_type = 'CREATED') = (previous_status IS NULL))
);
CREATE INDEX ix_order_events_order ON order_events(order_id, occurred_at, id);
CREATE INDEX ix_order_events_actor ON order_events(actor_user_id);

COMMIT;
