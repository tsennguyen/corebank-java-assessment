-- V1__init_schema.sql: Initialize core schema and configure schema-level security
CREATE SCHEMA IF NOT EXISTS core;

-- Revoke usage on schema core from Supabase default roles (anon, authenticated) if they exist.
-- This ensures that Supabase PostgREST auto-generated Data API cannot bypass Spring Boot security.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        REVOKE ALL ON SCHEMA core FROM anon;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        REVOKE ALL ON SCHEMA core FROM authenticated;
    END IF;
END $$;
