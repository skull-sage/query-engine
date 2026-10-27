-- Development only: the one database login the OrderQuery service connects as (spring.r2dbc.* in application.yml).
-- Runs as the administrator, once, when the database is first created, before the projection scripts that follow.
--
-- It is a plain login: no superuser, no createdb, no createrole, no bypassrls. It can use the public schema and
-- create tables there (query-engine creates qe_stored_query on first start), and the projection scripts grant it
-- read and write on the projection tables, as the service's event consumers need.
--
-- The password is a development default. In production the DBA creates the login and its password is supplied from a
-- secret manager.
CREATE ROLE order_query LOGIN PASSWORD 'order_query' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
GRANT CONNECT ON DATABASE order_query TO order_query;
GRANT USAGE, CREATE ON SCHEMA public TO order_query;
