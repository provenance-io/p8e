CREATE TABLE affiliate_share (
    uuid UUID PRIMARY KEY,
    affiliate_public_key TEXT REFERENCES affiliate(public_key) NOT NULL,
    public_key TEXT NOT NULL,
    created TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT affiliate_share_key_pair_unq UNIQUE (affiliate_public_key, public_key)
);
