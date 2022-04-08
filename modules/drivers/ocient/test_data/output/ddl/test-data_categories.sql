/* database: test-data */
CREATE DATABASE IF NOT EXISTS "test-data";

connect to jdbc:ocient://10.10.110.4:4050/test-data;

DROP TABLE IF EXISTS "public"."categories";
CREATE TABLE "public"."categories"(
  created TIMESTAMP TIME KEY BUCKET(1, HOUR) NOT NULL DEFAULT '0',
  id INT NOT NULL,
  "name" VARCHAR(255),
  CLUSTERING INDEX idx01 (id)
);
