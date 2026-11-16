CREATE TABLE cafe_route_point (
    id integer PRIMARY KEY CHECK (id = 1),
    latitude double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180)
);

CREATE TABLE order_route_points (
    order_id bigint PRIMARY KEY REFERENCES orders(id),
    latitude double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180)
);
