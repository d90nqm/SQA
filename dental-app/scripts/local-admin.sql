-- Only for the isolated dental-local development database.
\connect user-service
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE users
SET password = crypt('LocalAdmin@2026', gen_salt('bf', 10))
WHERE phone = '0900000000' AND email = 'admin@clinic.com';
