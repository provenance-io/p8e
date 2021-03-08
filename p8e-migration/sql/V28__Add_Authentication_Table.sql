CREATE TABLE authentication (
    uuid UUID PRIMARY KEY NOT NULL,
    affiliate_uuid UUID NOT NULL REFERENCES affiliate (affiliate_uuid),
    certificate_uuid UUID NOT NULL,
    challenge TEXT NOT NULL,
    created TIMESTAMPTZ NOT NULL,
    redeemed TIMESTAMPTZ DEFAULT NULL
);

CREATE INDEX idx_authentication_table_affiliate_uuid on authentication (affiliate_uuid);
CREATE INDEX idx_authentication_table_certificate_uuid on authentication (certificate_uuid);
CREATE INDEX idx_authentication_table_challenge on authentication (challenge);