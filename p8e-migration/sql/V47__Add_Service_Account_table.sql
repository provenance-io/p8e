CREATE TABLE service_accounts (
    private_key TEXT NOT NULL PRIMARY KEY,
    public_key TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    alias VARCHAR(255)
)