ALTER TABLE service_accounts DROP CONSTRAINT service_accounts_pkey;
ALTER TABLE service_accounts ADD PRIMARY KEY (public_key);

CREATE TABLE affiliate_identity (
    public_key TEXT NOT NULL REFERENCES affiliate(public_key),
    identity_uuid UUID NOT NULL,
    UNIQUE (public_key, identity_uuid)
);

CREATE TABLE service_identity (
    public_key TEXT NOT NULL REFERENCES service_accounts(public_key),
    identity_uuid UUID NOT NULL,
    UNIQUE (public_key, identity_uuid)
);

CREATE TABLE affiliate_service (
    affiliate_public_key TEXT NOT NULL REFERENCES affiliate(public_key),
    service_public_key TEXT NOT NULL REFERENCES service_accounts(public_key),
    UNIQUE (affiliate_public_key, service_public_key)
);