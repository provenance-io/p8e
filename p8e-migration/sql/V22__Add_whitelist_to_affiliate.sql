ALTER TABLE affiliate ADD COLUMN whitelist_data JSONB;

-- heavily influenced by https://dba.stackexchange.com/questions/196604/find-rows-containing-a-key-in-a-jsonb-array-of-records
CREATE OR REPLACE FUNCTION jsonb_arr_record_missing_key(jsonb, text)
  RETURNS text[] LANGUAGE sql IMMUTABLE AS
  'SELECT ARRAY (
     SELECT elem::text
     FROM jsonb_array_elements($1) elem
     WHERE NOT elem ? $2
  )';

CREATE INDEX envelope_errors_arr_missing_key_idx ON envelope USING gin (jsonb_arr_record_missing_key(data->'errors', 'readTime'));

ALTER TABLE envelope ADD COLUMN expiration_time TIMESTAMPTZ;