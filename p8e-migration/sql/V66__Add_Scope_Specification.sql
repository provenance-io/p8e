CREATE TABLE scope_specification_definition(
  uuid UUID PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT NOT NULL,
  parties_involved JSONB NOT NULL,
  website_url TEXT NOT NULL,
  icon_url TEXT NOT NULL,
  created TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX scope_spec_name_unq_idx ON scope_specification_definition (name);
