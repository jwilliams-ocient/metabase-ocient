/* database: avian-singles */
CREATE DATABASE IF NOT EXISTS "avian-singles";

connect to jdbc:ocient://10.10.110.4:4050/avian-singles;

DROP TABLE IF EXISTS "public"."users";
CREATE TABLE "public"."users"(
  created TIMESTAMP TIME KEY BUCKET(1, HOUR) NOT NULL DEFAULT '0',
  id INT NOT NULL,
  "name" VARCHAR(255),
  CLUSTERING INDEX idx01 (id)
);
