ALTER TABLE envelope
    ADD COLUMN transaction_hash TEXT,
    ADD COLUMN block_height INT8;

CREATE INDEX index_scope_transaction_id_idx ON index_scope USING btree (transaction_id);
