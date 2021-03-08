ALTER TABLE envelope ADD COLUMN error_time TIMESTAMPTZ;
ALTER TABLE envelope ADD COLUMN fragment_time TIMESTAMPTZ;
ALTER TABLE envelope ADD COLUMN executed_time TIMESTAMPTZ;
ALTER TABLE envelope ADD COLUMN chaincode_time TIMESTAMPTZ;
ALTER TABLE envelope ADD COLUMN outbound_time TIMESTAMPTZ;
ALTER TABLE envelope ADD COLUMN inbox_time TIMESTAMPTZ;
ALTER TABLE envelope ADD COLUMN index_time TIMESTAMPTZ;
ALTER TABLE envelope ADD COLUMN read_time TIMESTAMPTZ;
ALTER TABLE envelope ADD COLUMN complete_time TIMESTAMPTZ;
ALTER TABLE envelope ADD COLUMN signed_time TIMESTAMPTZ;
ALTER TABLE envelope ADD COLUMN is_invoker BOOLEAN;

UPDATE envelope SET error_time = (data#>>'{errorTime}')::TIMESTAMPTZ,
                    fragment_time = (data#>>'{fragmentTime}')::TIMESTAMPTZ,
                    executed_time = (data#>>'{executedTime}')::TIMESTAMPTZ,
                    chaincode_time = (data#>>'{chaincodeTime}')::TIMESTAMPTZ,
                    outbound_time = (data#>>'{outboundTime}')::TIMESTAMPTZ,
                    inbox_time = (data#>>'{inboxTime}')::TIMESTAMPTZ,
                    index_time = (data#>>'{indexTime}')::TIMESTAMPTZ,
                    read_time = (data#>>'{readTime}')::TIMESTAMPTZ,
                    complete_time = (data#>>'{completeTime}')::TIMESTAMPTZ,
                    signed_time = (data#>>'{signedTime}')::TIMESTAMPTZ,
                    is_invoker = (data#>>'{isInvoker}')::BOOLEAN;

CREATE INDEX envelope_error_time_idx on envelope (error_time);
CREATE INDEX envelope_fragment_time_idx on envelope (fragment_time);
CREATE INDEX envelope_executed_time_idx on envelope (executed_time);
CREATE INDEX envelope_chaincode_time_idx on envelope (chaincode_time);
CREATE INDEX envelope_outbound_time_idx on envelope (outbound_time);
CREATE INDEX envelope_inbox_time_idx on envelope (inbox_time);
CREATE INDEX envelope_index_time_idx on envelope (index_time);
CREATE INDEX envelope_read_time_idx on envelope (read_time);
CREATE INDEX envelope_complete_time_idx on envelope (complete_time);
CREATE INDEX envelope_signed_time_idx on envelope (signed_time);
CREATE INDEX envelope_is_invoker_idx on envelope (is_invoker);