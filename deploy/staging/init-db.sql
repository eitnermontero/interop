-- Bases de datos del hub en staging — creadas desde cero al primer arranque
-- del contenedor postgres (docker-entrypoint-initdb.d). Los esquemas y tablas
-- los crea Liquibase al arrancar cada microservicio.
CREATE DATABASE hub_base;
CREATE DATABASE hub_auth;
